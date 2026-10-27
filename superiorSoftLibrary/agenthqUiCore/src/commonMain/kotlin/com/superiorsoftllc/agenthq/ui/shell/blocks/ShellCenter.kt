package com.superiorsoftllc.agenthq.ui.shell.blocks

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.superiorsoftllc.agenthq.ui.CENTER_PANE_TAG
import com.superiorsoftllc.agenthq.ui.CENTER_TRANSCRIPT_TAG
import com.superiorsoftllc.agenthq.ui.CenterTabBar
import com.superiorsoftllc.agenthq.ui.ChatPanelHost
import com.superiorsoftllc.agenthq.ui.Composer
import com.superiorsoftllc.agenthq.ui.Header
import com.superiorsoftllc.agenthq.ui.LocalShell
import com.superiorsoftllc.agenthq.ui.NewAgentDialog
import com.superiorsoftllc.agenthq.ui.Notices
import com.superiorsoftllc.agenthq.ui.SpawnRows
import com.superiorsoftllc.agenthq.ui.SwitchPicker
import com.superiorsoftllc.agenthq.ui.Transcript
import com.superiorsoftllc.agenthq.ui.chatPanelHeader
import com.superiorsoftllc.agenthq.ui.chatPanelHost
import com.superiorsoftllc.agenthq.ui.block.layout.Block
import com.superiorsoftllc.agenthq.ui.block.layout.BlockScope
import com.superiorsoftllc.agenthq.ui.block.layout.Place
import com.superiorsoftllc.agenthq.ui.block.layout.RenderPlaced
import com.superiorsoftllc.agenthq.ui.net.CommandResult

private const val CENTER = "infantry.shell.center"

/**
 * BLOCKS-SHELL-1: the centre column (chat, centre tabs, the New agent dialog); it fills what the side panels leave.
 * BLOCKS-LAYOUT L9b: a container of child blocks `infantry.shell.center.<name>`, top to bottom, drawn straight into its
 * column.
 */
class CenterBlock(children: List<Block>, place: Place = Place(weight = 1f)) : Block("center", CENTER, place, children) {
    @Composable
    override fun Render(scope: BlockScope) {
        val s = LocalShell.current
        val controller = s.controller
        val compact = dockCompact()
        // MOBILE-LAYOUT-2: crossing 600dp (a resize, a rotation) parks this window class's panel flags and brings
        // back the other class's, so compact navigation never rewrites the wide layout. The panels render from
        // forWindow(compact) straight away; the effect then stores the same swap, so no frame shows stale flags.
        LaunchedEffect(compact) { controller.setCompactWindow(compact) }
        Column(scope.modifier.fillMaxHeight().testTag(CENTER_PANE_TAG)) {
            RenderPlaced(children)
        }
    }

    override fun withChildren(children: List<Block>) = CenterBlock(children, place)
}

private const val CHAT = "$CENTER.chat"

/** The chat panel the centre's chat children read; null outside [CenterChatBlock] (a child moved elsewhere draws nothing). */
private val LocalChatPanelHost = compositionLocalOf<ChatPanelHost?> { null }

/**
 * CHAT-PANEL-1: the main window is one host of the shared ChatPanel; the tab strip and the other tabs are its slots.
 * BLOCKS-LAYOUT L9b: its sections are child blocks `infantry.shell.center.chat.<name>`, top to bottom, drawn straight
 * into its column; they are ChatPanel's own sections ([ChatPanelHost]), so the chat keeps one implementation.
 */
class CenterChatBlock(children: List<Block>, place: Place = Place(weight = 1f)) : Block("chatPanel", CHAT, place, children) {
    @Composable
    override fun Render(scope: BlockScope) {
        val s = LocalShell.current
        val ui = s.ui
        val host = chatPanelHost(
            s.selected?.id, s.controller, s.chat, allowSpawn = true,
            header = chatPanelHeader(s.chat, s.controller) { ui.showBoard = true },
            content = { transcript -> s.tabs.Content(ui.centerTab, s.tabContext(transcript)) },
            transcriptTag = CENTER_TRANSCRIPT_TAG,
        )
        CompositionLocalProvider(LocalChatPanelHost provides host) {
            Column(scope.modifier.fillMaxWidth()) {
                RenderPlaced(children)
                host.SpawnRows()
                host.SwitchPicker()
                host.Composer()
            }
        }
    }

    override fun withChildren(children: List<Block>) = CenterChatBlock(children, place)
}

/** A chat section that has no node of its own: a placement other than the default gets a Column to wear it. */
@Composable
private fun Placed(modifier: Modifier, content: @Composable () -> Unit) {
    if (modifier == Modifier) content() else Column(modifier) { content() }
}

/** The session header with its Switch agent chip. */
object CenterChatHeader : Block("chatHeader", "$CHAT.header") {
    @Composable
    override fun Render(scope: BlockScope) {
        val host = LocalChatPanelHost.current ?: return
        Placed(scope.modifier) { host.Header() }
    }
}

/** The centre tab strip; its placement goes on the strip's row. */
object CenterChatTabs : Block("tabStrip", "$CHAT.tabs") {
    @Composable
    override fun Render(scope: BlockScope) {
        val s = LocalShell.current
        val ui = s.ui
        CenterTabBar(s.state, ui.centerTab, scope.modifier) { ui.centerTab = it }
    }
}

/**
 * The picked centre tab (the Chat tab is the transcript, its inline pending question and the Activity box) with the to-do /
 * reasoning side slot; it takes the free height, and its Box wears the placement.
 */
object CenterChatTranscript : Block("chatTranscript", "$CHAT.transcript", Place(weight = 1f)) {
    @Composable
    override fun Render(scope: BlockScope) {
        val host = LocalChatPanelHost.current ?: return
        host.Transcript(scope.modifier)
    }
}

/** The latest notice for this session or the window (NoticeStrip); it hides itself after 8 s. */
object CenterChatNotices : Block("noticeStrip", "$CHAT.notices") {
    @Composable
    override fun Render(scope: BlockScope) {
        val host = LocalChatPanelHost.current ?: return
        Placed(scope.modifier) { host.Notices() }
    }
}

private val CenterChat = CenterChatBlock(listOf(CenterChatHeader, CenterChatTabs, CenterChatTranscript, CenterChatNotices))

/** The New agent modal and its catalog refresh. A dialog has no node in the column, so the placement has nothing to wear. */
object CenterNewAgent : Block("newAgentDialog", "$CENTER.newAgent") {
    @Composable
    override fun Render(scope: BlockScope) {
        val s = LocalShell.current
        val state = s.state
        val controller = s.controller
        val ui = s.ui
        LaunchedEffect(ui.showNewAgent, state.connection.isLive) {
            if (ui.showNewAgent && state.connection.isLive) controller.refreshModelCatalog()
        }
        if (ui.showNewAgent) {
            NewAgentDialog(
                state,
                onAdd = { role, picked, task ->
                    controller.pickSpawnRole(role)
                    controller.pickModel(picked)
                    val r = controller.spawn(task)
                    if (r is CommandResult.Refused) r.reason else { ui.showNewAgent = false; null }
                },
                onDismiss = { ui.showNewAgent = false },
            )
        }
    }
}

val ShellCenter = CenterBlock(listOf(CenterChat, CenterNewAgent))
