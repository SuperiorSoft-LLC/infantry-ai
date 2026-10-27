package com.superiorsoftllc.agenthq.ui.shell

import androidx.compose.runtime.compositionLocalOf
import com.superiorsoftllc.agenthq.ui.ToastService
import com.superiorsoftllc.agenthq.ui.model.Notice
import com.superiorsoftllc.agenthq.ui.model.ProjectView
import com.superiorsoftllc.agenthq.ui.model.RunsState
import com.superiorsoftllc.agenthq.ui.model.ToastEvent
import com.superiorsoftllc.agenthq.ui.model.contractAtLeast
import com.superiorsoftllc.agenthq.ui.net.CommandResult
import com.superiorsoftllc.agenthq.ui.protocol.PipelineStateView
import com.superiorsoftllc.agenthq.ui.protocol.ProjectGitBranchList
import com.superiorsoftllc.agenthq.ui.protocol.ProjectGitCheckoutResult
import com.superiorsoftllc.agenthq.ui.protocol.ProjectGitHistory
import com.superiorsoftllc.agenthq.ui.protocol.ProjectGitPatch
import com.superiorsoftllc.agenthq.ui.protocol.ProjectGitStatus
import com.superiorsoftllc.agenthq.ui.protocol.Role
import com.superiorsoftllc.agenthq.ui.protocol.RoleBinding
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
