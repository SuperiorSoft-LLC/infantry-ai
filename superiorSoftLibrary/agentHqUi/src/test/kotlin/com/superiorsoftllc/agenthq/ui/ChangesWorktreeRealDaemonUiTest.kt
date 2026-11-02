package com.superiorsoftllc.agenthq.ui

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.withKeyDown
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
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertIs

/**
 * UX-CHANGES-1: the Worktree tab is gone and the Changes tab carries its job. The whole [App] against the REAL daemon
 * over a REAL temp repository with one linked worktree: `main` (the project's directory, with an untracked file) and
 * `feature` (2 commits ahead of `main`, one file modified, uncommitted). The daemon runs sessions in the `feature`
 * worktree, so the selected session's checkout is that worktree, not the project's directory.
 *
 * Tags and labels are string literals on purpose: the test compiles against the base, so its RED is a runtime
 * assertion and not a missing symbol.
 */
@OptIn(ExperimentalTestApi::class)
class ChangesWorktreeRealDaemonUiTest {
    /**
     * BUG-FLAKE-CHGWT (the FLAKE-4/FLAKE-6/FLAKE-7 pattern): the budget for every wait on a daemon answer here, agentHq's
     * TestWait (60 s, AGENTHQ_TEST_WAIT_MS overrides it). Under suite load the fixed 20 s wait for the session's branch
     * timed out on a daemon that answered late: the connection's project-git lane answers in request order, so the
     * session worktree's status queues behind every read the panel asked first (the session-less worktree list, the
     * shared checkout's status, the branch lists), about 40 git spawns at ~0.5 s each under load. A passing run waits
     * milliseconds; only a missing answer costs the whole budget.
     */
    private val daemonWaitMs = System.getenv("AGENTHQ_TEST_WAIT_MS")?.toLongOrNull()?.takeIf { it > 0 } ?: 60_000L

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

    private fun boot(workingDir: File): Int {
        val dir = Files.createTempDirectory("agenthq-ui-wt-daemon").toFile()
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
            // Every session this daemon spawns runs in the linked worktree: that is "the session's checkout".
            workingDir = workingDir,
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

    private fun until(what: String, c: Controller, timeoutMs: Long = daemonWaitMs, cond: (AppState) -> Boolean): AppState {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            c.state.value.takeIf(cond)?.let { return it }
            Thread.sleep(25)
        }
        error("not met: $what; conn=${c.state.value.connection} notices=${c.state.value.notices}")
    }

    @Test fun `Ctrl+3 opens the Changes tab and there is no Worktree tab`() {
        val c = Controller(scope)
        runDesktopComposeUiTest(1400, 900) {
            setContent { App(c, "ws://127.0.0.1:1/ws", null) }
            waitForIdle()
            onAllNodesWithText("Worktree  ^3").assertCountEquals(0)
            onNodeWithText("Changes ^3").assertExists()
            onRoot().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Three) } }
            waitForIdle()
            onNodeWithText("Select a project to see its changes.").assertExists()
        }
    }

    @Test fun `the Changes tab shows the session's worktree with its branch, ahead count, files and history, and follows the picker`() {
        val repo = Files.createTempDirectory("agenthq-ui-wt-repo").toFile()
        closers += { repo.deleteRecursively() }
        git(repo, "init", "-b", "main")
        File(repo, "README.md").writeText("hello\n")
        git(repo, "add", "README.md")
        git(repo, "commit", "-m", "add the readme")

        val holder = Files.createTempDirectory("agenthq-ui-wt-linked").toFile()
        closers += { holder.deleteRecursively() }
        val wt = File(holder, "feature-wt")
        git(repo, "worktree", "add", "-b", "feature", wt.absolutePath)
        File(wt, "feature.txt").writeText("one\n")
        git(wt, "add", "feature.txt")
        git(wt, "commit", "-m", "first feature commit")
        File(wt, "feature.txt").writeText("one\ntwo\n")
        git(wt, "commit", "-am", "second feature commit")
        File(wt, "feature.txt").writeText("one\ntwo\nnot committed yet\n")
        File(repo, "main-only.txt").writeText("only in the main checkout\n")

        // SESSION-CWD-1 (02f68f4): a session naming a project with a directory runs in THAT directory, which overrides
        // the daemon's workingDir. So the project's directory is the linked worktree: the session's checkout is `feature`,
        // and `main` (git's first worktree) stays listed as the shared checkout.
        val port = boot(workingDir = wt)
        val c = Controller(scope)
        runDesktopComposeUiTest(1800, 1000) {
            setContent { App(c, "ws://127.0.0.1:$port/ws", null) }
            waitUntil(timeoutMillis = daemonWaitMs) { c.state.value.connection.state == ConnState.CONNECTED }
            assertIs<CommandResult.Sent>(runBlocking { c.saveProject("Worktree project") })
            val project = until("project listed", c) { s -> s.projects.any { it.name == "Worktree project" } }.projects.single { it.name == "Worktree project" }
            runBlocking { c.selectProject(project.id) }
            assertIs<CommandResult.Sent>(runBlocking { c.saveProjectDirectory(project.id, wt.absolutePath) })
            waitUntil(timeoutMillis = daemonWaitMs) { c.state.value.activeProject?.directory == wt.absolutePath }
            assertIs<CommandResult.Sent>(runBlocking { c.spawn("hello from the worktree test") })
            val sessionId = until("session listed", c) { it.agents.isNotEmpty() }.agents.keys.single()
            c.select(sessionId)

            // 1. No Worktree tab; Changes shows the worktree selector on the SESSION's checkout (feature), not main.
            onAllNodesWithText("Worktree  ^3").assertCountEquals(0)
            onNodeWithText("Changes ^3").performClick()
            // The daemon's answer for the session's checkout first, then the header drawn from it: a miss on the first
            // wait is the daemon's answer, a miss on the second is the panel's render.
            waitUntil(timeoutMillis = daemonWaitMs) { c.state.value.changesWorktreeStatus?.takeIf { it.available }?.branch == "feature" }
            waitUntil(timeoutMillis = daemonWaitMs) {
                onAllNodes(hasTestTag("changes-branch") and hasText("feature")).fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithTag("worktree-select").assert(hasText("feature", substring = true))
            onNodeWithTag("worktree-select").assert(hasText("this session", substring = true))

            // 2. The branch and its ahead/behind count against its base, plus the uncommitted count.
            onNodeWithTag("changes-ahead").assertTextEquals("+2")
            onNodeWithTag("changes-behind").assertTextEquals("-0")
            onNodeWithTag("changes-base").assertTextEquals("vs main")
            onNodeWithTag("changes-uncommitted").assertTextEquals("1 uncommitted")

            // 3. The changes list names the feature file; History lists the two commits by subject.
            // BUG-CHANGES-WRONG-BRANCH (a7cbcd3): a linked worktree's inner picker defaults to its own branch vs the base,
            // so the list is the branch's committed diff, a separate daemon answer from the worktree status waited on
            // above. Wait for that answer (feature vs main, naming feature.txt), then for the row drawn from it.
            onNodeWithTag("changes-mode-working-tree").performClick()
            waitUntil(timeoutMillis = daemonWaitMs) {
                c.state.value.projectGitBranchChanges[project.id]?.takeIf { it.available && it.branch == "feature" && it.base == "main" }
                    ?.changes?.any { it.path == "feature.txt" } == true
            }
            waitUntil(timeoutMillis = daemonWaitMs) { onAllNodes(hasTestTag("branch-change-row-feature.txt")).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("feature.txt", substring = true).assertExists()
            onNodeWithText("main-only.txt", substring = true).assertDoesNotExist()
            onNodeWithTag("changes-mode-history").performClick()
            waitUntil(timeoutMillis = daemonWaitMs) { onAllNodesWithText("second feature commit").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("first feature commit").assertExists()
            val history = File("build/reports/ux-changes-1/changes-history.png").apply { parentFile.mkdirs() }
            ImageIO.write(onNodeWithTag("right-panel").captureToImage().toAwtImage(), "png", history)
            println("UX-CHANGES-1 screenshot: ${history.absolutePath}")

            // 4. Switching the selector to the other worktree moves the branch and the changes list with it.
            onNodeWithTag("changes-mode-working-tree").performClick()
            onNodeWithTag("worktree-select").performClick()
            onNodeWithTag("worktree-select-option-main").performClick()
            waitUntil(timeoutMillis = daemonWaitMs) { c.state.value.changesWorktreeStatus?.takeIf { it.available }?.branch == "main" }
            waitUntil(timeoutMillis = daemonWaitMs) {
                onAllNodes(hasTestTag("changes-branch") and hasText("main")).fetchSemanticsNodes().isNotEmpty()
            }
            waitUntil(timeoutMillis = daemonWaitMs) { onAllNodesWithText("main-only.txt", substring = true).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("feature.txt", substring = true).assertDoesNotExist()
            val main = File("build/reports/ux-changes-1/changes-main.png").apply { parentFile.mkdirs() }
            ImageIO.write(onNodeWithTag("right-panel").captureToImage().toAwtImage(), "png", main)
            println("UX-CHANGES-1 screenshot: ${main.absolutePath}")
        }
    }
}
