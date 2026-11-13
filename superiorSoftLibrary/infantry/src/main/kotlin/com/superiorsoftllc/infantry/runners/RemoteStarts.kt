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
import kotlinx.serialization.json.put
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
 * * [events] answers the orchestrator's `events` reads. RUNNER-POOL-8 (docs/RUNNER-POOL.md 3.1, 5): the outbox numbers each
 *   session's events 1, 2, 3 ... and keeps an event until the orchestrator acknowledges it (`events(acked)`), bounded by
 *   [outboxMax] (`limits.runners.outboxMax`; past it the oldest goes and the orchestrator sees the gap).
 * * [checkCommits] (RUNNER-POOL-8, `runners.pushOnCommit`): pushes the ticket branch of a session started with push-on-commit as
 *   soon as its worker committed, not only at the end of the turn.
 *
 * Nothing here takes a path from the wire: the clone folder comes from the project id (or the remote's hash), the worktree from
 * [TicketBranches]. Git runs through [git]; a hung git is bounded by [SystemGitCli]'s timeout.
 */
class RemoteStarts(
    private val cloneRoot: () -> File,
    private val outboxMax: () -> Int,
    private val git: GitCli = SystemGitCli(),
    private val log: (String) -> Unit = { System.err.println(it) },
    /** RUNNER-POOL-12: where the outbox, the per-session seq counters and the started sessions survive a daemon restart; null = memory only. */
    private val persistTo: File? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * RUNNER-CRED-1: the git a call that talks to the remote runs through when the orchestrator lent a credential: [git] with the
     * extra environment [LentCredentials.gitEnv] builds (an HTTP header via `GIT_CONFIG_*`, never argv or config on disk).
     */
    private val authedGit: (env: Map<String, String>) -> GitCli = { env -> SystemGitCli(extraEnv = env) },
) {
    /** RUNNER-CRED-1: the lent git credential per session, memory only (a restart forgets it; a push then uses the runner's own access). */
    private val gitCredentials = ConcurrentHashMap<String, com.superiorsoftllc.infantry.daemon.api.orchestrator.GitCredential>()

    /** [git], or the credentialed one when [credential] is set. */
    private fun remoteGit(credential: com.superiorsoftllc.infantry.daemon.api.orchestrator.GitCredential?): GitCli =
        if (credential == null) git else authedGit(LentCredentials.gitEnv(credential))

    private val branches = TicketBranches(git)
    private val sessions = ConcurrentHashMap<String, StartWorktree>()
    private val linked = ConcurrentHashMap.newKeySet<String>()
    private val outbox = ArrayDeque<RunnerEvent>()
    /** RUNNER-POOL-8: the last seq given per session. */
    private val seqs = HashMap<String, Long>()
    /** RUNNER-POOL-8: sessions started with push-on-commit -> the HEAD last pushed (blank = not known yet). */
    private val pushOnCommit = ConcurrentHashMap<String, String>()

    init {
        persistTo?.takeIf { it.isFile }?.let { f ->
            runCatching {
                val o = kotlinx.serialization.json.Json.parseToJsonElement(f.readText()) as kotlinx.serialization.json.JsonObject
                fun str(e: kotlinx.serialization.json.JsonElement?) = (e as kotlinx.serialization.json.JsonPrimitive).content
                (o["seqs"] as kotlinx.serialization.json.JsonObject).forEach { (k, v) -> seqs[k] = str(v).toLong() }
                (o["events"] as kotlinx.serialization.json.JsonArray).forEach { e ->
                    val m = e as kotlinx.serialization.json.JsonObject
                    outbox.addLast(RunnerEvent(str(m["s"]), str(m["q"]).toLong(), str(m["k"]), str(m["p"])))
                }
                (o["sessions"] as kotlinx.serialization.json.JsonObject).forEach { (k, v) ->
                    val m = v as kotlinx.serialization.json.JsonObject
                    sessions[k] = StartWorktree(str(m["path"]), str(m["branch"]), str(m["base"]), str(m["repoRoot"]))
                }
            }.onFailure { log("[infantry] runner: could not read the event outbox ${f.name}: ${it.message}") }
        }
    }

    /** Write the outbox, seq counters and started sessions next to the runner's state; best effort (a failure is logged). */
    @Synchronized
    private fun save() {
        val f = persistTo ?: return
        runCatching {
            val j = kotlinx.serialization.json.buildJsonObject {
                put("seqs", kotlinx.serialization.json.JsonObject(seqs.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) }))
                put("events", kotlinx.serialization.json.JsonArray(outbox.map { e ->
                    kotlinx.serialization.json.buildJsonObject { put("s", e.sessionId); put("q", e.seq); put("k", e.kind); put("p", e.payload) }
                }))
                put("sessions", kotlinx.serialization.json.JsonObject(sessions.mapValues { (_, w) ->
                    kotlinx.serialization.json.buildJsonObject { put("path", w.path); put("branch", w.branch); put("base", w.base); put("repoRoot", w.repoRoot) }
                }))
            }
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(j.toString())
            java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }.onFailure { log("[infantry] runner: could not write the event outbox ${f.name}: ${it.message}") }
    }

    /** RUNNER-AGENT-1: clone or fetch the project remote and return the clone (a ticketless start runs in it, no ticket branch). */
    fun cloneDir(gitRemote: String, projectId: String?, credential: com.superiorsoftllc.infantry.daemon.api.orchestrator.GitCredential? = null): File {
        val dir = File(cloneRoot(), folderFor(projectId, gitRemote))
        val remoteGit = remoteGit(credential)
        if (!File(dir, ".git").exists()) {
            dir.parentFile?.mkdirs()
            val cloned = remoteGit.run(dir.parentFile ?: dir, listOf("clone", gitRemote, dir.absolutePath))
            require(cloned.ok) { "git clone of the project's remote failed: ${cloned.err()}" }
        } else {
            git.run(dir, listOf("remote", "set-url", "origin", gitRemote))
            val fetched = remoteGit.run(dir, listOf("fetch", "--prune", "origin"))
            require(fetched.ok) { "git fetch of the project's remote failed: ${fetched.err()}" }
        }
        return dir
    }

    /** The clone, fetch and cut for one remote start; a refusal throws [IllegalArgumentException] with the reason. */
    fun checkout(
        gitRemote: String,
        projectId: String?,
        ticketId: String,
        title: String,
        base: String?,
        branch: String?,
        recordedBase: String? = null,
        /** RUNNER-CRED-1: the git credential the orchestrator lent for [gitRemote]; null = the runner's own access. */
        credential: com.superiorsoftllc.infantry.daemon.api.orchestrator.GitCredential? = null,
    ): StartWorktree {
        // Owner decision (RUNNER-POOL-6): a runner works and pushes only ticket branches, never the dev branch or another ref.
        require(branch == null || isTicketBranch(branch)) { "a runner works only on ${TicketBranches.BRANCH_PREFIX}* branches, not '$branch'" }
        val dir = File(cloneRoot(), folderFor(projectId, gitRemote))
        val remoteGit = remoteGit(credential)
        if (!File(dir, ".git").exists()) {
            dir.parentFile?.mkdirs()
            val cloned = remoteGit.run(dir.parentFile ?: dir, listOf("clone", "--no-checkout", gitRemote, dir.absolutePath))
            require(cloned.ok) { "git clone of the project's remote failed: ${cloned.err()}" }
        } else {
            git.run(dir, listOf("remote", "set-url", "origin", gitRemote))
            val fetched = remoteGit.run(dir, listOf("fetch", "--prune", "origin"))
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

    /**
     * RUNNER-GIT-IDENT-1: write [identity] (name, email) into the clone at [repo] as its `user.name` / `user.email`, so every worktree
     * cut from it commits as that identity (author and committer) instead of failing with "Author identity unknown". Null = nothing
     * written (the clone keeps whatever git config the runner user has). Best effort: a failure is logged, the start goes on.
     */
    fun applyIdentity(repo: File, identity: Pair<String, String>?) {
        val (name, email) = identity ?: return
        for ((key, value) in listOf("user.name" to name, "user.email" to email)) {
            val set = git.run(repo, listOf("config", key, value))
            if (!set.ok) log("[infantry] runner: could not set $key in ${repo.name}: ${set.err()}")
        }
    }

    /** Session [sessionId] runs in [worktree], cut by [checkout] for its orchestrator. */
    fun started(
        sessionId: String,
        worktree: StartWorktree,
        pushOnCommit: Boolean = false,
        /** RUNNER-CRED-1: the lent git credential its pushes use (memory only). */
        credential: com.superiorsoftllc.infantry.daemon.api.orchestrator.GitCredential? = null,
    ) {
        if (credential != null) gitCredentials[sessionId] = credential else gitCredentials.remove(sessionId)
        sessions[sessionId] = worktree
        save()
        if (pushOnCommit) this.pushOnCommit[sessionId] = head(worktree).orEmpty()
    }

    /** RUNNER-POOL-7: session [sessionId] was started over the link (with or without a git remote); its agent frames are forwarded. */
    fun linked(sessionId: String) {
        linked += sessionId
    }

    /** RUNNER-POOL-7: whether [sessionId] is a session the paired orchestrator started here (with or without a git remote). */
    fun isLinked(sessionId: String): Boolean = sessionId in linked || sessions.containsKey(sessionId)

    /** RUNNER-UNIFY-3: every session the paired orchestrator started here (with or without a git remote), whose frames it is pushed. */
    fun linkedIds(): Set<String> = linked + sessions.keys

    /** Whether [sessionId] was started by the orchestrator over the link. */
    fun isRemote(sessionId: String): Boolean = sessions.containsKey(sessionId)

    /** A status change of one session; queued as an event only for a session the orchestrator started. */
    fun onStatus(sessionId: String, state: String) {
        // RUNNER-SESSION-GHOST-1: a session started over the link without a ticket (the app's New agent) reports too; before, only
        // ticket starts did, so the orchestrator showed the app's session in STARTING for ever.
        if (isLinked(sessionId)) add(sessionId, RunnerEvent.STATUS, state)
    }

    /**
     * RUNNER-PROMPT-RELAY-1: epoch millis of the orchestrator's last outbox read (it reads every heartbeat while it is reachable); 0 =
     * not since this daemon started. A relayed prompt falls back to the runner's own timer only once this is older than that timer.
     */
    @Volatile
    var lastOrchestratorRead: Long = 0L
        private set

    /** A turn of [sessionId] ended with [code]: its ticket branch is pushed to `origin`, then the event is queued. */
    fun onTurnEnd(sessionId: String, code: Int?) {
        // RUNNER-SESSION-GHOST-1: a linked session with no ticket worktree has nothing to push, but its turn end is still reported.
        val wt = sessions[sessionId] ?: return run { if (sessionId in linked) add(sessionId, RunnerEvent.FINISHED, code?.toString().orEmpty()) }
        if (!isTicketBranch(wt.branch)) {
            log("[infantry] runner: push of ${wt.branch} for session $sessionId refused: only ${TicketBranches.BRANCH_PREFIX}* branches are pushed")
            add(sessionId, RunnerEvent.FINISHED, code?.toString().orEmpty())
            return
        }
        push(sessionId, wt)
        add(sessionId, RunnerEvent.FINISHED, code?.toString().orEmpty())
    }

    /**
     * RUNNER-POOL-8: push the ticket branch of every push-on-commit session whose HEAD moved since its last push. The daemon calls it
     * every `limits.runners.commitCheckSec`; with no such session it runs no git at all. Returns how many branches were pushed.
     */
    fun checkCommits(): Int = pushOnCommit.keys.count { sessionId ->
        val wt = sessions[sessionId] ?: run { pushOnCommit.remove(sessionId); return@count false }
        val now = head(wt) ?: return@count false
        if (now == pushOnCommit[sessionId] || !isTicketBranch(wt.branch)) false else push(sessionId, wt)
    }

    /** Push [wt]'s ticket branch to `origin`; true when it went through (the HEAD pushed is remembered for [checkCommits]). */
    private fun push(sessionId: String, wt: StartWorktree): Boolean {
        val pushed = remoteGit(gitCredentials[sessionId]).run(File(wt.path), listOf("push", "origin", "${wt.branch}:refs/heads/${wt.branch}"))
        if (!pushed.ok) {
            log("[infantry] runner: push of ${wt.branch} for session $sessionId failed: ${pushed.err()}")
            return false
        }
        if (pushOnCommit.containsKey(sessionId)) head(wt)?.let { pushOnCommit[sessionId] = it }
        return true
    }

    private fun head(wt: StartWorktree): String? =
        git.run(File(wt.path), listOf("rev-parse", "HEAD")).takeIf { it.ok }?.out()?.takeIf { it.isNotEmpty() }

    /** The session ended for good (killed or removed): no more pushes for it. */
    fun forget(sessionId: String) {
        sessions.remove(sessionId)
        linked.remove(sessionId)
        pushOnCommit.remove(sessionId)
        gitCredentials.remove(sessionId)
    }

    /** Every kept event with a seq above [sinceSeq] (an older orchestrator's read; it acknowledges nothing). */
    @Synchronized
    fun events(sinceSeq: Long): List<RunnerEvent> {
        lastOrchestratorRead = clock()
        return outbox.filter { it.seq > sinceSeq }
    }

    /**
     * RUNNER-POOL-8: drop what [acked] acknowledges (session -> the highest seq the orchestrator applied), then answer every event
     * still kept, oldest first. A lost answer costs nothing: the next call with the same [acked] answers the same events again.
     */
    @Synchronized
    fun events(acked: Map<String, Long>): List<RunnerEvent> {
        lastOrchestratorRead = clock()
        if (acked.isNotEmpty() && outbox.removeAll { it.seq <= (acked[it.sessionId] ?: 0L) }) save()
        return outbox.toList()
    }

    @Synchronized
    private fun add(sessionId: String, kind: String, payload: String) {
        val next = (seqs[sessionId] ?: 0L) + 1
        seqs[sessionId] = next
        outbox.addLast(RunnerEvent(sessionId, next, kind, payload))
        val keep = outboxMax().coerceAtLeast(1)
        while (outbox.size > keep) {
            val dropped = outbox.removeFirst()
            log("[infantry] runner: event outbox full (limits.runners.outboxMax = $keep): dropped ${dropped.kind} #${dropped.seq} of session ${dropped.sessionId}")
        }
        save()
    }

    companion object {
        /** The default of `runners.cloneRoot`: under the runner's state dir. */
        const val DEFAULT_FOLDER = "clones"

        /** RUNNER-CHECK-1: where a runner keeps its clones: the `runners.cloneRoot` setting when set, else [DEFAULT_FOLDER] under [stateDir]. */
        fun cloneRootDir(configured: String, stateDir: File): File =
            configured.takeIf { it.isNotBlank() }?.let(::File) ?: File(stateDir, DEFAULT_FOLDER)

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
