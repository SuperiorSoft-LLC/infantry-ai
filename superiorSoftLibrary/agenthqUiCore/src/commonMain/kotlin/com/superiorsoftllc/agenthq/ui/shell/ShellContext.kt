package com.superiorsoftllc.agenthq.ui.shell

import androidx.compose.runtime.compositionLocalOf
import com.superiorsoftllc.agenthq.ui.ToastService
import com.superiorsoftllc.agenthq.ui.protocol.TraitView
import com.superiorsoftllc.agenthq.ui.protocol.AgentProfile
import com.superiorsoftllc.agenthq.ui.model.TraitDraft
import com.superiorsoftllc.agenthq.ui.block.layout.Block
import com.superiorsoftllc.agenthq.ui.model.AgentView
import com.superiorsoftllc.agenthq.ui.model.MemoryNoteView
import com.superiorsoftllc.agenthq.ui.model.MemorySearchView
import com.superiorsoftllc.agenthq.ui.model.NoteScope
import com.superiorsoftllc.agenthq.ui.model.Notice
import com.superiorsoftllc.agenthq.ui.model.ProjectView
import com.superiorsoftllc.agenthq.ui.model.RunsState
import com.superiorsoftllc.agenthq.ui.model.ToastEvent
import com.superiorsoftllc.agenthq.ui.model.contractAtLeast
import com.superiorsoftllc.agenthq.ui.net.CommandResult
import com.superiorsoftllc.agenthq.ui.protocol.PipelineStateView
import com.superiorsoftllc.agenthq.ui.protocol.ProjectGitBranchDiffstat
import com.superiorsoftllc.agenthq.ui.protocol.ProjectGitBranchList
import com.superiorsoftllc.agenthq.ui.protocol.ProjectGitCheckoutResult
import com.superiorsoftllc.agenthq.ui.protocol.ProjectGitHistory
import com.superiorsoftllc.agenthq.ui.protocol.ProjectGitPatch
import com.superiorsoftllc.agenthq.ui.protocol.ProjectGitStatus
import com.superiorsoftllc.agenthq.ui.protocol.ProjectGitWorktreeList
import com.superiorsoftllc.agenthq.ui.protocol.ProjectGitWorktreeStatus
import com.superiorsoftllc.agenthq.ui.protocol.ProviderInfo
import com.superiorsoftllc.agenthq.ui.protocol.ProviderModels
import com.superiorsoftllc.agenthq.ui.protocol.Role
import com.superiorsoftllc.agenthq.ui.protocol.RoleBinding
import com.superiorsoftllc.agenthq.ui.model.EpicView
import com.superiorsoftllc.agenthq.ui.net.ImageUploadOutcome
import com.superiorsoftllc.agenthq.ui.protocol.Ticket
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
}

/** The daemon link: whether it is up and which contract the daemon announced. */
interface LinkContext {
    val live: Boolean
    /** The contract version from `ready` ("1.2.0"); null before it or from a daemon too old to say. */
    val contract: String?
    fun contractAtLeast(major: Int, minor: Int): Boolean = contractAtLeast(contract, major, minor)
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
}

/** S4b: the tab trees ([com.superiorsoftllc.agenthq.ui.plugin.AgentHqModule.tabTrees]) with the modules' overrides in. */
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

/** The board modal. */
interface BoardContext {
    val open: Boolean
    fun close()
    /** Closes the board on the chat of session [sessionId]. */
    fun openAgent(sessionId: String)
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
    fun role(id: String?): Role? = id?.let { wanted -> roles.firstOrNull { it.id == wanted } }
    /** The active project's profiles of [roleId]. */
    fun profilesFor(roleId: String): List<AgentProfile> = emptyList()
    /** The sessions, in the sidebar's order. */
    val agents: List<AgentView> get() = emptyList()
    /** The daemon's last refusal of a profile save. */
    val agentError: String? get() = null
    suspend fun save(id: String, name: String, color: String?, persona: String, icon: String? = null, description: String? = null): CommandResult =
        CommandResult.Refused(SHELL_DOMAIN_MISSING)
    suspend fun saveBinding(roleId: String, provider: String?, model: String?, effort: String?): CommandResult = CommandResult.Refused(SHELL_DOMAIN_MISSING)
    suspend fun delete(roleId: String): CommandResult = CommandResult.Refused(SHELL_DOMAIN_MISSING)
    suspend fun saveProfile(profile: AgentProfile): CommandResult = CommandResult.Refused(SHELL_DOMAIN_MISSING)
    suspend fun deleteProfile(id: String): CommandResult = CommandResult.Refused(SHELL_DOMAIN_MISSING)
    /** Starts a session as profile [profileId] with [prompt] as its first message. */
    suspend fun spawnAs(profileId: String, prompt: String): CommandResult = CommandResult.Refused(SHELL_DOMAIN_MISSING)
    /** S4d: the active project's profiles (every profile with none active): who a ticket can be assigned to. */
    val profiles: List<AgentProfile> get() = emptyList()
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
    suspend fun create(title: String, description: String, agentId: String?, epicId: String?, provider: String?, model: String?): CommandResult
    /** Each null leaves its field as it is; "" clears it. */
    suspend fun edit(
        id: String, title: String? = null, description: String? = null, epicId: String? = null,
        agentId: String? = null, provider: String? = null, model: String? = null, images: List<String>? = null,
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
}

/** S4d: the [ShellContext.tickets] of a shell without a backlog: none, every request refused. */
object NoTickets : TicketsContext {
    override val all: List<Ticket> get() = emptyList()
    override val visible: List<Ticket> get() = emptyList()
    override val updates: Flow<List<Ticket>> get() = emptyFlow()
    override val epics: Map<String, List<EpicView>> get() = emptyMap()
    override val error: String? get() = null
    override suspend fun create(title: String, description: String, agentId: String?, epicId: String?, provider: String?, model: String?) =
        CommandResult.Refused(SHELL_DOMAIN_MISSING)
    override suspend fun edit(id: String, title: String?, description: String?, epicId: String?, agentId: String?, provider: String?, model: String?, images: List<String>?) =
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
}

/**
 * Owner 2026-10-27, read-only config slot (schema, tree and storage come with ticket P1). The JSON stores values and
 * registry keys, never class names.
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
}

/** No config resolved: every key unset, no implementation registered (the core's until P1). */
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
