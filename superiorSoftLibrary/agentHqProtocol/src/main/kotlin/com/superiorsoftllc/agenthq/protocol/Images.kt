package com.superiorsoftllc.agenthq.protocol

import kotlinx.serialization.Serializable

/*
 * Contract 1.83.0 (IMG-1): image uploads. The daemon stores the bytes in the project's own directory and answers the
 * PATH, so every harness (Claude, Pi, opencode) can be handed an image as a plain file path -- no native image blocks.
 */

/**
 * `image_upload` (client -> daemon), answered by `image` ([ImageStored]) or refused with
 * `error` "image_upload refused: <why>" and nothing written.
 *
 * - [projectId]: an existing project WITH a directory; the file lands in `<directory>/.agenthq/uploads/<purpose>/`.
 * - [purpose]: REQUIRED, `chat`, `ticket` or `file` ([PURPOSES]); anything else is refused. Dedupe is per purpose.
 *   1.89.0 (ATTACH-INLINE-1): `file` is a chat attachment of ANY type, at most [MAX_FILE_BYTES]: no magic-byte check,
 *   stored as `<sha256>.<ext>` with the extension taken from [fileName] (`bin` when it has none usable), and swept
 *   like `chat`. [ImageStored.mimeType] echoes the declared type, or `application/octet-stream`.
 * - [fileName]: the client's own name for the file. Informational only: the stored name is `<sha256>.<ext>`.
 * - [mimeType]: `image/png`, `image/jpeg`, `image/gif` or `image/webp`; blank = take what the bytes say. A non-blank
 *   value must agree with the magic bytes, which are what is actually checked. Purpose `file`: informational only.
 * - [base64]: the file, standard base64. At most [MAX_BYTES] once decoded ([MAX_FILE_BYTES] for purpose `file`).
 */
@Serializable
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
        /** 1.89.0 (ATTACH-INLINE-1): a chat attachment of any type. */
        const val PURPOSE_FILE = "file"
        /** 20 MB, decoded: the limit for purpose `file`. */
        const val MAX_FILE_BYTES: Int = 20 * 1024 * 1024
        val PURPOSES: Set<String> = setOf(PURPOSE_CHAT, PURPOSE_TICKET, PURPOSE_FILE)

        /** The decoded size limit for [purpose]. */
        fun maxBytes(purpose: String): Int = if (purpose == PURPOSE_FILE) MAX_FILE_BYTES else MAX_BYTES

        fun decode(text: String): ImageUpload = StrictJson.decode(serializer(), text, "image_upload payload")
    }
}

/**
 * `image` (daemon -> the uploading connection): where an upload is stored. The same bytes uploaded again for the same
 * purpose answer the same [path] (deduplicated by [sha256] within the purpose); nothing is rewritten.
 */
@Serializable
data class ImageStored(
    /** Absolute path on the daemon's machine: what an agent is given. */
    val path: String,
    /** [path] relative to the project directory, `/`-separated: `.agenthq/uploads/<purpose>/<sha256>.<ext>`. */
    val relPath: String,
    /** Lowercase hex SHA-256 of the stored bytes. */
    val sha256: String,
    /** Size of the stored file in bytes. */
    val bytes: Long,
    /** The type the magic bytes identified (never merely the one the client declared). */
    val mimeType: String,
)
