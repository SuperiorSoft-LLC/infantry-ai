package com.superiorsoftllc.agenthq.plugins.official.chat

import com.superiorsoftllc.agenthq.ui.shell.blocks.*

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.superiorsoftllc.agenthq.ui.CENTER_PANE_TAG
import com.superiorsoftllc.agenthq.ui.CENTER_TRANSCRIPT_TAG
import com.superiorsoftllc.agenthq.plugins.official.chat.CenterTabBar
import com.superiorsoftllc.agenthq.plugins.official.chat.ChatPanelHost
import com.superiorsoftllc.agenthq.plugins.official.chat.Composer
import com.superiorsoftllc.agenthq.plugins.official.chat.Header
import com.superiorsoftllc.agenthq.plugins.official.chat.NewAgentDialogFrame
import com.superiorsoftllc.agenthq.plugins.official.chat.NewAgentDraft
import com.superiorsoftllc.agenthq.plugins.official.chat.NewAgentError
import com.superiorsoftllc.agenthq.plugins.official.chat.NewAgentExecutorStep
import com.superiorsoftllc.agenthq.plugins.official.chat.NewAgentRoleStep
import com.superiorsoftllc.agenthq.plugins.official.chat.NewAgentTaskStep
import com.superiorsoftllc.agenthq.plugins.official.chat.Notices
import com.superiorsoftllc.agenthq.plugins.official.chat.SpawnRows
import com.superiorsoftllc.agenthq.plugins.official.chat.SwitchPicker
import com.superiorsoftllc.agenthq.plugins.official.chat.Transcript
import com.superiorsoftllc.agenthq.plugins.official.chat.chatPanelHeader
import com.superiorsoftllc.agenthq.plugins.official.chat.chatPanelHost
import com.superiorsoftllc.agenthq.ui.block.layout.Block
import com.superiorsoftllc.agenthq.ui.block.layout.BlockScope
import com.superiorsoftllc.agenthq.ui.block.layout.Place
import com.superiorsoftllc.agenthq.ui.block.layout.RenderPlaced
import com.superiorsoftllc.agenthq.ui.net.CommandResult
import com.superiorsoftllc.agenthq.ui.block.layout.LocalBlockSize
import com.superiorsoftllc.agenthq.ui.model.PanelLayout
import com.superiorsoftllc.agenthq.plugins.official.chat.ChatPanel
import com.superiorsoftllc.agenthq.ui.shell.ChatContext
import com.superiorsoftllc.agenthq.ui.shell.ChatPanels
import com.superiorsoftllc.agenthq.ui.shell.LocalShellContext
import com.superiorsoftllc.agenthq.ui.shell.NoChat

private const val CENTER = "infantry.shell.center"

/** The window's chat domain; [NoChat] (no state, nothing drawn) where the shell has no window. */
private val chatCtx: ChatContext @Composable get() = LocalShellContext.current?.chat ?: NoChat

/** MOBILE-LAYOUT-1: below PanelLayout.COMPACT_BELOW_DP (600dp) of the middle region the window shows ONE pane. */
@Composable
private fun dockCompact(): Boolean = PanelLayout.isCompact(LocalBlockSize.current.width.value)

/**
 * BLOCKS-SHELL-1: the centre column (chat, centre tabs, the New agent dialog); it fills what the side panels leave.
 * BLOCKS-LAYOUT L9b: a container of child blocks `infantry.shell.center.<name>`, top to bottom, drawn straight into its
 * column.
 */
class CenterBlock(children: List<Block>, place: Place = Place(weight = 1f)) : Block("center", CENTER, place, children) {
    @Composable
    override fun Render(scope: BlockScope) {
        val controller = chatCtx.controller ?: return
        val compact = dockCompact()
        // Hands the shared chat panel to hosts that stay in the shell (the Assistant popup) through ChatPanels, once the column is drawn.
        SideEffect {
            ChatPanels.panel = { id, ctl, modifier, chat, allowSpawn, tag -> ChatPanel(id, ctl, modifier, chat = chat, allowSpawn = allowSpawn, transcriptTag = tag) }
        }
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
        val c = chatCtx
        val controller = c.controller ?: return
        val chat = c.chat ?: return
        val host = chatPanelHost(
            c.selectedId, controller, chat, allowSpawn = true,
            header = chatPanelHeader(chat, controller) { c.showBoard() },
            content = { transcript -> c.TabContent(transcript) },
            transcriptTag = CENTER_TRANSCRIPT_TAG,
        )
        CompositionLocalProvider(LocalChatPanelHost provides host) {
            Column(scope.modifier.fillMaxWidth()) {
                RenderPlaced(children)
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
        val c = chatCtx
        CenterTabBar(c.state ?: return, c.centerTab, scope.modifier) { c.setCenterTab(it) }
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

/** New agent mode only: the spawn role row, then the provider and model picker. */
object CenterChatSpawn : Block("spawnRows", "$CHAT.spawn") {
    @Composable
    override fun Render(scope: BlockScope) {
        val host = LocalChatPanelHost.current ?: return
        Placed(scope.modifier) { host.SpawnRows() }
    }
}

/** SWAP-AGENT-1: the inline Switch agent picker, while it is open for this session. */
object CenterChatSwitch : Block("switchAgentPicker", "$CHAT.switch") {
    @Composable
    override fun Render(scope: BlockScope) {
        val host = LocalChatPanelHost.current ?: return
        Placed(scope.modifier) { host.SwitchPicker() }
    }
}

/** The composer: PromptBox (attachments, paste, voice, commands, model picker, stop / resume / send). */
object CenterChatComposer : Block("promptBox", "$CHAT.composer") {
    @Composable
    override fun Render(scope: BlockScope) {
        val host = LocalChatPanelHost.current ?: return
        Placed(scope.modifier) { host.Composer() }
    }
}

private val CenterChat = CenterChatBlock(listOf(CenterChatHeader, CenterChatTabs, CenterChatTranscript, CenterChatNotices, CenterChatSpawn, CenterChatSwitch, CenterChatComposer))

private const val NEW_AGENT = "$CENTER.newAgent"

/** The open New agent modal's draft; null outside [NewAgentBlock] (a child moved elsewhere draws nothing). */
private val LocalNewAgentDraft = compositionLocalOf<NewAgentDraft?> { null }

/**
 * The New agent modal and its catalog refresh. A dialog has no node in the column, so the placement has nothing to wear.
 * BLOCKS-LAYOUT L11: a container of child blocks `infantry.shell.center.newAgent.<name>`, the modal's steps, top to
 * bottom, drawn straight into its scrolling column. The frame (dialog, title, Add, Cancel) and the draft stay its own.
 */
class NewAgentBlock(children: List<Block>, place: Place = Place()) : Block("newAgentDialog", NEW_AGENT, place, children) {
    @Composable
    override fun Render(scope: BlockScope) {
        val c = chatCtx
        val state = c.state ?: return
        val controller = c.controller ?: return
        LaunchedEffect(c.showNewAgent, state.connection.isLive) {
            if (c.showNewAgent && state.connection.isLive) controller.refreshModelCatalog()
        }
        // DEMO-MERGE-1 / POLICY-1: the New agent modal never opens while the policy turns agents.spawn off
        if (c.showNewAgent && com.superiorsoftllc.agenthq.ui.shell.rememberGate("agents.spawn", com.superiorsoftllc.agenthq.plugin.api.ActionKind.SPAWN).allowed) {
            NewAgentDialogFrame(
                state,
                onAdd = { role, picked, task, contextLimit ->
                    controller.pickSpawnRole(role)
                    controller.pickModel(picked)
                    val r = controller.spawn(task, contextLimit)
                    if (r is CommandResult.Refused) r.reason else { c.setShowNewAgent(false); null }
                },
                onDismiss = { c.setShowNewAgent(false) },
            ) { draft ->
                CompositionLocalProvider(LocalNewAgentDraft provides draft) { RenderPlaced(children) }
            }
        }
    }

    override fun withChildren(children: List<Block>) = NewAgentBlock(children, place)
}

/** Step 1: the role catalog (search, then the grid of roles); its placement goes on the step's column. */
object NewAgentRole : Block("newAgentRoles", "$NEW_AGENT.role") {
    @Composable
    override fun Render(scope: BlockScope) {
        NewAgentRoleStep(LocalNewAgentDraft.current ?: return, scope.modifier)
    }
}

/** Step 2: harness, provider and model, closed until a role is chosen (its binding seeds them). */
object NewAgentExecutor : Block("newAgentExecutor", "$NEW_AGENT.executor") {
    @Composable
    override fun Render(scope: BlockScope) {
        NewAgentExecutorStep(LocalNewAgentDraft.current ?: return, scope.modifier)
    }
}

/** Add's last refusal, while there is one (nothing otherwise). */
object NewAgentErrorLine : Block("newAgentError", "$NEW_AGENT.error") {
    @Composable
    override fun Render(scope: BlockScope) {
        NewAgentError(LocalNewAgentDraft.current ?: return, scope.modifier)
    }
}

/** Step 3: the first task. */
object NewAgentTask : Block("newAgentTask", "$NEW_AGENT.task") {
    @Composable
    override fun Render(scope: BlockScope) {
        NewAgentTaskStep(LocalNewAgentDraft.current ?: return, scope.modifier)
    }
}

private val CenterNewAgent = NewAgentBlock(listOf(NewAgentRole, NewAgentExecutor, NewAgentErrorLine, NewAgentTask))

val ShellCenter = CenterBlock(listOf(CenterChat, CenterNewAgent))

/** The Infantry_Chat module's centre fill id (module.json `fills`). */
const val CHAT_FILL_ID = "infantry.shell.center"
