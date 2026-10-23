package com.superiorsoftllc.agenthq.ui.shell

import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.awtTransferable
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

/**
 * Desktop/JVM host: settings are environment variables (as the CLI reads them), files are local files.
 *
 * PORT-1: the default daemon URL follows the port the daemon actually bound, recorded in
 * `<stateDir>/daemon.port` (state dir = `AGENTHQ_STATE_DIR`, else `~/.agenthq`, as the daemon reads it). With no
 * file it is the fixed 8787. An explicit `AGENTHQ_WS_URL` still wins over both (see `ShellConfig`).
 */
class JvmPlatformHost(
    private val env: (String) -> String? = System::getenv,
    private val home: String? = System.getProperty("user.home"),
) : PlatformHost {
    override val defaultDaemonUrl: String = "ws://127.0.0.1:${recordedPort() ?: DEFAULT_PORT}/ws"
    override fun setting(key: String): String? = env(key)
    override fun readTextFile(path: String): String? = runCatching { File(path).readText() }.getOrNull()

    /** CLIPATH-1: the native file dialog, when there is a display (never on a headless JVM). */
    override val canPickFiles: Boolean get() = !java.awt.GraphicsEnvironment.isHeadless()

    /** CLIPATH-1: a modal native "open file" dialog; the chosen file's absolute path, or null when cancelled. */
    override fun pickFile(title: String): String? = runCatching {
        val dialog = java.awt.FileDialog(null as java.awt.Frame?, title, java.awt.FileDialog.LOAD)
        dialog.isVisible = true // modal: returns once the user chose or cancelled
        val name = dialog.file ?: return@runCatching null
        File(dialog.directory, name).absolutePath
    }.getOrNull()

    /** PKG-3: the desktop file manager (Explorer on Windows), when there is a display and the platform supports it. */
    override fun openFolder(path: String): Boolean = runCatching {
        val dir = File(path).apply { mkdirs() }
        if (java.awt.GraphicsEnvironment.isHeadless() || !java.awt.Desktop.isDesktopSupported()) return@runCatching false
        val desktop = java.awt.Desktop.getDesktop()
        if (!desktop.isSupported(java.awt.Desktop.Action.OPEN)) return@runCatching false
        desktop.open(dir)
        true
    }.getOrDefault(false)

    override fun readBytes(path: String): ByteArray? = runCatching { File(path).takeIf { it.isFile }?.readBytes() }.getOrNull()

    override fun fileExists(path: String): Boolean? = runCatching { File(path).isFile }.getOrNull()

    /** IMG-4: a copied image file first (it keeps its own bytes), else copied pixels encoded as png. */
    override fun clipboardImage(): PickedImage? = runCatching {
        if (java.awt.GraphicsEnvironment.isHeadless()) return@runCatching null
        val contents = Toolkit.getDefaultToolkit().systemClipboard.getContents(null) ?: return@runCatching null
        imageFiles(contents).firstOrNull() ?: run {
            if (!contents.isDataFlavorSupported(DataFlavor.imageFlavor)) return@run null
            val image = contents.getTransferData(DataFlavor.imageFlavor) as? java.awt.Image ?: return@run null
            PickedImage("pasted.png", "image/png", png(image))
        }
    }.getOrNull()

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    override fun droppedImages(event: DragAndDropEvent): List<PickedImage> =
        runCatching { event.awtTransferable.let(::imageFiles) }.getOrDefault(emptyList())

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    override fun droppedFiles(event: DragAndDropEvent): List<PickedImage> =
        runCatching { event.awtTransferable.let { anyFiles(it) } }.getOrDefault(emptyList())

    override fun clipboardFile(): PickedImage? = runCatching {
        if (java.awt.GraphicsEnvironment.isHeadless()) return@runCatching null
        val contents = Toolkit.getDefaultToolkit().systemClipboard.getContents(null) ?: return@runCatching null
        anyFiles(contents).firstOrNull()
    }.getOrNull() ?: clipboardImage()

    override fun fileSize(path: String): Long? = runCatching { File(path).takeIf { it.isFile }?.length() }.getOrNull()

    private fun imageFiles(contents: Transferable): List<PickedImage> = anyFiles(contents).filter { it.isImage }

    /** ATTACH-INLINE-1: the copied / dropped files; one over the chat limit is listed by size, unread. */
    private fun anyFiles(contents: Transferable): List<PickedImage> {
        if (!contents.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return emptyList()
        val files = (contents.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>).orEmpty().filterIsInstance<File>()
        return files.filter { it.isFile }.map { f ->
            val size = f.length()
            val bytes = if (size > READ_LIMIT) ByteArray(0) else f.readBytes()
            PickedImage(f.name, PickedImage.mimeOf(f.name), bytes, size)
        }
    }

    private fun png(image: java.awt.Image): ByteArray {
        val buffered = image as? BufferedImage ?: BufferedImage(image.getWidth(null), image.getHeight(null), BufferedImage.TYPE_INT_ARGB)
            .also { b -> b.createGraphics().apply { drawImage(image, 0, 0, null); dispose() } }
        return ByteArrayOutputStream().also { ImageIO.write(buffered, "png", it) }.toByteArray()
    }

    private fun recordedPort(): Int? {
        val stateDir = env("AGENTHQ_STATE_DIR")?.takeIf { it.isNotBlank() }?.let(::File)
            ?: home?.let { File(it, ".agenthq") }
            ?: return null
        return runCatching { File(stateDir, PORT_FILE).readText().trim().toInt() }.getOrNull()?.takeIf { it in 1..65535 }
    }

    private companion object {
        const val DEFAULT_PORT = 8787
        /** No file over the chat's largest limit (20 MB) is read into memory. */
        const val READ_LIMIT = 20L * 1024 * 1024
        /** Same name as the daemon's `DaemonPort.FILE_NAME`; this module does not depend on the daemon. */
        const val PORT_FILE = "daemon.port"
    }
}

actual fun defaultPlatformHost(): PlatformHost = JvmPlatformHost()
