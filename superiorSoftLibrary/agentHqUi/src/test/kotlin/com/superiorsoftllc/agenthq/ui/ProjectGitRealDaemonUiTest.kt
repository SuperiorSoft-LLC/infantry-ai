package com.superiorsoftllc.agenthq.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.agenthq.daemon.AgentHqDaemon
import com.superiorsoftllc.agenthq.mailbox.JsonMailStore
import com.superiorsoftllc.agenthq.persistence.SqlAgentHqStore
import com.superiorsoftllc.agenthq.provider.StubProvider
import com.superiorsoftllc.agenthq.role.RoleService
import com.superiorsoftllc.agenthq.runner.StubScript
import com.superiorsoftllc.agenthq.session.JsonSessionStore
import com.superiorsoftllc.agenthq.stories.JsonStoryStore
import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.model.ConnState
import com.superiorsoftllc.agenthq.ui.net.CommandResult
import com.superiorsoftllc.agenthq.ui.net.Controller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * CP-2 slice (c), VISIBLE: the Git pane drawn from the REAL daemon (real Ktor server, real WebSocket, SQLite) over a
 * REAL temp git repository. A project is created and given a directory through the window's own controller; the
 * pane then shows the branch, the changed files, a file's diff and the history -- nothing is faked between the
 * working tree and the pixels.
 */
@OptIn(ExperimentalTestApi::class)
class ProjectGitRealDaemonUiTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val closers = mutableListOf<() -> Unit>()

    @AfterTest fun tearDown() {
        scope.cancel()
        closers.reversed().forEach { runCatching { it() } }
    }

    private fun git(dir: File, vararg args: String) {
        val p = ProcessBuilder(listOf("git", "-c", "user.name=AgentHQ UI Test", "-c", "user.email=ui@test.invalid") + args)
            .directory(dir).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0) { "git ${args.toList()} failed: $out" }
    }

    private fun newRepo(): File {
        val repo = Files.createTempDirectory("agenthq-ui-git").toFile()
        closers += { repo.deleteRecursively() }
        git(repo, "init", "-b", "main")
        File(repo, "README.md").writeText("hello\n")
        git(repo, "add", "README.md")
        git(repo, "commit", "-m", "add the readme")
        return repo
    }

    private fun boot(): Int {
        val dir = Files.createTempDirectory("agenthq-ui-git-daemon").toFile()
        val provider = StubProvider(StubScript.default(), delayMs = 0)
        val daemon = AgentHqDaemon.build(
            port = 0, host = "127.0.0.1", provider = provider, runner = provider.transport()!!,
            sessionStore = JsonSessionStore(File(dir, "sessions.json")),
            mailStore = JsonMailStore(File(dir, "mailbox.json")),
            store = SqlAgentHqStore.open(File(dir, "agenthq.db").absolutePath),
            mailBaseUrl = "http://127.0.0.1:1", token = null,
            storyStore = JsonStoryStore(File(dir, "stories.json")),
            roles = RoleService(File(dir, "roles")),
            // Hermetic (BUG-ModelsHeadOfLine): not build()'s builtIn(), which runs this host's `pi --list-models`.
            modelCatalogs = com.superiorsoftllc.agenthq.provider.HarnessModelCatalogs.EMPTY,
        )
        daemon.server.start(wait = false)
        closers += { daemon.server.stop(0, 200); daemon.close(); dir.deleteRecursively() }
        return runBlocking {
            repeat(250) {
                daemon.server.engine.resolvedConnectors().firstOrNull()?.port?.takeIf { it > 0 }?.let { return@runBlocking it }
                delay(20)
            }
            error("no port")
        }
    }

    private fun until(what: String, c: Controller, timeoutMs: Long = 20_000, cond: (AppState) -> Boolean): AppState {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            c.state.value.takeIf(cond)?.let { return it }
            Thread.sleep(25)
        }
        error("not met: $what; conn=${c.state.value.connection} notices=${c.state.value.notices}")
    }

    @Test fun `the Git pane shows a real repository's branch, changes, diff and history through the real daemon`() {
        val repo = newRepo()
        File(repo, "README.md").writeText("hello\nedited by the real daemon ui test\n")
        File(repo, "fresh.txt").writeText("fresh\n")
        val port = boot()

        val c = Controller(scope)
        c.connect("ws://127.0.0.1:$port/ws", null)
        until("connected", c) { it.connection.state == ConnState.CONNECTED }
        assertIs<CommandResult.Sent>(runBlocking { c.saveProject("Real git project") })
        val project = until("project listed", c) { s -> s.projects.any { it.name == "Real git project" } }.projects.single { it.name == "Real git project" }
        assertEquals("", project.directory, "a new project has no directory")
        runBlocking { c.selectProject(project.id) }

        runDesktopComposeUiTest(1000, 1000) {
            setContent {
                val state by c.state.collectAsState()
                ProjectGitPane(state, c)
            }
            // No directory yet: unknown with the daemon's own reason -- not clean.
            waitUntil(timeoutMillis = 15_000) { c.state.value.projectGit[project.id]?.available == false }
            onNodeWithText("Git unknown: this project has no directory configured").assertExists()
            onNodeWithText("Working tree clean. Nothing to commit.").assertDoesNotExist()

            // Give it a directory through the window's own controller; the daemon's `project` answer re-reads the tree.
            assertIs<CommandResult.Sent>(runBlocking { c.saveProjectDirectory(project.id, repo.absolutePath) })
            waitUntil(timeoutMillis = 15_000) { c.state.value.projectGit[project.id]?.available == true }
            waitUntil(timeoutMillis = 15_000) { c.state.value.projectGitHistory[project.id]?.commits?.isNotEmpty() == true }

            onNodeWithText("branch main @", substring = true).assertExists()
            onNodeWithText("README.md", substring = true).assertExists()
            onNodeWithText("fresh.txt", substring = true).assertExists()
            onNodeWithText("add the readme", substring = true).assertExists()

            onNodeWithText("README.md", substring = true).performScrollTo().performClick()
            waitUntil(timeoutMillis = 15_000) { c.state.value.projectGitPatch[project.id]?.available == true }
            onNodeWithText("+edited by the real daemon ui test").performScrollTo().assertExists()
        }
    }
}
