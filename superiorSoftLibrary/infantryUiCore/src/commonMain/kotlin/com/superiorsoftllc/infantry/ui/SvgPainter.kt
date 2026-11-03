package com.superiorsoftllc.infantry.ui

import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Density

/** Decode SVG bytes to a Painter; null where the target cannot (Android has no SVG decoder in compose resources). */
internal expect fun decodeSvgPainter(bytes: ByteArray, density: Density): Painter?
