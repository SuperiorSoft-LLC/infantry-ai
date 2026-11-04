// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.chat.daemon

import com.superiorsoftllc.infantry.daemon.api.files.ProjectFiles
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.security.MessageDigest
import java.util.Base64

/** The image types an upload may be, each with the extension it is stored under. */
enum class ImageType(val mimeType: String, val extension: String) {
    PNG("image/png", "png"),
    JPEG("image/jpeg", "jpg"),
    GIF("image/gif", "gif"),
    WEBP("image/webp", "webp"),
    ;

    companion object {
        /** What [bytes] are by their magic bytes, or null when they are none of the four. */
        fun sniff(bytes: ByteArray): ImageType? {
            fun at(offset: Int, vararg expected: Int): Boolean =
                bytes.size >= offset + expected.size && expected.indices.all { (bytes[offset + it].toInt() and 0xFF) == expected[it] }
            fun ascii(offset: Int, text: String): Boolean = at(offset, *text.map { it.code }.toIntArray())
            return when {
                at(0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> PNG
                at(0, 0xFF, 0xD8, 0xFF) -> JPEG
                ascii(0, "GIF87a") || ascii(0, "GIF89a") -> GIF
                ascii(0, "RIFF") && ascii(8, "WEBP") -> WEBP
                else -> null
            }
        }

        /** The type a declared mime string names (`image/jpg` read as jpeg), or null for anything else. */
        fun ofMime(mime: String): ImageType? = when (mime.trim().lowercase().substringBefore(';').trim()) {
            "image/png" -> PNG
            "image/jpeg", "image/jpg", "image/pjpeg" -> JPEG
            "image/gif" -> GIF
            "image/webp" -> WEBP
            else -> null
        }
    }
}

/** The `image_upload` payload (the wire's `ImageUpload`), as the plugin reads it. */
data class ImageUpload(
    val projectId: String,
    val purpose: String,
    val fileName: String = "",
    val mimeType: String = "",
    val base64: String,
) {
    companion object {
        /** 10 MB, decoded. */
        const val MAX_BYTES: Int = 10 * 1024 * 1024
        const val PURPOSE_CHAT = "chat"
        const val PURPOSE_TICKET = "ticket"
        /** A chat attachment of any type. */
        const val PURPOSE_FILE = "file"
        /** 20 MB, decoded: the limit for purpose `file`. */
        const val MAX_FILE_BYTES: Int = 20 * 1024 * 1024
        val PURPOSES: Set<String> = setOf(PURPOSE_CHAT, PURPOSE_TICKET, PURPOSE_FILE)
        private val KEYS = setOf("projectId", "purpose", "fileName", "mimeType", "base64")

        fun maxBytes(purpose: String): Int = if (purpose == PURPOSE_FILE) MAX_FILE_BYTES else MAX_BYTES

        /** Strict, like the wire: a malformed payload, an unknown key or a missing required key is refused (IllegalArgumentException). */
        fun decode(text: String): ImageUpload {
            val obj = try {
                Json.parseToJsonElement(text) as? JsonObject ?: throw IllegalArgumentException("image_upload payload must be a JSON object")
            } catch (e: SerializationException) {
                throw IllegalArgumentException("image_upload payload is malformed: ${e.message?.take(200)}")
            }
            obj.keys.firstOrNull { it !in KEYS }?.let { throw IllegalArgumentException("image_upload payload has an unknown field '${it.take(40)}'") }
            fun str(key: String, required: Boolean): String {
                val e = obj[key]
                if (e == null || e is JsonNull) {
                    require(!required) { "image_upload payload is missing '$key'" }
                    return ""
                }
                val p = e as? JsonPrimitive
                require(p != null && p.isString) { "image_upload payload field '$key' must be a string" }
                return p.content
            }
            return ImageUpload(str("projectId", true), str("purpose", true), str("fileName", false), str("mimeType", false), str("base64", true))
        }
    }
}

/** Where an upload was stored (the wire's `ImageStored`). */
data class ImageStored(val path: String, val relPath: String, val sha256: String, val bytes: Long, val mimeType: String) {
    fun toJson(): String = buildJsonObject {
        put("path", JsonPrimitive(path))
        put("relPath", JsonPrimitive(relPath))
        put("sha256", JsonPrimitive(sha256))
        put("bytes", JsonPrimitive(bytes))
        put("mimeType", JsonPrimitive(mimeType))
    }.toString()
}

/**
 * IMG-1: project-local image storage, over the project's [ProjectFiles] (DP-10a). Every refusal throws
 * [IllegalArgumentException] BEFORE anything touches the disk -- the uploads folder included -- so a refused upload leaves no trace.
 */
object ImageUploads {
    /** Relative to the project directory (the answer's `relPath`). */
    const val UPLOADS_REL = ProjectFiles.UPLOADS_REL
    /** Relative to the [ProjectFiles] root (`.infantry/`). */
    const val UPLOADS = "uploads"
    private const val ACCEPTED = "png, jpeg, gif or webp"

    /**
     * Validate [upload] and store it through [files]; answers where it is. IMG-5: the file's mtime is set to [nowMillis] on every
     * accepted upload, a dedupe hit included, so an image still being sent outlives the [ChatImageRetention] sweep.
     */
    fun store(files: ProjectFiles, upload: ImageUpload, nowMillis: Long = System.currentTimeMillis()): ImageStored {
        require(upload.purpose in ImageUpload.PURPOSES) {
            "purpose '${upload.purpose.take(40)}' is not accepted; only ${ImageUpload.PURPOSES.joinToString(" or ")}"
        }
        val limit = ImageUpload.maxBytes(upload.purpose)
        // Bound the work before decoding: base64 of the limit is at most this many characters (padding included).
        val maxEncoded = (limit.toLong() + 2) / 3 * 4
        val encoded = upload.base64.filterNot { it == '\r' || it == '\n' }
        require(encoded.length <= maxEncoded) { tooLarge(limit) }
        val bytes = try {
            Base64.getDecoder().decode(encoded)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("base64 is malformed: ${e.message}")
        }
        require(bytes.size <= limit) { tooLarge(limit) }
        require(bytes.isNotEmpty()) { "the file is empty" }
        val (extension, mimeType) = if (upload.purpose == ImageUpload.PURPOSE_FILE) {
            // ATTACH-INLINE-1: any type; the bytes are stored as they are and never interpreted.
            fileExtension(upload.fileName) to upload.mimeType.trim().take(120).ifBlank { "application/octet-stream" }
        } else {
            val type = ImageType.sniff(bytes)
                ?: throw IllegalArgumentException("not a $ACCEPTED image (its first bytes match none of them)")
            if (upload.mimeType.isNotBlank()) {
                val declared = ImageType.ofMime(upload.mimeType)
                    ?: throw IllegalArgumentException("mimeType '${upload.mimeType.take(80)}' is not accepted; only $ACCEPTED")
                require(declared == type) {
                    "mimeType '${upload.mimeType.take(80)}' contradicts the file's bytes, which are ${type.mimeType}"
                }
            }
            type.extension to type.mimeType
        }

        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        // One .gitignore at uploads/ covers every purpose folder below it.
        val ignore = "$UPLOADS/.gitignore"
        if (files.stat(ignore) == null) files.put(ignore, "*\n".toByteArray())
        val name = "$sha.$extension"
        val rel = "$UPLOADS/${upload.purpose}/$name"
        // Dedupe by content: the name IS the hash, so an existing file of the right size is these bytes.
        if (files.stat(rel)?.size != bytes.size.toLong()) files.put(rel, bytes)
        // IMG-5: "last used", the clock the retention sweep reads; a dedupe hit refreshes it.
        files.touch(rel, nowMillis)
        return ImageStored(
            path = files.absolutePath(rel),
            relPath = "$UPLOADS_REL/${upload.purpose}/$name",
            sha256 = sha,
            bytes = bytes.size.toLong(),
            mimeType = mimeType,
        )
    }

    /** The image purposes a fetch by id looks in, in order. */
    private val FETCH_PURPOSES = listOf(ImageUpload.PURPOSE_CHAT, ImageUpload.PURPOSE_TICKET)
    private val UPLOAD_ID = Regex("^[0-9a-f]{64}\\.(png|jpg|gif|webp)$")

    /** CHAT-IMAGE-REMOTE-1: [id] is a stored image upload's file name (`<sha256>.<ext>`), the only name a fetch accepts. */
    fun isUploadId(id: String): Boolean = UPLOAD_ID.matches(id)

    /** CHAT-IMAGE-REMOTE-1: the bytes and mime type of the image upload named [id] in [files], or null when none holds it. */
    fun find(files: ProjectFiles, id: String): Pair<ByteArray, String>? {
        if (!isUploadId(id)) return null
        val type = ImageType.entries.first { it.extension == id.substringAfterLast('.') }
        return FETCH_PURPOSES.firstNotNullOfOrNull { purpose -> files.get("$UPLOADS/$purpose/$id") }?.let { it to type.mimeType }
    }

    private fun tooLarge(limit: Int) = "the file is over the ${limit / (1024 * 1024)} MB limit ($limit bytes)"

    /** ATTACH-INLINE-1: [fileName]'s extension when it is 1-10 letters or digits, lowercased; `bin` otherwise. */
    internal fun fileExtension(fileName: String): String {
        val ext = fileName.substringAfterLast('/').substringAfterLast('\\').substringAfterLast('.', "").lowercase()
        return ext.takeIf { it.length in 1..10 && it.all { c -> c in 'a'..'z' || c in '0'..'9' } } ?: "bin"
    }
}
