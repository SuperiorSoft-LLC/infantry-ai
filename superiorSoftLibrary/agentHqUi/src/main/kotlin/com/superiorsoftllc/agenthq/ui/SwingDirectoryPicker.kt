package com.superiorsoftllc.agenthq.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.swing.JFileChooser

/** The platform folder chooser (Swing). Runs off the UI thread; null when cancelled. */
val SwingDirectoryPicker = DirectoryPicker { initial ->
    withContext(Dispatchers.IO) {
        val chooser = JFileChooser().apply {
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            dialogTitle = "Choose the project directory"
            initial.takeIf { it.isNotBlank() }?.let { File(it).takeIf(File::exists)?.let { f -> currentDirectory = f } }
        }
        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.absolutePath else null
    }
}
