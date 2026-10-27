package com.superiorsoftllc.agenthq.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.superiorsoftllc.agenthq.ui.model.AssistantPanelWidth

// S4c: the popup's view state stays in core (the window owns it, Shell.assistantPanel); the popup itself is app UI.
/**
 * ASSISTANT-1: the Assistant popup's own view state, owned by the window (not by the popup), so closing and reopening the
 * popup keeps its width, whether it is expanded, and (with its [ChatPanelState]) the draft and attachments.
 */
class AssistantPanelState {
    var open by mutableStateOf(false)
    var expanded by mutableStateOf(false)
    var width by mutableStateOf(AssistantPanelWidth.DEFAULT)

    fun toggle() { open = !open }
}

