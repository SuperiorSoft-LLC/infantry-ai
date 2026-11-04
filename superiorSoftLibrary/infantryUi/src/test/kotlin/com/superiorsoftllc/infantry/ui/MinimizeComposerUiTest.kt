// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.plugins.official.chat.*

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.superiorsoftllc.infantry.ui.net.Controller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.Test

/** CHAT-MINIMIZE-1: Minimize removes the composer (not rendered); the floating button brings it back with the same draft. */
@OptIn(ExperimentalTestApi::class)
class MinimizeComposerUiTest {
    @Test
    fun minimizeRemovesTheComposerAndRestoreKeepsTheDraft() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            runDesktopComposeUiTest(900, 600) {
                val controller = Controller(scope)
                val chat = ChatPanelState().also { it.draft = "half-written prompt" }
                setContent {
                    Box(Modifier.width(450.dp)) {
                        Column {
                            if (!chat.composerMinimized) {
                                PromptBox(chat.draft, chat::edit, {}, chat.focus, false, null, controller, {}, onMinimize = { chat.composerMinimized = true })
                            } else {
                                IconAction(AhqIcons.OpenInFull, COMPOSER_RESTORE_LABEL, { chat.composerMinimized = false }, modifier = Modifier.testTag(COMPOSER_RESTORE_TAG))
                            }
                        }
                    }
                }
                onNodeWithTag(PROMPT_BOX_TAG).assertTextContains("half-written prompt")
                onNodeWithTag(COMPOSER_MINIMIZE_TAG).performClick()
                onNodeWithTag(COMPOSER_TAG).assertDoesNotExist()
                onNodeWithTag(PROMPT_BOX_TAG).assertDoesNotExist()
                onNodeWithTag(COMPOSER_RESTORE_TAG).performClick()
                onNodeWithTag(PROMPT_BOX_TAG).assertTextContains("half-written prompt")
            }
        } finally { scope.cancel() }
    }
}
