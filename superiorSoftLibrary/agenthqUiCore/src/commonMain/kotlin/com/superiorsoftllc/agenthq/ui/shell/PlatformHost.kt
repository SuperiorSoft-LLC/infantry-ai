package com.superiorsoftllc.agenthq.ui.shell

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.draganddrop.DragAndDropEvent

/**
 * What the shared shell needs from the platform that hosts it. Each platform owns an implementation in its
 * own source set (JVM desktop: environment + files; Android: Intent extras + SharedPreferences), so common
 * code never touches `System`, `java.io` or an Android `Context`.
 */
interface PlatformHost {
    /** Daemon URL used when nothing is configured. Platform-owned: loopback on desktop, the emulator host alias on Android. */
    val defaultDaemonUrl: String

    /** A configuration value by key (an environment variable on desktop), or null when unset. */
    fun setting(key: String): String?

    /** The text of a file, or null when it cannot be read. Never throws. */
    fun readTextFile(path: String): String?

    /** CLIPATH-1: whether [pickFile] can show a file picker on this platform (Settings > CLI locations' Browse). */
    val canPickFiles: Boolean get() = false

    /** CLIPATH-1: let the user choose a file, titled [title]; its absolute path, or null when cancelled / unsupported. */
    fun pickFile(title: String): String? = null

    /** PKG-3: the folder this app writes its log files to (the installed desktop app), or null when it writes none. */
    val logsDir: String? get() = null

    /** PKG-3: open [path] in the platform's file manager; false when unsupported or it failed. Desktop only. */
    fun openFolder(path: String): Boolean = false

    /** UPDATE-1: Configuration > Updates (the installed desktop app only); null hides the section. */
    val updates: com.superiorsoftllc.agenthq.ui.UpdatesEntry? get() = null

    /** IMG-4: the bytes of the file at [path], or null when it cannot be read here. Never throws. */
    fun readBytes(path: String): ByteArray? = null

    /** IMG-5: whether a file is at [path]; null when this host cannot look (so nothing is shown as expired). */
    fun fileExists(path: String): Boolean? = null

    /** IMG-4: the image on the clipboard (copied pixels, or a copied image file), or null when there is none. */
    fun clipboardImage(): PickedImage? = null

    /** IMG-4: the image files a drag and drop carried onto the window; empty when none (or unsupported). */
    fun droppedImages(event: DragAndDropEvent): List<PickedImage> = emptyList()

    /** ATTACH-INLINE-1: every file a drop carried, of any type (the chat composer takes them all). */
    fun droppedFiles(event: DragAndDropEvent): List<PickedImage> = droppedImages(event)

    /** ATTACH-INLINE-1: a copied file of any type, else the clipboard's image; null when there is neither. */
    fun clipboardFile(): PickedImage? = clipboardImage()

    /** ATTACH-INLINE-1: the size of the file at [path] in bytes, so an oversize one is refused unread; null = unknown. */
    fun fileSize(path: String): Long? = null

    /**
     * MOBILE-CONNCHIP-1: what the title bar's connection chip does instead of opening the shared connect dialog
     * (Android: back to its own ConnectScreen). Null keeps the dialog (desktop).
     */
    val onChangeConnection: (() -> Unit)? get() = null
}

/**
 * IMG-4: an image the user picked, pasted or dropped, ready to upload. [mimeType] may be blank (the daemon sniffs).
 * ATTACH-INLINE-1: any file, in fact; [isImage] says which. [size] is the file's real size: a host leaves [bytes] empty
 * for a file over the chat limit rather than read it, and the composer refuses it by [size].
 */
class PickedImage(val fileName: String, val mimeType: String, val bytes: ByteArray, val size: Long = bytes.size.toLong()) {
    val isImage: Boolean get() = isImageName(fileName)

    companion object {
        /** The mime type a file name's extension names, or "" for anything that is not one of the four accepted. */
        fun mimeOf(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            else -> ""
        }

        fun isImageName(fileName: String): Boolean = mimeOf(fileName).isNotEmpty()
    }
}

/**
 * IMG-4: the host the composition runs on, for what a screen deep in the tree needs from the platform (reading an
 * image, the clipboard, a drop). [AgentHqShell] provides its own; a bare [com.superiorsoftllc.agenthq.ui.App] gets the
 * platform default.
 */
val LocalPlatformHost = staticCompositionLocalOf<PlatformHost> { defaultPlatformHost() }

/** The default host of the running platform (desktop environment on the JVM, a context-free default on Android). */
expect fun defaultPlatformHost(): PlatformHost

/** Setting keys shared with the CLI so one environment configures every client. */
object ShellKeys {
    const val WS_URL = "AGENTHQ_WS_URL"
    const val TOKEN_FILE = "AGENTHQ_TOKEN_FILE"
    const val TOKEN = "AGENTHQ_TOKEN"
}

/** First non-blank value across ordered sources (e.g. Intent extras, then saved preferences). */
class LayeredSettings(private vararg val sources: (String) -> String?) {
    operator fun get(key: String): String? = sources.firstNotNullOfOrNull { source -> source(key)?.takeIf { it.isNotBlank() } }
}
