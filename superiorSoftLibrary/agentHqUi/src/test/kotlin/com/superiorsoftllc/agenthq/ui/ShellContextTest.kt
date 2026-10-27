package com.superiorsoftllc.agenthq.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.isRoot
import com.superiorsoftllc.agenthq.ui.block.layout.BlockScope
import com.superiorsoftllc.agenthq.ui.model.CommandSpecView
import com.superiorsoftllc.agenthq.ui.model.Notice
import com.superiorsoftllc.agenthq.ui.model.ProjectView
import com.superiorsoftllc.agenthq.ui.model.RunsState
import com.superiorsoftllc.agenthq.ui.model.ToastEvent
import com.superiorsoftllc.agenthq.ui.model.ToastKind
import com.superiorsoftllc.agenthq.ui.net.CommandResult
import com.superiorsoftllc.agenthq.ui.protocol.ProjectGitStatus
import com.superiorsoftllc.agenthq.ui.protocol.Role
import com.superiorsoftllc.agenthq.ui.protocol.RoleBinding
import com.superiorsoftllc.agenthq.ui.shell.BoardContext
import com.superiorsoftllc.agenthq.ui.shell.ConfigContext
import com.superiorsoftllc.agenthq.ui.shell.ConfigValues
import com.superiorsoftllc.agenthq.ui.shell.EmptyConfig
import com.superiorsoftllc.agenthq.ui.shell.GitContext
import com.superiorsoftllc.agenthq.ui.shell.LinkContext
import com.superiorsoftllc.agenthq.ui.shell.LocalShellContext
import com.superiorsoftllc.agenthq.ui.shell.RolesContext
import com.superiorsoftllc.agenthq.ui.shell.RunContext
import com.superiorsoftllc.agenthq.ui.shell.SessionContext
import com.superiorsoftllc.agenthq.ui.shell.ShellContext
import com.superiorsoftllc.agenthq.ui.shell.ToastContext
import com.superiorsoftllc.agenthq.ui.shell.blocks.ShellBoard
import com.superiorsoftllc.agenthq.ui.shell.blocks.ShellGit
import com.superiorsoftllc.agenthq.ui.shell.blocks.ShellRoles
import com.superiorsoftllc.agenthq.ui.shell.blocks.ShellRun
import com.superiorsoftllc.agenthq.ui.shell.blocks.ShellToast
import com.superiorsoftllc.agenthq.ui.shell.implementation
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * S2: a fake [ShellContext] (no App, no Controller, no daemon) drives the migrated sections, and outside a shell (no
 * [LocalShellContext]) they draw nothing. The config slot resolves through the fake as P1's will.
 */
@OptIn(ExperimentalTestApi::class)
class ShellContextTest {
    private val sent = mutableListOf<String>()

    private val fakeRun = object : RunContext {
        override val runs = RunsState(commands = listOf(CommandSpecView("echo-hello", "Echo hello", null)))
        override val secretNames: List<String>? = null
        override val hasConsoleWindow = false
        override val interactiveSupported = false
        override suspend fun start(commandId: String, interactive: Boolean): CommandResult { sent += "start $commandId $interactive"; return CommandResult.Sent }
        override suspend fun stop(runId: String): CommandResult = CommandResult.Sent
        override fun open(runId: String) { sent += "open $runId" }
        override suspend fun saveCommand(id: String, name: String, argv: List<String>, cwd: String?, graceMillis: Long, secrets: List<String>) = CommandResult.Sent
        override suspend fun deleteCommand(id: String) = CommandResult.Sent
    }

    private val fakeGit = object : GitContext {
        override fun status(projectId: String): ProjectGitStatus? = null
        override val failure: String? = null
        override fun patch(projectId: String) = null
        override fun history(projectId: String) = null
        override fun branches(projectId: String) = null
        override fun checkoutResult(projectId: String) = null
        override fun pipeline(projectId: String) = null
        override suspend fun load(projectId: String): CommandResult { sent += "git load $projectId"; return CommandResult.Sent }
        override suspend fun loadDiff(projectId: String, path: String, staged: Boolean) = CommandResult.Sent
        override suspend fun loadBranches(projectId: String) = CommandResult.Sent
        override suspend fun checkout(projectId: String, branch: String, create: Boolean) = CommandResult.Sent
        override suspend fun setBaseBranch(projectId: String, branch: String?) = CommandResult.Sent
        override suspend fun saveDirectory(projectId: String, directory: String) = CommandResult.Sent
        override suspend fun loadPipeline(projectId: String) = CommandResult.Sent
        override suspend fun configurePipeline(projectId: String, stages: List<String>?) = CommandResult.Sent
    }

    private inner class FakeShell : ShellContext {
        var toasts by mutableStateOf(emptyList<ToastEvent>())
        override val link = object : LinkContext {
            override val live = true
            override val contract = "1.200.0"
        }
        override val session = object : SessionContext {
            override val activeProject = ProjectView("p1", "Acme")
            override val notices = emptyList<Notice>()
            override val projectRefusal: Notice? = null
        }
        override val git = fakeGit
        override val run = fakeRun
        override val board = object : BoardContext {
            override val open = false
            override fun close() {}
            override fun openAgent(sessionId: String) {}
        }
        override val roles = object : RolesContext {
            override val managerOpen = false
            override fun closeManager() {}
            override val roles = emptyList<Role>()
            override val bindings = emptyMap<String, RoleBinding>()
            override val traits = emptyMap<String, List<String>>()
        }
        override val toast = object : ToastContext {
            override val service = object : ToastService {
                override fun post(toast: ToastEvent) { toasts = toasts + toast }
                override fun dismiss(id: String) { toasts = toasts.filterNot { it.id == id } }
            }
            override val events get() = toasts
            override fun open(event: ToastEvent) { sent += "toast ${event.id}" }
        }
        override val config = EmptyConfig
    }

    @Test fun `a fake context drives the Run section with no controller behind it`() = runDesktopComposeUiTest(900, 500) {
        setContent { CompositionLocalProvider(LocalShellContext provides FakeShell()) { ShellRun.Render(BlockScope(Modifier, null)) } }
        onNodeWithTag(RUN_PANE_TAG).assertExists()
        onNodeWithTag(RUN_START_TAG).performClick()
        waitUntil(timeoutMillis = 2_000) { "start echo-hello false" in sent }
    }

    @Test fun `a fake context drives the Git section and its tree load`() = runDesktopComposeUiTest(900, 700) {
        setContent { CompositionLocalProvider(LocalShellContext provides FakeShell()) { ShellGit.Render(BlockScope(Modifier, null)) } }
        onNodeWithText("GIT · Acme").assertExists()
        waitUntil(timeoutMillis = 2_000) { "git load p1" in sent }
    }

    @Test fun `a toast posted through the fake service shows and its tap goes back to the context`() = runDesktopComposeUiTest(900, 500) {
        val shell = FakeShell()
        setContent { CompositionLocalProvider(LocalShellContext provides shell) { ShellToast.Render(BlockScope(Modifier, null)) } }
        shell.toast.service.post(ToastEvent("t1", at = 10, kind = ToastKind.INFO, title = "Hello from a fake"))
        onNodeWithText("Hello from a fake").performClick()
        waitUntil(timeoutMillis = 2_000) { "toast t1" in sent }
    }

    @Test fun `outside a shell the migrated sections draw nothing`() = runDesktopComposeUiTest(900, 500) {
        setContent {
            ShellRun.Render(BlockScope(Modifier, null))
            ShellGit.Render(BlockScope(Modifier, null))
            ShellToast.Render(BlockScope(Modifier, null))
            ShellBoard.Render(BlockScope(Modifier, null))
            ShellRoles.Render(BlockScope(Modifier, null))
        }
        onNodeWithTag(RUN_PANE_TAG).assertDoesNotExist()
        onNodeWithText("GIT", substring = true).assertDoesNotExist()
        onNodeWithTag(TOAST_HOST_TAG).assertDoesNotExist()
        assertEquals(1, onAllNodes(isRoot()).fetchSemanticsNodes().size)
        assertTrue(onAllNodes(isRoot()).fetchSemanticsNodes().single().children.isEmpty())
    }

    @Test fun `the config slot resolves a node's typed values and a registered implementation by key`() {
        val tree = mapOf("root" to mapOf<String, Any>("density" to 2, "title" to "Root"), "infantry.shell.run" to mapOf<String, Any>("title" to "Run"))
        val parents = mapOf("infantry.shell.run" to "root")
        val registry = mapOf("sorter.byName" to Comparator<String> { a, b -> a.compareTo(b) })
        val config = object : ConfigContext {
            override fun values(nodeId: String): ConfigValues = object : ConfigValues {
                private fun lookup(key: String): Any? {
                    var id: String? = nodeId
                    while (id != null) { tree[id]?.get(key)?.let { return it }; id = parents[id] }
                    return mapOf("compact" to false)[key] // the plugin default
                }
                override val keys = generateSequence(nodeId) { parents[it] }.flatMap { tree[it].orEmpty().keys }.toSet() + "compact"
                override fun string(key: String) = lookup(key) as? String
                override fun int(key: String) = lookup(key) as? Int
                override fun double(key: String) = lookup(key) as? Double
                override fun boolean(key: String) = lookup(key) as? Boolean
            }
            override fun <T : Any> implementation(type: KClass<T>, key: String): T? = registry[key]?.takeIf { type.isInstance(it) }?.let { type.javaObjectType.cast(it) }
        }
        val run = config.values("infantry.shell.run")
        assertEquals("Run", run.string("title")) // the child wins
        assertEquals(2, run.int("density")) // from the parent
        assertEquals(false, run.boolean("compact")) // the plugin default
        assertNull(run.int("title")) // another type is not coerced
        assertEquals(setOf("title", "density", "compact"), run.keys)
        assertEquals(listOf("a", "b"), listOf("b", "a").sortedWith(config.implementation<Comparator<String>>("sorter.byName")!!))
        assertNull(config.implementation<Comparator<String>>("sorter.missing"))
        assertNull(EmptyConfig.values("any").string("title"))
        assertNull(EmptyConfig.implementation<Runnable>("any"))
    }
}
