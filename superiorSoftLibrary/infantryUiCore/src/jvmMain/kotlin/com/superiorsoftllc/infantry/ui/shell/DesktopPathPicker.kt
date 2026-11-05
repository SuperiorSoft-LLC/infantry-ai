// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.shell

import com.superiorsoftllc.infantry.ui.PathKind
import com.superiorsoftllc.infantry.ui.PathPicker
import com.superiorsoftllc.infantry.ui.PathRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * PATH-PICKER-1 / NATIVE-PICKER-1: the desktop chooser behind every path field's Browse, the chat attachment button and
 * every other "open a file" on the desktop. Each OS gets its own native dialog:
 * - Windows: the Explorer-style common item dialog ([WindowsFileDialog], `IFileOpenDialog` over JNA), files and folders.
 * - macOS: `java.awt.FileDialog`, which is NSOpenPanel (folders with `apple.awt.fileDialogForDirectories`).
 * - Linux: zenity, then kdialog (the desktop's own GTK / KDE dialog, through the portal where the desktop routes it).
 * When the native dialog cannot run, it logs why and falls back to `java.awt.FileDialog` for files and a system-look
 * Swing chooser for folders. The dialog never runs on the UI thread's own stack: see [awaitOffUiThread].
 */
internal object DesktopPathPicker : PathPicker {
    private val osName = System.getProperty("os.name").orEmpty().lowercase()
    private val mac = osName.startsWith("mac")
    private val windows = osName.startsWith("windows")
    private val log: (String) -> Unit = { System.err.println("[native-picker] $it") }

    override suspend fun pick(request: PathRequest): String? = withContext(Dispatchers.IO) {
        choose(request.kind == PathKind.FOLDER, request.title, request.initial, request.extensions, multi = false)?.firstOrNull()
    }

    /**
     * The chosen paths (absolute; one unless [multi]), or null when cancelled. [extensions] narrows a file dialog (with
     * an "All files" row beside it); [initial] is a file or folder the dialog opens at. Blocks the caller: call it off the
     * UI thread, or through [awaitOffUiThread].
     */
    fun choose(folders: Boolean, title: String, initial: String, extensions: List<String>, multi: Boolean): List<String>? {
        val exts = extensions.map { it.removePrefix(".").lowercase() }.filter { it.isNotEmpty() }.distinct()
        val start = startDir(initial)
        val native = runCatching {
            when {
                windows -> WindowsFileDialog.open(folders, title, start?.absolutePath, windowsFilters(exts), multi, ownerWindow())
                mac -> awtDialog(title, start, exts, folders, multi)
                else -> linuxDialog(folders, title, start, exts, multi)
            }
        }
        val failure = native.exceptionOrNull() ?: return native.getOrNull()
        log("native dialog unavailable (${failure.message}); using the Java chooser")
        return runCatching { if (folders) swingFolder(title, start) else awtDialog(title, start, exts, folders = false, multi) }
            .onFailure { log("Java chooser failed too: ${it.message}") }.getOrNull()
    }

    /** The directory a dialog opens in: [initial] when it exists (its folder, for a file), else none. */
    private fun startDir(initial: String): File? =
        initial.takeIf { it.isNotBlank() }?.let(::File)?.let { if (it.isDirectory) it else it.parentFile }?.takeIf { it.isDirectory }

    private fun windowsFilters(exts: List<String>): List<WindowsFileDialog.Filter> {
        if (exts.isEmpty()) return emptyList()
        val spec = exts.joinToString(";") { "*.$it" }
        return listOf(WindowsFileDialog.Filter("Allowed files ($spec)", spec), WindowsFileDialog.Filter("All files (*.*)", "*.*"))
    }

    /** The app's window (the focused one, else the first shown), as an HWND, so the dialog is modal to it. Null = none. */
    private fun ownerWindow(): com.sun.jna.Pointer? = runCatching {
        val window = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
            ?: java.awt.Frame.getFrames().firstOrNull { it.isShowing }
        window?.let { com.sun.jna.Native.getComponentPointer(it) }
    }.getOrNull()

    private fun ownerFrame(): java.awt.Frame? = runCatching {
        (java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow as? java.awt.Frame)
            ?: java.awt.Frame.getFrames().firstOrNull { it.isShowing }
    }.getOrNull()

    /** zenity, else kdialog; null = cancelled (a non-zero exit). Throws when neither is installed. */
    private fun linuxDialog(folders: Boolean, title: String, start: File?, exts: List<String>, multi: Boolean): List<String>? {
        val dir = start?.absolutePath?.let { if (it.endsWith("/")) it else "$it/" }
        val zenity = buildList {
            addAll(listOf("zenity", "--file-selection", "--title=$title"))
            if (folders) add("--directory")
            if (multi) addAll(listOf("--multiple", "--separator=\n"))
            dir?.let { add("--filename=$it") }
            if (!folders && exts.isNotEmpty()) { add("--file-filter=" + exts.joinToString(" ") { "*.$it" }); add("--file-filter=*") }
        }
        val kdialog = buildList {
            addAll(listOf("kdialog", "--title", title))
            if (multi && !folders) addAll(listOf("--multiple", "--separate-output"))
            add(if (folders) "--getexistingdirectory" else "--getopenfilename")
            add(dir ?: System.getProperty("user.home"))
            if (!folders && exts.isNotEmpty()) add(exts.joinToString(" ") { "*.$it" })
        }
        for (cmd in listOf(zenity, kdialog)) {
            val process = runCatching { ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start() }.getOrNull() ?: continue
            val out = process.inputStream.bufferedReader().readText()
            if (process.waitFor() != 0) return null
            return out.lines().map { it.trim() }.filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }
        }
        throw IllegalStateException("neither zenity nor kdialog is installed")
    }

    /** `java.awt.FileDialog`: NSOpenPanel on macOS (folders too), the GTK dialog on Linux, the legacy dialog on Windows. */
    private fun awtDialog(title: String, start: File?, exts: List<String>, folders: Boolean, multi: Boolean): List<String>? {
        val key = "apple.awt.fileDialogForDirectories"
        val before = System.getProperty(key)
        if (folders) System.setProperty(key, "true")
        try {
            val dialog = java.awt.FileDialog(ownerFrame(), title, java.awt.FileDialog.LOAD)
            start?.let { dialog.directory = it.absolutePath }
            dialog.isMultipleMode = multi && !folders
            if (!folders && exts.isNotEmpty()) {
                dialog.filenameFilter = java.io.FilenameFilter { _, name -> name.substringAfterLast('.', "").lowercase() in exts }
                if (windows) dialog.file = exts.joinToString(";") { "*.$it" }
            }
            dialog.isVisible = true // modal: returns once the user chose or cancelled
            if (multi && !folders) return dialog.files.map { it.absolutePath }.takeIf { it.isNotEmpty() }
            val name = dialog.file ?: return null
            return listOf(File(dialog.directory, name).absolutePath)
        } finally {
            if (folders) { if (before == null) System.clearProperty(key) else System.setProperty(key, before) }
        }
    }

    private fun swingFolder(title: String, start: File?): List<String>? {
        // The chooser takes its look when it is built: build it under the system look, then put the app's look back.
        val previous = javax.swing.UIManager.getLookAndFeel()
        val chooser = try {
            runCatching { javax.swing.UIManager.setLookAndFeel(javax.swing.UIManager.getSystemLookAndFeelClassName()) }
            javax.swing.JFileChooser().apply {
                fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
                dialogTitle = title
                start?.let { currentDirectory = it }
            }
        } finally {
            runCatching { if (previous != null) javax.swing.UIManager.setLookAndFeel(previous) }
        }
        return if (chooser.showOpenDialog(ownerFrame()) == javax.swing.JFileChooser.APPROVE_OPTION) listOf(chooser.selectedFile.absolutePath) else null
    }
}

/**
 * NATIVE-PICKER-1: runs [block] (a modal native dialog) on its own thread and returns its result. Called on the AWT event
 * thread (Compose's UI thread), that thread keeps pumping events in an AWT secondary loop meanwhile, so the window keeps
 * drawing; called elsewhere it just waits.
 */
internal fun <T> awaitOffUiThread(block: () -> T): T {
    var result: Result<T> = Result.failure(IllegalStateException("picker thread did not run"))
    val loop = if (java.awt.EventQueue.isDispatchThread()) java.awt.Toolkit.getDefaultToolkit().systemEventQueue.createSecondaryLoop() else null
    val worker = Thread({
        result = runCatching(block)
        // Posted, not called: it runs inside the loop once enter() pumps, so an early finish cannot miss the exit.
        loop?.let { l -> java.awt.EventQueue.invokeLater { l.exit() } }
    }, "native-picker")
    worker.isDaemon = true
    worker.start()
    loop?.enter()
    worker.join()
    return result.getOrThrow()
}
