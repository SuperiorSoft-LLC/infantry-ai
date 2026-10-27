package com.superiorsoftllc.agenthq.ui.shell

import com.superiorsoftllc.agenthq.ui.RunConsoleHost
import com.superiorsoftllc.agenthq.ui.Shell
import com.superiorsoftllc.agenthq.ui.ToastService
import com.superiorsoftllc.agenthq.ui.model.AgentView
import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.model.CompactScreen
import com.superiorsoftllc.agenthq.ui.model.MemoryNoteView
import com.superiorsoftllc.agenthq.ui.model.MemorySearchView
import com.superiorsoftllc.agenthq.ui.model.NoteScope
import com.superiorsoftllc.agenthq.ui.model.Notice
import com.superiorsoftllc.agenthq.ui.model.ProjectView
import com.superiorsoftllc.agenthq.ui.model.RunsState
import com.superiorsoftllc.agenthq.ui.model.ToastEvent
import com.superiorsoftllc.agenthq.ui.model.changesBranch
import com.superiorsoftllc.agenthq.ui.model.changesShownBranch
import com.superiorsoftllc.agenthq.ui.model.changesWorktree
import com.superiorsoftllc.agenthq.ui.model.changesWorktreeStatus
import com.superiorsoftllc.agenthq.ui.net.CommandResult
import com.superiorsoftllc.agenthq.ui.net.Controller
import com.superiorsoftllc.agenthq.ui.protocol.Envelope
import com.superiorsoftllc.agenthq.ui.protocol.ProjectGitWorktreeStatus
import com.superiorsoftllc.agenthq.ui.protocol.ProviderInfo
import com.superiorsoftllc.agenthq.ui.protocol.ProviderModels
import com.superiorsoftllc.agenthq.ui.protocol.Role
import com.superiorsoftllc.agenthq.ui.protocol.RoleBinding
import com.superiorsoftllc.agenthq.ui.toastEvents
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * S2: the core's [ShellContext]: [shell]'s state snapshot, every action delegated to its [Controller] and window state,
 * as the blocks did before they read the context. [App][com.superiorsoftllc.agenthq.ui.App] provides one per composition.
 */
class CoreShellContext(private val shell: Shell, console: RunConsoleHost?) : ShellContext {
    private val state = shell.state
    private val ui = shell.ui
    override val link: LinkContext = CoreLink(state)
    override val session: SessionContext = CoreSession(state)
    override val git: GitContext = CoreGit(state, shell.controller)
    override val run: RunContext = CoreRun(state, shell.controller, console)
    override val config: ConfigContext = EmptyConfig
    override val layout: LayoutContext = object : LayoutContext {
        override fun tabTree(id: String) = shell.tabs.tabTree(id)
    }
    override val notes: NotesContext = CoreNotes(state, shell.controller)
    override val changes: ChangesContext = CoreChanges(state, shell.controller)
    override val executor: ExecutorContext = CoreExecutor(state)

    override val board: BoardContext = object : BoardContext {
        override val open: Boolean get() = ui.showBoard
        override fun close() { ui.showBoard = false; shell.root.requestFocus() }
        override fun openAgent(sessionId: String) {
            shell.controller.select(sessionId); ui.centerTab = shell.homeTab; shell.chat.spawnMode = false; ui.showBoard = false
            // MOBILE-LAYOUT-2: a compact window opens the Board from the list; "Agent" lands on the output.
            if (state.panels.compactWindow) shell.controller.showCompactScreen(CompactScreen.OUTPUT)
        }
    }

    override val roles: RolesContext = object : RolesContext {
        override val managerOpen: Boolean get() = ui.showRoles
        override fun closeManager() { ui.showRoles = false }
        override val roles: List<Role> get() = state.roles
        override val bindings: Map<String, RoleBinding> get() = state.roleBindings
        override val traits: Map<String, List<String>> get() = state.roleTraits
    }

    override val toast: ToastContext = object : ToastContext {
        override val service: ToastService get() = shell.controller.toasts
        override val events: List<ToastEvent> by lazy { toastEvents(state) }
        override fun open(event: ToastEvent) {
            event.projectId?.let { id -> shell.actionScope.launch { shell.controller.selectProject(id) } }
            if (event.sessionId != null && state.agents.containsKey(event.sessionId)) shell.controller.select(event.sessionId) else ui.showBoard = true
        }
    }
}

/** S2: [state]'s link, for a pane drawn outside a shell (its `(state, controller)` entry point). */
class CoreLink(private val state: AppState) : LinkContext {
    override val live: Boolean get() = state.connection.isLive
    override val contract: String? get() = state.connection.contract
}

/** S2: [state]'s session, for a pane drawn outside a shell. */
class CoreSession(private val state: AppState) : SessionContext {
    override val activeProject: ProjectView? get() = state.activeProject
    override val notices: List<Notice> get() = state.notices
    override val projectRefusal: Notice? get() = state.projectRefusal
    override val selectedAgentId: String? get() = state.selectedId
    override val selectedAgent: AgentView? get() = state.selected
}

/** S4b: [state]'s notes and [controller]'s note requests, for a pane drawn outside a shell. */
class CoreNotes(private val state: AppState, private val controller: Controller) : NotesContext {
    override fun notes(key: String): List<MemoryNoteView> = state.memoryNotes[key].orEmpty()
    override val search: MemorySearchView? get() = state.memorySearch
    override suspend fun load(projectId: String, at: NoteScope) = controller.loadMemory(projectId, at)
    override suspend fun get(projectId: String, name: String, at: NoteScope) = controller.getMemory(projectId, name, at)
    override suspend fun search(projectId: String, query: String, at: NoteScope) = controller.searchMemory(projectId, query, at)
    override suspend fun save(projectId: String, name: String, description: String, body: String, at: NoteScope) =
        controller.saveMemory(projectId, name, description, body, at)
    override suspend fun delete(projectId: String, name: String, at: NoteScope) = controller.deleteMemory(projectId, name, at)
}

/** S4b: [state]'s worktrees and branch changes and [controller]'s Changes requests, for a pane drawn outside a shell. */
class CoreChanges(private val state: AppState, private val controller: Controller) : ChangesContext {
    override fun worktrees(projectId: String) = state.projectWorktrees[projectId]
    override val worktree: String? get() = state.changesWorktree
    override val worktreeStatus: ProjectGitWorktreeStatus? get() = state.changesWorktreeStatus
    override val branch: String? get() = state.changesBranch
    override val shownBranch: String? get() = state.changesShownBranch
    override fun branchChanges(projectId: String) = state.projectGitBranchChanges[projectId]
    override suspend fun loadTree(projectId: String) = controller.loadProjectTree(projectId)
    override suspend fun loadWorktrees(projectId: String, sessionId: String?) = controller.loadProjectWorktrees(projectId, sessionId)
    override suspend fun loadWorktreeStatus(projectId: String, path: String) = controller.loadWorktreeStatus(projectId, path)
    override suspend fun loadBranchChanges(projectId: String, branch: String) = controller.loadBranchChanges(projectId, branch)
    override fun selectWorktree(projectId: String, path: String) = controller.selectChangesWorktree(projectId, path)
    override fun selectBranch(projectId: String, branch: String?) = controller.selectChangesBranch(projectId, branch)
    override suspend fun pollWorktrees(projectId: String, sessionId: String?, timeoutMs: Long) =
        controller.pollOnce(Envelope.PROJECT_GIT_WORKTREES, Envelope.PROJECT_GIT_WORKTREE_LIST, projectId, timeoutMs) {
            controller.loadProjectWorktrees(projectId, sessionId)
        }
    override suspend fun pollWorktreeStatus(projectId: String, path: String, timeoutMs: Long) =
        controller.pollOnce(Envelope.PROJECT_GIT_WORKTREE_STATUS, Envelope.PROJECT_GIT_WORKTREE, projectId, timeoutMs) {
            controller.loadWorktreeStatus(projectId, path)
        }
}

/**
 * BUG-CHANGES-BRANCH-1: one Changes poll, which returns only once the daemon replied to it, so the panel never has more
 * than one of each poll waiting. The daemon answers a connection's frames in order: polls that went out on every tick
 * without waiting piled up behind a slow git (409 unanswered under load) and a branch pick queued behind all of them.
 *
 * The wait ends on a [reply] frame naming [projectId], on the daemon's `<request> refused|failed: ...` error for it, or
 * on a `ready`: nothing asked on the old link is answered on a new one. A drop the panel sees cancels the polling
 * effects (`live` keys them), but a Connect to the same daemon keeps the link live throughout, so only that `ready`
 * ends this wait then. A send the controller refused waits for nothing, and [timeoutMs] bounds the wait.
 */
private suspend fun Controller.pollOnce(request: String, reply: String, projectId: String, timeoutMs: Long, ask: suspend () -> CommandResult) {
    coroutineScope {
        // Listening starts before the request goes out, so a fast reply cannot slip past.
        val replied = launch(start = CoroutineStart.UNDISPATCHED) { client.frames.first { it.endsPoll(request, reply, projectId) } }
        if (ask() !is CommandResult.Sent) return@coroutineScope replied.cancel()
        // A delay() timer, not withTimeoutOrNull: it runs on the poll interval's own clock. In a Compose UI test the
        // effects' delay() follows the test clock, and a withTimeoutOrNull wait there was never released by it.
        val timeout = launch { delay(timeoutMs); replied.cancel() }
        replied.join()
        timeout.cancel()
    }
}

/** True when this frame ends the wait for a [request] about [projectId]: its [reply] answer, its error, or a new link. */
private fun Envelope.endsPoll(request: String, reply: String, projectId: String): Boolean = when (type) {
    reply -> runCatching { Json.parseToJsonElement(data).jsonObject["projectId"]?.jsonPrimitive?.contentOrNull }.getOrNull() == projectId
    Envelope.ERROR -> data.startsWith("$request ")
    Envelope.READY -> true
    else -> false
}

/** S2: [state]'s git and [controller]'s git requests, for a pane drawn outside a shell. */
class CoreGit(private val state: AppState, private val controller: Controller) : GitContext {
    override fun status(projectId: String) = state.projectGit[projectId]
    override val failure: String? get() = state.projectGitFailure
    override fun patch(projectId: String) = state.projectGitPatch[projectId]
    override fun history(projectId: String) = state.projectGitHistory[projectId]
    override fun branches(projectId: String) = state.projectGitBranches[projectId]
    override fun checkoutResult(projectId: String) = state.projectGitCheckout[projectId]
    override fun pipeline(projectId: String) = state.pipelines[projectId]
    override suspend fun load(projectId: String) = controller.loadProjectGit(projectId)
    override suspend fun loadDiff(projectId: String, path: String, staged: Boolean) = controller.loadProjectDiff(projectId, path, staged)
    override suspend fun loadBranches(projectId: String) = controller.loadProjectBranches(projectId)
    override suspend fun checkout(projectId: String, branch: String, create: Boolean) = controller.checkoutProjectBranch(projectId, branch, create)
    override suspend fun setBaseBranch(projectId: String, branch: String?) = controller.setProjectBaseBranch(projectId, branch)
    override suspend fun saveDirectory(projectId: String, directory: String) = controller.saveProjectDirectory(projectId, directory)
    override suspend fun loadPipeline(projectId: String) = controller.loadPipeline(projectId)
    override suspend fun configurePipeline(projectId: String, stages: List<String>?) = controller.configurePipeline(projectId, stages)
}

/** S2: [state]'s runs and [controller]'s run requests; [console] is the desktop's console window, for a pane outside a shell. */
class CoreRun(private val state: AppState, private val controller: Controller, private val console: RunConsoleHost?) : RunContext {
    override val runs: RunsState get() = state.runs
    override val secretNames: List<String>?
        get() = if (controller.supportsKeyValueSecrets()) state.secrets?.entries?.map { it.name }.orEmpty() else null
    override val hasConsoleWindow: Boolean get() = console != null
    override val interactiveSupported: Boolean get() = controller.supportsRunConsole()
    override suspend fun start(commandId: String, interactive: Boolean): CommandResult =
        if (interactive) controller.startRun(commandId, interactive = true) else controller.startRun(commandId)
    override suspend fun stop(runId: String) = controller.stopRun(runId)
    override fun open(runId: String) {
        controller.selectRun(runId)
        console?.open(runId, controller)
    }
    override suspend fun saveCommand(id: String, name: String, argv: List<String>, cwd: String?, graceMillis: Long, secrets: List<String>) =
        controller.saveCommand(id, name, argv, cwd, graceMillis, secrets)
    override suspend fun deleteCommand(id: String) = controller.deleteCommand(id)
}

/** S4c: [state]'s harnesses and model catalog, for a pane drawn outside a shell. */
class CoreExecutor(private val state: AppState) : ExecutorContext {
    override val providers: List<ProviderInfo> get() = state.providers
    override val modelCatalog: List<ProviderModels> get() = state.modelCatalog
}
