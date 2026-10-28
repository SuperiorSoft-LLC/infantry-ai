package com.superiorsoftllc.agenthq.ui.shell.blocks

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.superiorsoftllc.agenthq.ui.AssistantPopupChat
import com.superiorsoftllc.agenthq.ui.AssistantPopupFrame
import com.superiorsoftllc.agenthq.ui.AssistantPopupHeader
import com.superiorsoftllc.agenthq.ui.AssistantPopupResize
import com.superiorsoftllc.agenthq.ui.TITLE_BAR_HEIGHT
import com.superiorsoftllc.agenthq.ui.block.layout.Block
import com.superiorsoftllc.agenthq.ui.block.layout.BlockScope
import com.superiorsoftllc.agenthq.ui.block.layout.Place
import com.superiorsoftllc.agenthq.ui.block.layout.RenderPlaced
import com.superiorsoftllc.agenthq.ui.block.layout.Size

private const val ASSISTANT = "infantry.shell.assistant"

private const val BODY = "$ASSISTANT.body"

/** The open popup the Assistant's children read: whether it is drawn in a compact window. */
private class AssistantHost(val compact: Boolean)

/** The open popup; null outside [AssistantBlock] (a child moved elsewhere draws nothing). */
private val LocalAssistantHost = compositionLocalOf<AssistantHost?> { null }

/**
 * ASSISTANT-1: the global Assistant's popup, docked top right under the title bar (a full-screen sheet when compact). The
 * window owns its state, so closing it keeps the draft, the width and the model; it is one more host of ChatPanel.
 * BLOCKS-LAYOUT L11: a container of child blocks `infantry.shell.assistant.<name>`, left to right, drawn straight into the
 * popup's row. The frame (the row at the dragged width, its border, the first ask that makes the Assistant) stays its own.
 */
class AssistantBlock(children: List<Block>, place: Place = Place()) : Block("assistantPopup", ASSISTANT, place, children) {
    @Composable
    override fun Render(scope: BlockScope) = with(scope) {
        val s = appShell
        if (!s.assistantPanel.open) return@with
        val compactWindow = s.state.panels.compactWindow
        AssistantPopupFrame(
            s.state, s.controller, s.assistantPanel, compact = compactWindow,
            modifier = Modifier.aligned(Alignment.TopEnd).then(if (compactWindow) Modifier else Modifier.padding(top = TITLE_BAR_HEIGHT + 1.dp, bottom = 28.dp)),
        ) {
            CompositionLocalProvider(LocalAssistantHost provides AssistantHost(compactWindow)) { RenderPlaced(children) }
        }
    }

    override fun withChildren(children: List<Block>) = AssistantBlock(children, place)
}

/** The docked popup's drag edge (↔ cursor, grip; drag left = wider). Expanded or compact it draws nothing. */
object AssistantResize : Block("resizeHandle", "$ASSISTANT.resize") {
    @Composable
    override fun Render(scope: BlockScope) {
        val host = LocalAssistantHost.current ?: return
        AssistantPopupResize(appShell.assistantPanel, host.compact, scope.modifier)
    }
}

/** Weight 1: the popup's column, whose children are `infantry.shell.assistant.body.<name>`, top to bottom. */
class AssistantBodyBlock(children: List<Block>, place: Place = Place(weight = 1f, size = Size.FillHeight)) :
    Block("assistantBody", BODY, place, children) {
    @Composable
    override fun Render(scope: BlockScope) {
        if (LocalAssistantHost.current == null) return
        Column(scope.modifier) { RenderPlaced(children) }
    }

    override fun withChildren(children: List<Block>) = AssistantBodyBlock(children, place)
}

/** The title row (name, expand or dock, close) and the divider under it; its placement goes on the row. */
object AssistantHeader : Block("assistantHeader", "$BODY.header") {
    @Composable
    override fun Render(scope: BlockScope) {
        val host = LocalAssistantHost.current ?: return
        AssistantPopupHeader(appShell.assistantPanel, host.compact, scope.modifier)
    }
}

/** Weight 1: the Assistant's chat (the shared ChatPanel), or "Starting the Assistant..." until the daemon has made it. */
object AssistantChat : Block("assistantChat", "$BODY.chat", Place(weight = 1f, size = Size.FillWidth)) {
    @Composable
    override fun Render(scope: BlockScope) {
        if (LocalAssistantHost.current == null) return
        val s = appShell
        AssistantPopupChat(s.state, s.controller, s.assistantChat, scope.modifier)
    }
}

val ShellAssistant = AssistantBlock(listOf(AssistantResize, AssistantBodyBlock(listOf(AssistantHeader, AssistantChat))))
