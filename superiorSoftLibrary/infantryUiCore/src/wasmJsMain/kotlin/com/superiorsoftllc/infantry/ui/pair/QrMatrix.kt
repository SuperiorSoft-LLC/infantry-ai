package com.superiorsoftllc.infantry.ui.pair

/** WEB-1: ZXing is JVM only, so the browser shows no pairing QR (pairing is a desktop/phone feature): an empty matrix. */
actual fun qrMatrix(text: String): Array<BooleanArray> = emptyArray()
