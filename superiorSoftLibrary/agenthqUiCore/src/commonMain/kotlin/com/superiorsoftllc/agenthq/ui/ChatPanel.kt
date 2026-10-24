package com.superiorsoftllc.agenthq.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import com.superiorsoftllc.agenthq.ui.net.ImageUploadOutcome
import com.superiorsoftllc.agenthq.ui.protocol.ImageUploadWire
import com.superiorsoftllc.agenthq.ui.pair.applyPairing
import com.superiorsoftllc.agenthq.ui.shell.LocalPlatformHost
import com.superiorsoftllc.agenthq.ui.shell.ServerChoice
import com.superiorsoftllc.agenthq.ui.shell.ServerSwitcher
import com.superiorsoftllc.agenthq.ui.shell.PickedImage
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import com.superiorsoftllc.agenthq.ui.model.PickedModel
import com.superiorsoftllc.agenthq.ui.model.NoticeKind
import com.superiorsoftllc.agenthq.ui.model.localNotices
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.superiorsoftllc.agenthq.ui.model.AgentGroup
import com.superiorsoftllc.agenthq.ui.model.parseHexColor
import com.superiorsoftllc.agenthq.ui.model.roleHue
import androidx.compose.foundation.horizontalScroll
import com.superiorsoftllc.agenthq.ui.model.RightPanelWidth
import com.superiorsoftllc.agenthq.ui.model.SidebarWidth
import com.superiorsoftllc.agenthq.ui.model.CompactScreen
import com.superiorsoftllc.agenthq.ui.model.PanelLayout
import com.superiorsoftllc.agenthq.ui.model.PanelSide
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import com.superiorsoftllc.agenthq.ui.model.isSafeChatUrl
import com.superiorsoftllc.agenthq.ui.model.AgentScope
import com.superiorsoftllc.agenthq.ui.model.SlashSuggestion
import com.superiorsoftllc.agenthq.ui.model.isSlashCommand
import com.superiorsoftllc.agenthq.ui.model.moveSlashSelection
import com.superiorsoftllc.agenthq.ui.model.slashHeader
import com.superiorsoftllc.agenthq.ui.model.HarnessSwitch
import com.superiorsoftllc.agenthq.ui.model.switchChipText
import com.superiorsoftllc.agenthq.ui.model.slashPick
import com.superiorsoftllc.agenthq.ui.model.slashSubmitOnEnter
import com.superiorsoftllc.agenthq.ui.model.slashSuggestions
import com.superiorsoftllc.agenthq.ui.model.slashTyped
import com.superiorsoftllc.agenthq.ui.model.branchLine
import com.superiorsoftllc.agenthq.ui.model.changesView
import com.superiorsoftllc.agenthq.ui.model.ticketBranchFor
import com.superiorsoftllc.agenthq.ui.model.ticketTagFull
import com.superiorsoftllc.agenthq.ui.model.ticketTagLabel
import com.superiorsoftllc.agenthq.ui.model.reportFlagOf
import com.superiorsoftllc.agenthq.ui.model.watchFlagOf
import com.superiorsoftllc.agenthq.ui.model.UiFlags
import com.superiorsoftllc.agenthq.ui.model.ChatBlock
import com.superiorsoftllc.agenthq.ui.model.ChatItem
import com.superiorsoftllc.agenthq.ui.model.ChatSpan
import com.superiorsoftllc.agenthq.ui.model.ChatStatus
import com.superiorsoftllc.agenthq.ui.model.OutputLine
import com.superiorsoftllc.agenthq.ui.model.USER_AVATAR_ID
import com.superiorsoftllc.agenthq.ui.model.UserPortrait
import com.superiorsoftllc.agenthq.ui.model.contractAtLeast
import com.superiorsoftllc.agenthq.ui.model.avatarFor
import com.superiorsoftllc.agenthq.ui.model.chatItems
import com.superiorsoftllc.agenthq.ui.model.chatLineBlocks
import com.superiorsoftllc.agenthq.ui.model.parseChatInline
import com.superiorsoftllc.agenthq.ui.model.AgentView
import com.superiorsoftllc.agenthq.ui.model.SessionExecutor
import com.superiorsoftllc.agenthq.ui.model.COMPACTING_LABEL
import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.model.DiffTarget
import com.superiorsoftllc.agenthq.ui.model.ConnState
import com.superiorsoftllc.agenthq.ui.model.LineKind
import com.superiorsoftllc.agenthq.ui.model.activityTimestamp
import com.superiorsoftllc.agenthq.ui.model.isBusy
import com.superiorsoftllc.agenthq.ui.model.QueuedPrompt
import com.superiorsoftllc.agenthq.ui.model.interruptBlocker
import com.superiorsoftllc.agenthq.ui.model.queuedFor
import com.superiorsoftllc.agenthq.ui.model.isRunning
import com.superiorsoftllc.agenthq.ui.model.needsInput
import com.superiorsoftllc.agenthq.ui.model.insertIntoPrompt
import com.superiorsoftllc.agenthq.ui.model.promptBlocker
import com.superiorsoftllc.agenthq.ui.model.parseNodes
import com.superiorsoftllc.agenthq.ui.model.stateLabel
import com.superiorsoftllc.agenthq.ui.model.visibleAgents
import com.superiorsoftllc.agenthq.ui.net.CommandResult
import com.superiorsoftllc.agenthq.ui.net.Controller
import com.superiorsoftllc.agenthq.ui.protocol.SessionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * CHAT-PANEL-1: everything one chat owns that is NOT the daemon's data: the draft, the images/files tagged into it, the last
 * upload refusal, the New-agent mode, the open Switch agent picker and the composer's focus requester. The host decides
 * the lifetime: the main window keeps one for the whole window (its draft follows the selection, as it always did), a
 * popup or a future popout/split pane keeps one per session (see [rememberChatPanelState]). Two panels never share one.
 */
class ChatPanelState {
    var draft by mutableStateOf("")
    var attachments by mutableStateOf(listOf<ChatAttachment>())
        private set
    var attachError by mutableStateOf<String?>(null)
    var spawnMode by mutableStateOf(false)
    var switchAsk by mutableStateOf<SwitchAgentAsk?>(null)
    val focus: FocusRequester = FocusRequester()

    val attachBusy: Boolean get() = attachments.any { it.path == null }

    fun openSwitch(sessionId: String, harness: String? = null) {
        spawnMode = false
        switchAsk = SwitchAgentAsk(sessionId, harness, (switchAsk?.seq ?: 0) + 1)
    }

    /** ATTACH-INLINE-1: the composer's text changed; a tag deleted from it releases its attachment. */
    fun edit(text: String) {
        draft = text
        attachments = ComposerTags.prune(text, attachments)
    }

    /** Text inserted from outside (a saved prompt, a skill) and the box focused. */
    fun insert(text: String) {
        draft = insertIntoPrompt(draft, text)
        focus.requestFocus()
    }

    fun removeAttachment(path: String) {
        attachments.firstOrNull { it.path == path }?.let { a -> edit(ComposerTags.strip(draft, a.token)) }
    }

    fun wire(text: String): String = ComposerTags.toPrompt(text, attachments)

    /**
     * IMG-2 / ATTACH-INLINE-1: give each picked file a tag NOW (the box inserts the returned tokens at its caret), then
     * upload it into the project the message goes to: an image as purpose `chat`, any other file as purpose `file`.
     * Over the limit (images 10 MB, other files 20 MB) or refused by the daemon: an inline error, and no tag.
     */
    fun attach(picked: List<PickedImage>, controller: Controller, scope: CoroutineScope, agent: AgentView?, state: AppState): String {
        if (picked.isEmpty()) return ""
        val projectId = (if (spawnMode) null else agent?.session?.projectId?.takeIf { it.isNotBlank() }) ?: state.activeProjectId
        attachError = null
        val tagged = mutableListOf<Pair<ChatAttachment, PickedImage>>()
        for (file in picked) {
            val limit = if (file.isImage) ImageUploadWire.MAX_BYTES else CHAT_FILE_MAX_BYTES
            if (file.size > limit) { attachError = "${file.fileName}: the file is over the ${limit / (1024 * 1024)} MB limit"; continue }
            val token = ComposerTags.nextToken(draft + tagged.joinToString("") { it.first.token.toString() }, attachments)
                ?: run { attachError = "${file.fileName}: too many attachments in one message"; null } ?: continue
            val a = ChatAttachment(token, file.fileName, file.isImage, file.bytes)
            attachments = attachments + a
            tagged += a to file
        }
        if (tagged.isNotEmpty()) scope.launch {
            for ((a, file) in tagged) {
                val r = if (file.isImage) controller.uploadChatImage(projectId, file.fileName, file.mimeType, file.bytes)
                else controller.uploadChatFile(projectId, file.fileName, file.mimeType, file.bytes)
                when (r) {
                    // A tag deleted while it uploaded stays released: its file is simply never referenced.
                    is ImageUploadOutcome.Stored -> attachments = attachments.map { if (it.token == a.token) it.stored(r.image.path) else it }
                    is ImageUploadOutcome.Refused -> {
                        attachError = "${file.fileName}: ${r.reason}"
                        edit(ComposerTags.strip(draft, a.token))
                    }
                }
            }
        }
        return tagged.joinToString("") { it.first.token.toString() }
    }

    fun submit(controller: Controller, scope: CoroutineScope, agent: AgentView?) {
        val typed = draft.trim()
        // ATTACH-INLINE-1: each tag becomes its `[image: ...]` / `[file: ...]` reference where it stands; a `/command` line
        // never carries them (it is not a prompt).
        val text = if (isSlashCommand(ComposerTags.strip(typed))) ComposerTags.strip(typed).trim() else ComposerTags.toPrompt(typed, attachments)
        scope.launch {
            val harness = if (spawnMode || agent == null) null else HarnessSwitch.parse(text)
            val r: CommandResult = when {
                // CMD-HARNESS-1: a client command, never a prompt: it swaps the agent's harness.
                harness != null -> controller.switchHarness(agent!!.id, harness)
                spawnMode || agent == null -> controller.spawn(text)
                agent.session.resumable && agent.session.state in ENDED_STATES -> controller.resume(agent.id, text)
                isBusy(agent) -> controller.queueMessage(agent.id, text)
                else -> controller.prompt(agent.id, text)
            }
            if (r is CommandResult.Sent || r is CommandResult.Queued) {
                edit("")
                attachError = null
                // A `/model` or `/effort` in the New agent box only sets the next spawn's selection: stay in the spawn box.
                if (!(spawnMode && isSlashCommand(text))) spawnMode = false
            }
        }
    }

    /** Ctrl+R: resume the ended session with the draft as its prompt. */
    fun resume(controller: Controller, scope: CoroutineScope, agent: AgentView) {
        scope.launch {
            if (controller.resume(agent.id, ComposerTags.toPrompt(draft, attachments).trim()) is CommandResult.Sent) edit("")
        }
    }
}

/** A [ChatPanelState] owned by the calling composition and keyed by [sessionId]: another session gets a fresh one. */
@Composable
fun rememberChatPanelState(sessionId: String?): ChatPanelState = remember(sessionId) { ChatPanelState() }

/**
 * CHAT-PANEL-1: the one chat component. Header, transcript, activity box, notices, the New agent / Switch agent pickers and
 * the composer (attachments, paste, voice, commands, model picker, stop/resume/send) for the session [sessionId].
 *
 * It reads the session from [controller] by id (never from the window's selection), keeps its draft and attachments in
 * [chat], and uses no global: two panels bound to different sessions work side by side in one window, and a popup, a
 * popout window or a split pane can host it as it is. The main window and the Assistant popup are two hosts of this
 * one function.
 *
 * [beforeContent] and [content] are the host's hook for the main window's tab strip and its other tabs: [content] gets the
 * transcript as a slot to show or replace. [allowSpawn] false removes the New agent mode (a panel bound to one fixed session).
 */
@Composable
fun ChatPanel(
    sessionId: String?,
    controller: Controller,
    modifier: Modifier = Modifier,
    chat: ChatPanelState = rememberChatPanelState(sessionId),
    allowSpawn: Boolean = true,
    onOpenTicket: (String) -> Unit = {},
    /** The panel's header; the default is the session header with its Switch agent chip. */
    header: @Composable (agent: AgentView?, openSwitch: (() -> Unit)?) -> Unit = { a, open ->
        CenterHeader(a, chat.spawnMode, controller.state.collectAsState().value, onOpenSwitch = open, onOpenTicket = onOpenTicket)
    },
    beforeContent: @Composable () -> Unit = {},
    content: @Composable (transcript: @Composable () -> Unit) -> Unit = { it() },
    transcriptTag: String = CENTER_TRANSCRIPT_TAG,
) {
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    val agent = sessionId?.let { state.agents[it] }
    LaunchedEffect(state.harnessPickerAsk) { state.harnessPickerAsk?.let { chat.openSwitch(it.sessionId, it.harness) } }
    // CP-1b: every time New agent opens (and on reconnect while open), re-read the model catalog.
    LaunchedEffect(chat.spawnMode || chat.switchAsk != null, state.connection.isLive) {
        if ((chat.spawnMode || chat.switchAsk != null) && state.connection.isLive) controller.refreshModelCatalog()
    }
    Column(modifier) {
        header(agent, agent?.let { a -> { chat.openSwitch(a.id) } })
        beforeContent()
        Box(Modifier.weight(1f).fillMaxWidth().testTag(transcriptTag)) {
            content {
                androidx.compose.runtime.CompositionLocalProvider(LocalUserPortrait provides state.userPortrait) {
                    OutputPane(agent, chat.spawnMode, roleColor = state.role(agent?.role)?.color, onStartSession = if (allowSpawn) ({ chat.spawnMode = true; chat.focus.requestFocus() }) else null)
                }
            }
        }
        NoticeStrip(state, agent?.id)
        if (chat.spawnMode && state.roles.isNotEmpty()) {
            // ROLE-1c: the role a new agent spawns as. Its binding is the DEFAULT below; the picker still overrides it for this spawn.
            SpawnRoleRow(state, controller)
        }
        if (chat.spawnMode && state.modelCatalog.isNotEmpty()) {
            // PICKER 2/2: which provider+model the new agent runs on (from the `models` frame; none picked = daemon defaults).
            // PICKER-ALL-2: the shared Harness -> Provider -> Model picker; untouched, the stored pick is left as is.
            // The picker bounds its own lists; no outer scroll, so a row is never clipped under a second scroller.
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                SeededExecutorPicker(
                    state.providers, state.modelCatalog, state.pickedModel, key = chat.spawnMode, tags = CONFIG_EXECUTOR_TAGS,
                    onPick = { controller.pickModel(it) },
                )
            }
        }
        // SWAP-AGENT-1: the one Switch agent picker, inline above the composer like the New agent rows.
        chat.switchAsk?.takeIf { !chat.spawnMode && agent?.id == it.sessionId }?.let { ask ->
            SwitchAgentPicker(
                state, agent!!, ask,
                onApply = { role, picked -> controller.switchAgent(agent.id, role, picked) },
                onClose = { chat.switchAsk = null },
            )
        }
        PromptBox(
            chat.draft, chat::edit, { chat.submit(controller, scope, agent) }, chat.focus, chat.spawnMode, agent, controller,
            onSpawnToggle = { if (allowSpawn) chat.spawnMode = !chat.spawnMode },
            onSwitchAgent = { agent?.let { if (chat.switchAsk?.sessionId == it.id) chat.switchAsk = null else chat.openSwitch(it.id) } },
            attachments = chat.attachments, attachBusy = chat.attachBusy, attachError = chat.attachError,
            onAttach = { chat.attach(it, controller, scope, agent, state) },
            onRemoveAttachment = chat::removeAttachment,
            wire = chat::wire,
        )
    }
}
