// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTransferAction
import androidx.compose.ui.geometry.Offset
import com.superiorsoftllc.infantry.ui.shell.JvmPlatformHost
import com.superiorsoftllc.infantry.ui.shell.PickedImage
import com.superiorsoftllc.infantry.ui.shell.PlatformHost
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.image.BufferedImage
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame

/**
 * BUG-PASTE-1: the real app (runRealDaemonUi, dev preview and installed) wraps the platform host in
 * [PinnedDaemonHost]. Every file/clipboard capability must reach the wrapped host, or paste, drop and attach
 * silently do nothing in the real app while every fake-host UI test still passes.
 */
class PinnedDaemonHostTest {
    private val png = PickedImage("shot.png", "image/png", byteArrayOf(1, 2, 3))

    private val base = object : PlatformHost {
        override val defaultDaemonUrl = "ws://127.0.0.1:1/ws"
        override fun setting(key: String): String? = null
        override fun readTextFile(path: String): String? = null
        override fun clipboardImage(): PickedImage = png
        override fun clipboardFile(): PickedImage = png
        override fun droppedImages(event: DragAndDropEvent): List<PickedImage> = listOf(png)
        override fun droppedFiles(event: DragAndDropEvent): List<PickedImage> = listOf(png)
        override fun readBytes(path: String): ByteArray = png.bytes
        override fun fileExists(path: String): Boolean = true
        override fun fileSize(path: String): Long = 3
    }

    private fun pinned(host: PlatformHost) = PinnedDaemonHost(host, "ws://127.0.0.1:9/ws", logsDir = null)

    @Test fun storedTokenIsSuppliedWithoutAPromptWhenNoneIsConfigured() {
        val host = PinnedDaemonHost(base, "ws://127.0.0.1:9/ws", logsDir = null, storedToken = { "stored-token" })
        assertEquals("stored-token", host.setting(com.superiorsoftllc.infantry.ui.shell.ShellKeys.TOKEN))
    }

    @Test fun explicitTokenBeatsTheStoredOne() {
        val withToken = object : PlatformHost by base {
            override fun setting(key: String): String? = if (key == com.superiorsoftllc.infantry.ui.shell.ShellKeys.TOKEN) "explicit" else null
        }
        val host = PinnedDaemonHost(withToken, "ws://127.0.0.1:9/ws", logsDir = null, storedToken = { "stored-token" })
        assertEquals("explicit", host.setting(com.superiorsoftllc.infantry.ui.shell.ShellKeys.TOKEN))
    }

    @Test fun remoteOffMeansNoTokenIsSupplied() {
        val host = PinnedDaemonHost(base, "ws://127.0.0.1:9/ws", logsDir = null, storedToken = { null })
        assertEquals(null, host.setting(com.superiorsoftllc.infantry.ui.shell.ShellKeys.TOKEN))
    }

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    @Test fun clipboardAndFileCapabilitiesReachTheWrappedHost() {
        val host = pinned(base)
        assertSame(png, host.clipboardImage())
        assertSame(png, host.clipboardFile())
        assertSame(png.bytes, host.readBytes("x"))
        assertEquals(true, host.fileExists("x"))
        assertEquals(3L, host.fileSize("x"))
        // The attach button reads the picked file; drag and drop reads the event. Same cause, same wrapper.
        val drop = DragAndDropEvent(DragAndDropTransferAction.Copy, nativeEvent = null, positionInRootImpl = Offset.Zero)
        assertEquals(listOf(png), host.droppedFiles(drop))
        assertEquals(listOf(png), host.droppedImages(drop))
    }

    /** The real AWT clipboard through the real host, as the owner's Ctrl+V reaches it: copied pixels, then a copied file. */
    @Test fun realClipboardImageAndFileArePastedThroughThePinnedHost() {
        assertFalse(GraphicsEnvironment.isHeadless(), "this test needs a display: the clipboard path is skipped when headless")
        val host = pinned(JvmPlatformHost(env = { null }, home = Files.createTempDirectory("infantry-home").toFile().absolutePath))
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard

        val pixels = BufferedImage(4, 3, BufferedImage.TYPE_INT_ARGB)
        clipboard.setContents(single(DataFlavor.imageFlavor, pixels), null)
        val pasted = assertNotNull(host.clipboardImage(), "copied pixels")
        assertEquals("image/png", pasted.mimeType)
        assertNotNull(host.clipboardFile(), "copied pixels via the composer's clipboardFile")

        val file = Files.createTempFile("bug-paste-1", ".png").toFile().apply { writeBytes(png.bytes); deleteOnExit() }
        clipboard.setContents(single(DataFlavor.javaFileListFlavor, listOf(file)), null)
        assertEquals(file.name, assertNotNull(host.clipboardImage(), "copied image file").fileName)
        assertEquals(file.name, assertNotNull(host.clipboardFile(), "copied file").fileName)
    }

    private fun single(flavor: DataFlavor, data: Any) = object : Transferable {
        override fun getTransferDataFlavors() = arrayOf(flavor)
        override fun isDataFlavorSupported(f: DataFlavor) = f == flavor
        override fun getTransferData(f: DataFlavor): Any = if (f == flavor) data else throw UnsupportedFlavorException(f)
    }
}
