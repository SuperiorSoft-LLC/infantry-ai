// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.pair

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.BarcodeFormat
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/** PAIR-1: the text of the QR code in a picture (png / jpg / gif), or null when there is none. Never throws. */
object QrImageDecoder {
    fun decode(bytes: ByteArray): String? = runCatching {
        val image = ImageIO.read(ByteArrayInputStream(bytes)) ?: return null
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(image.width, image.height, image.getRGB(0, 0, image.width, image.height, null, 0, image.width))))
        val hints = mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true)
        MultiFormatReader().decode(bitmap, hints).text
    }.getOrNull()
}
