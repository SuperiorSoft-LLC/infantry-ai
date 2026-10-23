package com.superiorsoftllc.agenthq.images

import com.superiorsoftllc.agenthq.mvp.ProjectStore
import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.protocol.ImageStored
import com.superiorsoftllc.agenthq.protocol.ImageUpload
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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

/**
 * IMG-1: project-local image storage. Every refusal throws [IllegalArgumentException] BEFORE anything touches the
 * disk -- the uploads folder included -- so a refused upload leaves no trace.
 */
object ImageUploads {
    const val UPLOADS_REL = ".agenthq/uploads"
    private const val ACCEPTED = "png, jpeg, gif or webp"

    /**
     * Validate [upload] and store it under [projectRoot]; answers where it is. IMG-5: the file's mtime is set to
     * [nowMillis] on every accepted upload, a dedupe hit included, so an image still being sent outlives the
     * [ChatImageRetention] sweep.
     */
    fun store(projectRoot: File, upload: ImageUpload, nowMillis: Long = System.currentTimeMillis()): ImageStored {
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
        val uploads = File(projectRoot, UPLOADS_REL)
        val dir = File(uploads, upload.purpose)
        Files.createDirectories(dir.toPath())
        val ignore = File(uploads, ".gitignore")
        if (!ignore.isFile) ignore.writeText("*\n")
        val name = "$sha.$extension"
        val target = File(dir, name)
        // Dedupe by content: the name IS the hash, so an existing file of the right size is these bytes.
        if (!(target.isFile && target.length() == bytes.size.toLong())) {
            val temp = Files.createTempFile(dir.toPath(), ".upload-", ".part")
            try {
                Files.write(temp, bytes)
                try {
                    Files.move(temp, target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temp, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(temp)
            }
        }
        // IMG-5: "last used", the clock the retention sweep reads; a dedupe hit refreshes it.
        target.setLastModified(nowMillis)
        return ImageStored(
            path = target.absoluteFile.normalize().path,
            relPath = "$UPLOADS_REL/${upload.purpose}/$name",
            sha256 = sha,
            bytes = bytes.size.toLong(),
            mimeType = mimeType,
        )
    }

    private fun tooLarge(limit: Int) = "the file is over the ${limit / (1024 * 1024)} MB limit ($limit bytes)"

    /** ATTACH-INLINE-1: [fileName]'s extension when it is 1-10 letters or digits, lowercased; `bin` otherwise. */
    internal fun fileExtension(fileName: String): String {
        val ext = fileName.substringAfterLast('/').substringAfterLast('\\').substringAfterLast('.', "").lowercase()
        return ext.takeIf { it.length in 1..10 && it.all { c -> c in 'a'..'z' || c in '0'..'9' } } ?: "bin"
    }
}

/** IMG-1: the `image_upload` wire surface. Which project, and its directory, is the [ProjectStore]'s answer. */
class ImageUploadHandler(
    private val projects: ProjectStore,
    private val json: Json = Json { encodeDefaults = true },
    /** IMG-5: the clock an upload's mtime is stamped from; the same one [ChatImageRetention] sweeps against. */
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Answer one frame, or null when it is not an `image_upload`. */
    fun answer(envelope: Envelope): Envelope? {
        if (envelope.type != Envelope.IMAGE_UPLOAD) return null
        return try {
            val upload = ImageUpload.decode(envelope.data)
            val project = projects.get(upload.projectId)
            require(project.directory.isNotBlank()) { "project '${project.id}' has no directory to store uploads in" }
            val root = File(project.directory)
            require(root.isDirectory) { "project '${project.id}' directory does not exist: ${project.directory}" }
            Envelope(Envelope.IMAGE, json.encodeToString(ImageStored.serializer(), ImageUploads.store(root, upload, clock())))
        } catch (e: IllegalArgumentException) {
            Envelope(Envelope.ERROR, "${Envelope.IMAGE_UPLOAD} refused: ${e.message ?: "invalid request"}")
        } catch (e: Exception) {
            Envelope(Envelope.ERROR, "${Envelope.IMAGE_UPLOAD} failed: ${e.message ?: e::class.simpleName}")
        }
    }
}
