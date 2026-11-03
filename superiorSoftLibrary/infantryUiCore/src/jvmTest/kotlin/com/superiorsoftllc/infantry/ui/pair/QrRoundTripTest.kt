package com.superiorsoftllc.infantry.ui.pair

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class QrRoundTripTest {
    /** Renders the matrix the way [QrCodeView] does (quiet zone, 6 px modules) to PNG bytes. */
    private fun png(text: String): ByteArray {
        val m = qrMatrix(text)
        val cell = 6
        val quiet = 4
        val side = (m.size + 2 * quiet) * cell
        val image = BufferedImage(side, side, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until side) for (x in 0 until side) {
            val mx = x / cell - quiet
            val my = y / cell - quiet
            val dark = my in m.indices && mx in m[my].indices && m[my][mx]
            image.setRGB(x, y, if (dark) 0x000000 else 0xFFFFFF)
        }
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    @Test
    fun encodeThenDecodeGivesTheSamePayload() {
        for (link in listOf(
            PairingLink("ws://100.67.51.8:8797/ws", "k9Qx-long-bearer-token_0123456789abcdef0123456789", "Gaming PC"),
            PairingLink("wss://agent.example.net/ws", "a+b/c=d&e%f", "Anthony's 中 box"),
            PairingLink("ws://[fd7a:115c:a1e0::1]:8797/ws", "t", null),
        )) {
            val decoded = QrImageDecoder.decode(png(link.toUri()))
            assertEquals(link.toUri(), decoded)
            assertEquals(link, assertIs<PairingLink.Parsed.Ok>(PairingLink.parse(decoded!!)).link)
        }
    }

    @Test
    fun aPictureWithoutAQrCodeDecodesToNull() {
        val blank = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()
        assertNull(QrImageDecoder.decode(blank))
        assertNull(QrImageDecoder.decode(byteArrayOf(1, 2, 3)))
    }
}
