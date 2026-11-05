// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.infantry.ui.shell.LocalPlatformHost
import kotlinx.coroutines.launch

/** PATH-PICKER-1: what a [PathPicker] asks the user to choose. */
enum class PathKind { FOLDER, FILE }

/**
 * PATH-PICKER-1: one picker request. [initial] is where the dialog opens (may be blank); [extensions] narrows a [PathKind.FILE]
 * chooser (`ihq`, no dot; empty = any file). A folder request ignores [extensions].
 */
data class PathRequest(val kind: PathKind, val title: String, val initial: String = "", val extensions: List<String> = emptyList())

/**
 * PATH-PICKER-1: the platform's native folder/file chooser. Suspends (never blocks the UI thread) and answers the chosen
 * absolute path, or null when the user cancelled.
 */
fun interface PathPicker {
    suspend fun pick(request: PathRequest): String?
}

/**
 * PATH-PICKER-1: the picker of this composition. [com.superiorsoftllc.infantry.ui.shell.InfantryShell] provides the host's
 * own (`PlatformHost.pathPicker`); a test injects a fake. Null = the older [LocalDirectoryPicker] for folders, else none.
 */
val LocalPathPicker = staticCompositionLocalOf<PathPicker?> { null }

/** PATH-PICKER-1: the picker this composition uses for [kind], or null where the platform has none (Browse is then hidden). */
@Composable
fun currentPathPicker(kind: PathKind): PathPicker? {
    LocalPathPicker.current?.let { return it }
    if (kind == PathKind.FOLDER) LocalDirectoryPicker.current?.let { dir -> return PathPicker { dir.pick(it.initial) } }
    return null
}

const val PATH_FIELD_BROWSE_TAG_SUFFIX = "-browse"

/**
 * PATH-PICKER-1: the one path field: a text box plus "Browse…", which opens the platform's native folder or file chooser
 * ([kind], [extensions]). A picked path goes to [onPicked] (default: [onValueChange], so it just fills the box); typing still
 * works. Where the platform has no chooser (web: paths live on the daemon's computer; Android: the system picker answers a
 * content URI, not a path the daemon can open) there is no Browse button, and [noPickerNote] (the host's own, by default)
 * says what to type instead. [tag] tags the box; Browse is `tag + PATH_FIELD_BROWSE_TAG_SUFFIX`.
 */
@Composable
fun PathField(
    value: String,
    onValueChange: (String) -> Unit,
    kind: PathKind,
    label: String,
    modifier: Modifier = Modifier,
    tag: String? = null,
    title: String = label,
    extensions: List<String> = emptyList(),
    enabled: Boolean = true,
    textStyle: TextStyle = TextStyle(fontSize = 12.sp),
    placeholder: String? = null,
    isError: Boolean = false,
    supportingText: String? = null,
    onPicked: (String) -> Unit = onValueChange,
    noPickerNote: String? = LocalPlatformHost.current.pathPickerNote,
) {
    val picker = currentPathPicker(kind)
    val scope = rememberCoroutineScope()
    Column(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value, onValueChange, label = { Text(label) }, singleLine = true, enabled = enabled, isError = isError,
                textStyle = textStyle, shape = Palette.PopupShape,
                placeholder = placeholder?.let { { Text(it, fontSize = 12.sp) } },
                supportingText = supportingText?.let { { Text(it, fontSize = 10.sp) } },
                modifier = Modifier.weight(1f).let { m -> tag?.let { m.testTag(it) } ?: m },
            )
            if (picker != null) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val chosen = picker.pick(PathRequest(kind, title, value.trim(), extensions))?.trim().orEmpty()
                            if (chosen.isNotEmpty()) onPicked(chosen)
                        }
                    },
                    enabled = enabled,
                    modifier = Modifier.heightIn(min = 40.dp).semantics { contentDescription = "Browse for $label" }
                        .let { m -> tag?.let { m.testTag(it + PATH_FIELD_BROWSE_TAG_SUFFIX) } ?: m },
                ) { Text("Browse…", fontSize = 12.sp) }
            }
        }
        if (picker == null && noPickerNote != null) Text(noPickerNote, color = Palette.Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
    }
}
