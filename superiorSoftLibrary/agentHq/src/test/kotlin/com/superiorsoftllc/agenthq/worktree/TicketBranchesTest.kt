package com.superiorsoftllc.agenthq.worktree

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * BRANCH-PER-TASK 1/3, against REAL git: starting a ticket cuts exactly one `ticket/<short-id>-<slug>`
 * branch in its own worktree off the configured base's commit, starting it again reuses that branch and
 * worktree, and a missing base is refused with the reason. BUG-REGRESS-1: uncommitted changes in the base
 * checkout never refuse a cut and never reach the ticket's branch.
 */
class TicketBranchesTest {

    private val git = testGit()
    private val branches = TicketBranches(git, provisioners = WorktreeProvisions(listOf(AndroidSdkProvision { if (it == AndroidSdkProvision.SETTING) System.getProperty("java.io.tmpdir") else null })))

    private fun head(repo: File): String = git.run(repo, listOf("rev-parse", "HEAD")).out()

    private fun ticketBranchesIn(repo: File): List<String> =
        git.run(repo, listOf("branch", "--list", "ticket/*")).out().lines()
            .map { it.trim().removePrefix("* ").removePrefix("+ ") }.filter { it.isNotBlank() }

    private fun worktreeCount(repo: File): Int =
        git.run(repo, listOf("worktree", "list", "--porcelain")).out().lines().count { it.startsWith("worktree ") }

    @Test
    fun startingATicket_createsOneBranchAndOneWorktree_recordedWithItsBase() {
        val repo = newGitRepo()
        val base = head(repo)

        val outcome = branches.ensure(TicketBranchRequest("ticket-1a2b3c4d", "Fix the thing", repo, "HEAD"))

        val cut = assertIs<TicketBranchOutcome.Ready>(outcome)
        val ready = cut.worktree
        assertEquals("ticket/1a2b3c4d-fix-the-thing", ready.branch)
        assertEquals(base, ready.base, "the base is the commit the branch was cut from")
        assertEquals("cut from HEAD ${base.take(8)}", cut.note, "a fresh cut says what it was cut from")
        assertEquals(listOf("ticket/1a2b3c4d-fix-the-thing"), ticketBranchesIn(repo))
        assertTrue(File(ready.path, ".git").exists(), "the ticket has its own linked worktree")
        assertEquals(2, worktreeCount(repo), "the main checkout plus exactly one ticket worktree")
    }

    @Test
    fun startingTheSameTicketAgain_reusesTheBranchAndWorktree_neverASecondBranch() {
        val repo = newGitRepo()
        val first = assertIs<TicketBranchOutcome.Ready>(
            branches.ensure(TicketBranchRequest("ticket-1a2b3c4d", "Fix the thing", repo, "HEAD")),
        ).worktree

        // The daemon hands back what it recorded on the ticket the first time.
        val reused = assertIs<TicketBranchOutcome.Ready>(
            branches.ensure(
                TicketBranchRequest(
                    "ticket-1a2b3c4d", "Fix the thing", repo, "HEAD",
                    recordedBranch = first.branch,
                    recordedBase = first.base,
                ),
            ),
        )
        val again = reused.worktree

        assertEquals(null, reused.note, "nothing was cut, so there is no cut note")
        assertEquals(first.branch, again.branch)
        assertEquals(first.base, again.base)
        assertEquals(first.path, again.path)
        assertEquals(1, ticketBranchesIn(repo).size, "still exactly one ticket branch")
        assertEquals(2, worktreeCount(repo), "still exactly one ticket worktree")
    }

    /** BUG-REGRESS-1: the cut takes the base's commit, never the checkout's working state, so dirt never refuses it. */
    @Test
    fun aDirtyBaseCheckout_neverRefusesTheCut_andTheBranchHoldsOnlyCommittedWork() {
        val repo = newGitRepo()
        val base = head(repo)
        // A tracked modification in the base checkout: someone's half-finished work.
        File(repo, "README.md").writeText("half-written\n")
        assertTrue(git.run(repo, listOf("status", "--porcelain", "--untracked-files=no")).out().isNotEmpty(), "the checkout is dirty")

        val outcome = branches.ensure(TicketBranchRequest("ticket-1a2b3c4d", "Fix the thing", repo, "HEAD"))

        val cut = assertIs<TicketBranchOutcome.Ready>(outcome, "a dirty base checkout never refuses the cut: $outcome")
        assertEquals(base, cut.worktree.base, "cut from the base ref's commit")
        assertEquals("cut from HEAD ${base.take(8)}", cut.note)
        assertEquals(listOf("ticket/1a2b3c4d-fix-the-thing"), ticketBranchesIn(repo))
        assertEquals("hello", File(cut.worktree.path, "README.md").readText().trim(), "only committed work is on the ticket branch")
        assertEquals("half-written", File(repo, "README.md").readText().trim(), "the edit stays in the base checkout")
    }

    /** BUG-REGRESS-1: a configured base branch (AGENTHQ_BASE_BRANCH) is cut from, not whatever the checkout has out. */
    @Test
    fun aConfiguredBaseBranch_isCutFromThatBranchsCommit_notTheCheckedOutOne() {
        val repo = newGitRepo()
        val release = head(repo)
        assertTrue(git.run(repo, listOf("branch", "release")).ok, "a release branch at the first commit")
        File(repo, "README.md").writeText("moved on\n")
        assertTrue(git.run(repo, listOf("commit", "-q", "-am", "moved on")).ok, "the checked-out branch moves past it")
        val checkedOut = head(repo)
        assertTrue(checkedOut != release, "the checkout's HEAD is not the release branch")

        val cut = assertIs<TicketBranchOutcome.Ready>(
            branches.ensure(TicketBranchRequest("ticket-1a2b3c4d", "Fix the thing", repo, "release")),
        )

        assertEquals(release, cut.worktree.base, "cut from the configured branch's commit")
        assertEquals("cut from release ${release.take(8)}", cut.note)
        assertEquals("hello", File(cut.worktree.path, "README.md").readText().trim(), "the ticket branch holds the release content")
    }

    @Test
    fun theBaseRef_isAgenthqBaseBranchWhenSet_elseHead() {
        val env = { value: String? -> TicketBaseRef.fromEnv { name -> value.takeIf { name == TicketBaseRef.ENV } } }
        assertEquals("release", env(" release ").refFor(BaseScope("any-project")))
        assertEquals(TicketBaseRef.HEAD, env(null).refFor(BaseScope()))
        assertEquals(TicketBaseRef.HEAD, env("  ").refFor(BaseScope()), "a blank variable is unset")
    }

    @Test
    fun aMissingBase_isRefused_withTheBaseNameInTheReason() {
        val repo = newGitRepo()

        val outcome = branches.ensure(
            TicketBranchRequest("ticket-1a2b3c4d", "Fix the thing", repo, "no-such-base"),
        )

        val refused = assertIs<TicketBranchOutcome.Refused>(outcome)
        assertTrue(refused.reason.contains("no-such-base"), "the reason names the missing base: ${refused.reason}")
        assertEquals(emptyList(), ticketBranchesIn(repo))
    }

    @Test
    fun aBlankTitle_getsAFallbackSlug_andAnUntrackedFileInTheBaseIsNotDirt() {
        val repo = newGitRepo()
        File(repo, "scratch.txt").writeText("an untracked note is not work in the base\n")

        val ready = assertIs<TicketBranchOutcome.Ready>(
            branches.ensure(TicketBranchRequest("ticket-deadbeef", "   ", repo, "HEAD")),
        ).worktree

        assertEquals("ticket/deadbeef-task", ready.branch)
        assertFalse(ready.branch.contains(" "))
    }
}
