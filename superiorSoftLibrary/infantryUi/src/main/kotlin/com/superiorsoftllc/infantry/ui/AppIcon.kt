package com.superiorsoftllc.infantry.ui

import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * APP-ICON-1: the window/taskbar icon, the Infantry AI emblem as a 256px transparent PNG generated
 * by icons/GenInfantryIcon.java. Without it AWT shows the runtime's default icon.
 */
object AppIcon {
    const val RESOURCE = "infantry-icon.png"

    fun image(): BufferedImage? =
        AppIcon::class.java.classLoader.getResourceAsStream(RESOURCE)?.use { ImageIO.read(it) }

    fun painter(): Painter? = image()?.let { BitmapPainter(it.toComposeImageBitmap()) }
}
