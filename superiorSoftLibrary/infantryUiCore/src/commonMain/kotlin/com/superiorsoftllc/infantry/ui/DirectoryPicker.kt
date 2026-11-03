package com.superiorsoftllc.infantry.ui

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * A native "choose a folder" dialog, supplied by the platform shell. Returns the chosen absolute path, or null
 * when the user cancelled. [initial] is the path to start from (may be blank).
 */
fun interface DirectoryPicker {
    suspend fun pick(initial: String): String?
}

/** Null where the platform has no folder chooser; the Git tab then shows only the text box. */
val LocalDirectoryPicker = staticCompositionLocalOf<DirectoryPicker?> { null }
