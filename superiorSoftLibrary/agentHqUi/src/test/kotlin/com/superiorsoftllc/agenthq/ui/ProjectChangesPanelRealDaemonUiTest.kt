package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.plugins.official.changes.*

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.toAwtImage
import javax.imageio.ImageIO
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.agenthq.daemon.AgentHqDaemon
import com.superiorsoftllc.agenthq.mailbox.JsonMailStore
import com.superiorsoftllc.agenthq.persistence.SqlAgentHqStore
import com.superiorsoftllc.agenthq.provider.StubProvider
import com.superiorsoftllc.agenthq.runner.StubScript
import com.superiorsoftllc.agenthq.session.JsonSessionStore
import com.superiorsoftllc.agenthq.stories.JsonStoryStore
import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.model.changesWorktreeStatus
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
 * The side panel in the REAL window: the whole [App] against the REAL daemon (Ktor, WebSocket, SQLite) over a REAL temp
 * git repository. Clicking the right-hand Changes tab shows the branch and changed files; a file created behind the
 * window's back appears after Refresh. The center Git tab is still there.
 */
@OptIn(ExperimentalTestApi::class)
class ProjectChangesPanelRealDaemonUiTest {
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
            memory = FixedMemory,
            port = 0, host = "127.0.0.1", provider = provider, runner = provider.transport()!!,
            sessionStore = JsonSessionStore(File(dir, "sessions.json")),
            mailStore = JsonMailStore(File(dir, "mailbox.json")),
            store = SqlAgentHqStore.open(File(dir, "agenthq.db").absolutePath),
            mailBaseUrl = "http://127.0.0.1:1", token = null,
            storyStore = JsonStoryStore(File(dir, "stories.json")),
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

    @Test fun `the right-hand Changes tab shows a real repository's branch and files and picks up a new file on Refresh`() {
        val repo = newRepo()
        File(repo, "README.md").writeText("hello\nedited by the side panel test\n")
        val port = boot()

        val c = Controller(scope)
        runDesktopComposeUiTest(1800, 1000) {
            setContent { App(c, "ws://127.0.0.1:$port/ws", null) }
            waitUntil(timeoutMillis = 20_000) { c.state.value.connection.state == ConnState.CONNECTED }
            assertIs<CommandResult.Sent>(runBlocking { c.saveProject("Side panel project") })
            val project = until("project listed", c) { s -> s.projects.any { it.name == "Side panel project" } }.projects.single { it.name == "Side panel project" }
            runBlocking { c.selectProject(project.id) }
            assertIs<CommandResult.Sent>(runBlocking { c.saveProjectDirectory(project.id, repo.absolutePath) })
            waitUntil(timeoutMillis = 15_000) { c.state.value.activeProject?.directory == repo.absolutePath }

            onNodeWithText("Git").assertExists()
            // UX-CHANGES-1: the tab is "Changes ^3"; the branch line is the shown worktree's header (the project's checkout).
            onNodeWithText("Changes ^3").performClick()
            waitUntil(timeoutMillis = 15_000) { c.state.value.changesWorktreeStatus?.available == true }
            onNodeWithText("CHANGES · Side panel project").assertExists()
            onNode(hasTestTag(CHANGES_BRANCH_TAG) and hasText("main")).assertExists()
            onNodeWithText("README.md", substring = true).assertExists()
            onNodeWithText("late-arrival.txt", substring = true).assertDoesNotExist()

            File(repo, "late-arrival.txt").writeText("created while the window was open\n")
            onNodeWithTag(PROJECT_CHANGES_REFRESH_TAG).performClick()
            waitUntil(timeoutMillis = 15_000) { c.state.value.changesWorktreeStatus?.changes?.any { it.path == "late-arrival.txt" } == true }
            onNodeWithText("late-arrival.txt", substring = true).assertExists()
            onNodeWithText("2 changed files").assertExists()
        }
    }

    /**
     * BRANCH-VIEW-2: a side branch that changes file B only. The working tree has file A changed. Picking the branch in
     * the panel's selector lists B (not A), the header names the branch and its base, and clicking B opens the diff
     * modal on the branch's own diff (B's added line).
     */
    @Test fun `picking a side branch in the Changes panel lists that branch's files and opens its diff`() {
        val repo = newRepo()
        File(repo, "b.txt").writeText("base line\n")
        git(repo, "add", "b.txt")
        git(repo, "commit", "-m", "add b")
        git(repo, "checkout", "-b", "side")
        File(repo, "b.txt").writeText("base line\nadded on the side branch\n")
        git(repo, "commit", "-am", "side changes b")
        git(repo, "checkout", "main")
        File(repo, "a.txt").writeText("working tree only\n")
        val port = boot()

        val c = Controller(scope)
        runDesktopComposeUiTest(1800, 1000) {
            setContent { App(c, "ws://127.0.0.1:$port/ws", null) }
            waitUntil(timeoutMillis = 20_000) { c.state.value.connection.state == ConnState.CONNECTED }
            assertIs<CommandResult.Sent>(runBlocking { c.saveProject("Branch project") })
            val project = until("project listed", c) { s -> s.projects.any { it.name == "Branch project" } }.projects.single { it.name == "Branch project" }
            runBlocking { c.selectProject(project.id) }
            assertIs<CommandResult.Sent>(runBlocking { c.saveProjectDirectory(project.id, repo.absolutePath) })
            waitUntil(timeoutMillis = 15_000) { c.state.value.activeProject?.directory == repo.absolutePath }

            onNodeWithText("Changes ^3").performClick()
            waitUntil(timeoutMillis = 15_000) { c.state.value.changesWorktreeStatus?.available == true }
            onNodeWithText("a.txt", substring = true).assertExists()
            waitUntil(timeoutMillis = 15_000) { c.state.value.projectGitBranches[project.id]?.branches?.any { it.name == "side" } == true }

            onNodeWithTag(BRANCH_SELECT_TAG).performClick()
            onNodeWithTag(BRANCH_SELECT_OPTION_TAG + "side").performClick()
            waitUntil(timeoutMillis = 15_000) { onAllNodesWithText("side vs main", substring = true).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("side vs main · 1 file · +1 −0", substring = true).assertExists()
            onNodeWithTag(BRANCH_CHANGE_ROW_TAG + "b.txt").assertExists()
            onNodeWithText("a.txt", substring = true).assertDoesNotExist()
            val panel = File("build/reports/branch-view-2/branch-panel.png").apply { parentFile.mkdirs() }
            ImageIO.write(onNodeWithTag("right-panel").captureToImage().toAwtImage(), "png", panel)
            println("BRANCH-VIEW-2 screenshot: ${panel.absolutePath}")

            onNodeWithTag(BRANCH_CHANGE_ROW_TAG + "b.txt").performClick()
            waitUntil(timeoutMillis = 15_000) { onAllNodesWithText("+added on the side branch").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(DIFF_MODAL_TAG).assertExists()
            val out = File("build/reports/branch-view-2/branch-diff-modal.png").apply { parentFile.mkdirs() }
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", out)
            println("BRANCH-VIEW-2 screenshot: ${out.absolutePath}")

            // Back to the working tree: the default entry, and its files again.
            onNodeWithTag(DIFF_MODAL_CLOSE_TAG).performClick()
            onNodeWithTag(BRANCH_SELECT_TAG).performClick()
            onNodeWithTag(BRANCH_SELECT_OPTION_TAG + WORKING_TREE_KEY).performClick()
            waitUntil(timeoutMillis = 15_000) { onAllNodesWithText("a.txt", substring = true).fetchSemanticsNodes().isNotEmpty() }
        }
    }
}
