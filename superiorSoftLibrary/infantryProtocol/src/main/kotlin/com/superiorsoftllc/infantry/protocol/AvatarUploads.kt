// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.protocol

/** The only two image formats an avatar upload may be. */
enum class ImageFormat(val extension: String, val mediaType: String) {
    PNG("png", "image/png"),
    WEBP("webp", "image/webp");

    companion object {
        /** The format a filename's extension names, or null. Used to type a bundled or stored file. */
        fun fromFileName(name: String): ImageFormat? =
            entries.firstOrNull { name.endsWith(".${it.extension}", ignoreCase = true) }
    }
}

/** A validated, storable avatar: its format, its pixel size and its bytes. */
class ValidatedAvatar(
    val format: ImageFormat,
    val width: Int,
    val height: Int,
    val bytes: ByteArray,
) {
    /** Number of bytes, for a caller that wants to show it. */
    val byteCount: Int get() = bytes.size
}

/** An upload the guardrails refuse; [message] is written for a person, so it is shown as-is. */
class InvalidAvatarException(message: String) : IllegalArgumentException(message)

/**
 * The upload guardrails: it is a badge on an agent row, not an image editor.
 *
 * Accepted: **PNG or WebP only**, at most [MAX_DIMENSION] pixels on either side, at most
 * [MAX_BYTES] of file. Format and size are read from the bytes themselves, never from a
 * client-supplied name or content type, so a renamed `.png` that is not one is refused.
 *
 * WebP has three container layouts and all three are read (extended `VP8X`, lossy `VP8 `, lossless
 * `VP8L`), because a user's browser produces whichever it likes from the same "WebP".
 */
object AvatarUploads {
    /** ~256 KB. */
    const val MAX_BYTES: Int = 256 * 1024

    /** ~512 px on either side. */
    const val MAX_DIMENSION: Int = 512

    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    /** The format [bytes] actually are, or null when it is neither PNG nor WebP. */
    fun detect(bytes: ByteArray): ImageFormat? {
        if (bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE)) return ImageFormat.PNG
        if (bytes.size >= 12 && ascii(bytes, 0, 4) == "RIFF" && ascii(bytes, 8, 4) == "WEBP") {
            return ImageFormat.WEBP
        }
        return null
    }

    /** The pixel size [bytes] declare, or null when the header cannot be read. */
    fun dimensions(bytes: ByteArray, format: ImageFormat): Pair<Int, Int>? = when (format) {
        ImageFormat.PNG -> pngDimensions(bytes)
        ImageFormat.WEBP -> webpDimensions(bytes)
    }

    /**
     * Validate an upload, or throw [InvalidAvatarException] naming the first thing wrong. Never
     * silently truncates or resizes: the caller decides what to do with a refusal.
     */
    fun validate(bytes: ByteArray): ValidatedAvatar {
        if (bytes.isEmpty()) throw InvalidAvatarException("the image is empty")
        if (bytes.size > MAX_BYTES) {
            throw InvalidAvatarException(
                "the image is ${bytes.size / 1024} KB; the limit is ${MAX_BYTES / 1024} KB",
            )
        }
        val format = detect(bytes)
            ?: throw InvalidAvatarException("only PNG and WebP avatars are accepted")
        val (width, height) = dimensions(bytes, format)
            ?: throw InvalidAvatarException(
                "cannot read the ${format.extension.uppercase()} size; the file looks truncated",
            )
        if (width <= 0 || height <= 0) {
            throw InvalidAvatarException("the image has no pixels ($width x $height)")
        }
        if (width > MAX_DIMENSION || height > MAX_DIMENSION) {
            throw InvalidAvatarException(
                "the image is ${width}x$height; the limit is ${MAX_DIMENSION}x$MAX_DIMENSION",
            )
        }
        return ValidatedAvatar(format, width, height, bytes)
    }

    // --- header readers -------------------------------------------------------------------------

    private fun pngDimensions(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < 24 || ascii(bytes, 12, 4) != "IHDR") return null
        return be32(bytes, 16) to be32(bytes, 20)
    }

    private fun webpDimensions(bytes: ByteArray): Pair<Int, Int>? = when (ascii(bytes, 12, 4)) {
        "VP8X" -> if (bytes.size < 30) null else (le24(bytes, 24) + 1) to (le24(bytes, 27) + 1)
        "VP8L" -> {
            if (bytes.size < 25 || u8(bytes, 20) != 0x2F) null else {
                val bits = le32(bytes, 21)
                ((bits and 0x3FFF) + 1) to (((bits ushr 14) and 0x3FFF) + 1)
            }
        }
        "VP8 " -> {
            val startCode = bytes.size >= 26 && u8(bytes, 23) == 0x9D && u8(bytes, 24) == 0x01 && u8(bytes, 25) == 0x2A
            if (!startCode || bytes.size < 30) null else (le16(bytes, 26) and 0x3FFF) to (le16(bytes, 28) and 0x3FFF)
        }
        else -> null
    }

    private fun ascii(bytes: ByteArray, offset: Int, length: Int): String {
        if (offset < 0 || offset + length > bytes.size) return ""
        return String(bytes, offset, length, Charsets.US_ASCII)
    }

    private fun u8(bytes: ByteArray, i: Int): Int = bytes[i].toInt() and 0xFF
    private fun le16(bytes: ByteArray, i: Int): Int = u8(bytes, i) or (u8(bytes, i + 1) shl 8)
    private fun le24(bytes: ByteArray, i: Int): Int = u8(bytes, i) or (u8(bytes, i + 1) shl 8) or (u8(bytes, i + 2) shl 16)
    private fun le32(bytes: ByteArray, i: Int): Int =
        u8(bytes, i) or (u8(bytes, i + 1) shl 8) or (u8(bytes, i + 2) shl 16) or (u8(bytes, i + 3) shl 24)
    private fun be32(bytes: ByteArray, i: Int): Int =
        (u8(bytes, i) shl 24) or (u8(bytes, i + 1) shl 16) or (u8(bytes, i + 2) shl 8) or u8(bytes, i + 3)
}
