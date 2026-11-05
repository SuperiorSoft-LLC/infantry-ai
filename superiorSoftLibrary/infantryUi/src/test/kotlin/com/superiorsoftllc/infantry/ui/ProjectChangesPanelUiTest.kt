// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.plugins.official.changes.*

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.infantry.ui.net.Controller
import com.superiorsoftllc.infantry.ui.net.DaemonClient
import com.superiorsoftllc.infantry.ui.protocol.ChangeKind
import com.superiorsoftllc.infantry.ui.protocol.ChangedFile
import com.superiorsoftllc.infantry.ui.protocol.Envelope
import com.superiorsoftllc.infantry.ui.protocol.GitWorktree
import com.superiorsoftllc.infantry.ui.protocol.Project
import com.superiorsoftllc.infantry.ui.protocol.ProjectGitStatus
import com.superiorsoftllc.infantry.ui.protocol.ProjectGitWorktreeList
import com.superiorsoftllc.infantry.ui.protocol.ProjectGitWorktreeStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.superiorsoftllc.infantry.ui.model.ConnectionInfo
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class ChangesFakeLink(scope: CoroutineScope, url: String = "ws://127.0.0.1:1/ws") : DaemonClient(scope) {
    val sent = CopyOnWriteArrayList<Envelope>()
    private val incoming = MutableSharedFlow<Envelope>(extraBufferCapacity = 64)
    override val frames: SharedFlow<Envelope> get() = incoming
    // The link's address from the start (the Controller applies it in its constructor), so the window shows the URL
    // without a dial and no later link-state emission can race the pushed `ready`.
    override val info: StateFlow<ConnectionInfo> = MutableStateFlow(ConnectionInfo(url = url))
    override suspend fun send(envelope: Envelope): Boolean { sent += envelope; return true }
    // QA-FIX-E: a fake link never dials. App's first effect calls connect(url); the inherited one opened a real socket to
    // the test's dead url, and its refusal (RECONNECTING, then a retry each backoff) overwrote the CONNECTED that the
    // pushed `ready` had set, so under load `isLive` never held (AssistantOverridesUiTest). Same as every NoDialLink.
    override fun connect(url: String, token: String?) {}
    override fun disconnect() {}
    fun push(e: Envelope) {
        val deadline = System.currentTimeMillis() + 10_000
        while (incoming.subscriptionCount.value == 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        check(incoming.tryEmit(e)) { "frame buffer full" }
    }
    fun sentOf(type: String) = sent.filter { it.type == type }
}

/**
 * UX: the project's working tree as a right-hand side panel. Driven like a person drives it; the daemon side is a
 * fake link, so the assertions are exactly "what the panel asked" and "what it drew".
 */
@OptIn(ExperimentalTestApi::class)
class ProjectChangesPanelUiTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private fun projects(vararg p: Project) = Envelope(Envelope.PROJECTS, json.encodeToString(ListSerializer(Project.serializer()), p.toList()))
    private fun status(s: ProjectGitStatus) = Envelope(Envelope.PROJECT_GIT, json.encodeToString(ProjectGitStatus.serializer(), s))

    private fun withPanel(contract: String, pollMs: Long, width: Int = 400, body: androidx.compose.ui.test.ComposeUiTest.(ChangesFakeLink, Controller) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val link = ChangesFakeLink(scope)
            val controller = Controller(scope, link)
            runDesktopComposeUiTest(width, 800) {
                setContent {
                    val state by controller.state.collectAsState()
                    ProjectChangesPanel(state, controller, pollMs)
                }
                link.push(Envelope(Envelope.READY, "contract=$contract provider=stub instance=i1"))
                waitUntil(timeoutMillis = 10_000) { controller.state.value.connection.isLive }
                body(link, controller)
            }
        } finally {
            scope.cancel()
        }
    }

    private val dirty = ProjectGitStatus(
        "p1", "/srv/alpha", available = true, repoRoot = "/srv/alpha", branch = "main", head = "1a2b3c4d5e6f",
        changes = listOf(ChangedFile("README.md", ChangeKind.MODIFIED, staged = false), ChangedFile("fresh.txt", ChangeKind.UNTRACKED, staged = false)),
    )

    @Test fun `the panel asks for the tree only, and draws the branch and every changed file with its status`() = withPanel("1.36.0", 60_000) { link, controller ->
        onNodeWithText("Select a project to see its changes.").assertExists()
        link.push(projects(Project("p1", "Alpha", directory = "/srv/alpha")))
        waitUntil(timeoutMillis = 10_000) { link.sentOf(Envelope.PROJECT_GIT_STATUS).any { it.data == "p1" } }
        link.push(status(dirty))
        waitUntil(timeoutMillis = 10_000) { controller.state.value.projectGit.containsKey("p1") }

        onNodeWithText("branch main @ 1a2b3c4d").assertExists()
        onNodeWithText("README.md", substring = true).assertExists()
        onNodeWithText("fresh.txt", substring = true).assertExists()
        onNodeWithText("2 changed files").assertExists()
        // A polling side panel must not drag the history query along with every tick.
        assertTrue(link.sentOf(Envelope.PROJECT_GIT_LOG).isEmpty(), "the panel never asks for history: ${link.sent.map { it.type }}")
    }

    // UX-ICON-1: Refresh is an icon button named "Refresh" (contentDescription), driven by that name.
    @Test fun `the Refresh icon asks the daemon again and a changed answer replaces the list`() = withPanel("1.36.0", 60_000) { link, controller ->
        link.push(projects(Project("p1", "Alpha", directory = "/srv/alpha")))
        waitUntil(timeoutMillis = 10_000) { link.sentOf(Envelope.PROJECT_GIT_STATUS).isNotEmpty() }
        link.push(status(dirty))
        waitUntil(timeoutMillis = 10_000) { controller.state.value.projectGit.containsKey("p1") }

        val before = link.sentOf(Envelope.PROJECT_GIT_STATUS).size
        onNodeWithContentDescription("Refresh").performClick()
        waitUntil(timeoutMillis = 10_000) { link.sentOf(Envelope.PROJECT_GIT_STATUS).size > before }

        link.push(status(dirty.copy(changes = listOf(ChangedFile("only-now.kt", ChangeKind.ADDED, staged = true)))))
        waitUntil(timeoutMillis = 10_000) { controller.state.value.projectGit["p1"]?.changes?.size == 1 }
        onNodeWithText("only-now.kt", substring = true).assertExists()
        onNodeWithText("README.md", substring = true).assertDoesNotExist()
    }

    @Test fun `while open the panel re-reads the tree by itself`() = withPanel("1.36.0", 100) { link, _ ->
        link.push(projects(Project("p1", "Alpha", directory = "/srv/alpha")))
        waitUntil(timeoutMillis = 10_000) { link.sentOf(Envelope.PROJECT_GIT_STATUS).size >= 3 }
    }

    @Test fun `an unreadable tree is unknown with the daemon's reason, a clean one says clean`() = withPanel("1.36.0", 60_000) { link, controller ->
        link.push(projects(Project("p1", "Alpha")))
        waitUntil(timeoutMillis = 10_000) { link.sentOf(Envelope.PROJECT_GIT_STATUS).isNotEmpty() }
        link.push(status(ProjectGitStatus("p1", "", available = false, reason = "this project has no directory configured")))
        waitUntil(timeoutMillis = 10_000) { controller.state.value.projectGit.containsKey("p1") }
        onNodeWithText("Git unknown: this project has no directory configured").assertExists()
        onNodeWithText("Working tree clean. Nothing to commit.").assertDoesNotExist()

        link.push(status(ProjectGitStatus("p1", "/srv/alpha", available = true, repoRoot = "/srv/alpha", branch = "main", head = "abcdef012345")))
        waitUntil(timeoutMillis = 10_000) { controller.state.value.projectGit["p1"]?.available == true }
        onNodeWithText("Working tree clean. Nothing to commit.").assertExists()
    }

    @Test fun `a daemon older than 1_36 is sent no git frame and the panel says why`() = withPanel("1.35.0", 100) { link, controller ->
        link.push(projects(Project("p1", "Alpha", directory = "/srv/alpha")))
        waitUntil(timeoutMillis = 10_000) { controller.state.value.activeProjectId == "p1" }
        onNodeWithText("This daemon does not support the project git view (needs contract 1.36.0).").assertExists()
        Thread.sleep(400)
        assertTrue(link.sent.none { it.type.startsWith("project_git") }, "no project_git frame goes to an older daemon: ${link.sent.map { it.type }}")
    }

    /** Owner bug 2026-10-19: a narrow panel wrapped a long path and drew "untracked" one letter per line. */
    @Test fun `in a narrow panel a long path stays on one line, keeps its extension, and the status stays horizontal`() = withPanel("1.36.0", 60_000, width = 200) { link, controller ->
        link.push(projects(Project("p1", "Alpha", directory = "/srv/alpha")))
        waitUntil(timeoutMillis = 10_000) { link.sentOf(Envelope.PROJECT_GIT_STATUS).isNotEmpty() }
        val long = ".notes/research/2026-10-10-koog-vs-infantry-verdict.md"
        link.push(status(dirty.copy(changes = listOf(ChangedFile(long, ChangeKind.UNTRACKED, staged = false)))))
        waitUntil(timeoutMillis = 10_000) { controller.state.value.projectGit["p1"]?.changes?.size == 1 }
        waitForIdle()

        onNodeWithText(".md").assertIsDisplayed()
        val label = onNodeWithText("untracked").assertIsDisplayed().fetchSemanticsNode().size
        assertTrue(label.width > label.height, "the status label is drawn horizontally: $label")
        // The path cell and the one-letter badge share a font: one line of path is no taller than the badge.
        val badge = onNodeWithText("?").fetchSemanticsNode().size.height
        val path = onNodeWithText(long).fetchSemanticsNode().size.height
        assertTrue(path <= badge, "the path does not wrap: path $path px vs badge $badge px")
    }

    private fun worktreeList(l: ProjectGitWorktreeList) = Envelope(Envelope.PROJECT_GIT_WORKTREE_LIST, json.encodeToString(ProjectGitWorktreeList.serializer(), l))
    private fun worktreeStatus(w: ProjectGitWorktreeStatus) = Envelope(Envelope.PROJECT_GIT_WORKTREE, json.encodeToString(ProjectGitWorktreeStatus.serializer(), w))
    private val alphaWorktrees = ProjectGitWorktreeList(
        "p1", available = true, worktrees = listOf(GitWorktree("/srv/alpha", branch = "main", main = true, project = true)), selected = "/srv/alpha",
    )
    private val alphaStatus = ProjectGitWorktreeStatus("p1", "/srv/alpha", available = true, branch = "main", head = "1a2b3c4d5e6f")

    /** A 1.88.0 panel on project p1 showing its /srv/alpha worktree, once its first status poll went out. */
    private fun androidx.compose.ui.test.ComposeUiTest.showAlpha(link: ChangesFakeLink, controller: Controller) {
        link.push(projects(Project("p1", "Alpha", directory = "/srv/alpha")))
        waitUntil(timeoutMillis = 10_000) { controller.state.value.activeProjectId == "p1" }
        controller.selectChangesWorktree("p1", "/srv/alpha")
        waitUntil(timeoutMillis = 10_000) { link.sentOf(Envelope.PROJECT_GIT_WORKTREE_STATUS).isNotEmpty() }
    }

    /**
     * Waits for [cond] and fails when it took half a poll timeout of the panel's (virtual) clock: then the timeout, not
     * the reply, would have let the poll go. A waitUntil runs that clock far faster than the wall clock.
     */
    private fun androidx.compose.ui.test.ComposeUiTest.promptly(what: String, cond: () -> Boolean) {
        val from = mainClock.currentTime
        waitUntil(timeoutMillis = 10_000) { cond() }
        val took = mainClock.currentTime - from
        assertTrue(took < PROJECT_CHANGES_POLL_TIMEOUT_MS / 2, "$what took $took ms of the panel's clock")
    }

    /**
     * BUG-CHANGES-BRANCH-1: the daemon answers a connection's frames in order, so polls sent on every tick without
     * waiting piled up behind a slow git (409 unanswered under load) and a branch pick waited behind all of them.
     */
    @Test fun `a 1_88 poll is asked again only after the daemon replied to the last one`() = withPanel("1.88.0", 100) { link, controller ->
        showAlpha(link, controller)
        val statusPolls = { link.sentOf(Envelope.PROJECT_GIT_WORKTREE_STATUS).size }
        val listPolls = { link.sentOf(Envelope.PROJECT_GIT_WORKTREES).size }

        // Thirty status periods (five of the list's) and no reply: still one request of each.
        mainClock.advanceTimeBy(30 * 100L)
        assertEquals(1, statusPolls(), "one worktree status outstanding: ${link.sent.map { it.type }}")
        assertEquals(1, listPolls(), "one worktree list outstanding: ${link.sent.map { it.type }}")

        // Another project's answer, or another request's error, is not the reply.
        link.push(worktreeStatus(alphaStatus.copy(projectId = "p2")))
        link.push(Envelope(Envelope.ERROR, "${Envelope.PROJECT_GIT_BRANCH_CHANGES} refused: no local branch side"))
        mainClock.advanceTimeBy(30 * 100L)
        assertEquals(1, statusPolls(), "only the reply lets the next poll go: ${link.sent.map { it.type }}")

        // The answer lets the next poll go, and so does the daemon's error for that request.
        link.push(worktreeStatus(alphaStatus))
        promptly("the poll after the answer") { statusPolls() == 2 }
        link.push(Envelope(Envelope.ERROR, "${Envelope.PROJECT_GIT_WORKTREE_STATUS} failed: git timed out"))
        promptly("the poll after the error") { statusPolls() == 3 }
        link.push(worktreeList(alphaWorktrees))
        promptly("the list poll after its answer") { listPolls() == 2 }
        mainClock.advanceTimeBy(30 * 100L)
        assertEquals(3, statusPolls(), "the third poll waits for its own reply: ${link.sent.map { it.type }}")
    }

    @Test fun `a 1_88 poll whose reply never comes is asked again after the timeout, or at once on a new link`() = withPanel("1.88.0", 100) { link, controller ->
        showAlpha(link, controller)
        val statusPolls = { link.sentOf(Envelope.PROJECT_GIT_WORKTREE_STATUS).size }

        mainClock.advanceTimeBy(PROJECT_CHANGES_POLL_TIMEOUT_MS - 1_000)
        assertEquals(1, statusPolls(), "no second poll before the timeout: ${link.sent.map { it.type }}")
        mainClock.advanceTimeBy(1_000 + 2 * 100L)
        waitUntil(timeoutMillis = 10_000) { statusPolls() == 2 }

        // A new link ends the wait too: nothing asked on the old one is answered on it. A Connect to the same daemon keeps
        // the link live throughout, so the panel never sees it down and only the new link's `ready` lets the next poll go.
        link.push(Envelope(Envelope.READY, "contract=1.88.0 provider=stub instance=i1"))
        promptly("the poll on the new link") { statusPolls() == 3 }
    }
}
