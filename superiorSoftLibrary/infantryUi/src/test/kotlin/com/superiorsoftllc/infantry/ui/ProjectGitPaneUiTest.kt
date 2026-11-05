// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.plugins.official.git.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.infantry.ui.net.Controller
import com.superiorsoftllc.infantry.ui.net.DaemonClient
import com.superiorsoftllc.infantry.ui.net.PROJECT_BASE_UNSUPPORTED
import com.superiorsoftllc.infantry.ui.net.PROJECT_GIT_CHECKOUT_UNSUPPORTED
import com.superiorsoftllc.infantry.ui.protocol.ChangeKind
import com.superiorsoftllc.infantry.ui.protocol.ChangedFile
import com.superiorsoftllc.infantry.ui.protocol.Envelope
import com.superiorsoftllc.infantry.ui.protocol.GitBranch
import com.superiorsoftllc.infantry.ui.protocol.GitCommit
import com.superiorsoftllc.infantry.ui.protocol.Project
import com.superiorsoftllc.infantry.ui.protocol.ProjectGitBranchList
import com.superiorsoftllc.infantry.ui.protocol.ProjectGitCheckoutResult
import com.superiorsoftllc.infantry.ui.protocol.ProjectGitHistory
import com.superiorsoftllc.infantry.ui.protocol.ProjectGitPatch
import com.superiorsoftllc.infantry.ui.protocol.ProjectGitStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A daemon link with no network: records every frame the controller sends, and lets the test push frames in. */
private class GitFakeLink(scope: CoroutineScope) : DaemonClient(scope) {
    val sent = CopyOnWriteArrayList<Envelope>()
    private val incoming = MutableSharedFlow<Envelope>(extraBufferCapacity = 64)
    override val frames: SharedFlow<Envelope> get() = incoming
    override suspend fun send(envelope: Envelope): Boolean { sent += envelope; return true }
    fun push(e: Envelope) {
        val deadline = System.currentTimeMillis() + 10_000
        while (incoming.subscriptionCount.value == 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        check(incoming.tryEmit(e)) { "frame buffer full" }
    }
    fun sentOf(type: String) = sent.filter { it.type == type }
}

/**
 * CP-2 slice (c): the Git pane, driven like a person drives it. The daemon side is a fake link (frames pushed in,
 * requests recorded), so the assertions are exactly "what the window asked" and "what it drew".
 */
@OptIn(ExperimentalTestApi::class)
class ProjectGitPaneUiTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun projects(vararg p: Project) = Envelope(Envelope.PROJECTS, json.encodeToString(ListSerializer(Project.serializer()), p.toList()))
    private fun status(s: ProjectGitStatus) = Envelope(Envelope.PROJECT_GIT, json.encodeToString(ProjectGitStatus.serializer(), s))
    private fun patch(p: ProjectGitPatch) = Envelope(Envelope.PROJECT_GIT_PATCH, json.encodeToString(ProjectGitPatch.serializer(), p))
    private fun history(h: ProjectGitHistory) = Envelope(Envelope.PROJECT_GIT_HISTORY, json.encodeToString(ProjectGitHistory.serializer(), h))

    private var picker: DirectoryPicker? = null

    private fun withPane(contract: String, body: androidx.compose.ui.test.ComposeUiTest.(GitFakeLink, Controller) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val link = GitFakeLink(scope)
            val controller = Controller(scope, link)
            runDesktopComposeUiTest(900, 900) {
                setContent {
                    val state by controller.state.collectAsState()
                    CompositionLocalProvider(LocalDirectoryPicker provides picker) { ProjectGitPane(state, controller) }
                }
                link.push(Envelope(Envelope.READY, "contract=$contract provider=stub instance=i1"))
                waitUntil(timeoutMillis = 10_000) { controller.state.value.connection.isLive }
                body(link, controller)
            }
        } finally {
            scope.cancel()
        }
    }

    @Test fun `the pane asks for the active project's tree and history, and draws what the daemon answers`() = withPane("1.36.0") { link, controller ->
        onNodeWithText("Select a project to see its git tree.").assertExists()

        link.push(projects(Project("p1", "Alpha", directory = "/srv/alpha")))
        waitUntil(timeoutMillis = 10_000) { link.sentOf(Envelope.PROJECT_GIT_STATUS).any { it.data == "p1" } }
        waitUntil(timeoutMillis = 10_000) { link.sentOf(Envelope.PROJECT_GIT_LOG).any { it.data.contains("\"p1\"") } }

        link.push(
            status(
                ProjectGitStatus(
                    "p1", "/srv/alpha", available = true, repoRoot = "/srv/alpha", branch = "main", head = "1a2b3c4d5e6f",
                    changes = listOf(ChangedFile("README.md", ChangeKind.MODIFIED, staged = false), ChangedFile("fresh.txt", ChangeKind.UNTRACKED, staged = false)),
                ),
            ),
        )
        link.push(history(ProjectGitHistory("p1", available = true, commits = listOf(GitCommit("a".repeat(40), "aaaaaaaa", "Ann", 1_700_000_000, "add the readme")))))
        waitUntil(timeoutMillis = 10_000) { controller.state.value.projectGitHistory.containsKey("p1") }

        onNodeWithText("branch main @ 1a2b3c4d").assertExists()
        onNodeWithText("README.md", substring = true).assertExists()
        onNodeWithText("fresh.txt", substring = true).assertExists()
        onNodeWithText("add the readme", substring = true).assertExists()

        // Picking a changed file asks for ITS diff, on the side it was listed under, and draws the answer.
        onNodeWithText("README.md", substring = true).performScrollTo().performClick()
        waitUntil(timeoutMillis = 10_000) { link.sentOf(Envelope.PROJECT_GIT_DIFF).isNotEmpty() }
        val asked = link.sentOf(Envelope.PROJECT_GIT_DIFF).last().data
        assertTrue(asked.contains("\"README.md\"") && asked.contains("\"projectId\":\"p1\"") && asked.contains("\"staged\":false"), "diff request names the file: $asked")
        link.push(patch(ProjectGitPatch("p1", "README.md", staged = false, available = true, patch = "@@ -1 +1 @@\n-hello\n+edited by the pane test\n")))
        waitUntil(timeoutMillis = 10_000) { controller.state.value.projectGitPatch.containsKey("p1") }
        onNodeWithText("+edited by the pane test").performScrollTo().assertExists()
    }

    @Test fun `an unreadable tree is stated as unknown with the daemon's reason and is never drawn as clean`() = withPane("1.36.0") { link, controller ->
        link.push(projects(Project("p1", "Alpha")))
        waitUntil(timeoutMillis = 10_000) { link.sentOf(Envelope.PROJECT_GIT_STATUS).isNotEmpty() }
        link.push(status(ProjectGitStatus("p1", "", available = false, reason = "this project has no directory configured")))
        link.push(history(ProjectGitHistory("p1", available = false, reason = "this project has no directory configured")))
        waitUntil(timeoutMillis = 10_000) { controller.state.value.projectGit.containsKey("p1") }

        onNodeWithText("Git unknown: this project has no directory configured").assertExists()
        onNodeWithText("Working tree clean. Nothing to commit.").assertDoesNotExist()
        onNodeWithText("History unknown: this project has no directory configured").assertExists()
    }

    @Test fun `a known clean tree says clean, and a repository with no commit says so`() = withPane("1.36.0") { link, controller ->
        link.push(projects(Project("p1", "Alpha", directory = "/srv/alpha")))
        waitUntil(timeoutMillis = 10_000) { link.sentOf(Envelope.PROJECT_GIT_STATUS).isNotEmpty() }
        link.push(status(ProjectGitStatus("p1", "/srv/alpha", available = true, repoRoot = "/srv/alpha", branch = "main", head = null)))
        link.push(history(ProjectGitHistory("p1", available = true)))
        waitUntil(timeoutMillis = 10_000) { controller.state.value.projectGitHistory.containsKey("p1") }

        onNodeWithText("Working tree clean. Nothing to commit.").assertExists()
        onNodeWithText("No commits yet.").assertExists()
    }

    @Test fun `saving a directory sends an edit naming the project, and a refusal shows inline`() = withPane("1.36.0") { link, controller ->
        link.push(projects(Project("p1", "Alpha")))
        waitUntil(timeoutMillis = 10_000) { controller.state.value.activeProjectId == "p1" }

        onNodeWithText("Project directory").performTextReplacement("C:\\work\\alpha")
        onNodeWithText("Save directory").performClick()
        waitUntil(timeoutMillis = 10_000) { link.sentOf(Envelope.PROJECT_SAVE).isNotEmpty() }
        assertEquals("""{"id":"p1","directory":"C:\\work\\alpha"}""", link.sentOf(Envelope.PROJECT_SAVE).single().data)

        link.push(Envelope(Envelope.ERROR, "project_save refused: directory is not a directory that exists on the daemon's machine: 'C:\\work\\alpha'"))
        waitUntil(timeoutMillis = 10_000) { controller.state.value.notices.isNotEmpty() }
        onNodeWithText("project_save refused: directory is not a directory that exists on the daemon's machine: 'C:\\work\\alpha'").assertExists()

        // The daemon's `project` answer carries the saved directory, and a changed directory re-reads the tree.
        val before = link.sentOf(Envelope.PROJECT_GIT_STATUS).size
        link.push(Envelope(Envelope.PROJECT, json.encodeToString(Project.serializer(), Project("p1", "Alpha", directory = "C:\\work\\alpha"))))
        waitUntil(timeoutMillis = 10_000) { link.sentOf(Envelope.PROJECT_GIT_STATUS).size > before }
        onNodeWithText("Directory: C:\\work\\alpha").assertExists()
    }

    @Test fun `Browse fills the box, saves the chosen path, and a cancel changes nothing`() {
        var answer: String? = "/picked/dir"
        picker = DirectoryPicker { answer }
        try {
            withPane("1.36.0") { link, controller ->
                link.push(projects(Project("p1", "Alpha")))
                waitUntil(timeoutMillis = 10_000) { controller.state.value.activeProjectId == "p1" }
                answer = null
                onNodeWithTag(GIT_DIRECTORY_FIELD_TAG + PATH_FIELD_BROWSE_TAG_SUFFIX).performClick()
                Thread.sleep(300)
                assertTrue(link.sentOf(Envelope.PROJECT_SAVE).isEmpty(), "cancelled picker saves nothing")
                answer = "/picked/dir"
                onNodeWithTag(GIT_DIRECTORY_FIELD_TAG + PATH_FIELD_BROWSE_TAG_SUFFIX).performClick()
                waitUntil(timeoutMillis = 10_000) { link.sentOf(Envelope.PROJECT_SAVE).isNotEmpty() }
                assertEquals("""{"id":"p1","directory":"/picked/dir"}""", link.sentOf(Envelope.PROJECT_SAVE).single().data)
            }
        } finally { picker = null }
    }

    @Test fun `no picker, no Browse button, text box remains`() = withPane("1.36.0") { link, controller ->
        link.push(projects(Project("p1", "Alpha")))
        waitUntil(timeoutMillis = 10_000) { controller.state.value.activeProjectId == "p1" }
        onNodeWithTag(GIT_DIRECTORY_FIELD_TAG + PATH_FIELD_BROWSE_TAG_SUFFIX).assertDoesNotExist()
        onNodeWithText("Project directory").assertExists()
    }

    @Test fun `a daemon older than 1_36 is sent no git frame and the pane says why`() = withPane("1.35.0") { link, controller ->
        link.push(projects(Project("p1", "Alpha", directory = "/srv/alpha")))
        waitUntil(timeoutMillis = 10_000) { controller.state.value.activeProjectId == "p1" }
        onNodeWithText("This daemon does not support the project git view (needs contract 1.36.0).").assertExists()
        Thread.sleep(300)
        assertTrue(
            link.sent.none { it.type.startsWith("project_git") },
            "no project_git frame goes to an older daemon: ${link.sent.map { it.type }}",
        )
    }

    // ---- BUG-PGIT-LOAD ------------------------------------------------------------------------------

    @Test fun `selecting another project and saving its directory each ask for its git status`() = withPane("1.36.0") { link, controller ->
        link.push(projects(Project("p1", "Alpha", directory = "/srv/alpha"), Project("p2", "Beta")))
        waitUntil(timeoutMillis = 10_000) { link.sentOf(Envelope.PROJECT_GIT_STATUS).any { it.data == "p1" } }

        runBlocking { controller.selectProject("p2") }
        waitUntil(timeoutMillis = 10_000) { link.sentOf(Envelope.PROJECT_GIT_STATUS).any { it.data == "p2" } }
        val before = link.sentOf(Envelope.PROJECT_GIT_STATUS).count { it.data == "p2" }

        // The daemon's answer to a directory save is the `project` frame carrying the new directory.
        link.push(Envelope(Envelope.PROJECT, json.encodeToString(Project.serializer(), Project("p2", "Beta", directory = "/srv/beta"))))
        waitUntil(timeoutMillis = 10_000) { link.sentOf(Envelope.PROJECT_GIT_STATUS).count { it.data == "p2" } > before }
    }

    @Test fun `a git status the daemon could not answer shows its error, never not read yet`() = withPane("1.36.0") { link, controller ->
        link.push(projects(Project("p1", "Alpha", directory = "C:/work/alpha")))
        waitUntil(timeoutMillis = 10_000) { link.sentOf(Envelope.PROJECT_GIT_STATUS).isNotEmpty() }
        val error = "project_git_status failed: Cannot run program \"git\": CreateProcess error=2"
        link.push(Envelope(Envelope.ERROR, error))
        waitUntil(timeoutMillis = 10_000) { controller.state.value.projectGitFailure != null }

        onNodeWithText("Git unknown: $error").assertExists()
        onNodeWithText("Git unknown: not read yet").assertDoesNotExist()

        // A real answer replaces it.
        link.push(status(ProjectGitStatus("p1", "C:/work/alpha", available = false, reason = "git could not be run by the daemon: x")))
        waitUntil(timeoutMillis = 10_000) { controller.state.value.projectGit.containsKey("p1") }
        onNodeWithText("Git unknown: git could not be run by the daemon: x").assertExists()
    }

    // ---- BRANCH-SWITCH-1 ----------------------------------------------------------------------------

    private fun branches(l: ProjectGitBranchList) = Envelope(Envelope.PROJECT_GIT_BRANCH_LIST, json.encodeToString(ProjectGitBranchList.serializer(), l))
    private fun checkout(r: ProjectGitCheckoutResult) = Envelope(Envelope.PROJECT_GIT_CHECKOUT_RESULT, json.encodeToString(ProjectGitCheckoutResult.serializer(), r))

    @Test fun `Switch on a branch sends project_git_checkout and a refusal shows its reason inline`() = withPane("1.74.0") { link, controller ->
        link.push(projects(Project("p1", "Alpha", directory = "/srv/alpha")))
        waitUntil(timeoutMillis = 10_000) { link.sentOf(Envelope.PROJECT_GIT_BRANCHES).isNotEmpty() }
        link.push(status(ProjectGitStatus("p1", "/srv/alpha", available = true, repoRoot = "/srv/alpha", branch = "main", head = "1a2b3c4d5e6f")))
        link.push(branches(ProjectGitBranchList("p1", available = true, current = "main", branches = listOf(GitBranch("main"), GitBranch("feature")))))
        waitUntil(timeoutMillis = 10_000) { controller.state.value.projectGitBranches.containsKey("p1") }

        // The checked-out branch offers no Switch; the other one does, and clicking it asks the daemon to switch.
        onNodeWithTag(GIT_SWITCH_TAG + "main").assertDoesNotExist()
        onNodeWithTag(GIT_SWITCH_TAG + "feature").performScrollTo().performClick()
        waitUntil(timeoutMillis = 10_000) { link.sentOf(Envelope.PROJECT_GIT_CHECKOUT).isNotEmpty() }
        assertEquals("""{"projectId":"p1","branch":"feature","create":false}""", link.sentOf(Envelope.PROJECT_GIT_CHECKOUT).single().data)

        link.push(
            checkout(
                ProjectGitCheckoutResult(
                    "p1", "feature", switched = false, code = "dirty", dirtyCount = 2, previous = "main", current = "main",
                    reason = "the working tree is not clean: 2 files have uncommitted changes; commit them first",
                ),
            ),
        )
        waitUntil(timeoutMillis = 10_000) { controller.state.value.projectGitCheckout.containsKey("p1") }
        onNodeWithText("Cannot switch to 'feature': the working tree is not clean: 2 files have uncommitted changes; commit them first")
            .performScrollTo().assertExists()
    }

    @Test fun `New branch from current sends a create checkout with the typed name`() = withPane("1.74.0") { link, controller ->
        link.push(projects(Project("p1", "Alpha", directory = "/srv/alpha")))
        waitUntil(timeoutMillis = 10_000) { link.sentOf(Envelope.PROJECT_GIT_BRANCHES).isNotEmpty() }
        link.push(branches(ProjectGitBranchList("p1", available = true, current = "main", branches = listOf(GitBranch("main")))))
        waitUntil(timeoutMillis = 10_000) { controller.state.value.projectGitBranches.containsKey("p1") }

        onNodeWithTag(GIT_NEW_BRANCH_FIELD_TAG).performScrollTo().performTextReplacement("topic/new")
        onNodeWithTag(GIT_NEW_BRANCH_TAG).performClick()
        waitUntil(timeoutMillis = 10_000) { link.sentOf(Envelope.PROJECT_GIT_CHECKOUT).isNotEmpty() }
        assertEquals("""{"projectId":"p1","branch":"topic/new","create":true}""", link.sentOf(Envelope.PROJECT_GIT_CHECKOUT).single().data)
    }

    @Test fun `a daemon older than 1_74 is never sent a checkout and the pane says why`() = withPane("1.73.0") { link, controller ->
        link.push(projects(Project("p1", "Alpha", directory = "/srv/alpha")))
        waitUntil(timeoutMillis = 10_000) { link.sentOf(Envelope.PROJECT_GIT_BRANCHES).isNotEmpty() }
        link.push(branches(ProjectGitBranchList("p1", available = true, current = "main", branches = listOf(GitBranch("main"), GitBranch("feature")))))
        waitUntil(timeoutMillis = 10_000) { controller.state.value.projectGitBranches.containsKey("p1") }

        onNodeWithTag(GIT_SWITCH_TAG + "feature").assertDoesNotExist()
        onNodeWithText(PROJECT_GIT_CHECKOUT_UNSUPPORTED).performScrollTo().assertExists()
        assertTrue(link.sentOf(Envelope.PROJECT_GIT_CHECKOUT).isEmpty())
    }

    // ---- BASE-1b: the base branch new ticket starts cut from ------------------------------------------

    /** STEP 1 (characterization): a 1.81.0 daemon and a project with a repository -- the Git tab offers the control. */
    @Test fun `the Git tab offers a base branch for new tasks control`() = withPane("1.81.0") { link, controller ->
        link.push(projects(Project("p1", "Alpha", directory = "/srv/alpha")))
        waitUntil(timeoutMillis = 10_000) { link.sentOf(Envelope.PROJECT_GIT_BRANCHES).isNotEmpty() }
        link.push(branches(ProjectGitBranchList("p1", available = true, current = "main", branches = listOf(GitBranch("main"), GitBranch("develop")))))
        waitUntil(timeoutMillis = 10_000) { controller.state.value.projectGitBranches.containsKey("p1") }
        onNodeWithText("BASE BRANCH FOR NEW TASKS").assertExists()
    }

    private fun project(p: Project) = Envelope(Envelope.PROJECT, json.encodeToString(Project.serializer(), p))

    /** A 1.81.0 pane on project p1 whose branch list is [names] (current = main), with the dropdown open. */
    private fun androidx.compose.ui.test.ComposeUiTest.openBase(link: GitFakeLink, controller: Controller, names: List<String>, base: String = "") {
        link.push(projects(Project("p1", "Alpha", directory = "/srv/alpha", baseBranch = base)))
        waitUntil(timeoutMillis = 10_000) { link.sentOf(Envelope.PROJECT_GIT_BRANCHES).isNotEmpty() }
        link.push(branches(ProjectGitBranchList("p1", available = true, current = "main", branches = names.map { GitBranch(it) })))
        waitUntil(timeoutMillis = 10_000) { controller.state.value.projectGitBranches.containsKey("p1") }
        onNodeWithTag(GIT_BASE_TOGGLE_TAG).performScrollTo().performClick()
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasTestTag(GIT_BASE_SEARCH_TAG)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun `typing filters 200+ branches case-insensitively, picking one saves it, and Default clears it`() = withPane("1.81.0") { link, controller ->
        val names = listOf("main", "develop", "release/2.0") + (1..220).map { "feature/topic-$it" }
        openBase(link, controller, names)
        onNodeWithTag(GIT_BASE_SUMMARY_TAG).assertTextContains("Not set: new tasks will be refused")
        // Default + every branch, before any typing.
        onNodeWithTag(GIT_BASE_SEARCH_TAG + SELECT_COUNT_SUFFIX).assertTextEquals("${names.size + 1} of ${names.size + 1}")

        onNodeWithTag(GIT_BASE_SEARCH_TAG).performTextReplacement("TOPIC-21")
        // topic-21 and topic-210..219: eleven rows, matched regardless of case.
        onNodeWithTag(GIT_BASE_SEARCH_TAG + SELECT_COUNT_SUFFIX).assertTextEquals("11 of ${names.size + 1}")
        onNodeWithTag(GIT_BASE_ITEM_TAG + "develop").assertDoesNotExist()
        onNodeWithTag(GIT_BASE_SEARCH_TAG).performTextReplacement("zzz")
        onNodeWithTag(GIT_BASE_SEARCH_TAG + SELECT_NO_HIT_SUFFIX).assertExists()

        onNodeWithTag(GIT_BASE_SEARCH_TAG).performTextReplacement("Devel")
        onNodeWithTag(GIT_BASE_SEARCH_TAG + SELECT_COUNT_SUFFIX).assertTextEquals("1 of ${names.size + 1}")
        // The one row left is highlighted; Enter picks it (typing then clicking is the same onPick, covered below).
        onNodeWithTag(GIT_BASE_ITEM_TAG + "develop").assertIsSelected()
        onNodeWithTag(GIT_BASE_SEARCH_TAG).performKeyInput { pressKey(Key.Enter) }
        waitUntil(timeoutMillis = 10_000) { link.sentOf(Envelope.PROJECT_BASE).isNotEmpty() }
        assertEquals("""{"projectId":"p1","branch":"develop"}""", link.sentOf(Envelope.PROJECT_BASE).single().data)
        onNodeWithTag(GIT_BASE_RESULT_TAG).assertTextEquals("Saving…")

        // The daemon's answer is the whole project, now carrying the setting: the button shows it.
        link.push(project(Project("p1", "Alpha", directory = "/srv/alpha", baseBranch = "develop")))
        waitUntil(timeoutMillis = 10_000) { controller.state.value.activeProject?.baseBranch == "develop" }
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasTestTag(GIT_BASE_SUMMARY_TAG) and hasText("develop", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(GIT_BASE_RESULT_TAG).assertDoesNotExist()

        // Default clears it.
        onNodeWithTag(GIT_BASE_TOGGLE_TAG).performScrollTo().performClick()
        onNodeWithTag(GIT_BASE_SEARCH_TAG).performTextReplacement("default")
        onNodeWithTag(GIT_BASE_DEFAULT_ITEM_TAG).assertIsSelected()
        onNodeWithTag(GIT_BASE_SEARCH_TAG).performKeyInput { pressKey(Key.Enter) }
        waitUntil(timeoutMillis = 10_000) { link.sentOf(Envelope.PROJECT_BASE).size == 2 }
        assertEquals("""{"projectId":"p1","clear":true}""", link.sentOf(Envelope.PROJECT_BASE)[1].data)
        link.push(project(Project("p1", "Alpha", directory = "/srv/alpha", baseBranch = "")))
        waitUntil(timeoutMillis = 10_000) { controller.state.value.activeProject?.baseBranch == "" }
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasTestTag(GIT_BASE_SUMMARY_TAG) and hasText("Not set", substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun `a refused base branch shows the daemon's reason inline and the setting is unchanged`() = withPane("1.81.0") { link, controller ->
        openBase(link, controller, listOf("main", "gone"), base = "main")
        onNodeWithTag(GIT_BASE_ITEM_TAG + "gone").performScrollTo().performClick()
        waitUntil(timeoutMillis = 10_000) { link.sentOf(Envelope.PROJECT_BASE).isNotEmpty() }
        val reason = "project_base refused: base branch 'gone' does not resolve to a commit in /srv/alpha"
        link.push(Envelope(Envelope.ERROR, reason))
        waitUntil(timeoutMillis = 10_000) { controller.state.value.notices.any { it.text == reason } }
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasTestTag(GIT_BASE_RESULT_TAG) and hasText(reason)).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(GIT_BASE_RESULT_TAG).performScrollTo().assertTextEquals(reason)
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasTestTag(GIT_BASE_SUMMARY_TAG) and hasText("main", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        // The directory section does not repeat it.
        assertEquals(1, onAllNodes(hasText(reason)).fetchSemanticsNodes().size)
    }

    @Test fun `picking the setting already stored sends nothing`() = withPane("1.81.0") { link, controller ->
        openBase(link, controller, listOf("main", "develop"), base = "develop")
        waitForIdle()
        onNodeWithTag(GIT_BASE_ITEM_TAG + "develop").performScrollTo().performClick()
        Thread.sleep(300)
        assertTrue(link.sentOf(Envelope.PROJECT_BASE).isEmpty())
    }

    @Test fun `with no branch and with one branch the control still offers Default and what exists`() = withPane("1.81.0") { link, controller ->
        openBase(link, controller, emptyList())
        onNodeWithTag(GIT_BASE_SEARCH_TAG + SELECT_COUNT_SUFFIX).assertTextEquals("1 of 1")
        onNodeWithTag(GIT_BASE_DEFAULT_ITEM_TAG).assertExists()

        link.push(branches(ProjectGitBranchList("p1", available = true, current = "main", branches = listOf(GitBranch("main")))))
        waitUntil(timeoutMillis = 10_000) { controller.state.value.projectGitBranches["p1"]?.branches?.size == 1 }
        onNodeWithTag(GIT_BASE_SEARCH_TAG + SELECT_COUNT_SUFFIX).assertTextEquals("2 of 2")
        onNodeWithTag(GIT_BASE_ITEM_TAG + "main").assertTextContains("checked out", substring = true)
    }

    @Test fun `a daemon older than 1_81 is never sent project_base and the pane says why`() = withPane("1.80.0") { link, controller ->
        link.push(projects(Project("p1", "Alpha", directory = "/srv/alpha")))
        waitUntil(timeoutMillis = 10_000) { controller.state.value.activeProjectId == "p1" }
        onNodeWithText(PROJECT_BASE_UNSUPPORTED).performScrollTo().assertExists()
        onNodeWithTag(GIT_BASE_TOGGLE_TAG).assertDoesNotExist()
        assertTrue(link.sentOf(Envelope.PROJECT_BASE).isEmpty())
    }

    @Test fun `with 100+ untracked files the base summary is on screen and the untracked list is collapsed`() = withPane("1.81.0") { link, controller ->
        link.push(projects(Project("p1", "Alpha", directory = "/srv/alpha", baseBranch = "feature-x")))
        waitUntil(timeoutMillis = 10_000) { link.sentOf(Envelope.PROJECT_GIT_STATUS).isNotEmpty() }
        val files = (1..120).map { ChangedFile("untracked-$it.txt", ChangeKind.UNTRACKED, staged = false) }
        link.push(status(ProjectGitStatus("p1", "/srv/alpha", available = true, repoRoot = "/srv/alpha", branch = "main", head = "1a2b3c4d5e6f", changes = files)))
        waitUntil(timeoutMillis = 10_000) { controller.state.value.projectGit.containsKey("p1") }

        // Visible without any scrolling, and the change button works from there.
        onNodeWithTag(GIT_BASE_SUMMARY_TAG).assertIsDisplayed().assertTextContains("Base for new tasks: feature-x")
        onNodeWithTag(GIT_BASE_TOGGLE_TAG).assertIsDisplayed().performClick()
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasTestTag(GIT_BASE_SEARCH_TAG)).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(GIT_BASE_SEARCH_TAG).assertIsDisplayed()
        onNodeWithTag(GIT_BASE_TOGGLE_TAG).performClick()

        // The long list is collapsed (rows absent) until its header is clicked.
        onNodeWithText("untracked-1.txt", substring = true).assertDoesNotExist()
        onNodeWithTag(GIT_LIST_TOGGLE_TAG + "Not staged").performClick()
        onNodeWithText("untracked-1.txt", substring = true).assertExists()
    }
}
