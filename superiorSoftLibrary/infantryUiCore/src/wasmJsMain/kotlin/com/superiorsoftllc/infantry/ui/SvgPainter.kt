package com.superiorsoftllc.infantry.ui

import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Density
import org.jetbrains.compose.resources.decodeToSvgPainter

internal actual fun decodeSvgPainter(bytes: ByteArray, density: Density): Painter? = bytes.decodeToSvgPainter(density)
