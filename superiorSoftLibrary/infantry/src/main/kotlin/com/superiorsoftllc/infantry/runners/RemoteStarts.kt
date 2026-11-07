// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.runners

import com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerEvent
import com.superiorsoftllc.infantry.daemon.api.orchestrator.StartWorktree
import com.superiorsoftllc.infantry.worktree.GitCli
import com.superiorsoftllc.infantry.worktree.SystemGitCli
import com.superiorsoftllc.infantry.worktree.TicketBranchOutcome
import com.superiorsoftllc.infantry.worktree.TicketBranchRequest
import com.superiorsoftllc.infantry.worktree.TicketBranches
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * RUNNER-POOL-6 (docs/RUNNER-POOL.md section 4): the RUNNER side of a start its orchestrator sends with a git remote.
 *
 * * [checkout] clones the remote into the clone cache ([cloneRoot], one folder per project) or fetches it when the clone exists,
 *   then cuts the ticket's worktree with the same [TicketBranches] code a local start uses: a fresh cut from `origin/<base>` (the
 *   remote's view, fetched just now), or the ticket's recorded branch (fetched from `origin/<branch>` when this clone lacks it).
 * * [started] remembers a session it cut a worktree for; [onTurnEnd] pushes that session's ticket branch to `origin` after every
 *   finished turn (the orchestrator merges from the remote, never from this disk) and queues a [RunnerEvent.FINISHED] event.
 * * [events] answers the orchestrator's `events` reads: the last [eventsKept] events of sessions it started, after a seq.
 *
 * Nothing here takes a path from the wire: the clone folder comes from the project id (or the remote's hash), the worktree from
 * [TicketBranches]. Git runs through [git]; a hung git is bounded by [SystemGitCli]'s timeout.
 */
class RemoteStarts(
    private val cloneRoot: () -> File,
    private val eventsKept: () -> Int,
    private val git: GitCli = SystemGitCli(),
    private val log: (String) -> Unit = { System.err.println(it) },
) {
    private val branches = TicketBranches(git)
    private val sessions = ConcurrentHashMap<String, StartWorktree>()
    private val linked = ConcurrentHashMap.newKeySet<String>()
    private val outbox = ArrayDeque<RunnerEvent>()
    private var seq = 0L

    /** The clone, fetch and cut for one remote start; a refusal throws [IllegalArgumentException] with the reason. */
    fun checkout(
        gitRemote: String,
        projectId: String?,
        ticketId: String,
        title: String,
        base: String?,
        branch: String?,
        recordedBase: String? = null,
    ): StartWorktree {
        // Owner decision (RUNNER-POOL-6): a runner works and pushes only ticket branches, never the dev branch or another ref.
        require(branch == null || isTicketBranch(branch)) { "a runner works only on ${TicketBranches.BRANCH_PREFIX}* branches, not '$branch'" }
        val dir = File(cloneRoot(), folderFor(projectId, gitRemote))
        if (!File(dir, ".git").exists()) {
            dir.parentFile?.mkdirs()
            val cloned = git.run(dir.parentFile ?: dir, listOf("clone", "--no-checkout", gitRemote, dir.absolutePath))
            require(cloned.ok) { "git clone of the project's remote failed: ${cloned.err()}" }
        } else {
            git.run(dir, listOf("remote", "set-url", "origin", gitRemote))
            val fetched = git.run(dir, listOf("fetch", "--prune", "origin"))
            require(fetched.ok) { "git fetch of the project's remote failed: ${fetched.err()}" }
        }
        if (branch != null && !git.run(dir, listOf("rev-parse", "--verify", "--quiet", "refs/heads/$branch")).ok &&
            git.run(dir, listOf("rev-parse", "--verify", "--quiet", "refs/remotes/origin/$branch")).ok
        ) {
            git.run(dir, listOf("branch", branch, "origin/$branch"))
        }
        val baseRef = base?.takeIf { it.isNotBlank() }?.let { b ->
            listOf("origin/$b", b).firstOrNull { git.run(dir, listOf("rev-parse", "--verify", "--quiet", "$it^{commit}")).ok }
        } ?: base.orEmpty()
        val request = TicketBranchRequest(ticketId, title, dir, baseRef, recordedBranch = branch, recordedBase = recordedBase)
        return when (val outcome = branches.ensure(request)) {
            is TicketBranchOutcome.Ready -> outcome.worktree.let {
                require(isTicketBranch(it.branch)) { "a runner works only on ${TicketBranches.BRANCH_PREFIX}* branches, not '${it.branch}'" }
                StartWorktree(it.path, it.branch, it.base, it.repoRoot)
            }
            is TicketBranchOutcome.Refused -> throw IllegalArgumentException(outcome.reason)
        }
    }

    /** Session [sessionId] runs in [worktree], cut by [checkout] for its orchestrator. */
    fun started(sessionId: String, worktree: StartWorktree) {
        sessions[sessionId] = worktree
    }

    /** RUNNER-POOL-7: session [sessionId] was started over the link (with or without a git remote); its agent frames are forwarded. */
    fun linked(sessionId: String) {
        linked += sessionId
    }

    /** RUNNER-POOL-7: whether [sessionId] is a session the paired orchestrator started here (with or without a git remote). */
    fun isLinked(sessionId: String): Boolean = sessionId in linked || sessions.containsKey(sessionId)

    /** Whether [sessionId] was started by the orchestrator over the link. */
    fun isRemote(sessionId: String): Boolean = sessions.containsKey(sessionId)

    /** A status change of one session; queued as an event only for a session the orchestrator started. */
    fun onStatus(sessionId: String, state: String) {
        if (sessions.containsKey(sessionId)) add(sessionId, RunnerEvent.STATUS, state)
    }

    /** A turn of [sessionId] ended with [code]: its ticket branch is pushed to `origin`, then the event is queued. */
    fun onTurnEnd(sessionId: String, code: Int?) {
        val wt = sessions[sessionId] ?: return
        if (!isTicketBranch(wt.branch)) {
            log("[infantry] runner: push of ${wt.branch} for session $sessionId refused: only ${TicketBranches.BRANCH_PREFIX}* branches are pushed")
            add(sessionId, RunnerEvent.FINISHED, code?.toString().orEmpty())
            return
        }
        val pushed = git.run(File(wt.path), listOf("push", "origin", "${wt.branch}:refs/heads/${wt.branch}"))
        if (!pushed.ok) log("[infantry] runner: push of ${wt.branch} for session $sessionId failed: ${pushed.err()}")
        add(sessionId, RunnerEvent.FINISHED, code?.toString().orEmpty())
    }

    /** The session ended for good (killed or removed): no more pushes for it. */
    fun forget(sessionId: String) {
        sessions.remove(sessionId)
        linked.remove(sessionId)
    }

    @Synchronized
    fun events(sinceSeq: Long): List<RunnerEvent> = outbox.filter { it.seq > sinceSeq }

    @Synchronized
    private fun add(sessionId: String, kind: String, payload: String) {
        outbox.addLast(RunnerEvent(sessionId, ++seq, kind, payload))
        val keep = eventsKept().coerceAtLeast(1)
        while (outbox.size > keep) outbox.removeFirst()
    }

    companion object {
        /** The default of `runners.cloneRoot`: under the runner's state dir. */
        const val DEFAULT_FOLDER = "clones"

        /** Whether [branch] is a ticket branch (the only refs a runner pushes): under the prefix, with no `..` or ref tricks. */
        fun isTicketBranch(branch: String): Boolean =
            branch.startsWith(TicketBranches.BRANCH_PREFIX) && branch.length > TicketBranches.BRANCH_PREFIX.length &&
                ".." !in branch && branch.none { it.isWhitespace() || it in ":^~?*[\\" }
        private val SAFE = Regex("[^A-Za-z0-9._-]")
        private const val HASH_CHARS = 16

        /** One folder per project: the project id made path-safe, else a hash of the remote URL. Never a path from the wire. */
        fun folderFor(projectId: String?, gitRemote: String): String =
            projectId?.takeIf { it.isNotBlank() }?.replace(SAFE, "_")?.trim('.')?.takeIf { it.isNotEmpty() }
                ?: ("remote-" + MessageDigest.getInstance("SHA-256").digest(gitRemote.toByteArray()).joinToString("") { "%02x".format(it) }.take(HASH_CHARS))
    }
}
