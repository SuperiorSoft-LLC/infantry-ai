package com.superiorsoftllc.agenthq.worktree

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * What a session spawn needs from a workspace provisioner: make one workspace, and say where it is.
 * A narrow seam on purpose -- the lifecycle ([com.superiorsoftllc.agenthq.session.SessionManager])
 * asks for a workspace and gets a path; everything git-shaped stays behind this interface.
 */
interface WorktreeProvisioner {
    fun create(sessionId: String, repo: File, branchHint: String? = null, baseRef: String? = null): WorktreeRecord

    /**
     * BRANCH-PER-TASK 1/3: register a worktree the caller already cut (a ticket's own branch), so status,
     * cleanup and restart reconciliation see it like any session worktree. Default: no-op.
     */
    fun adopt(record: WorktreeRecord) {}
}

/** A worktree could not be created (bad repo, no commits, or an unrecoverable git failure). */
class WorktreeException(message: String) : RuntimeException(message)

/**
 * Phase 4: one git worktree per session, via the `git` CLI (no JGit -- see [GitCli]).
 *
 * COLLISION MODEL, stated once because it is the whole point:
 *  - **The manager owns branch names.** A caller never passes a branch it chose; it passes a task
 *    hint at most. The name is `agenthq/<slug-of-task>-<session-tag>`, and the session tag comes from
 *    the session's own unique id. Two concurrent sessions on one repository therefore CANNOT derive
 *    the same branch, so two agents are never handed one tree.
 *  - The path is `<repo>/.worktrees/<branch with '/' replaced by '-'>`, matching the convention this
 *    repository already uses (`.worktrees/feature-agenthq-phase3`).
 *  - If that path or branch already exists (a leftover, a reused id), creation RETRIES with a numeric
 *    suffix up to [maxAttempts], then fails LOUDLY. It never reuses or deletes another agent's
 *    worktree or branch.
 *  - [create] is idempotent per session id, so a re-invocation (daemon retry, client re-send) returns
 *    the same worktree rather than cutting a second one.
 *
 * @param worktreesDir where a repository's worktrees live; `.worktrees/` at the repo root by default.
 * @param sessionTag the uniqueness seed; defaults to the last 8 alphanumerics of the session id.
 */
class GitWorktreeManager(
    private val git: GitCli = SystemGitCli(),
    private val worktreesDir: (repoRoot: File) -> File = { File(it, ".worktrees") },
    private val clock: () -> Long = System::currentTimeMillis,
    private val sessionTag: (String) -> String = { id ->
        id.filter { it.isLetterOrDigit() }.takeLast(8).ifEmpty { "session" }
    },
    private val maxAttempts: Int = 5,
    /** The most changed files one status carries; beyond it the list is cut and `changesTruncated` is set. */
    private val maxChanges: Int = DEFAULT_MAX_CHANGES,
    /** Where the changed-file list comes from; git by default, swappable without touching the frame. */
    private val changeReader: WorkspaceChangeReader = GitChangeReader(git),
) : WorktreeProvisioner {

    /** Session id -> its worktree. Rebuilt from persisted sessions at startup via [reconcile]. */
    private val records = ConcurrentHashMap<String, WorktreeRecord>()

    override fun create(sessionId: String, repo: File, branchHint: String?, baseRef: String?): WorktreeRecord {
        records[sessionId]?.let { if (!it.removed) return it }

        // Cut from the MAIN checkout: a worktree nested inside another worktree would be its own trap.
        val root = mainRoot(repo)
        if (!git.isRepo(root)) throw WorktreeException("not a git repository: ${root.path}")

        val base = baseRef?.takeIf { it.isNotBlank() } ?: "HEAD"
        val baseCommit = resolveCommit(repo, base)
            ?: throw WorktreeException("cannot resolve base ref '$base' in ${repo.path}")

        val slug = slug(branchHint)
        val tag = sessionTag(sessionId)
        var lastError = ""
        for (attempt in 0 until maxAttempts) {
            val branch = buildBranch(slug, tag, attempt)
            val path = File(worktreesDir(root), branch.replace('/', '-'))
            if (path.exists()) {
                lastError = "path already exists: ${path.path}"
                continue
            }
            val result = git.run(root, listOf("worktree", "add", "-b", branch, path.absolutePath, baseCommit))
            if (result.ok) {
                val record = WorktreeRecord(
                    sessionId = sessionId,
                    repoRoot = root.absolutePath,
                    path = path.absolutePath,
                    branch = branch,
                    baseRef = baseCommit,
                    createdAt = clock(),
                )
                records[sessionId] = record
                return record
            }
            lastError = result.err()
            if (!isCollision(lastError)) throw WorktreeException("git worktree add failed: $lastError")
        }
        throw WorktreeException("could not create a unique worktree after $maxAttempts attempts: $lastError")
    }

    override fun adopt(record: WorktreeRecord) {
        records[record.sessionId] = record
    }

    fun get(sessionId: String): WorktreeRecord? = records[sessionId]

    fun list(): List<WorktreeRecord> = records.values.sortedBy { it.createdAt }

    fun status(sessionId: String): WorktreeStatus? = records[sessionId]?.let { status(it) }

    fun statuses(): List<WorktreeStatus> = list().map { status(it) }

    /**
     * Compute the live state of [record] from git -- never cached, because "is it dirty, has it
     * moved" is only true at the instant you ask. Everything here is observation: a missing `.git`
     * file means the directory is gone; `status --porcelain` emptiness means clean; `rev-list
     * --left-right --count <base>...HEAD` gives behind/ahead against the immutable cut point.
     */
    fun status(record: WorktreeRecord): WorktreeStatus {
        val path = File(record.path)
        // A linked worktree carries a `.git` FILE; the main checkout carries a `.git` directory.
        val exists = File(path, ".git").exists()
        if (!exists) {
            return WorktreeStatus(
                record.sessionId, record.repoRoot, record.path, record.branch, record.baseRef,
                exists = false, dirty = false, ahead = 0, behind = 0, removed = record.removed,
            )
        }
        // ONE read answers both "dirty" and "which files", so they cannot disagree. It runs against the
        // SESSION'S worktree (guarded by the .git check above), never the project root. A failed read is
        // UNKNOWN (null), never "clean".
        val all = changeReader.read(path)
        val dirty = if (all != null) all.isNotEmpty() else false
        val changes = all?.take(maxChanges)
        val counts = git.run(path, listOf("rev-list", "--left-right", "--count", "${record.baseRef}...HEAD")).out()
        val (behind, ahead) = parseAheadBehind(counts)
        return WorktreeStatus(
            record.sessionId, record.repoRoot, record.path, record.branch, record.baseRef,
            exists = true, dirty = dirty, ahead = ahead, behind = behind, removed = record.removed,
            changes = changes,
            changesTruncated = all != null && all.size > maxChanges,
        )
    }

    /**
     * Remove a session's worktree. The DEFAULT here is the safety rail: a worktree with uncommitted
     * changes is REFUSED unless [force], because removing it would silently destroy work. The branch
     * is KEPT unless [deleteBranch] (and, for an unmerged branch, unless [deleteUnmergedBranch]),
     * which is what makes the operation REVERSIBLE: the branch is the archive, and
     * `git worktree add <path> <branch>` restores the checkout with nothing lost.
     */
    fun remove(
        sessionId: String,
        force: Boolean = false,
        deleteBranch: Boolean = false,
        deleteUnmergedBranch: Boolean = false,
    ): CleanupResult {
        val record = records[sessionId] ?: throw WorktreeException("no worktree known for session $sessionId")
        val root = File(record.repoRoot)
        val path = File(record.path)
        val exists = File(path, ".git").exists()
        val dirty = if (exists) status(record).dirty else false

        if (exists && dirty && !force) {
            return CleanupResult(
                sessionId, record.path, record.branch, removed = false, branchKept = true,
                dirty = true, forced = false,
                message = "refusing to remove a worktree with uncommitted changes (would lose work): " +
                    "${record.path} -- commit first, or call again with force=true",
            )
        }

        if (exists) {
            val args = buildList {
                add("worktree"); add("remove")
                if (force) add("--force")
                add(path.absolutePath)
            }
            val result = git.run(root, args)
            if (!result.ok) {
                return CleanupResult(
                    sessionId, record.path, record.branch, removed = false, branchKept = true,
                    dirty = dirty, forced = force,
                    message = "git worktree remove failed: ${result.err()}",
                )
            }
            git.run(root, listOf("worktree", "prune"))
        }

        var branchKept = true
        if (deleteBranch) {
            val del = git.run(root, listOf("branch", if (deleteUnmergedBranch) "-D" else "-d", record.branch))
            branchKept = !del.ok
        }
        records[sessionId] = record.copy(removedAt = clock())

        val message = when {
            !exists -> "no worktree at ${record.path}; archived"
            branchKept -> "worktree removed; branch '${record.branch}' kept " +
                "(recover with: git worktree add ${record.path} ${record.branch})"
            else -> "worktree and branch '${record.branch}' removed"
        }
        return CleanupResult(
            sessionId, record.path, record.branch, removed = exists, branchKept = branchKept,
            dirty = dirty, forced = force && dirty, message = message,
        )
    }

    /**
     * Rebuild the registry from persisted sessions at daemon startup, so a worktree cut by a previous
     * daemon is still addressable (status/remove) after a restart. Status is never persisted -- it is
     * recomputed from git on demand -- so re-registering the identity is all that is needed.
     */
    fun reconcile(refs: List<WorktreeRef>) {
        for (ref in refs) {
            records.putIfAbsent(
                ref.sessionId,
                WorktreeRecord(
                    sessionId = ref.sessionId, repoRoot = ref.repoRoot, path = ref.path,
                    branch = ref.branch, baseRef = ref.baseRef, createdAt = 0L,
                ),
            )
        }
    }

    // --- internals ---

    /** The main checkout's root, even when [repo] is itself a linked worktree (`--git-common-dir`). */
    private fun mainRoot(repo: File): File {
        val result = git.run(repo, listOf("rev-parse", "--path-format=absolute", "--git-common-dir"))
        val common = if (result.ok) result.out().lineSequence().firstOrNull()?.trim().orEmpty() else ""
        if (common.isNotEmpty()) {
            val dir = File(common)
            if (dir.isDirectory && dir.name == ".git") return dir.parentFile ?: repo.canonicalFile
        }
        return repo.canonicalFile
    }

    private fun resolveCommit(cwd: File, ref: String): String? {
        val result = git.run(cwd, listOf("rev-parse", "--verify", "$ref^{commit}"))
        if (!result.ok) return null
        return result.out().lineSequence().firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun buildBranch(slug: String, tag: String, attempt: Int): String {
        val suffix = if (attempt == 0) "" else "-$attempt"
        return "agenthq/$slug-$tag$suffix"
    }

    /** A readable, filesystem- and git-safe slug from a task hint; empty hints become "session". */
    private fun slug(hint: String?): String {
        val cleaned = hint.orEmpty().lowercase()
            .map { if (it.isLetterOrDigit()) it else '-' }
            .joinToString("")
            .split('-')
            .filter { it.isNotEmpty() }
            .joinToString("-")
            .take(32)
            .trim('-')
        return cleaned.ifEmpty { "session" }
    }

    private fun isCollision(stderr: String): Boolean {
        val s = stderr.lowercase()
        return s.contains("already exists") ||
            s.contains("already checked out") ||
            s.contains("already used by worktree") ||
            s.contains("is a main working tree")
    }

    private companion object {
        const val DEFAULT_MAX_CHANGES = 500
    }

    private fun parseAheadBehind(output: String): Pair<Int, Int> {
        val parts = output.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.size < 2) return 0 to 0
        // `rev-list --left-right --count <base>...HEAD` prints "<behind>\t<ahead>".
        val behind = parts[0].toIntOrNull() ?: 0
        val ahead = parts[1].toIntOrNull() ?: 0
        return behind to ahead
    }
}
