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
 * PATH-PICKER-1: the desktop chooser behind every path field's Browse. Files use the OS dialog (`java.awt.FileDialog`: the
 * Windows/macOS file dialog, GTK on Linux). Folders use the OS dialog on macOS (`apple.awt.fileDialogForDirectories`); AWT
 * has no native folder dialog elsewhere, so there a folder chooser is drawn in the system look and feel (Windows/GTK), not
 * Swing's own. The dialog is modal on a worker thread, so the UI thread keeps drawing; null = cancelled.
 */
internal object DesktopPathPicker : PathPicker {
    private val mac = System.getProperty("os.name").orEmpty().lowercase().startsWith("mac")
    private val windows = System.getProperty("os.name").orEmpty().lowercase().startsWith("windows")

    override suspend fun pick(request: PathRequest): String? = withContext(Dispatchers.IO) {
        runCatching {
            when {
                request.kind == PathKind.FILE -> fileDialog(request, folders = false)
                mac -> fileDialog(request, folders = true)
                else -> systemLookFolder(request)
            }
        }.getOrNull()
    }

    /** The directory a dialog opens in: [PathRequest.initial] when it exists (its folder, for a file), else none. */
    private fun startDir(initial: String): File? =
        initial.takeIf { it.isNotBlank() }?.let(::File)?.let { if (it.isDirectory) it else it.parentFile }?.takeIf { it.isDirectory }

    private fun fileDialog(request: PathRequest, folders: Boolean): String? {
        val key = "apple.awt.fileDialogForDirectories"
        val before = System.getProperty(key)
        if (folders) System.setProperty(key, "true")
        try {
            val dialog = java.awt.FileDialog(null as java.awt.Frame?, request.title, java.awt.FileDialog.LOAD)
            startDir(request.initial)?.let { dialog.directory = it.absolutePath }
            val exts = request.extensions.map { it.removePrefix(".").lowercase() }.filter { it.isNotEmpty() }
            if (!folders && exts.isNotEmpty()) {
                // macOS/GTK honour the filter; the Windows dialog ignores it but takes a wildcard file name instead.
                dialog.filenameFilter = java.io.FilenameFilter { _, name -> name.substringAfterLast('.', "").lowercase() in exts }
                if (windows) dialog.file = exts.joinToString(";") { "*.$it" }
            }
            dialog.isVisible = true // modal: returns once the user chose or cancelled
            val name = dialog.file ?: return null
            return File(dialog.directory, name).absolutePath
        } finally {
            if (folders) { if (before == null) System.clearProperty(key) else System.setProperty(key, before) }
        }
    }

    private fun systemLookFolder(request: PathRequest): String? {
        // The chooser takes its look when it is built: build it under the system look, then put the app's look back.
        val previous = javax.swing.UIManager.getLookAndFeel()
        val chooser = try {
            runCatching { javax.swing.UIManager.setLookAndFeel(javax.swing.UIManager.getSystemLookAndFeelClassName()) }
            javax.swing.JFileChooser().apply {
                fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
                dialogTitle = request.title
                startDir(request.initial)?.let { currentDirectory = it }
            }
        } finally {
            runCatching { if (previous != null) javax.swing.UIManager.setLookAndFeel(previous) }
        }
        return if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) chooser.selectedFile.absolutePath else null
    }
}
