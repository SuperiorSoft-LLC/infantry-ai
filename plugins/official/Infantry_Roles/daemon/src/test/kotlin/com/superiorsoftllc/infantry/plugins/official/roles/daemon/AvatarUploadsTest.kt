package com.superiorsoftllc.infantry.plugins.official.roles.daemon

import com.superiorsoftllc.infantry.protocol.InvalidAvatarException

import com.superiorsoftllc.infantry.protocol.ImageFormat

import com.superiorsoftllc.infantry.protocol.AvatarUploads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The avatar upload guardrails: PNG/WebP only, ~512px, ~256KB. */
class AvatarUploadsTest {

    @Test
    fun detectsPngAndWebp() {
        assertEquals(ImageFormat.PNG, AvatarUploads.detect(pngBytes(16, 16)))
        assertEquals(ImageFormat.WEBP, AvatarUploads.detect(webpVp8x(16, 16)))
        assertNull(AvatarUploads.detect("GIF89a".toByteArray()))
        assertNull(AvatarUploads.detect(byteArrayOf()))
    }

    @Test
    fun readsPngDimensions() {
        assertEquals(320 to 200, AvatarUploads.dimensions(pngBytes(320, 200), ImageFormat.PNG))
    }

    @Test
    fun readsRealPngDimensions() {
        // A PNG the JDK itself writes, so the reader is proven against a real file, not only a header.
        val image = java.awt.image.BufferedImage(37, 11, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val out = java.io.ByteArrayOutputStream()
        javax.imageio.ImageIO.write(image, "png", out)
        assertEquals(37 to 11, AvatarUploads.dimensions(out.toByteArray(), ImageFormat.PNG))
    }

    @Test
    fun readsEveryWebpLayout() {
        assertEquals(100 to 40, AvatarUploads.dimensions(webpVp8x(100, 40), ImageFormat.WEBP))
        assertEquals(100 to 40, AvatarUploads.dimensions(webpVp8l(100, 40), ImageFormat.WEBP))
        assertEquals(100 to 40, AvatarUploads.dimensions(webpVp8(100, 40), ImageFormat.WEBP))
    }

    @Test
    fun validateAcceptsAWellFormedUpload() {
        val valid = AvatarUploads.validate(pngBytes(512, 512))
        assertEquals(ImageFormat.PNG, valid.format)
        assertEquals(512, valid.width)
        assertEquals(512, valid.height)
        assertEquals(ImageFormat.WEBP, AvatarUploads.validate(webpVp8l(1, 1)).format)
    }

    @Test
    fun validateRejectsANonImage() {
        val error = kotlin.runCatching { AvatarUploads.validate("just text".toByteArray()) }.exceptionOrNull()
        assertTrue(error is InvalidAvatarException, "expected InvalidAvatarException, got $error")
        assertTrue(error.message!!.contains("PNG and WebP"))
    }

    @Test
    fun validateRejectsAnOversizedFile() {
        val big = pngBytes(16, 16).copyOf(300_000)
        val error = kotlin.runCatching { AvatarUploads.validate(big) }.exceptionOrNull()
        assertTrue(error is InvalidAvatarException)
        assertTrue(error.message!!.contains("KB"), error.message)
    }

    @Test
    fun validateRejectsAnOversizedImage() {
        val error = kotlin.runCatching { AvatarUploads.validate(pngBytes(513, 10)) }.exceptionOrNull()
        assertTrue(error is InvalidAvatarException)
        assertTrue(error.message!!.contains("512x512"), error.message)
    }

    @Test
    fun validateRejectsATruncatedHeader() {
        val error = kotlin.runCatching { AvatarUploads.validate(pngBytes(16, 16).copyOf(12)) }.exceptionOrNull()
        assertTrue(error is InvalidAvatarException)
    }

    @Test
    fun formatFromFileNameIgnoresCase() {
        assertEquals(ImageFormat.PNG, ImageFormat.fromFileName("architect.PNG"))
        assertEquals(ImageFormat.WEBP, ImageFormat.fromFileName("a.webp"))
        assertNull(ImageFormat.fromFileName("a.gif"))
        assertFalse(ImageFormat.PNG.mediaType.isBlank())
    }

    // --- helpers --------------------------------------------------------------------------------

    private fun pngBytes(width: Int, height: Int): ByteArray {
        val bytes = ByteArray(24)
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A).copyInto(bytes, 0)
        "IHDR".toByteArray(Charsets.US_ASCII).copyInto(bytes, 12)
        putBe32(bytes, 16, width)
        putBe32(bytes, 20, height)
        return bytes
    }

    private fun webpVp8x(width: Int, height: Int): ByteArray {
        val bytes = ByteArray(30)
        webpHeader(bytes, "VP8X")
        putLe24(bytes, 24, width - 1)
        putLe24(bytes, 27, height - 1)
        return bytes
    }

    private fun webpVp8l(width: Int, height: Int): ByteArray {
        val bytes = ByteArray(25)
        webpHeader(bytes, "VP8L")
        bytes[20] = 0x2F
        putLe32(bytes, 21, (width - 1) or ((height - 1) shl 14))
        return bytes
    }

    private fun webpVp8(width: Int, height: Int): ByteArray {
        val bytes = ByteArray(30)
        webpHeader(bytes, "VP8 ")
        bytes[23] = 0x9D.toByte(); bytes[24] = 0x01; bytes[25] = 0x2A
        putLe16(bytes, 26, width)
        putLe16(bytes, 28, height)
        return bytes
    }

    private fun webpHeader(bytes: ByteArray, fourcc: String) {
        "RIFF".toByteArray(Charsets.US_ASCII).copyInto(bytes, 0)
        "WEBP".toByteArray(Charsets.US_ASCII).copyInto(bytes, 8)
        fourcc.toByteArray(Charsets.US_ASCII).copyInto(bytes, 12)
    }

    private fun putBe32(b: ByteArray, i: Int, v: Int) {
        b[i] = (v ushr 24).toByte(); b[i + 1] = (v ushr 16).toByte()
        b[i + 2] = (v ushr 8).toByte(); b[i + 3] = v.toByte()
    }

    private fun putLe16(b: ByteArray, i: Int, v: Int) {
        b[i] = v.toByte(); b[i + 1] = (v ushr 8).toByte()
    }

    private fun putLe24(b: ByteArray, i: Int, v: Int) {
        b[i] = v.toByte(); b[i + 1] = (v ushr 8).toByte(); b[i + 2] = (v ushr 16).toByte()
    }

    private fun putLe32(b: ByteArray, i: Int, v: Int) {
        b[i] = v.toByte(); b[i + 1] = (v ushr 8).toByte()
        b[i + 2] = (v ushr 16).toByte(); b[i + 3] = (v ushr 24).toByte()
    }
}
