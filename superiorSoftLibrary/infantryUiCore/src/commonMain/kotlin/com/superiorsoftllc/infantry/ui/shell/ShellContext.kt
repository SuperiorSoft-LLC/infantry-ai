// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import com.superiorsoftllc.infantry.ui.ConfigTab
import com.superiorsoftllc.infantry.ui.ToastService
import com.superiorsoftllc.infantry.ui.protocol.OwnerQuestionView
import com.superiorsoftllc.infantry.ui.protocol.TraitView
import com.superiorsoftllc.infantry.ui.protocol.AgentProfile
import com.superiorsoftllc.infantry.ui.model.TraitDraft
import com.superiorsoftllc.infantry.ui.block.layout.Block
import com.superiorsoftllc.infantry.ui.model.AgentView
import com.superiorsoftllc.infantry.ui.model.MemoryNoteView
import com.superiorsoftllc.infantry.ui.model.MemorySearchView
import com.superiorsoftllc.infantry.ui.model.NoteScope
import com.superiorsoftllc.infantry.ui.model.Notice
import com.superiorsoftllc.infantry.ui.model.ProjectView
import com.superiorsoftllc.infantry.ui.protocol.PromptView
import com.superiorsoftllc.infantry.ui.protocol.SkillView
import com.superiorsoftllc.infantry.ui.model.RunsState
import com.superiorsoftllc.infantry.ui.model.ToastEvent
import com.superiorsoftllc.infantry.ui.model.toToast
import com.superiorsoftllc.infantry.ui.model.contractAtLeast
import com.superiorsoftllc.infantry.ui.net.CommandResult
import com.superiorsoftllc.infantry.ui.protocol.PipelineStateView
import com.superiorsoftllc.infantry.ui.protocol.ProjectGitBranchDiffstat
import com.superiorsoftllc.infantry.ui.protocol.ProjectGitBranchList
import com.superiorsoftllc.infantry.ui.protocol.ProjectGitCheckoutResult
import com.superiorsoftllc.infantry.ui.protocol.ProjectGitHistory
import com.superiorsoftllc.infantry.ui.protocol.ProjectGitPatch
import com.superiorsoftllc.infantry.ui.protocol.ProjectGitStatus
import com.superiorsoftllc.infantry.ui.protocol.ProjectGitWorktreeList
import com.superiorsoftllc.infantry.ui.protocol.ProjectGitWorktreeStatus
import com.superiorsoftllc.infantry.ui.protocol.ProviderInfo
import com.superiorsoftllc.infantry.ui.protocol.ProviderModels
import com.superiorsoftllc.infantry.ui.protocol.Role
import com.superiorsoftllc.infantry.ui.protocol.RoleBinding
import com.superiorsoftllc.infantry.ui.model.EpicView
import com.superiorsoftllc.infantry.ui.net.ImageUploadOutcome
import com.superiorsoftllc.infantry.ui.protocol.Ticket
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.reflect.KClass

/**
 * S2 (drop-in path): what the shell gives the blocks it draws, so a block compiled outside core reads state and acts
 * through this, never through `App`, `Shell` or `Controller`. One small sub-interface per domain: a block depends only on
 * the ones it uses. Values are the snapshot of the composition (the context is provided anew when the state changes,
 * so a reader recomposes); actions answer with the daemon's [CommandResult].
 *
 * STABILITY RULE: this surface is plugin API. Members are only ever added (with a default where a third party might
 * implement the interface); none is renamed, retyped or removed. See docs/BLOCKS-LAYOUT-DESIGN.md, section 6q.
 */
interface ShellContext {
    val link: LinkContext
    val session: SessionContext
    val git: GitContext
    val run: RunContext
    val board: BoardContext
    val roles: RolesContext
    val toast: ToastContext
    val config: ConfigContext
    /** S4b (docs/SHELL-LEAKS.md): the tab trees as the window resolved them. */
    val layout: LayoutContext get() = NoLayout
    /** S4b: the project knowledge notes. */
    val notes: NotesContext get() = NoNotes
    /** S4b: the Changes tab's worktrees, branch changes and polls. */
    val changes: ChangesContext get() = NoChanges
    /** S4c: the daemon's harnesses and model catalog, for the executor pickers. */
    val executor: ExecutorContext get() = NoExecutor
    /** S4c: the trait library and each role's traits, for the roles window and the Roles pane. */
    val traits: TraitsContext get() = NoTraits
    /** S4d: the backlog (tickets, epics) and its requests, for the board and anything that files or moves a ticket. */
    val tickets: TicketsContext get() = NoTickets
    /** S4d: the window's own chrome: which build, which pane or dialog to open. */
    val window: WindowContext get() = NoWindow
    /** S4d: the owner's open questions (ASK-1) and the window's way to show or close them. */
    val questions: QuestionsContext get() = NoQuestions
    /** RUNTIME-9: the saved prompts (contract 1.44.0) and their requests, for the official Infantry_Prompts tab. */
    val prompts: PromptsContext get() = NoPrompts
    /** RUNTIME-9: the saved skills (contract 1.45.0) and their requests, for the official Infantry_Skills tab. */
    val skills: SkillsContext get() = NoSkills
    /** RUNTIME-10 (additive): plan limits, the pacing allowance and per-session statistics, for the Usage and Stats tabs. */
    val usage: UsageContext get() = NoUsage
    /** USAGE-1 (additive): the daemon usage meter's daily token/cost rows, for the Stats tab's by-day / by-week view. */
    val meter: UsageMeterContext get() = NoUsageMeter
    /** PACE-TREND-1 (additive): the pacing history request behind the official Infantry_Pace tab. */
    val pace: PaceContext get() = NoPace
    /** RUNTIME-11 (additive): the epic requests behind the official Infantry_Epics tab. */
    val epics: EpicsContext get() = NoEpics
    /** RUNTIME-11 (additive): what the official Infantry_Review tab may do with a queued node. */
    val review: ReviewContext get() = NoReview
    /** RUNTIME-12 (additive): the pod requests behind the official Infantry_Pods tab. */
    val pods: PodsContext get() = NoPods
    /** RUNTIME-13 (additive): the trigger requests behind the official Infantry_Triggers tab. */
    val triggers: TriggersContext get() = NoTriggers
    /** RUNTIME-13 (additive): the attention and question requests behind the official Infantry_Attention tab. */
    val attention: AttentionContext get() = NoAttention
    /** RUNTIME-16 (additive): what the official Infantry_Alerts (incoming-comms sound, spoken alerts) listens to. */
    val alerts: AlertsContext get() = NoAlerts
    /** RUNTIME-18 (additive): the daemon's process watch behind the official Infantry_Processes status-bar badge. */
    val processes: ProcessesContext get() = NoProcesses
    /** RUNTIME-20 (additive): what the official Infantry_Header (title bar, project picker) reads and asks of the window. */
    val header: HeaderContext get() = NoHeader
    /** RUNTIME-21 (additive): what the official Infantry_Agents (the left panel's agents list) reads and asks of the window. */
    val agents: AgentsContext get() = NoAgents
    /** RUNTIME-22 (additive): what the official Infantry_Chat (the centre column, New agent) reads and asks of the window. */
    val chat: ChatContext get() = NoChat
    /** RUNTIME-24 (additive): what the official Infantry_Assistant (the global Assistant popup) reads and asks of the window. */
    val assistant: AssistantContext get() = NoAssistant
    /** RUNTIME-25 (additive): what the official Infantry_Changes (the diff modal) reads and asks of the window. */
    val diff: DiffContext get() = NoDiff
}

/**
 * RUNTIME-25 (F9): the diff modal behind the official Infantry_Changes module, which fills `infantry.shell.diff`.
 * The open file is the window's (the Changes tab opens it), so it is handed in here.
 */
interface DiffContext {
    /** The window's state; null where there is no window (the modal draws nothing). */
    val state: com.superiorsoftllc.infantry.ui.model.AppState?
    val controller: com.superiorsoftllc.infantry.ui.net.Controller?
    /** The file whose diff is open; null while none is. */
    val target: com.superiorsoftllc.infantry.ui.model.DiffTarget?
    /** Opens [next] (prev / next file), or closes the modal on null and hands the focus back to the window. */
    fun show(next: com.superiorsoftllc.infantry.ui.model.DiffTarget?)
}

/** No window: no diff open. */
object NoDiff : DiffContext {
    override val state: com.superiorsoftllc.infantry.ui.model.AppState? = null
    override val controller: com.superiorsoftllc.infantry.ui.net.Controller? = null
    override val target: com.superiorsoftllc.infantry.ui.model.DiffTarget? = null
    override fun show(next: com.superiorsoftllc.infantry.ui.model.DiffTarget?) {}
}

/**
 * RUNTIME-24 (F8): the Assistant popup behind the official Infantry_Assistant module, which fills `infantry.shell.assistant`.
 * The popup's view state stays the window's (it survives closing), so it is handed in here.
 */
interface AssistantContext {
    /** The window's state; null where there is no window (the popup draws nothing). */
    val state: com.superiorsoftllc.infantry.ui.model.AppState?
    val controller: com.superiorsoftllc.infantry.ui.net.Controller?
    /** Open or closed, the dragged width, the draft's expanded flag. */
    val panel: com.superiorsoftllc.infantry.ui.AssistantPanelState
    /** The Assistant's own chat panel state (draft, attachments). */
    val chat: com.superiorsoftllc.infantry.ui.ChatPanelState?
}

/** No window: a closed panel, nothing to draw. */
object NoAssistant : AssistantContext {
    override val state: com.superiorsoftllc.infantry.ui.model.AppState? = null
    override val controller: com.superiorsoftllc.infantry.ui.net.Controller? = null
    override val panel: com.superiorsoftllc.infantry.ui.AssistantPanelState = com.superiorsoftllc.infantry.ui.AssistantPanelState()
    override val chat: com.superiorsoftllc.infantry.ui.ChatPanelState? = null
}

/**
 * RUNTIME-22 (F6): the centre column behind the official Infantry_Chat module, which fills `infantry.shell.center`.
 * Values are the composition's snapshot; the actions are the window's own.
 */
interface ChatContext {
    /** The window's state; null where there is no window (the column draws nothing). */
    val state: com.superiorsoftllc.infantry.ui.model.AppState?
    val controller: com.superiorsoftllc.infantry.ui.net.Controller?
    /** The main window's chat panel state (draft, attachments, spawn mode). */
    val chat: com.superiorsoftllc.infantry.ui.ChatPanelState?
    /** The selected agent's session id. */
    val selectedId: String?
    /** The picked centre tab id. */
    val centerTab: String
    fun setCenterTab(id: String)
    /** Whether the New agent dialog is open. */
    val showNewAgent: Boolean
    fun setShowNewAgent(open: Boolean)
    /** Opens the board (the chat header's ticket tag). */
    fun showBoard()
    /** Draws the picked centre tab; [transcript] is the chat's, which the Chat tab shows. */
    @androidx.compose.runtime.Composable
    fun TabContent(transcript: @androidx.compose.runtime.Composable () -> Unit)
}

/** No window: nothing to draw, every action a no-op. */
object NoChat : ChatContext {
    override val state: com.superiorsoftllc.infantry.ui.model.AppState? = null
    override val controller: com.superiorsoftllc.infantry.ui.net.Controller? = null
    override val chat: com.superiorsoftllc.infantry.ui.ChatPanelState? = null
    override val selectedId: String? = null
    override val centerTab: String = ""
    override fun setCenterTab(id: String) {}
    override val showNewAgent: Boolean = false
    override fun setShowNewAgent(open: Boolean) {}
    override fun showBoard() {}
    @androidx.compose.runtime.Composable
    override fun TabContent(transcript: @androidx.compose.runtime.Composable () -> Unit) {}
}

/**
 * RUNTIME-22: the shared chat panel, drawn by the official Infantry_Chat, for a host that stays in the shell until its own
 * split (the Assistant popup, F8). Null while Infantry_Chat is not loaded (the host then draws nothing).
 * HOUSEKEEP-1: kept on purpose. A composable with parameters cannot travel through a fill (fills are parameterless
 * blocks/sections), and Infantry_Assistant must not depend on Infantry_Chat's classes, so this shell-owned holder is the
 * one hand-off between the two plugins. Revisit when the Assistant gets its own chat block.
 */
object ChatPanels {
    var panel: (@androidx.compose.runtime.Composable (sessionId: String, controller: com.superiorsoftllc.infantry.ui.net.Controller, modifier: androidx.compose.ui.Modifier, chat: com.superiorsoftllc.infantry.ui.ChatPanelState, allowSpawn: Boolean, transcriptTag: String) -> Unit)? = null
}

/**
 * RUNTIME-21 (F5): the left panel's agents list behind the official Infantry_Agents module, which fills the panel
 * section `infantry.agents`. The panel frame (width, background, resize handle, drawer) stays in the shell.
 */
interface AgentsContext {
    /** The window's state; null where there is no window (the list draws nothing). */
    val state: com.superiorsoftllc.infantry.ui.model.AppState?
    /** The agents the list shows (project scope applied). */
    val visible: List<com.superiorsoftllc.infantry.ui.model.AgentView>
    val query: String
    fun setQuery(q: String)
    val roleFilter: String?
    fun setRoleFilter(role: String?)
    /** The search field's focus requester (Ctrl+K). */
    val searchFocus: androidx.compose.ui.focus.FocusRequester
    /** Selects agent [id] and shows its chat (leaving a compact window's drawer). */
    fun select(id: String)
    /** Opens the New agent dialog. */
    fun newAgent()
    suspend fun remove(id: String)
    suspend fun assign(id: String, projectId: String)
    /** AGENT-TASKS-7: hand ticket [ticketId] to agent profile [agentId] (start if free, else queue). */
    suspend fun assignTicket(ticketId: String, agentId: String) {}
    /** AGENT-MENU-1: take ticket [ticketId] off its worker (back to todo). */
    suspend fun unassignTicket(ticketId: String) {}
    /** CLI-PTY-5: "Resume session" on a dormant agent: attach its CLI with no turn (Controller.resumeSession). */
    suspend fun resumeSession(id: String) {}
    /** AGENT-TASKS-7: opens ticket [ticketId]'s detail (the board's modal), where one is loaded. */
    fun openTicket(ticketId: String) {}
    /** AGENT-TASKS-7: an `agent_queue` action on [agentId]: list, remove, move. */
    suspend fun agentQueue(agentId: String, action: String, ticketId: String? = null, at: Int? = null) {}
    /** Dismisses the daemon's newest project / workspace refusal. */
    fun dismissProjectRefusal()
    /** Opens the board; null when no module fills the board overlay (no Board button then). */
    val openBoard: (() -> Unit)?
    /** Whether the official Infantry_Review tab is loaded (the "To review" counter shows only then). */
    val reviewLoaded: Boolean
}

/** No window: nothing to list, every action a no-op. */
object NoAgents : AgentsContext {
    override val state: com.superiorsoftllc.infantry.ui.model.AppState? = null
    override val visible: List<com.superiorsoftllc.infantry.ui.model.AgentView> = emptyList()
    override val query: String = ""
    override fun setQuery(q: String) {}
    override val roleFilter: String? = null
    override fun setRoleFilter(role: String?) {}
    override val searchFocus: androidx.compose.ui.focus.FocusRequester = androidx.compose.ui.focus.FocusRequester()
    override fun select(id: String) {}
    override fun newAgent() {}
    override suspend fun remove(id: String) {}
    override suspend fun assign(id: String, projectId: String) {}
    override fun dismissProjectRefusal() {}
    override val openBoard: (() -> Unit)? = null
    override val reviewLoaded: Boolean = false
}

/**
 * RUNTIME-20 (F4): the window's title bar behind the official Infantry_Header module, which fills `infantry.shell.header`.
 * Values are the composition's snapshot; the actions are the window's own.
 */
interface HeaderContext {
    /** The window's state; null where there is no window (the header draws nothing). */
    val state: com.superiorsoftllc.infantry.ui.model.AppState?
    /** The controller the project picker's requests (create, edit, switch project) go through; null with [state]. */
    val controller: com.superiorsoftllc.infantry.ui.net.Controller?
    /** Which agents the avatar strip lists. */
    val agentScope: com.superiorsoftllc.infantry.ui.model.AgentScope
    /** The host's server dropdown; null where the host has none. */
    val switcher: ServerSwitcher?
    /** Shows or hides one side panel. */
    fun togglePanel(side: com.superiorsoftllc.infantry.ui.model.PanelSide)
    /** Opens tab [id] (the attention chip opens Attention). */
    fun showTab(id: String)
    /** Opens the connection (or the host's own connect screen, MOBILE-CONNCHIP-1). */
    fun connect()
    /** Switches to [choice] on the server dropdown. */
    fun switchTo(choice: ServerChoice)
    /** Opens the Servers list. */
    fun manageServers()
    /** The Assistant's entry in the team strip; true while its popup is open. */
    val assistantOpen: Boolean
    /** Whether the Assistant exists on this daemon (or already has a session): then the strip shows its entry. */
    val hasAssistant: Boolean
    /** A tap on the Assistant's entry. */
    fun toggleAssistant()
}

/** No window: nothing to draw, every action a no-op. */
object NoHeader : HeaderContext {
    override val state: com.superiorsoftllc.infantry.ui.model.AppState? = null
    override val controller: com.superiorsoftllc.infantry.ui.net.Controller? = null
    override val agentScope: com.superiorsoftllc.infantry.ui.model.AgentScope = com.superiorsoftllc.infantry.ui.model.AgentScope.GLOBAL
    override val switcher: ServerSwitcher? = null
    override fun togglePanel(side: com.superiorsoftllc.infantry.ui.model.PanelSide) {}
    override fun showTab(id: String) {}
    override fun connect() {}
    override fun switchTo(choice: ServerChoice) {}
    override fun manageServers() {}
    override val assistantOpen: Boolean = false
    override val hasAssistant: Boolean = false
    override fun toggleAssistant() {}
}

/**
 * RUNTIME-18 (F2): the daemon's process watch (ROGUE-1, contract 1.120.0) behind the official Infantry_Processes module,
 * which fills the status bar's `infantry.shell.footer.processesBadge` slot. The values are the composition's snapshot;
 * whether the daemon has the watch at all is [LinkContext.contractAtLeast].
 */
interface ProcessesContext {
    /** The daemon's last process report (memory, tracked / rogue / not-ours processes); null until it sends one. */
    val report: com.superiorsoftllc.infantry.ui.protocol.ProcessReport?
    /** The daemon's `processes.autoReap` setting as last answered; null until it answers (the report's value applies). */
    val autoReap: Boolean?
    /** Asks for a fresh report (silent; refused by a daemon without the watch). */
    suspend fun refresh(): CommandResult
    /** Reaps rogue process [pid], or with null every rogue one; the daemon refuses anything its sessions did not start. */
    suspend fun reap(pid: Long?): CommandResult
    /** Sets the daemon's `processes.autoReap`. */
    suspend fun setAutoReap(on: Boolean): CommandResult
}

/** No process watch: no report, every request refused (a shell without a window, and fakes). */
object NoProcesses : ProcessesContext {
    override val report: com.superiorsoftllc.infantry.ui.protocol.ProcessReport? = null
    override val autoReap: Boolean? = null
    override suspend fun refresh(): CommandResult = CommandResult.Refused(SHELL_DOMAIN_MISSING)
    override suspend fun reap(pid: Long?): CommandResult = CommandResult.Refused(SHELL_DOMAIN_MISSING)
    override suspend fun setAutoReap(on: Boolean): CommandResult = CommandResult.Refused(SHELL_DOMAIN_MISSING)
}

/**
 * RUNTIME-16: the window's feeds and the owner's settings behind the official Infantry_Alerts module, which fills the
 * shell's `infantry.shell.sound` and `infantry.shell.voice` slots. The settings are the Configuration's (read in
 * composition, so a change redraws the module).
 */
interface AlertsContext {
    /** The window's state, as it changes (the sound watches it for incoming comms). */
    val state: kotlinx.coroutines.flow.StateFlow<com.superiorsoftllc.infantry.ui.model.AppState>?
    /** What the daemon asks to be spoken (VOICE-1). */
    val spoken: kotlinx.coroutines.flow.Flow<com.superiorsoftllc.infantry.ui.voice.SpokenNotice>
    val commsSound: com.superiorsoftllc.infantry.ui.sound.IncomingCommsSoundSettings
    val voice: com.superiorsoftllc.infantry.ui.voice.VoiceSettings
}

/** No alerts: no state to watch, nothing spoken (a shell without a window, and fakes). */
object NoAlerts : AlertsContext {
    override val state: kotlinx.coroutines.flow.StateFlow<com.superiorsoftllc.infantry.ui.model.AppState>? = null
    override val spoken: kotlinx.coroutines.flow.Flow<com.superiorsoftllc.infantry.ui.voice.SpokenNotice> = kotlinx.coroutines.flow.emptyFlow()
    override val commsSound = com.superiorsoftllc.infantry.ui.sound.IncomingCommsSoundSettings()
    override val voice = com.superiorsoftllc.infantry.ui.voice.VoiceSettings()
}

/**
 * RUNTIME-13: the daemon requests behind the official Triggers tab. The triggers and their events come from the window's
 * state ([com.superiorsoftllc.infantry.ui.plugin.TabContext.state], `AppState.triggers`).
 */
interface TriggersContext {
    /** Saves a trigger (`trigger_save`); [payload] is the form's checked save body (with `id` when it edits one). */
    suspend fun save(payload: kotlinx.serialization.json.JsonObject): CommandResult
    suspend fun fire(id: String): CommandResult
    /** Re-arms a tripped (or re-enables a disabled) trigger. */
    suspend fun arm(id: String): CommandResult
    suspend fun disable(id: String): CommandResult
    suspend fun delete(id: String): CommandResult
    /** Asks for trigger [id]'s event rows. */
    suspend fun loadEvents(id: String): CommandResult
}

/** No trigger requests: every one refused. */
object NoTriggers : TriggersContext {
    override suspend fun save(payload: kotlinx.serialization.json.JsonObject): CommandResult = CommandResult.Refused(SHELL_DOMAIN_MISSING)
    override suspend fun fire(id: String): CommandResult = CommandResult.Refused(SHELL_DOMAIN_MISSING)
    override suspend fun arm(id: String): CommandResult = CommandResult.Refused(SHELL_DOMAIN_MISSING)
    override suspend fun disable(id: String): CommandResult = CommandResult.Refused(SHELL_DOMAIN_MISSING)
    override suspend fun delete(id: String): CommandResult = CommandResult.Refused(SHELL_DOMAIN_MISSING)
    override suspend fun loadEvents(id: String): CommandResult = CommandResult.Refused(SHELL_DOMAIN_MISSING)
}

/**
 * RUNTIME-13: what the official Attention tab may do: acknowledge an item, answer / cancel / show an owner question, open
 * an agent. The items and questions come from the window's state (`AppState.attention`, `AppState.questions`).
 */
interface AttentionContext {
    /** Acknowledges attention item [id] (`attention_ack`). */
    suspend fun ack(id: String): CommandResult
    /** Answers (or overrides the auto-pick of) an owner question. */
    suspend fun answer(answer: com.superiorsoftllc.infantry.ui.protocol.QuestionAnswerWire): CommandResult
    /** Closes question [id] on the daemon. */
    suspend fun cancelQuestion(id: String): CommandResult
    /** Opens question [id] in its asker's chat. */
    fun openQuestion(id: String)
    /** Opens session [sessionId]. */
    fun openSession(sessionId: String)
}

/** No attention requests: every one refused, opening does nothing. */
object NoAttention : AttentionContext {
    override suspend fun ack(id: String): CommandResult = CommandResult.Refused(SHELL_DOMAIN_MISSING)
    override suspend fun answer(answer: com.superiorsoftllc.infantry.ui.protocol.QuestionAnswerWire): CommandResult = CommandResult.Refused(SHELL_DOMAIN_MISSING)
    override suspend fun cancelQuestion(id: String): CommandResult = CommandResult.Refused(SHELL_DOMAIN_MISSING)
    override fun openQuestion(id: String) {}
    override fun openSession(sessionId: String) {}
}

/**
 * RUNTIME-12: the daemon requests behind the official Pods tab. The pods it shows come from the window's state
 * ([com.superiorsoftllc.infantry.ui.plugin.TabContext.state], `AppState.pods`).
 */
interface PodsContext {
    /** Submits a pod for [goal] with [nodes] (`pod_submit`). */
    suspend fun submit(goal: String, nodes: List<com.superiorsoftllc.infantry.ui.protocol.NodeSpec>): CommandResult
    /** Asks for pod [podId] (`pod_query`). */
    suspend fun query(podId: String): CommandResult
    /** Cancels pod [podId] (`pod_cancel`). */
    suspend fun cancel(podId: String): CommandResult
    /** Selects session [sessionId] (a node's agent). */
    fun openAgent(sessionId: String)
}

/** No pod requests: every one refused, opening an agent does nothing. */
object NoPods : PodsContext {
    override suspend fun submit(goal: String, nodes: List<com.superiorsoftllc.infantry.ui.protocol.NodeSpec>): CommandResult = CommandResult.Refused(SHELL_DOMAIN_MISSING)
    override suspend fun query(podId: String): CommandResult = CommandResult.Refused(SHELL_DOMAIN_MISSING)
    override suspend fun cancel(podId: String): CommandResult = CommandResult.Refused(SHELL_DOMAIN_MISSING)
    override fun openAgent(sessionId: String) {}
}

/**
 * RUNTIME-11: the daemon requests behind the official Epics tab (contract 1.19.0). The epics and tickets it shows come
 * from the window's state ([com.superiorsoftllc.infantry.ui.plugin.TabContext.state]).
 */
interface EpicsContext {
    /** Asks for [projectId]'s epics and tickets. */
    suspend fun load(projectId: String): CommandResult
    /** Files a new epic under [projectId] (`epic_save`, create only). */
    suspend fun save(projectId: String, name: String, goal: String?): CommandResult
    /** Files a new ticket [title] under epic [epicId] of [projectId]. */
    suspend fun addTask(projectId: String, title: String, epicId: String): CommandResult
    /** Moves ticket [id] to column [status]. */
    suspend fun moveTicket(id: String, status: String): CommandResult
}

/** No epic requests: every one refused. */
object NoEpics : EpicsContext {
    override suspend fun load(projectId: String): CommandResult = CommandResult.Refused(SHELL_DOMAIN_MISSING)
    override suspend fun save(projectId: String, name: String, goal: String?): CommandResult = CommandResult.Refused(SHELL_DOMAIN_MISSING)
    override suspend fun addTask(projectId: String, title: String, epicId: String): CommandResult = CommandResult.Refused(SHELL_DOMAIN_MISSING)
    override suspend fun moveTicket(id: String, status: String): CommandResult = CommandResult.Refused(SHELL_DOMAIN_MISSING)
}

/** RUNTIME-11: the Review tab's actions. The queue itself is the window's state (`AppState.reviewQueue`). */
interface ReviewContext {
    /** Selects session [sessionId] (a queued node's agent). */
    fun openAgent(sessionId: String)
}

/** No review actions: opening an agent does nothing. */
object NoReview : ReviewContext {
    override fun openAgent(sessionId: String) {}
}

/**
 * RUNTIME-10: the daemon requests behind the official Usage and Stats tabs (contracts 1.59.0 / 1.92.0 / 1.119.0 /
 * 1.121.0). What they show comes from the window's state ([com.superiorsoftllc.infantry.ui.plugin.TabContext.state]).
 */
interface UsageContext {
    /** Asks for the plan-limit meters again (and the pacing allowance where the daemon has it). */
    suspend fun refreshLimits(): CommandResult
    /** Asks for the plan meters and pacing allowance again; [force] skips the daemon's re-read throttle. */
    suspend fun refreshUsage(force: Boolean = false): CommandResult
    /** A manual percent, an override or the rule; the answer is the fresh `usage_report`. */
    suspend fun usageSet(set: com.superiorsoftllc.infantry.ui.protocol.UsageSet): CommandResult
    /** Asks for [sessionId]'s statistics; the answer is a `session_stats` frame the window keeps. */
    suspend fun sessionStats(sessionId: String): CommandResult
}

/** No usage requests: every one refused. */
object NoUsage : UsageContext {
    override suspend fun refreshLimits(): CommandResult = CommandResult.Refused(SHELL_DOMAIN_MISSING)
    override suspend fun refreshUsage(force: Boolean): CommandResult = CommandResult.Refused(SHELL_DOMAIN_MISSING)
    override suspend fun usageSet(set: com.superiorsoftllc.infantry.ui.protocol.UsageSet): CommandResult = CommandResult.Refused(SHELL_DOMAIN_MISSING)
    override suspend fun sessionStats(sessionId: String): CommandResult = CommandResult.Refused(SHELL_DOMAIN_MISSING)
}

/** USAGE-1: asks the daemon for the usage meter rows; the answer lands in the window's state ([AppState.usageMeter]). */
interface UsageMeterContext {
    suspend fun refresh(): CommandResult
}

/** No usage meter: every request refused. */
object NoUsageMeter : UsageMeterContext {
    override suspend fun refresh(): CommandResult = CommandResult.Refused(SHELL_DOMAIN_MISSING)
}

/** PACE-TREND-1: asks the daemon's pace plugin for its report; the answer lands in the window's state ([AppState.paceReport]). */
interface PaceContext {
    /** [query] is the `pace_get` JSON (provider, window, sinceHours); empty = everything. */
    suspend fun refresh(query: String = ""): CommandResult
}

/** No pace plugin on the daemon, or no window: every request refused. */
object NoPace : PaceContext {
    override suspend fun refresh(query: String): CommandResult = CommandResult.Refused(SHELL_DOMAIN_MISSING)
}

/** The daemon link: whether it is up and which contract the daemon announced. */
interface LinkContext {
    val live: Boolean
    /** The contract version from `ready` ("1.2.0"); null before it or from a daemon too old to say. */
    val contract: String?
    fun contractAtLeast(major: Int, minor: Int): Boolean = contractAtLeast(contract, major, minor)
    /** S4d: the daemon's build stamp from `ready`; null before it or from a daemon too old to say. */
    val daemonBuild: String? get() = null
    /** DEMO-MERGE-1: every `key=value` field of the last `ready`, verbatim; a module adapting the UI to its deployment reads its own. */
    val readyFields: Map<String, String> get() = emptyMap()
    /** DEMO-MERGE-1: the latest message per refusal code the daemon declared a notice (`ready` notices=); a module shows them. */
    val notices: Map<String, String> get() = emptyMap()
    /** DEMO-MERGE-1: the daemon serves this link a restricted surface (`ready` access=restricted): owner-only reads are refused. */
    val restricted: Boolean get() = readyFields["access"] == "restricted"
    /** POLICY-1 (docs/POLICY.md): the daemon's active policy; [PolicyWire.Production] (nothing off) when none was sent. */
    val policy: com.superiorsoftllc.infantry.plugin.api.PolicyWire get() = com.superiorsoftllc.infantry.plugin.api.PolicyWire.Production
    /** POLICY-1: the owner's switch (`policy_set`); the daemon refuses it unless [policy] says `canSwitch`. */
    fun selectPolicy(presetId: String) {}
    /**
     * The GENERIC write (an undeclared write action) may be enabled: live, the [policy] does not turn undeclared writes
     * off, and the nearest [WritePolicy] allows writes. A control with a named action uses [gate] / [Gated] instead.
     * Read-only views keep using [live].
     */
    val canWrite: Boolean @Composable get() = gate("").enabled
    /**
     * NOTIFY-1 (additive, generic): the `data` of every daemon frame of [type] this link receives, in order (a plugin's own
     * push frames, e.g. one its daemon half sends with `api.events.broadcast`). Empty outside a shell.
     */
    fun frames(type: String): kotlinx.coroutines.flow.Flow<String> = kotlinx.coroutines.flow.emptyFlow()
    /** NOTIFY-1 (additive, generic): sends one frame of [type] with [data] on this link (a plugin's own frame); false when not sent. */
    suspend fun sendFrame(type: String, data: String): Boolean = false
    /**
     * RUNNER-UI-ADD-1 (additive, generic): sends one frame of [type] with [data] under a fresh request id and returns the
     * daemon's DIRECT answer to it: the frame the daemon echoes that id on (an answer of [type], or its `error` refusal,
     * [LinkReply.refused]). Never another request's answer of the same type, which [frames] + [sendFrame] cannot tell apart
     * when two requests of one type are in flight. Null when nothing was sent (no link, or outside a shell). The daemon must
     * echo the id (contract 1.87.0); a frame it answers without one is never matched.
     */
    suspend fun request(type: String, data: String): LinkReply? = null
}

/** RUNNER-UI-ADD-1: the daemon's direct answer to one [LinkContext.request]: its frame [type] and [data]. */
data class LinkReply(val type: String, val data: String) {
    /** The daemon refused the request (an `error` frame, e.g. a policy or owner-only gate); [data] says why. */
    val refused: Boolean get() = type == com.superiorsoftllc.infantry.ui.protocol.Envelope.ERROR
}

/** The window's working context: the active project and the daemon's refusals. */
interface SessionContext {
    val activeProject: ProjectView?
    /** Frames the daemon refused or reported that belong to no session, oldest first. */
    val notices: List<Notice>
    /** The newest project / workspace refusal, until a success, a switch or a dismiss clears it. */
    val projectRefusal: Notice?
    /** S4b: the selected session's id, even before its agent row arrives; null with none selected. */
    val selectedAgentId: String? get() = null
    /** S4b: the selected session's agent; null with none selected or its row not known yet. */
    val selectedAgent: AgentView? get() = null
    /** S4c: the active project's id, even before its row arrives; null with none active. */
    val activeProjectId: String? get() = activeProject?.id
    /** S4d: the daemon's projects, in its order. */
    val projects: List<ProjectView> get() = listOfNotNull(activeProject)
    /** S4d: whether the session list has [sessionId] (a question's asker may be gone). */
    fun hasSession(sessionId: String): Boolean = false
}

/**
 * S4d (docs/SHELL-LEAKS.md): the window's chrome: its build, its server's mismatch, and what a block may open (the chat,
 * a tab, the configuration modal, the connection). The window's panes and dialogs stay its own.
 */
interface WindowContext {
    /** This app's build stamp; null where the host does not know it. */
    val appBuild: String?
    /** SERVER-UPDATE-4: client and server differ by more than a patch (the text Configuration > Server shows); else null. */
    val serverMismatch: String?
    /** Shows the selected session's chat in the centre (leaves a spawn form). */
    fun showChat()
    /** Shows tab [id] where it lives (centre or right). */
    fun showTab(id: String)
    /** Opens the configuration modal, on [tab] or where the owner left it. */
    fun openConfig(tab: ConfigTab? = null)
    /** Opens the connection: the host's own connect screen where it has one, else the connect dialog. */
    fun openConnection()
}

/** S4d: no window (a pane drawn outside one): nothing to open. */
object NoWindow : WindowContext {
    override val appBuild: String? get() = null
    override val serverMismatch: String? get() = null
    override fun showChat() {}
    override fun showTab(id: String) {}
    override fun openConfig(tab: ConfigTab?) {}
    override fun openConnection() {}
}

/** S4d (docs/SHELL-LEAKS.md): ASK-1's open questions, which the window puts off (Later) and brings back. */
interface QuestionsContext {
    /** The questions still open on the daemon, oldest first. */
    val open: List<OwnerQuestionView>
    /** The ids put off with Later in this window. */
    val dismissed: Set<String>
    /** Closes question [id] on the daemon (its asker is gone, or the owner dismissed it). */
    suspend fun cancel(id: String): CommandResult
    /** Brings the questions back and opens question [id]'s asker, where it is in the list. */
    fun show(id: String)
    /** Brings every put-off question back. */
    fun showAll()
}

/** S4d: no questions. */
object NoQuestions : QuestionsContext {
    override val open: List<OwnerQuestionView> get() = emptyList()
    override val dismissed: Set<String> get() = emptySet()
    override suspend fun cancel(id: String): CommandResult = CommandResult.Refused(SHELL_DOMAIN_MISSING)
    override fun show(id: String) {}
    override fun showAll() {}
}

/** The active project's git, by project id, and the requests that load or change it. */
interface GitContext {
    fun status(projectId: String): ProjectGitStatus?
    /** Why the last tree read failed; the tree is then unknown, never clean. */
    val failure: String?
    fun patch(projectId: String): ProjectGitPatch?
    fun history(projectId: String): ProjectGitHistory?
    fun branches(projectId: String): ProjectGitBranchList?
    fun checkoutResult(projectId: String): ProjectGitCheckoutResult?
    fun pipeline(projectId: String): PipelineStateView?
    suspend fun load(projectId: String): CommandResult
    suspend fun loadDiff(projectId: String, path: String, staged: Boolean): CommandResult
    suspend fun loadBranches(projectId: String): CommandResult
    suspend fun checkout(projectId: String, branch: String, create: Boolean): CommandResult
    /** [branch] null goes back to the daemon's default base. */
    suspend fun setBaseBranch(projectId: String, branch: String?): CommandResult
    /** [directory] "" clears it. */
    suspend fun saveDirectory(projectId: String, directory: String): CommandResult
    suspend fun loadPipeline(projectId: String): CommandResult
    /** [stages] null removes the pipeline. */
    suspend fun configurePipeline(projectId: String, stages: List<String>?): CommandResult
}

/** Saved commands and their runs. */
interface RunContext {
    val runs: RunsState
    /** The daemon's secret names a command may name; null when the daemon has no key-value secrets. */
    val secretNames: List<String>?
    /** This platform opens a run in a console window (the desktop); elsewhere its lines show in the pane. */
    val hasConsoleWindow: Boolean
    /** The daemon runs a command interactively (contract 1.134.0). */
    val interactiveSupported: Boolean
    suspend fun start(commandId: String, interactive: Boolean): CommandResult
    suspend fun stop(runId: String): CommandResult
    /** Shows [runId]'s lines and, where [hasConsoleWindow], opens its console window. */
    fun open(runId: String)
    suspend fun saveCommand(id: String, name: String, argv: List<String>, cwd: String?, graceMillis: Long, secrets: List<String>): CommandResult
    suspend fun deleteCommand(id: String): CommandResult

    /** RUN-SCOPE-1: the project the Run tab shows (the window's active one); null with none. */
    val project: ProjectView? get() = null
    /** RUN-SCOPE-1: [saveCommand] for [project] (its cwd is relative to that project's folder). */
    suspend fun saveCommand(id: String, name: String, argv: List<String>, cwd: String?, graceMillis: Long, secrets: List<String>, project: String?): CommandResult =
        saveCommand(id, name, argv, cwd, graceMillis, secrets)
    /** RUN-SCOPE-1: [start] that names the project; the daemon refuses another project's command. */
    suspend fun start(commandId: String, interactive: Boolean, project: String?): CommandResult = start(commandId, interactive)
    /** RUN-SCOPE-1: registers [projectId]'s folder (what `project_config_set directory` does); "" clears it. */
    suspend fun setProjectFolder(projectId: String, directory: String): CommandResult = CommandResult.Refused(SHELL_DOMAIN_MISSING)
}

/** S4b: the tab trees ([com.superiorsoftllc.infantry.ui.plugin.InfantryModule.tabTrees]) with the modules' overrides in. */
interface LayoutContext {
    /** The resolved tree whose root id is [id]; null when no module contributes it. */
    fun tabTree(id: String): Block?
    /** S4d: the window is compact (below 600dp, MOBILE-LAYOUT-2), so wide content scrolls sideways or stacks. */
    val compact: Boolean get() = false
}

/** No tab tree resolved: a tab draws its own default tree. */
object NoLayout : LayoutContext {
    override fun tabTree(id: String): Block? = null
}

/** S4b: why a shell without the notes or changes domain refuses their requests. */
const val SHELL_DOMAIN_MISSING = "this shell does not provide it"

/** S4b: the project knowledge notes (contracts 1.20.0 / 1.21.0 / 1.51.0), per state key ([NoteScope.key]). */
interface NotesContext {
    /** The note index of [key] (a project and scope); empty before it loads. */
    fun notes(key: String): List<MemoryNoteView>
    /** The newest search answer; its `projectId` is the key it was asked for. */
    val search: MemorySearchView?
    suspend fun load(projectId: String, at: NoteScope): CommandResult
    /** Fetches note [name]'s body, which the index withholds. */
    suspend fun get(projectId: String, name: String, at: NoteScope): CommandResult
    suspend fun search(projectId: String, query: String, at: NoteScope): CommandResult
    suspend fun save(projectId: String, name: String, description: String, body: String, at: NoteScope): CommandResult
    suspend fun delete(projectId: String, name: String, at: NoteScope): CommandResult
}

/** No notes: an empty index, and every request refused. */
object NoNotes : NotesContext {
    override fun notes(key: String): List<MemoryNoteView> = emptyList()
    override val search: MemorySearchView? get() = null
    override suspend fun load(projectId: String, at: NoteScope): CommandResult = CommandResult.Refused(SHELL_DOMAIN_MISSING)
    override suspend fun get(projectId: String, name: String, at: NoteScope): CommandResult = CommandResult.Refused(SHELL_DOMAIN_MISSING)
    override suspend fun search(projectId: String, query: String, at: NoteScope): CommandResult = CommandResult.Refused(SHELL_DOMAIN_MISSING)
    override suspend fun save(projectId: String, name: String, description: String, body: String, at: NoteScope): CommandResult =
        CommandResult.Refused(SHELL_DOMAIN_MISSING)
    override suspend fun delete(projectId: String, name: String, at: NoteScope): CommandResult = CommandResult.Refused(SHELL_DOMAIN_MISSING)
}

/**
 * S4b: the Changes tab (UX-CHANGES-1, BRANCH-VIEW-2): the project's worktrees, the one shown and its status, a branch's
 * changes, and the requests that load or pick them. The working tree itself and the branch list are [GitContext]'s.
 */
interface ChangesContext {
    fun worktrees(projectId: String): ProjectGitWorktreeList?
    /** The worktree the header shows (contract 1.88.0); null before the list arrives. */
    val worktree: String?
    val worktreeStatus: ProjectGitWorktreeStatus?
    /** The branch picked in the inner picker before 1.88.0; null is the working tree. */
    val branch: String?
    /** From 1.88.0: the branch of the worktree the header shows, which the inner picker follows. */
    val shownBranch: String?
    fun branchChanges(projectId: String): ProjectGitBranchDiffstat?
    /** The directory's working tree (before 1.88.0). */
    suspend fun loadTree(projectId: String): CommandResult
    /** [sessionId] names the selected session, so the daemon opens the view on its checkout. */
    suspend fun loadWorktrees(projectId: String, sessionId: String?): CommandResult
    suspend fun loadWorktreeStatus(projectId: String, path: String): CommandResult
    suspend fun loadBranchChanges(projectId: String, branch: String): CommandResult
    fun selectWorktree(projectId: String, path: String)
    /** [branch] null is the working tree. */
    fun selectBranch(projectId: String, branch: String?)
    /**
     * BUG-CHANGES-BRANCH-1: one worktree-list poll that returns only once the daemon replied to it (or refused it, or a
     * new link came up), bounded by [timeoutMs], so polls never pile up behind a slow git.
     */
    suspend fun pollWorktrees(projectId: String, sessionId: String?, timeoutMs: Long)
    /** The same for the shown worktree's status. */
    suspend fun pollWorktreeStatus(projectId: String, path: String, timeoutMs: Long)
}

/** No changes domain: nothing known, every request refused, every poll returns at once. */
object NoChanges : ChangesContext {
    override fun worktrees(projectId: String): ProjectGitWorktreeList? = null
    override val worktree: String? get() = null
    override val worktreeStatus: ProjectGitWorktreeStatus? get() = null
    override val branch: String? get() = null
    override val shownBranch: String? get() = null
    override fun branchChanges(projectId: String): ProjectGitBranchDiffstat? = null
    override suspend fun loadTree(projectId: String): CommandResult = CommandResult.Refused(SHELL_DOMAIN_MISSING)
    override suspend fun loadWorktrees(projectId: String, sessionId: String?): CommandResult = CommandResult.Refused(SHELL_DOMAIN_MISSING)
    override suspend fun loadWorktreeStatus(projectId: String, path: String): CommandResult = CommandResult.Refused(SHELL_DOMAIN_MISSING)
    override suspend fun loadBranchChanges(projectId: String, branch: String): CommandResult = CommandResult.Refused(SHELL_DOMAIN_MISSING)
    override fun selectWorktree(projectId: String, path: String) {}
    override fun selectBranch(projectId: String, branch: String?) {}
    override suspend fun pollWorktrees(projectId: String, sessionId: String?, timeoutMs: Long) {}
    override suspend fun pollWorktreeStatus(projectId: String, path: String, timeoutMs: Long) {}
}

/** RUNTIME-9: the saved prompts (contract 1.44.0): the list, its load and refusal state, and the requests. */
interface PromptsContext {
    val all: List<PromptView>
    val loading: Boolean
    /** The daemon's last refusal of a prompt request; null after a success. */
    val error: String?
    suspend fun load(): CommandResult
    /** Saves by [name] (an existing one is updated); [tagsCsv] comma separated. */
    suspend fun save(name: String, body: String, tagsCsv: String): CommandResult
    suspend fun delete(id: String): CommandResult
}

/** RUNTIME-9: no prompts: none, every request refused. */
object NoPrompts : PromptsContext {
    override val all: List<PromptView> get() = emptyList()
    override val loading: Boolean get() = false
    override val error: String? get() = null
    override suspend fun load(): CommandResult = CommandResult.Refused(SHELL_DOMAIN_MISSING)
    override suspend fun save(name: String, body: String, tagsCsv: String): CommandResult = CommandResult.Refused(SHELL_DOMAIN_MISSING)
    override suspend fun delete(id: String): CommandResult = CommandResult.Refused(SHELL_DOMAIN_MISSING)
}

/** RUNTIME-9: the saved skills (contract 1.45.0): the list, its load and refusal state, and the requests. */
interface SkillsContext {
    val all: List<SkillView>
    val loading: Boolean
    /** The daemon's last refusal of a skill request; null after a success. */
    val error: String?
    suspend fun load(): CommandResult
    /** Saves by [name] (an existing one is updated); [tagsCsv] comma separated. */
    suspend fun save(name: String, description: String, body: String, tagsCsv: String): CommandResult
    suspend fun delete(id: String): CommandResult
    /** SKILLS-GLOBAL-1b: the app state and controller behind the layered-skills panel; null = no layered panel. */
    val appState: com.superiorsoftllc.infantry.ui.model.AppState? get() = null
    val appController: com.superiorsoftllc.infantry.ui.net.Controller? get() = null
}

/** RUNTIME-9: no skills: none, every request refused. */
object NoSkills : SkillsContext {
    override val all: List<SkillView> get() = emptyList()
    override val loading: Boolean get() = false
    override val error: String? get() = null
    override suspend fun load(): CommandResult = CommandResult.Refused(SHELL_DOMAIN_MISSING)
    override suspend fun save(name: String, description: String, body: String, tagsCsv: String): CommandResult = CommandResult.Refused(SHELL_DOMAIN_MISSING)
    override suspend fun delete(id: String): CommandResult = CommandResult.Refused(SHELL_DOMAIN_MISSING)
}

/** The board modal. */
interface BoardContext {
    val open: Boolean
    fun close()
    /** Closes the board on the chat of session [sessionId]. */
    fun openAgent(sessionId: String)
    /** TICKET-COMMENTS-1: the ticket whose full-open modal is drawn over the window (the board or a session view), if any. */
    val openTicketId: String? get() = null
    fun openTicket(ticketId: String) {}
    fun closeTicket() {}
}

/** The role catalog and the role manager dialog. */
interface RolesContext {
    val managerOpen: Boolean
    fun closeManager()
    /** The daemon's roles, in its order. */
    val roles: List<Role>
    /** What each role runs on, by role id; a role with no entry has no binding. */
    val bindings: Map<String, RoleBinding>
    /** Each role's trait ids, by role id. */
    val traits: Map<String, List<String>>
    // S4c (docs/SHELL-LEAKS.md): what the roles window and the Roles pane read and send; each has a default, so an
    // older shell compiles unchanged and its window refuses the edit.
    /** The daemon takes role descriptions and deletes (contract 1.108) / role icons (1.93). */
    val canEdit: Boolean get() = false
    val iconsSupported: Boolean get() = false
    /** ROLE-PLUGIN-1: the daemon stores a role's preferred cards / tools (contract 1.153). */
    val preferencesSupported: Boolean get() = false
    fun role(id: String?): Role? = id?.let { wanted -> roles.firstOrNull { it.id == wanted } }
    /** The active project's profiles of [roleId]. */
    fun profilesFor(roleId: String): List<AgentProfile> = emptyList()
    /** The sessions, in the sidebar's order. */
    val agents: List<AgentView> get() = emptyList()
    /** The daemon's last refusal of a profile save. */
    val agentError: String? get() = null
    suspend fun save(id: String, name: String, color: String?, persona: String, icon: String? = null, description: String? = null): CommandResult =
        CommandResult.Refused(SHELL_DOMAIN_MISSING)
    /** ROLE-PLUGIN-1: [save] with the role's preferred [cards] / [tools] (null = keep); an older shell drops them. */
    suspend fun save(
        id: String, name: String, color: String?, persona: String, icon: String?, description: String?, cards: List<String>?, tools: List<String>?,
    ): CommandResult = save(id, name, color, persona, icon, description)
    suspend fun saveBinding(roleId: String, provider: String?, model: String?, effort: String?): CommandResult = CommandResult.Refused(SHELL_DOMAIN_MISSING)
    suspend fun delete(roleId: String): CommandResult = CommandResult.Refused(SHELL_DOMAIN_MISSING)
    suspend fun saveProfile(profile: AgentProfile): CommandResult = CommandResult.Refused(SHELL_DOMAIN_MISSING)
    suspend fun deleteProfile(id: String): CommandResult = CommandResult.Refused(SHELL_DOMAIN_MISSING)
    /** Starts a session as profile [profileId] with [prompt] as its first message. */
    suspend fun spawnAs(profileId: String, prompt: String): CommandResult = CommandResult.Refused(SHELL_DOMAIN_MISSING)
    /** S4d: the active project's profiles (every profile with none active): who a ticket can be assigned to. */
    val profiles: List<AgentProfile> get() = emptyList()
}

/** RUNTIME-12: no roles domain: no roles, no manager, every request refused (a pane drawn without a shell). */
object NoRoles : RolesContext {
    override val managerOpen: Boolean get() = false
    override fun closeManager() {}
    override val roles: List<Role> get() = emptyList()
    override val bindings: Map<String, RoleBinding> get() = emptyMap()
    override val traits: Map<String, List<String>> get() = emptyMap()
}

/**
 * S4d: the backlog (contracts 1.5.0 / 1.6.0 / 1.60.0): the daemon's tickets and epics, and the requests that file, edit
 * or move a ticket. The daemon owns the columns and the order; a reader only shows them.
 */
interface TicketsContext {
    /** Every project's tickets. */
    val all: List<Ticket>
    /** The active project's tickets, or all of them with none active. */
    val visible: List<Ticket>
    /** [visible] after each change, for a reader that waits for the daemon's answer to a move (the board's drops). */
    val updates: Flow<List<Ticket>>
    /** The epics by project id. */
    val epics: Map<String, List<EpicView>>
    /** The daemon's last refusal of a ticket save; null after a success. */
    val error: String?
    fun ticket(id: String?): Ticket? = id?.let { wanted -> all.firstOrNull { it.id == wanted } }
    /** One column of [visible], top first. */
    fun column(status: String): List<Ticket> = visible.filter { it.status == status }.sortedBy { it.position }
    suspend fun create(title: String, description: String, agentId: String?, epicId: String?, provider: String?, model: String?, contextLimit: Int? = null, effort: String? = null): CommandResult
    /** Each null leaves its field as it is; "" clears it. */
    suspend fun edit(
        id: String, title: String? = null, description: String? = null, epicId: String? = null,
        agentId: String? = null, provider: String? = null, model: String? = null, images: List<String>? = null,
        contextLimit: Int? = null, effort: String? = null,
    ): CommandResult
    suspend fun delete(id: String): CommandResult
    /** [position] null keeps the daemon's placement in [status]. */
    suspend fun move(id: String, status: String, position: Int? = null): CommandResult
    suspend fun reorder(id: String, position: Int): CommandResult
    /** Starts the top startable To do ticket. */
    suspend fun startNext(): CommandResult
    suspend fun loadEpics(projectId: String): CommandResult
    /** Stores an image (purpose ticket) under [projectId]'s directory (IMG-4). */
    suspend fun uploadImage(projectId: String?, fileName: String, mimeType: String, bytes: ByteArray): ImageUploadOutcome
    /** TICKET-COMMENTS-3: one call of a daemon plugin's tool (the board's comment tools); success = its text, failure = why not. */
    suspend fun pluginTool(name: String, arguments: kotlinx.serialization.json.JsonObject): Result<String> = Result.failure(IllegalStateException(SHELL_DOMAIN_MISSING))
}

/** S4d: the [ShellContext.tickets] of a shell without a backlog: none, every request refused. */
object NoTickets : TicketsContext {
    override val all: List<Ticket> get() = emptyList()
    override val visible: List<Ticket> get() = emptyList()
    override val updates: Flow<List<Ticket>> get() = emptyFlow()
    override val epics: Map<String, List<EpicView>> get() = emptyMap()
    override val error: String? get() = null
    override suspend fun create(title: String, description: String, agentId: String?, epicId: String?, provider: String?, model: String?, contextLimit: Int?, effort: String?) =
        CommandResult.Refused(SHELL_DOMAIN_MISSING)
    override suspend fun edit(id: String, title: String?, description: String?, epicId: String?, agentId: String?, provider: String?, model: String?, images: List<String>?, contextLimit: Int?, effort: String?) =
        CommandResult.Refused(SHELL_DOMAIN_MISSING)
    override suspend fun delete(id: String) = CommandResult.Refused(SHELL_DOMAIN_MISSING)
    override suspend fun move(id: String, status: String, position: Int?) = CommandResult.Refused(SHELL_DOMAIN_MISSING)
    override suspend fun reorder(id: String, position: Int) = CommandResult.Refused(SHELL_DOMAIN_MISSING)
    override suspend fun startNext() = CommandResult.Refused(SHELL_DOMAIN_MISSING)
    override suspend fun loadEpics(projectId: String) = CommandResult.Refused(SHELL_DOMAIN_MISSING)
    override suspend fun uploadImage(projectId: String?, fileName: String, mimeType: String, bytes: ByteArray): ImageUploadOutcome =
        ImageUploadOutcome.Refused(SHELL_DOMAIN_MISSING)
}

/** S4c: the trait library and the traits bound to each role. */
interface TraitsContext {
    val all: List<TraitView>
    val loading: Boolean
    val error: String?
    fun idsFor(roleId: String): List<String>
    /** The roles that bind [traitId], sorted. */
    fun rolesBinding(traitId: String): List<String>
    suspend fun setForRole(roleId: String, traitIds: List<String>): CommandResult
    suspend fun save(draft: TraitDraft): CommandResult
    suspend fun delete(id: String): CommandResult
}

/** S4c: the [ShellContext.traits] of a shell without traits: none, every edit refused. */
object NoTraits : TraitsContext {
    override val all: List<TraitView> get() = emptyList()
    override val loading: Boolean get() = false
    override val error: String? get() = null
    override fun idsFor(roleId: String): List<String> = emptyList()
    override fun rolesBinding(traitId: String): List<String> = emptyList()
    override suspend fun setForRole(roleId: String, traitIds: List<String>): CommandResult = CommandResult.Refused(SHELL_DOMAIN_MISSING)
    override suspend fun save(draft: TraitDraft): CommandResult = CommandResult.Refused(SHELL_DOMAIN_MISSING)
    override suspend fun delete(id: String): CommandResult = CommandResult.Refused(SHELL_DOMAIN_MISSING)
}

/** The toasts: the service any block toasts through, what the host shows, and the tap on one. */
interface ToastContext {
    val service: ToastService
    /** The toasts and cross-project notices, oldest first. */
    val events: List<ToastEvent>
    /** Opens what [event] is about: its project, then its session, else the board. */
    fun open(event: ToastEvent)

    /**
     * NOTIFY-CORE-1 (docs/wiki/Notifications.md): the notification bus. Post with [Notifications.notify]; a plugin presenter
     * registers with [Notifications.present]. The default (a shell with no bus) draws a notification as a plain toast.
     */
    val notifications: com.superiorsoftllc.infantry.plugin.api.Notifications get() = ToastNotifications(service)

    /** NOTIFY-CORE-1: the bus's history (the inbox), newest first; empty where the shell keeps none. */
    val history: List<com.superiorsoftllc.infantry.plugin.host.NotificationCenter.Record> get() = emptyList()

    /** NOTIFY-CORE-1: action [actionId] of toast [event] was pressed. */
    fun action(event: ToastEvent, actionId: String) {}

    /** NOTIFY-CORE-1: the owner closed toast [event]. */
    fun dismiss(event: ToastEvent) = service.dismiss(event.id)

    /** NOTIFY-CORE-1: empties the history. */
    fun clearHistory() {}
}

/** NOTIFY-CORE-1: the bus front door of a shell that has only a [ToastService]: each notification becomes one toast. */
class ToastNotifications(private val service: ToastService) : com.superiorsoftllc.infantry.plugin.api.Notifications {
    override fun notify(title: String, body: String) {
        notify(com.superiorsoftllc.infantry.plugin.api.Notification(title = title, body = body))
    }

    override fun notify(notification: com.superiorsoftllc.infantry.plugin.api.Notification): String {
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        val n = notification.copy(id = notification.id.ifBlank { "toast-$now-${notification.title.hashCode()}" }, at = notification.at.takeIf { it > 0 } ?: now)
        service.post(n.toToast())
        return n.id
    }

    override fun dismiss(id: String) = service.dismiss(id)
}

/**
 * Owner 2026-10-27, read-only config slot; P1 step 2 (44aee6e5) answers it with the merged tree over the daemon's
 * node_config (`ResolvedConfig`). The JSON stores values and registry keys, never class names.
 */
interface ConfigContext {
    /** Node [nodeId]'s resolved values: the node's own, else its parent's, up to the root, else the plugin default. */
    fun values(nodeId: String): ConfigValues
    /** The implementation of [type] registered under [key], or null when none is. */
    fun <T : Any> implementation(type: KClass<T>, key: String): T?
}

inline fun <reified T : Any> ConfigContext.implementation(key: String): T? = implementation(T::class, key)

/** A node's resolved config, typed; null where the key is unset or holds another type. */
interface ConfigValues {
    val keys: Set<String>
    fun string(key: String): String?
    fun int(key: String): Int?
    fun double(key: String): Double?
    fun boolean(key: String): Boolean?
    /** P1 step 2: a list of strings (a `multiSelect`); null where unset or not a list of strings. */
    fun strings(key: String): List<String>? = null
    /** CONFIG-9: a `classes` field's tokens for [slot] (space-separated in the value); null where [key] is unset or has no such slot. */
    fun classes(slot: String, key: String = "classes"): List<String>? = null
    /** CONFIG-3: a `color` field as 0xAARRGGBB (`"#RRGGBB"` is opaque); null where unset or not a colour. */
    fun color(key: String): Long? = parseConfigColor(string(key))
    /**
     * CONFIG-3: whether the user set [key] (a stored `user` row at this node or a parent), as opposed to it resolving
     * from a declared default. A block that already has its own persisted state applies a value only when this is true.
     */
    fun overridden(key: String): Boolean = false
    /** CONFIG-FIELDS-2: the raw resolved JSON of [key]; null where unset (and for fakes that only type their values). */
    fun json(key: String): kotlinx.serialization.json.JsonElement? = null
    /** CONFIG-FIELDS-4 (G3): a `group` field as a [ConfigValues] of its own keys; null where unset or not an object. */
    fun obj(key: String): ConfigValues? = null
    /** CONFIG-FIELDS-4 (G3): a `list` of objects as one [ConfigValues] per item; null where unset or any item is not an object. */
    fun list(key: String): List<ConfigValues>? = null
}

/**
 * CONFIG-FIELDS-2 (G9): the resolved values decoded into [T] (a `@Serializable` class) by property name. A key [T] does
 * not declare is ignored, a key the config does not set keeps [T]'s default; null when [T] cannot be built from them.
 */
inline fun <reified T : Any> ConfigValues.configAs(): T? = configAs(kotlinx.serialization.serializer<T>())

fun <T : Any> ConfigValues.configAs(serializer: kotlinx.serialization.KSerializer<T>): T? = runCatching {
    val obj = kotlinx.serialization.json.JsonObject(keys.mapNotNull { k -> json(k)?.let { k to it } }.toMap())
    kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }.decodeFromJsonElement(serializer, obj)
}.getOrNull()

/** CONFIG-3: `"#RRGGBB"` or `"#AARRGGBB"` as 0xAARRGGBB, else null. */
fun parseConfigColor(text: String?): Long? {
    val hex = text?.trim()?.removePrefix("#") ?: return null
    if (hex.length != 6 && hex.length != 8 || !hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
    val v = hex.toLong(16)
    return if (hex.length == 6) 0xFF000000L or v else v
}

/** No config resolved: every key unset, no implementation registered (a shell without a factory, and fakes). */
object EmptyConfig : ConfigContext, ConfigValues {
    override fun values(nodeId: String): ConfigValues = this
    override fun <T : Any> implementation(type: KClass<T>, key: String): T? = null
    override val keys: Set<String> get() = emptySet()
    override fun string(key: String): String? = null
    override fun int(key: String): Int? = null
    override fun double(key: String): Double? = null
    override fun boolean(key: String): Boolean? = null
}

/** S2: the [ShellContext] of the window being composed; null outside a shell, where a migrated section draws nothing. */
val LocalShellContext = compositionLocalOf<ShellContext?> { null }

/** S4c: the daemon's harnesses and their models, for the executor pickers (profile, role, ticket, New agent). */
interface ExecutorContext {
    val providers: List<ProviderInfo>
    val modelCatalog: List<ProviderModels>
}

/** S4c: the [ShellContext.executor] of a shell without a daemon catalog. */
object NoExecutor : ExecutorContext {
    override val providers: List<ProviderInfo> get() = emptyList()
    override val modelCatalog: List<ProviderModels> get() = emptyList()
}

/**
 * CONFIG-FIELDS-3 (G8): [inner] as one module sees it. A node [allows] refuses answers [EmptyConfig] (every key unset), so a
 * denied read looks like an unconfigured node, never an error. The host builds [allows] from `ConfigReadGate`.
 */
class GatedConfig(private val inner: ConfigContext, private val allows: (nodeId: String) -> Boolean) : ConfigContext {
    override fun values(nodeId: String): ConfigValues = if (allows(nodeId)) inner.values(nodeId) else EmptyConfig
    override fun <T : Any> implementation(type: KClass<T>, key: String): T? = inner.implementation(type, key)
}

/** CONFIG-FIELDS-4: [inner] with a different [config] (one module's gated view); every other slot is [inner]'s. */
class ScopedShellContext(inner: ShellContext, override val config: ConfigContext) : ShellContext by inner
