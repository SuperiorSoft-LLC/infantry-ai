// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runDesktopComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** PATH-PICKER-1: the shared path field: Browse fills it through the injected picker, typing still works, no picker = no Browse. */
@OptIn(ExperimentalTestApi::class)
class PathFieldUiTest {
    private val tag = "path-under-test"

    @Test fun `Browse fills the field with the picked path, and a cancel leaves it as it was`() = runDesktopComposeUiTest {
        val requests = mutableListOf<PathRequest>()
        var answer: String? = null
        val picker = PathPicker { requests += it; answer }
        setContent {
            var value by remember { mutableStateOf("C:\\start") }
            CompositionLocalProvider(LocalPathPicker provides picker) {
                PathField(value, { value = it }, PathKind.FILE, "Plugin file", tag = tag, title = "Choose a plugin", extensions = listOf("ihq"))
            }
        }
        onNodeWithTag(tag + PATH_FIELD_BROWSE_TAG_SUFFIX).performClick()
        waitUntil(timeoutMillis = 5_000) { requests.size == 1 }
        waitForIdle()
        onNodeWithTag(tag).assertTextContains("C:\\start")
        assertEquals(PathRequest(PathKind.FILE, "Choose a plugin", "C:\\start", listOf("ihq")), requests.single())

        answer = "C:\\plugins\\notes.ihq"
        onNodeWithTag(tag + PATH_FIELD_BROWSE_TAG_SUFFIX).performClick()
        waitUntil(timeoutMillis = 5_000) { runCatching { onNodeWithTag(tag).assertTextContains("C:\\plugins\\notes.ihq") }.isSuccess }

        onNodeWithTag(tag).performTextReplacement("D:\\typed")
        onNodeWithTag(tag).assertTextContains("D:\\typed")
    }

    @Test fun `onPicked gets the folder instead of the box when the caller saves at once`() = runDesktopComposeUiTest {
        val picked = mutableListOf<String>()
        setContent {
            CompositionLocalProvider(LocalPathPicker provides PathPicker { "  /srv/repo  " }) {
                PathField("", {}, PathKind.FOLDER, "Project folder", tag = tag, onPicked = { picked += it })
            }
        }
        onNodeWithTag(tag + PATH_FIELD_BROWSE_TAG_SUFFIX).performClick()
        waitUntil(timeoutMillis = 5_000) { picked.isNotEmpty() }
        assertEquals(listOf("/srv/repo"), picked)
    }

    @Test fun `the older directory picker still serves a folder field`() = runDesktopComposeUiTest {
        var value by mutableStateOf("")
        setContent {
            CompositionLocalProvider(LocalDirectoryPicker provides DirectoryPicker { "/from/old/picker" }) {
                PathField(value, { value = it }, PathKind.FOLDER, "Project folder", tag = tag)
            }
        }
        onNodeWithTag(tag + PATH_FIELD_BROWSE_TAG_SUFFIX).performClick()
        waitUntil(timeoutMillis = 5_000) { value == "/from/old/picker" }
    }

    @Test fun `no picker means no Browse button, the note says what to type, and typing works`() = runDesktopComposeUiTest {
        setContent {
            var value by remember { mutableStateOf("") }
            CompositionLocalProvider(LocalDirectoryPicker provides DirectoryPicker { "/never" }) {
                // A file field never falls back to the folder-only picker.
                PathField(value, { value = it }, PathKind.FILE, "Plugin file", tag = tag, noPickerNote = "Type the path.")
            }
        }
        onNodeWithTag(tag + PATH_FIELD_BROWSE_TAG_SUFFIX).assertDoesNotExist()
        onNodeWithText("Type the path.").assertExists()
        onNodeWithTag(tag).performTextInput("/srv/x.ihq")
        onNodeWithTag(tag).assertTextContains("/srv/x.ihq")
    }
}
