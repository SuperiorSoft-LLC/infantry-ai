package com.superiorsoftllc.agenthq.ui.shell

import com.superiorsoftllc.agenthq.ui.RunConsoleHost
import com.superiorsoftllc.agenthq.ui.Shell
import com.superiorsoftllc.agenthq.ui.ToastService
import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.model.CompactScreen
import com.superiorsoftllc.agenthq.ui.model.Notice
import com.superiorsoftllc.agenthq.ui.model.ProjectView
import com.superiorsoftllc.agenthq.ui.model.RunsState
import com.superiorsoftllc.agenthq.ui.model.ToastEvent
import com.superiorsoftllc.agenthq.ui.net.CommandResult
import com.superiorsoftllc.agenthq.ui.net.Controller
import com.superiorsoftllc.agenthq.ui.protocol.Role
import com.superiorsoftllc.agenthq.ui.protocol.RoleBinding
import com.superiorsoftllc.agenthq.ui.toastEvents
import kotlinx.coroutines.launch

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
