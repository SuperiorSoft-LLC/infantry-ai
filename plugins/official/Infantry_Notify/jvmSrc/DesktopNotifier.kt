// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.notify

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.superiorsoftllc.infantry.ui.shell.LocalPlatformHost
import com.superiorsoftllc.infantry.ui.sound.SoundPlayer
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.awt.Color
import java.awt.Desktop
import java.awt.EventQueue
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.net.URI
import javax.sound.sampled.AudioSystem

@Composable
internal actual fun rememberNotifier(): Notifier {
    val player = LocalPlatformHost.current.soundPlayer
    return remember(player) { DesktopNotifier(player) }
}

/**
 * The desktop's system notification: a Windows toast through the tray (`TrayIcon.displayMessage`; the tray icon is added
 * on the first notification, so a desktop that never gets one shows no icon). Clicking it brings the window to the front,
 * opens its link (if any) and its ticket. WAV plays through javax.sound; mp3 through the app's own player; ogg is not
 * played on desktop.
 */
internal class DesktopNotifier(private val mp3Player: SoundPlayer?) : Notifier {
    override val platform = "desktop"
    private val clicks = MutableSharedFlow<String>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val opened: Flow<String> = clicks

    override fun show(p: NotifyPayload): Boolean {
        val icon = Tray.icon() ?: return false
        Tray.lastClicked = { onClick(p) }
        val type = when (p.level) { "urgent" -> TrayIcon.MessageType.WARNING; else -> TrayIcon.MessageType.INFO }
        icon.displayMessage(p.title, p.body.ifBlank { " " }, type)
        return true
    }

    private fun onClick(p: NotifyPayload) {
        EventQueue.invokeLater {
            Frame.getFrames().firstOrNull { it.isDisplayable && it.isVisible }?.let { f ->
                if (f.extendedState and Frame.ICONIFIED != 0) f.extendedState = f.extendedState and Frame.ICONIFIED.inv()
                f.toFront(); f.requestFocus()
            }
            p.url?.let { u -> runCatching { if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI(u)) } }
        }
        p.ticketId?.let { clicks.tryEmit(it) }
    }

    override fun play(type: String, bytes: ByteArray): Boolean = when (type) {
        "audio/wav" -> playWav(bytes)
        "audio/mpeg" -> mp3Player?.play(bytes, 100) ?: false
        else -> false
    }

    private fun playWav(bytes: ByteArray): Boolean = runCatching {
        val stream = AudioSystem.getAudioInputStream(ByteArrayInputStream(bytes))
        val clip = AudioSystem.getClip()
        clip.open(stream)
        clip.addLineListener { e -> if (e.type == javax.sound.sampled.LineEvent.Type.STOP) clip.close() }
        clip.start()
        true
    }.getOrDefault(false)

    /** One tray icon for the app, made on first use. */
    private object Tray {
        @Volatile var lastClicked: (() -> Unit)? = null
        private var made: TrayIcon? = null

        @Synchronized
        fun icon(): TrayIcon? {
            made?.let { return it }
            if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) return null
            return runCatching {
                val tray = SystemTray.getSystemTray()
                val size = tray.trayIconSize
                val icon = TrayIcon(image(maxOf(16, size.width), maxOf(16, size.height)), "Infantry AI")
                icon.isImageAutoSize = true
                // A click on the toast (Windows sends an action event) and a double click on the icon both open the app.
                icon.addActionListener { lastClicked?.invoke() }
                tray.add(icon)
                icon
            }.getOrNull().also { made = it }
        }

        private fun image(w: Int, h: Int): BufferedImage = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB).apply {
            val g = createGraphics()
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = Color(0x2E, 0x7D, 0x32)
            g.fillOval(0, 0, w - 1, h - 1)
            g.color = Color.WHITE
            g.fillRect(w * 7 / 16, h / 4, w / 8, h / 2)
            g.dispose()
        }
    }
}
