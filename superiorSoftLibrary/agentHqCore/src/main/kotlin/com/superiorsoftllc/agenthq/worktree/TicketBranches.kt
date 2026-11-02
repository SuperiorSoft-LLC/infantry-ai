package com.superiorsoftllc.agenthq.worktree

import java.io.File

/**
 * One ticket's request. [recordedBranch] and [recordedBase] are what the ticket already holds from an
 * earlier start (both null for a ticket never started); the worktree path is never stored, it is
 * recomputed from the branch, the same convention as [GitWorktreeManager].
 */
data class TicketBranchRequest(
    val ticketId: String,
    val title: String,
    val repo: File,
    val baseBranch: String,
    val recordedBranch: String? = null,
    val recordedBase: String? = null,
)

sealed interface TicketBranchOutcome {
    /**
     * [note] says what a FRESH cut came from (`cut from <ref> <short sha>`), for the start's answer; null when
     * the start reused the branch the ticket already had. [warning] is anything the worktree still lacks (no Android
     * SDK found), kept apart so a caller can qualify [note] (the setting that chose the base) without tagging the warning.
     */
    data class Ready(val worktree: TicketWorktree, val note: String? = null, val warning: String? = null) : TicketBranchOutcome
    data class Refused(val reason: String) : TicketBranchOutcome
}

/**
 * BRANCH-PER-TASK 1/3: starting a ticket gives it exactly one branch and one worktree.
 *
 * - A ticket never started cuts `ticket/<short-id>-<slug>` in `<repo>/.worktrees/` off the COMMIT that
 *   [TicketBranchRequest.baseBranch] resolves to (the ref [TicketBaseRef] chose). The short id is the ticket
 *   id without its `ticket-` prefix (8 characters); the slug comes from the title.
 * - BUG-REGRESS-1: the cut takes that commit and nothing else, so the base checkout's working state never
 *   matters: uncommitted changes, tracked or not, stay where they are and never refuse a start. The outcome's
 *   note names the ref and the commit, so the start's answer says what the ticket's branch holds.
 * - A ticket that already holds a branch REUSES it: the worktree is re-attached to the existing branch
 *   when its directory is gone. It never cuts a second branch for the same ticket.
 * - A base is refused, with the reason, when it does not resolve to a commit.
 * - A branch that already exists under a ticket's name, with no record on the ticket, is refused rather
 *   than adopted: its base is unknown.
 *
 * Git runs through [git] (never JGit), and nothing here pushes or merges.
 */
class TicketBranches(
    private val git: GitCli = SystemGitCli(),
    private val worktreesDir: (repoRoot: File) -> File = { File(it, ".worktrees") },
    private val provisioners: WorktreeProvisions = WorktreeProvisions.default(),
) {

    fun ensure(request: TicketBranchRequest): TicketBranchOutcome {
        val root = mainRoot(request.repo)
        if (!git.isRepo(root)) return TicketBranchOutcome.Refused("not a git repository: ${root.path}")

        val recordedBranch = request.recordedBranch
        if (recordedBranch != null) {
            return reattach(request, root, recordedBranch, request.recordedBase)
        }

        val base = resolveCommit(root, request.baseBranch)
            ?: return TicketBranchOutcome.Refused(
                "base branch '${request.baseBranch}' does not resolve to a commit in ${root.path}",
            )

        val branch = branchFor(request.ticketId, request.title)
        if (git.run(root, listOf("rev-parse", "--verify", "--quiet", "refs/heads/$branch")).ok) {
            return TicketBranchOutcome.Refused(
                "branch '$branch' already exists but the ticket has no record of it; not adopting a branch of unknown base",
            )
        }
        val path = pathFor(root, branch)
        if (path.exists()) return TicketBranchOutcome.Refused("worktree path already exists: ${path.path}")

        val added = git.run(root, listOf("worktree", "add", "-b", branch, path.absolutePath, base))
        if (!added.ok) return TicketBranchOutcome.Refused("git worktree add failed: ${added.err()}")
        return TicketBranchOutcome.Ready(
            TicketWorktree(branch = branch, base = base, path = path.absolutePath, repoRoot = root.absolutePath),
            note = "cut from ${request.baseBranch} ${base.take(SHORT_SHA)}",
            warning = provisioners.provision(root, path),
        )
    }

    /** The ticket already has its branch: reuse it, never cut a second one. */
    private fun reattach(
        request: TicketBranchRequest,
        root: File,
        branch: String,
        recordedBase: String?,
    ): TicketBranchOutcome {
        val path = pathFor(root, branch)
        val base = recordedBase ?: resolveCommit(root, branch)
            ?: return TicketBranchOutcome.Refused("recorded branch '$branch' is missing and has no recorded base")
        val ready = TicketBranchOutcome.Ready(
            TicketWorktree(branch = branch, base = base, path = path.absolutePath, repoRoot = root.absolutePath),
        )
        if (File(path, ".git").exists()) return ready

        val reattachedReady = { ready.copy(warning = provisioners.provision(root, path)) }

        val branchExists = git.run(root, listOf("rev-parse", "--verify", "--quiet", "refs/heads/$branch")).ok
        val args = if (branchExists) {
            listOf("worktree", "add", path.absolutePath, branch)
        } else {
            // The branch was deleted by hand: re-create it at the commit the ticket recorded, not at today's base.
            listOf("worktree", "add", "-b", branch, path.absolutePath, base)
        }
        val result = git.run(root, args)
        if (!result.ok) return TicketBranchOutcome.Refused("git worktree add failed for '$branch': ${result.err()}")
        return reattachedReady()
    }

    private fun pathFor(root: File, branch: String): File =
        File(worktreesDir(root), branch.replace('/', '-'))

    /**
     * BASE-1a: the commit [ref] resolves to where a ticket start in [repo] cuts ([mainRoot]), or null. The SAME
     * resolution [ensure] runs, so a base accepted at set time is a base a cut can use.
     */
    fun resolveBase(repo: File, ref: String): String? = resolveCommit(mainRoot(repo), ref)

    /** BASE-1a: [dir]'s shared git directory (`--git-common-dir`, canonical), or null when [dir] is not in a repository. */
    fun commonDir(dir: File): File? {
        if (!dir.isDirectory) return null
        val result = git.run(dir, listOf("rev-parse", "--path-format=absolute", "--git-common-dir"))
        if (!result.ok) return null
        val common = result.out().lineSequence().firstOrNull()?.trim().orEmpty()
        return common.takeIf { it.isNotEmpty() }?.let { File(it).canonicalFile }
    }

    private fun resolveCommit(cwd: File, ref: String): String? {
        val result = git.run(cwd, listOf("rev-parse", "--verify", "--quiet", "$ref^{commit}"))
        if (!result.ok) return null
        return result.out().lineSequence().firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * The main checkout's root, even when [repo] is itself a linked worktree (`--git-common-dir`): where a ticket's
     * worktree goes and where its base ref resolves. The daemon's boot line names it through here (BUG-REGRESS-1).
     */
    fun mainRoot(repo: File): File {
        val result = git.run(repo, listOf("rev-parse", "--path-format=absolute", "--git-common-dir"))
        val common = if (result.ok) result.out().lineSequence().firstOrNull()?.trim().orEmpty() else ""
        if (common.isNotEmpty()) {
            val dir = File(common)
            if (dir.isDirectory && dir.name == ".git") return dir.parentFile ?: repo.canonicalFile
        }
        return repo.canonicalFile
    }

    companion object {
        /** How much of a commit id a note shows. */
        private const val SHORT_SHA = 8

        /** `ticket/<short-id>-<slug>`, where the slug is the title in lowercase words, never empty. */
        fun branchFor(ticketId: String, title: String): String {
            val shortId = ticketId.removePrefix("ticket-").lowercase()
                .filter { it.isLetterOrDigit() }.take(8).ifEmpty { "ticket" }
            return "ticket/$shortId-${slug(title)}"
        }

        internal fun slug(title: String): String {
            val cleaned = title.lowercase()
                .map { if (it.isLetterOrDigit()) it else '-' }
                .joinToString("")
                .split('-')
                .filter { it.isNotEmpty() }
                .joinToString("-")
                .take(40)
                .trim('-')
            return cleaned.ifEmpty { "task" }
        }
    }
}
