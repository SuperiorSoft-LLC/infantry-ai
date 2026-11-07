// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.plugins.official.board.*
import com.superiorsoftllc.infantry.plugins.official.agents.*

import com.superiorsoftllc.infantry.plugins.official.git.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.infantry.daemon.InfantryDaemon
import com.superiorsoftllc.infantry.mailbox.JsonMailStore
import com.superiorsoftllc.infantry.persistence.SqlInfantryStore
import com.superiorsoftllc.infantry.provider.HarnessModelCatalogs
import com.superiorsoftllc.infantry.provider.StubProvider
import com.superiorsoftllc.infantry.runner.StubScript
import com.superiorsoftllc.infantry.session.JsonSessionStore
import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.model.ConnState
import com.superiorsoftllc.infantry.ui.net.CommandResult
import com.superiorsoftllc.infantry.ui.net.Controller
import com.superiorsoftllc.infantry.ui.protocol.AgentProfile
import com.superiorsoftllc.infantry.ui.protocol.TicketColumns
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
import kotlin.test.assertNotEquals

/**
 * BASE-1b, VISIBLE and END TO END: the Git tab's "Base branch for new tasks" dropdown against the REAL daemon (real
 * Ktor server, WebSocket, SQLite) cutting in a REAL temp git repository. What the daemon holds is read back over a
 * second, fresh connection; what a ticket start cut is read from git itself.
 *
 * The repository: `main` c1 -> m2 (checked out), `develop` c1 -> d1. HEAD is m2, so a start that ignored the setting
 * would be cut from m2 and never from d1.
 */
@OptIn(ExperimentalTestApi::class)
class ProjectBaseBranchRealDaemonUiTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val closers = mutableListOf<() -> Unit>()

    @AfterTest fun tearDown() {
        scope.cancel()
        closers.reversed().forEach { runCatching { it() } }
    }

    private fun git(dir: File, vararg args: String): String {
        val p = ProcessBuilder(listOf("git", "-c", "user.name=Infantry UI Test", "-c", "user.email=ui@test.invalid") + args)
            .directory(dir).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0) { "git ${args.toList()} failed: $out" }
        return out.trim()
    }

    private fun commit(repo: File, file: String) {
        File(repo, file).writeText("$file\n")
        git(repo, "add", file)
        git(repo, "commit", "-q", "-m", "add $file")
    }

    /** The repository described on the class; returns it with develop's tip (d1) and main's (m2). */
    private fun newRepo(): Triple<File, String, String> {
        val repo = Files.createTempDirectory("infantry-ui-base").toFile()
        closers += { repo.deleteRecursively() }
        git(repo, "init", "-q", "-b", "main")
        commit(repo, "README.md")
        git(repo, "checkout", "-q", "-b", "develop")
        commit(repo, "develop.txt")
        val d1 = git(repo, "rev-parse", "HEAD")
        git(repo, "checkout", "-q", "main")
        commit(repo, "main-only.txt")
        val m2 = git(repo, "rev-parse", "HEAD")
        return Triple(repo, d1, m2)
    }

    private fun boot(repo: File): Int {
        val dir = Files.createTempDirectory("infantry-ui-base-daemon").toFile()
        val provider = StubProvider(StubScript.default(), delayMs = 0)
        val daemon = InfantryDaemon.build(
            memory = FixedMemory,
            port = 0, host = "127.0.0.1", provider = provider, runner = provider.transport()!!,
            repo = repo,
            sessionStore = JsonSessionStore(File(dir, "sessions.json")),
            mailStore = JsonMailStore(File(dir, "mailbox.json")),
            store = SqlInfantryStore.open(File(dir, "infantry.db").absolutePath),
            mailBaseUrl = "http://127.0.0.1:1", token = null,
            workingDir = File(dir, "workdir").apply { mkdirs() },
            modelCatalogs = HarnessModelCatalogs.EMPTY,
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

    /** What the DAEMON holds for [projectId]'s base branch, read over a fresh connection of its own. */
    private fun daemonBaseBranch(port: Int, projectId: String): String {
        val probe = Controller(scope)
        probe.connect("ws://127.0.0.1:$port/ws", null)
        val s = until("probe sees the project", probe) { st -> st.connection.isLive && st.projects.any { it.id == projectId } }
        return s.projects.single { it.id == projectId }.baseBranch
    }

    /** A connected window controller with a project whose directory is [repo]; returns the project id. */
    private fun projectOn(c: Controller, repo: File): String {
        until("connected", c) { it.connection.state == ConnState.CONNECTED }
        assertIs<CommandResult.Sent>(runBlocking { c.saveProject("Base project") })
        val id = until("project listed", c) { s -> s.projects.any { it.name == "Base project" } }.projects.single { it.name == "Base project" }.id
        runBlocking { c.selectProject(id) }
        assertIs<CommandResult.Sent>(runBlocking { c.saveProjectDirectory(id, repo.absolutePath) })
        until("directory stored", c) { s -> s.projects.single { it.id == id }.directory.isNotEmpty() }
        return id
    }

    /** Opens the dropdown, types [typed], and picks [branch] (null = Default) with Enter -- as a person does. */
    private fun ComposeUiTest.pickBase(c: Controller, projectId: String, typed: String, branch: String?) {
        waitUntil(timeoutMillis = 15_000) { c.state.value.projectGitBranches[projectId]?.branches?.isNotEmpty() == true }
        onNodeWithTag(GIT_BASE_TOGGLE_TAG).performScrollTo().performClick()
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasTestTag(GIT_BASE_SEARCH_TAG)).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(GIT_BASE_SEARCH_TAG).performTextReplacement(typed)
        // [typed] leaves exactly one row, highlighted; Enter picks it.
        onNodeWithTag(GIT_BASE_SEARCH_TAG + SELECT_COUNT_SUFFIX).assertTextEquals("1 of ${c.state.value.projectGitBranches[projectId]!!.branches.size + 1}")
        onNodeWithTag(branch?.let { GIT_BASE_ITEM_TAG + it } ?: GIT_BASE_DEFAULT_ITEM_TAG).assertIsSelected()
        onNodeWithTag(GIT_BASE_SEARCH_TAG).performKeyInput { pressKey(Key.Enter) }
    }

    @Test fun `the Git tab dropdown filters the real branches, saves develop to the daemon, and Default clears it`() {
        val (repo, _, _) = newRepo()
        val port = boot(repo)
        val c = Controller(scope)
        c.connect("ws://127.0.0.1:$port/ws", null)
        val projectId = projectOn(c, repo)
        assertEquals("", daemonBaseBranch(port, projectId), "a new project has no base branch")

        runDesktopComposeUiTest(1000, 1000) {
            setContent {
                val state by c.state.collectAsState()
                ProjectGitPane(state, c)
            }
            // Typing "DEV" leaves only develop: main is filtered out.
            waitUntil(timeoutMillis = 15_000) { c.state.value.projectGitBranches[projectId]?.branches?.size == 2 }
            onNodeWithTag(GIT_BASE_TOGGLE_TAG).performScrollTo().performClick()
            onNodeWithTag(GIT_BASE_SEARCH_TAG).performTextReplacement("DEV")
            onNodeWithTag(GIT_BASE_ITEM_TAG + "develop").assertExists()
            onNodeWithTag(GIT_BASE_ITEM_TAG + "main").assertDoesNotExist()
            onNodeWithTag(GIT_BASE_TOGGLE_TAG).performScrollTo().performClick() // close it again

            pickBase(c, projectId, "DEV", "develop")
            waitUntil(timeoutMillis = 15_000) { c.state.value.activeProject?.baseBranch == "develop" }
            waitUntil(timeoutMillis = 10_000) { onAllNodes(hasTestTag(GIT_BASE_SUMMARY_TAG) and hasText("develop", substring = true)).fetchSemanticsNodes().isNotEmpty() }
            assertEquals("develop", daemonBaseBranch(port, projectId), "the daemon stored the picked branch")

            pickBase(c, projectId, "none", null) // LAUNCH-FIRSTRUN-1: the clear row reads "None (...)" now
            waitUntil(timeoutMillis = 15_000) { c.state.value.activeProject?.baseBranch == "" }
            waitUntil(timeoutMillis = 10_000) { onAllNodes(hasTestTag(GIT_BASE_SUMMARY_TAG) and hasText("Not set", substring = true)).fetchSemanticsNodes().isNotEmpty() }
            assertEquals("", daemonBaseBranch(port, projectId), "Default cleared the daemon's setting")
        }
    }

    /** LAUNCH-FIRSTRUN-1: the New Project form (name + repository) leaves a project whose first ticket can start. */
    @Test fun `a project created with a repository takes its checked-out branch as the base`() {
        val (repo, _, _) = newRepo()
        val port = boot(repo)
        val c = Controller(scope)
        c.connect("ws://127.0.0.1:$port/ws", null)
        until("connected", c) { it.connection.state == ConnState.CONNECTED }
        assertIs<CommandResult.Sent>(runBlocking { c.createProject("First project", repo.absolutePath) })
        val id = until("project listed", c) { s -> s.projects.any { it.name == "First project" } }.projects.single { it.name == "First project" }.id
        until("base adopted", c) { s -> s.projects.single { it.id == id }.baseBranch == "main" }
        assertEquals("main", daemonBaseBranch(port, id), "the daemon stored the checked-out branch (main), not develop")
    }

    @Test fun `a ticket started from the board after picking develop in the Git tab is cut from develop`() {
        val (repo, d1, m2) = newRepo()
        val port = boot(repo)
        val c = Controller(scope)
        runDesktopComposeUiTest(1700, 1000) {
            setContent { App(c, "ws://127.0.0.1:$port/ws", null) }
            waitUntil(timeoutMillis = 20_000) { c.state.value.connection.isLive && c.state.value.roles.isNotEmpty() }
            val projectId = projectOn(c, repo)
            assertIs<CommandResult.Sent>(runBlocking { c.saveProfile(AgentProfile(id = "backend", name = "Backend", role = "backend")) })
            until("profile listed", c) { it.profiles.isNotEmpty() }

            // The Git tab: pick develop.
            onNodeWithText("Git").performClick()
            pickBase(c, projectId, "devel", "develop")
            waitUntil(timeoutMillis = 15_000) { c.state.value.activeProject?.baseBranch == "develop" }
            assertEquals("develop", daemonBaseBranch(port, projectId))

            // The board: a ticket in the project, started with its Start button (ticket_move to in_progress).
            assertIs<CommandResult.Sent>(runBlocking { c.createTicket("Cut me from develop", "", null, "backend") })
            until("ticket on the board", c) { s -> s.tickets.any { it.title == "Cut me from develop" } }
            onNodeWithTag(SIDEBAR_BOARD_BUTTON_TAG).performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText("Cut me from develop")).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Start").performClick()
            waitUntil(timeoutMillis = 20_000) { c.state.value.tickets.single().let { it.branch != null && it.base != null } }
            val ticket = c.state.value.tickets.single()

            // What git says: the ticket's branch forks from develop's tip d1, and main's m2 is not in it.
            assertEquals(d1, ticket.base, "the ticket records develop's commit as its base")
            assertEquals(d1, git(repo, "merge-base", ticket.branch!!, "develop"), "the ticket branch forks at develop's tip")
            assertNotEquals(m2, git(repo, "merge-base", ticket.branch!!, "main"), "the checked-out HEAD (m2) is not its base")

            // What the window says.
            // BUG-BASE-087: the note names the setting that chose the base.
            val note = "started \"Cut me from develop\": cut from develop ${d1.take(8)} (project base)"
            waitUntil(timeoutMillis = 10_000) { c.state.value.toasts.any { it.body == note } }
            waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("cut from develop ${d1.take(8)}", substring = true)).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(TicketColumns.IN_PROGRESS == ticket.status || TicketColumns.DONE == ticket.status, true, "the ticket started: ${ticket.status}")
        }
    }
}
