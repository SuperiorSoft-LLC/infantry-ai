// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.projectgit

import com.superiorsoftllc.infantry.protocol.Project
import com.superiorsoftllc.infantry.worktree.ChangeKind
import com.superiorsoftllc.infantry.worktree.GitCli
import com.superiorsoftllc.infantry.worktree.GitResult
import com.superiorsoftllc.infantry.worktree.WorktreeChanges
import java.io.File
import java.io.IOException

/**
 * CP-2 slice (b): reads git over the directory a PROJECT lives in.
 *
 * Every read runs `git` with the project's OWN directory (resolved up to its repository top level) as
 * the working directory, through the same [GitCli] seam the worktree manager uses -- so a project
 * never sees the daemon's checkout, and a test substitutes a temp repo by configuring a directory.
 *
 * Reads are READ-ONLY and take no index lock (`--no-optional-locks`): plain `git status` refreshes
 * the index, which would make an agent's own `git add` in the same tree fail intermittently.
 *
 * The honesty rule of the wire (see `ProjectGitWire.kt`): a read that could not happen answers
 * `available = false` with a reason a user can act on, NEVER an empty "clean" answer. A request the
 * service refuses to hand to git at all (a path that could leave the repository) throws
 * [IllegalArgumentException], which the handler turns into `project_git_diff refused: ...`.
 */
class ProjectGitService(
    private val git: GitCli,
    private val changeCap: Int = DEFAULT_CHANGE_CAP,
    private val patchCap: Int = DEFAULT_PATCH_CAP,
    private val branchCap: Int = DEFAULT_BRANCH_CAP,
) {

    fun status(project: Project): ProjectGitStatus {
        val root = when (val resolved = resolveRoot(project)) {
            is Root.Unavailable -> return ProjectGitStatus(project.id, project.directory, available = false, reason = resolved.reason)
            is Root.Found -> resolved
        }
        val porcelain = git.run(
            root.dir,
            listOf("--no-optional-locks", "status", "--porcelain=v1", "-z", "--untracked-files=all"),
        )
        if (!porcelain.ok) {
            return ProjectGitStatus(
                project.id, project.directory, available = false,
                reason = "git status failed: ${porcelain.err().lineSequence().firstOrNull().orEmpty()}",
            )
        }
        val all = WorktreeChanges.parse(porcelain.stdout)
        // `symbolic-ref` answers the branch even before the first commit; it fails on a detached HEAD.
        val branch = git.run(root.dir, listOf("symbolic-ref", "--quiet", "--short", "HEAD")).takeIf { it.ok }?.out()?.ifEmpty { null }
        val head = git.run(root.dir, listOf("rev-parse", "--verify", "--quiet", "HEAD")).takeIf { it.ok }?.out()?.ifEmpty { null }
        return ProjectGitStatus(
            projectId = project.id,
            directory = project.directory,
            available = true,
            repoRoot = root.dir.path,
            branch = branch,
            head = head,
            changes = all.take(changeCap),
            changesTruncated = all.size > changeCap,
        )
    }

    /**
     * [worktree] (UX-CHANGES-1, 1.88.0): read the diff in that worktree of the project's repository instead of the
     * project's directory; a path git does not list as one of its worktrees is refused (see [resolveWorktree]).
     */
    fun diff(project: Project, path: String, staged: Boolean, worktree: String? = null): ProjectGitPatch {
        // Refused BEFORE anything else, so a hostile path is never even resolved against a repository.
        val relative = checkRelativePath(path)
        val root = when (val resolved = if (worktree == null) resolveRoot(project) else resolveWorktree(project, worktree)) {
            is Root.Unavailable -> return ProjectGitPatch(project.id, relative, staged, available = false, reason = resolved.reason, worktree = worktree)
            is Root.Found -> resolved
        }

        val untracked = !staged && git.run(
            root.dir,
            listOf("--no-optional-locks", "ls-files", "--others", "--exclude-standard", "-z", "--", relative),
        ).let { it.ok && it.stdout.isNotBlank() }

        val result = if (untracked) {
            // Only a path git itself lists as untracked in THIS repository is ever read from disk by the
            // no-index diff, so the escape check above is not the only thing keeping it inside the tree.
            // Exit 1 means "there is a difference", which for a new file is the answer, not a failure.
            git.run(root.dir, listOf("--no-optional-locks", "diff", "--no-color", "--no-ext-diff", "--no-index", "--", "/dev/null", relative))
                .let { if (it.exitCode == 1) it.copy(exitCode = 0) else it }
        } else {
            val args = buildList {
                addAll(listOf("--no-optional-locks", "diff", "--no-color", "--no-ext-diff"))
                if (staged) add("--cached")
                addAll(listOf("--", relative))
            }
            git.run(root.dir, args)
        }
        if (!result.ok) {
            return ProjectGitPatch(
                project.id, relative, staged, available = false,
                reason = "git diff failed: ${result.err().lineSequence().firstOrNull().orEmpty()}",
                worktree = worktree,
            )
        }
        val text = result.stdout
        // Git prints a one-line notice instead of content for a binary file; there is no text to show.
        if (text.lineSequence().any { it.startsWith("Binary files ") && it.endsWith(" differ") }) {
            return ProjectGitPatch(project.id, relative, staged, available = true, binary = true, worktree = worktree)
        }
        val truncated = text.length > patchCap
        return ProjectGitPatch(
            projectId = project.id,
            path = relative,
            staged = staged,
            available = true,
            patch = if (truncated) text.substring(0, patchCap) else text,
            truncated = truncated,
            worktree = worktree,
        )
    }

    fun log(project: Project, limit: Int): ProjectGitHistory {
        val root = when (val resolved = resolveRoot(project)) {
            is Root.Unavailable -> return ProjectGitHistory(project.id, available = false, reason = resolved.reason)
            is Root.Found -> resolved
        }
        // A repository with no commit has an empty history; `git log` would fail there, which is not a failure.
        if (!git.run(root.dir, listOf("rev-parse", "--verify", "--quiet", "HEAD")).ok) {
            return ProjectGitHistory(project.id, available = true)
        }
        val wanted = limit.coerceIn(1, ProjectGitLogRequest.MAX_LIMIT)
        // One more than asked for, so "there is more" is a measurement and not a guess.
        val result = git.run(
            root.dir,
            listOf("--no-optional-locks", "log", "--max-count=${wanted + 1}", "--format=$LOG_FORMAT"),
        )
        if (!result.ok) {
            return ProjectGitHistory(
                project.id, available = false,
                reason = "git log failed: ${result.err().lineSequence().firstOrNull().orEmpty()}",
            )
        }
        val commits = parseLog(result)
        return ProjectGitHistory(
            projectId = project.id,
            available = true,
            commits = commits.take(wanted),
            truncated = commits.size > wanted,
        )
    }

    // ---- BRANCH-VIEW-1 (contract 1.65.0): the branch view --------------------------------------
    //
    // Everything below reads refs and objects only: no checkout, no working-tree read, no index lock.
    // A branch or base name reaches git ONLY as a fully-qualified `refs/heads/<name>` that passed
    // `check-ref-format` and resolved to a commit (or as a 40-hex commit id that resolved); every later
    // command is handed the resolved commit ids, never the caller's string.

    fun branches(project: Project): ProjectGitBranchList {
        val root = when (val resolved = resolveRoot(project)) {
            is Root.Unavailable -> return ProjectGitBranchList(project.id, available = false, reason = resolved.reason)
            is Root.Found -> resolved
        }
        val refs = git.run(
            root.dir,
            listOf(
                "--no-optional-locks", "for-each-ref", "--count=${branchCap + 1}",
                "--format=%(refname)%1f%(objectname)%1f%(upstream:short)", "refs/heads/",
            ),
        )
        if (!refs.ok) {
            return ProjectGitBranchList(
                project.id, available = false,
                reason = "git for-each-ref failed: ${refs.err().lineSequence().firstOrNull().orEmpty()}",
            )
        }
        val worktrees = worktreeBranches(root.dir)
        val all = refs.stdout.lineSequence().mapNotNull { line ->
            val fields = line.trimEnd('\r').split(FIELD_SEPARATOR)
            if (fields.size < 2 || !fields[0].startsWith(HEADS)) return@mapNotNull null
            val name = fields[0].removePrefix(HEADS)
            val path = worktrees[name]
            GitBranch(
                name = name,
                head = fields[1],
                upstream = fields.getOrNull(2)?.ifEmpty { null },
                isWorktree = path != null,
                worktreePath = path,
            )
        }.toList()
        val current = git.run(root.dir, listOf("symbolic-ref", "--quiet", "--short", "HEAD")).takeIf { it.ok }?.out()?.ifEmpty { null }
        return ProjectGitBranchList(
            projectId = project.id,
            available = true,
            current = current,
            branches = all.take(branchCap),
            truncated = all.size > branchCap,
        )
    }

    fun branchChanges(project: Project, branch: String, base: String?): ProjectGitBranchDiffstat {
        checkBranchName(branch)
        base?.let { checkBaseName(it) }
        val range = when (val r = resolveRange(project, branch, base)) {
            is Range.Unavailable -> return ProjectGitBranchDiffstat(project.id, branch, r.base, available = false, reason = r.reason)
            is Range.Found -> r
        }
        val common = listOf("--no-optional-locks", "diff", "--no-color", "--no-ext-diff", "-M", "-z")
        val names = git.run(range.root, common + listOf("--name-status", range.mergeBase, range.branchSha, "--"))
        val stats = git.run(range.root, common + listOf("--numstat", range.mergeBase, range.branchSha, "--"))
        val failed = listOf(names, stats).firstOrNull { !it.ok }
        if (failed != null) {
            return ProjectGitBranchDiffstat(
                project.id, branch, range.base, range.mergeBase, available = false,
                reason = "git diff failed: ${failed.err().lineSequence().firstOrNull().orEmpty()}",
            )
        }
        val counts = parseNumstat(stats.stdout)
        val all = parseNameStatus(names.stdout).map { f ->
            val c = counts[f.path]
            f.copy(added = c?.first ?: 0, removed = c?.second ?: 0, binary = c == null && counts.containsKey(f.path))
        }
        return ProjectGitBranchDiffstat(
            projectId = project.id,
            branch = branch,
            base = range.base,
            mergeBase = range.mergeBase,
            available = true,
            changes = all.take(changeCap),
            truncated = all.size > changeCap,
        )
    }

    fun branchDiff(project: Project, branch: String, base: String?, path: String): ProjectGitPatch {
        val relative = checkRelativePath(path)
        checkBranchName(branch)
        base?.let { checkBaseName(it) }
        val range = when (val r = resolveRange(project, branch, base)) {
            is Range.Unavailable -> return ProjectGitPatch(project.id, relative, staged = false, available = false, reason = r.reason, branch = branch)
            is Range.Found -> r
        }
        val result = git.run(
            range.root,
            listOf("--no-optional-locks", "diff", "--no-color", "--no-ext-diff", range.mergeBase, range.branchSha, "--", relative),
        )
        if (!result.ok) {
            return ProjectGitPatch(
                project.id, relative, staged = false, available = false, branch = branch,
                reason = "git diff failed: ${result.err().lineSequence().firstOrNull().orEmpty()}",
            )
        }
        val text = result.stdout
        if (text.lineSequence().any { it.startsWith("Binary files ") && it.endsWith(" differ") }) {
            return ProjectGitPatch(project.id, relative, staged = false, available = true, binary = true, branch = branch)
        }
        val truncated = text.length > patchCap
        return ProjectGitPatch(
            projectId = project.id,
            path = relative,
            staged = false,
            available = true,
            patch = if (truncated) text.substring(0, patchCap) else text,
            truncated = truncated,
            branch = branch,
        )
    }

    private sealed interface Range {
        data class Found(val root: File, val base: String, val branchSha: String, val mergeBase: String) : Range
        data class Unavailable(val base: String, val reason: String) : Range
    }

    private fun resolveRange(project: Project, branch: String, base: String?): Range {
        val root = when (val resolved = resolveRoot(project)) {
            is Root.Unavailable -> return Range.Unavailable(base.orEmpty(), resolved.reason)
            is Root.Found -> resolved.dir
        }
        // A well-formed name that is not an existing local branch is a refusal, like an unknown project.
        val branchSha = resolveLocalBranch(root, branch)
            ?: throw IllegalArgumentException("no local branch '${branch.take(80)}'")
        val (baseName, baseSha) = if (base != null) {
            base to (resolveBase(root, base) ?: throw IllegalArgumentException("no local branch or commit '${base.take(80)}'"))
        } else {
            defaultBase(root, branch, branchSha)
                ?: return Range.Unavailable("", "no base found for '$branch': no recorded base, no $MVP_BRANCH branch and no default branch")
        }
        val mb = git.run(root, listOf("merge-base", baseSha, branchSha))
        if (!mb.ok || mb.out().isEmpty()) {
            return Range.Unavailable(baseName, "no merge-base between '$branch' and '$baseName': the histories are unrelated")
        }
        return Range.Found(root, baseName, branchSha, mb.out())
    }

    /** The commit of local branch [name], or null when there is no such branch. [name] is already checked. */
    private fun resolveLocalBranch(root: File, name: String): String? {
        val ref = HEADS + name
        if (!git.run(root, listOf("check-ref-format", ref)).ok) return null
        return git.run(root, listOf("rev-parse", "--verify", "--quiet", "$ref^{commit}"))
            .takeIf { it.ok }?.out()?.ifEmpty { null }
    }

    /** A base is a local branch, or a full commit id that exists. */
    private fun resolveBase(root: File, base: String): String? =
        if (FULL_SHA.matches(base)) {
            git.run(root, listOf("rev-parse", "--verify", "--quiet", "$base^{commit}"))
                .takeIf { it.ok }?.out()?.ifEmpty { null }
        } else {
            resolveLocalBranch(root, base)
        }

    /**
     * The default base of [branch]: its recorded base (the `<branch>=<sha>` line of [EXPECTED_BASES_PATH],
     * as committed ON the branch, else as on disk in the repository), else [MVP_BRANCH], else the repository's
     * default branch (origin/HEAD, `init.defaultBranch`, `main`, `master`). Never the branch itself.
     */
    private fun defaultBase(root: File, branch: String, branchSha: String): Pair<String, String>? {
        recordedBase(root, branch, branchSha)?.let { sha -> resolveBase(root, sha)?.let { return sha to it } }
        val candidates = buildList {
            add(MVP_BRANCH)
            git.run(root, listOf("symbolic-ref", "--quiet", "refs/remotes/origin/HEAD")).takeIf { it.ok }
                ?.out()?.removePrefix("refs/remotes/origin/")?.ifEmpty { null }?.let { add(it) }
            git.run(root, listOf("config", "--get", "init.defaultBranch")).takeIf { it.ok }?.out()?.ifEmpty { null }?.let { add(it) }
            add("main")
            add("master")
        }
        for (name in candidates.distinct()) {
            if (name == branch || !isSafeBranchName(name)) continue
            resolveLocalBranch(root, name)?.let { return name to it }
        }
        return null
    }

    private fun recordedBase(root: File, branch: String, branchSha: String): String? {
        val committed = git.run(root, listOf("cat-file", "-p", "$branchSha:$EXPECTED_BASES_PATH")).takeIf { it.ok }?.stdout
        val onDisk = File(root, EXPECTED_BASES_PATH).takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }
        return sequenceOf(committed, onDisk).filterNotNull().firstNotNullOfOrNull { text ->
            text.lineSequence()
                .map { it.trim() }
                .filter { !it.startsWith("#") && it.substringBefore('=').trim() == branch }
                .map { it.substringAfter('=').trim().lowercase() }
                .firstOrNull { FULL_SHA.matches(it) }
        }
    }

    /** Branch name -> the worktree path that has it checked out (`git worktree list --porcelain`). */
    private fun worktreeBranches(root: File): Map<String, String> {
        val result = git.run(root, listOf("--no-optional-locks", "worktree", "list", "--porcelain"))
        if (!result.ok) return emptyMap()
        val map = mutableMapOf<String, String>()
        var path: String? = null
        for (raw in result.stdout.lineSequence()) {
            val line = raw.trimEnd('\r')
            when {
                line.startsWith("worktree ") -> path = line.removePrefix("worktree ")
                line.startsWith("branch $HEADS") -> path?.let { map[line.removePrefix("branch $HEADS")] = it }
                line.isEmpty() -> path = null
            }
        }
        return map
    }

    /** `--name-status -z`: `X\0path\0` or, for a rename/copy, `R100\0from\0to\0`. */
    private fun parseNameStatus(out: String): List<BranchChangedFile> {
        val tokens = out.split('\u0000')
        val files = mutableListOf<BranchChangedFile>()
        var i = 0
        while (i < tokens.size) {
            val code = tokens[i]
            if (code.isEmpty()) { i++; continue }
            when (code[0]) {
                'R', 'C' -> {
                    val from = tokens.getOrNull(i + 1) ?: break
                    val to = tokens.getOrNull(i + 2) ?: break
                    files += if (code[0] == 'R') BranchChangedFile(to, ChangeKind.RENAMED, fromPath = from)
                    else BranchChangedFile(to, ChangeKind.ADDED, fromPath = from)
                    i += 3
                }
                else -> {
                    val path = tokens.getOrNull(i + 1) ?: break
                    val kind = when (code[0]) {
                        'A' -> ChangeKind.ADDED
                        'D' -> ChangeKind.DELETED
                        'U' -> ChangeKind.CONFLICTED
                        else -> ChangeKind.MODIFIED // M, T (type change)
                    }
                    files += BranchChangedFile(path, kind)
                    i += 2
                }
            }
        }
        return files
    }

    /**
     * `--numstat -z`: `added\tremoved\tpath\0`, or for a rename `added\tremoved\t\0from\0to\0`. A binary
     * file counts `-\t-`, recorded as a null pair so the caller can flag it binary.
     */
    private fun parseNumstat(out: String): Map<String, Pair<Int, Int>?> {
        val tokens = out.split('\u0000')
        val map = mutableMapOf<String, Pair<Int, Int>?>()
        var i = 0
        while (i < tokens.size) {
            val head = tokens[i]
            if (head.isEmpty()) { i++; continue }
            val parts = head.split('\t', limit = 3)
            if (parts.size < 3) { i++; continue }
            val counts = parts[0].toIntOrNull()?.let { a -> parts[1].toIntOrNull()?.let { r -> a to r } }
            if (parts[2].isEmpty()) {
                val to = tokens.getOrNull(i + 2) ?: break
                map[to] = counts
                i += 3
            } else {
                map[parts[2]] = counts
                i += 1
            }
        }
        return map
    }

    // ---- UX-CHANGES-1 (contract 1.88.0): the worktree view ------------------------------------------
    //
    // Read-only like the rest: `worktree list`, `status`, `rev-list --count` and `log`, all with no index lock.
    // A worktree is only read when the PROJECT's repository lists it, so a request cannot point git anywhere else.

    /**
     * Every worktree of the project's repository. [sessionDir] is the directory the asking session runs in (null
     * when none): the worktree holding it is marked [GitWorktree.session] and becomes [ProjectGitWorktreeList.selected];
     * otherwise the one holding the project's directory is selected. "Holding" is the DEEPEST worktree that contains
     * the directory, because a linked worktree often sits inside the main one (`.worktrees/<name>`).
     */
    fun worktrees(project: Project, sessionDir: String?): ProjectGitWorktreeList {
        val root = when (val resolved = resolveRoot(project)) {
            is Root.Unavailable -> return ProjectGitWorktreeList(project.id, available = false, reason = resolved.reason)
            is Root.Found -> resolved.dir
        }
        val listed = when (val r = listWorktrees(root)) {
            is Listed.Failed -> return ProjectGitWorktreeList(project.id, available = false, reason = r.reason)
            is Listed.Ok -> r.entries
        }
        fun holder(dir: File?): WorktreeEntry? = dir?.let { d ->
            listed.filter { !it.bare && contains(File(it.path), d) }.maxByOrNull { canonical(File(it.path)).path.length }
        }
        val projectHolder = holder(project.directory.trim().takeIf { it.isNotEmpty() }?.let(::File))
        val sessionHolder = holder(sessionDir?.trim()?.takeIf { it.isNotEmpty() }?.let(::File))
        val worktrees = listed.mapIndexed { i, w ->
            GitWorktree(
                path = w.path, branch = w.branch, head = w.head, main = i == 0,
                project = w === projectHolder, session = w === sessionHolder,
                detached = w.detached, bare = w.bare, prunable = w.prunable,
            )
        }
        return ProjectGitWorktreeList(
            projectId = project.id,
            available = true,
            worktrees = worktrees,
            selected = (sessionHolder ?: projectHolder ?: listed.firstOrNull { !it.bare })?.path,
        )
    }

    /** One worktree's branch, ahead/behind against its base, uncommitted changes and recent history. */
    fun worktreeStatus(project: Project, path: String, historyLimit: Int): ProjectGitWorktreeStatus {
        val dir = when (val resolved = resolveWorktree(project, path)) {
            is Root.Unavailable -> return ProjectGitWorktreeStatus(project.id, path, available = false, reason = resolved.reason)
            is Root.Found -> resolved.dir
        }
        val porcelain = git.run(dir, listOf("--no-optional-locks", "status", "--porcelain=v1", "-z", "--untracked-files=all"))
        if (!porcelain.ok) {
            return ProjectGitWorktreeStatus(
                project.id, path, available = false,
                reason = "git status failed: ${porcelain.err().lineSequence().firstOrNull().orEmpty()}",
            )
        }
        val all = WorktreeChanges.parse(porcelain.stdout)
        val branch = currentBranch(dir)
        val head = git.run(dir, listOf("rev-parse", "--verify", "--quiet", "HEAD")).takeIf { it.ok }?.out()?.ifEmpty { null }

        var base = ""
        var ahead: Int? = null
        var behind: Int? = null
        var baseReason = ""
        when {
            head == null -> baseReason = "no commit yet"
            branch == null -> baseReason = "HEAD is detached"
            else -> when (val found = defaultBase(dir, branch, head)) {
                null -> baseReason = "no base found for '$branch': no recorded base, no $MVP_BRANCH branch and no default branch"
                else -> {
                    base = found.first
                    // `base...HEAD` with --left-right counts "only in base" (behind), then "only in HEAD" (ahead).
                    val counted = git.run(dir, listOf("--no-optional-locks", "rev-list", "--left-right", "--count", "${found.second}...$head"))
                    val parts = counted.takeIf { it.ok }?.out()?.trim()?.split(WHITESPACE)
                    val left = parts?.getOrNull(0)?.toIntOrNull()
                    val right = parts?.getOrNull(1)?.toIntOrNull()
                    if (left == null || right == null) {
                        baseReason = "git rev-list failed: ${counted.err().lineSequence().firstOrNull().orEmpty()}"
                    } else {
                        behind = left
                        ahead = right
                    }
                }
            }
        }

        var commits = emptyList<GitCommit>()
        var commitsTruncated = false
        if (head != null) {
            val wanted = historyLimit.coerceIn(1, ProjectGitLogRequest.MAX_LIMIT)
            // One more than asked for, so "there is more" is a measurement and not a guess.
            val log = git.run(dir, listOf("--no-optional-locks", "log", "--max-count=${wanted + 1}", "--format=$LOG_FORMAT", head))
            if (!log.ok) {
                return ProjectGitWorktreeStatus(
                    project.id, path, available = false,
                    reason = "git log failed: ${log.err().lineSequence().firstOrNull().orEmpty()}",
                )
            }
            val parsed = parseLog(log)
            commits = parsed.take(wanted)
            commitsTruncated = parsed.size > wanted
        }

        return ProjectGitWorktreeStatus(
            projectId = project.id,
            path = path,
            available = true,
            branch = branch,
            head = head,
            base = base,
            ahead = ahead,
            behind = behind,
            baseReason = baseReason,
            uncommitted = all.map { it.path }.distinct().size,
            changes = all.take(changeCap),
            changesTruncated = all.size > changeCap,
            commits = commits,
            commitsTruncated = commitsTruncated,
        )
    }

    private data class WorktreeEntry(
        val path: String,
        val head: String? = null,
        val branch: String? = null,
        val detached: Boolean = false,
        val bare: Boolean = false,
        val prunable: Boolean = false,
    )

    private sealed interface Listed {
        data class Ok(val entries: List<WorktreeEntry>) : Listed
        data class Failed(val reason: String) : Listed
    }

    /** `git worktree list --porcelain`: blank-line separated records, the main worktree first. */
    private fun listWorktrees(root: File): Listed {
        val result = git.run(root, listOf("--no-optional-locks", "worktree", "list", "--porcelain"))
        if (!result.ok) return Listed.Failed("git worktree list failed: ${result.err().lineSequence().firstOrNull().orEmpty()}")
        val entries = mutableListOf<WorktreeEntry>()
        var current: WorktreeEntry? = null
        for (raw in result.stdout.lineSequence()) {
            val line = raw.trimEnd('\r')
            val entry = current
            when {
                line.startsWith("worktree ") -> {
                    entry?.let { entries += it }
                    current = WorktreeEntry(path = line.removePrefix("worktree "))
                }
                line.isEmpty() -> { entry?.let { entries += it }; current = null }
                entry == null -> Unit
                line.startsWith("HEAD ") -> current = entry.copy(head = line.removePrefix("HEAD ").ifEmpty { null })
                line.startsWith("branch $HEADS") -> current = entry.copy(branch = line.removePrefix("branch $HEADS"))
                line == "detached" -> current = entry.copy(detached = true)
                line == "bare" -> current = entry.copy(bare = true)
                line == "prunable" || line.startsWith("prunable ") -> current = entry.copy(prunable = true)
            }
        }
        current?.let { entries += it }
        return Listed.Ok(entries)
    }

    /**
     * The directory of the worktree [path] names, when the project's repository lists it. Anything else is refused
     * ([IllegalArgumentException]): the path is the caller's string and must never become a place git runs in.
     */
    private fun resolveWorktree(project: Project, path: String): Root {
        val wanted = path.trim()
        require(wanted.isNotEmpty()) { "worktree path is blank" }
        require(wanted.none { it.isISOControl() }) { "worktree path contains a control character" }
        val root = when (val resolved = resolveRoot(project)) {
            is Root.Unavailable -> return resolved
            is Root.Found -> resolved.dir
        }
        val listed = when (val r = listWorktrees(root)) {
            is Listed.Failed -> return Root.Unavailable(r.reason)
            is Listed.Ok -> r.entries
        }
        val match = listed.firstOrNull { !it.bare && (it.path == wanted || sameDir(File(it.path), File(wanted))) }
            ?: throw IllegalArgumentException("not a worktree of this project's repository: '${wanted.take(160)}'")
        val dir = File(match.path)
        if (match.prunable || !dir.isDirectory) return Root.Unavailable("the worktree directory does not exist: ${match.path}")
        return Root.Found(dir)
    }

    private fun canonical(f: File): File = runCatching { f.canonicalFile }.getOrDefault(f.absoluteFile)

    /** True when [dir] is [worktree] or below it. */
    private fun contains(worktree: File, dir: File): Boolean {
        val top = canonical(worktree).path.trimEnd(File.separatorChar)
        val inner = canonical(dir).path
        return inner == top || inner.startsWith(top + File.separatorChar)
    }

    // ---- BRANCH-SWITCH-1 (contract 1.74.0): switch the checked-out branch -------------------------
    //
    // The ONE write this class makes. It runs `git switch <branch>` (`-c` to create from HEAD) in the
    // repository top level, and ONLY when nothing can be lost: never `--force`, `--discard-changes`,
    // `--merge` or a stash. Every refusal is an answer (`switched = false` + code + reason), checked in this
    // order: name, repository, existence, other worktree, dirty tree. Git itself stays the last guard: a race
    // that dirties the tree after the check makes `git switch` refuse on its own, and that is reported as is.
    //
    // "Dirty" counts TRACKED paths with uncommitted changes (staged or not). Untracked files do not block a
    // switch: git carries them across, and refuses by itself if the target branch would overwrite one.

    fun checkout(project: Project, branch: String, create: Boolean): ProjectGitCheckoutResult {
        fun refused(code: String, reason: String, previous: String? = null, dirty: Int = 0, held: String? = null) =
            ProjectGitCheckoutResult(
                projectId = project.id, branch = branch, create = create, switched = false, code = code, reason = reason,
                previous = previous, current = previous, dirtyCount = dirty, worktreePath = held,
            )

        val shown = branch.filterNot { it.isISOControl() }.take(80)
        if (!isSafeBranchName(branch)) return refused(CheckoutRefusal.BAD_NAME, "not a valid branch name: '$shown'")
        val root = when (val resolved = resolveRoot(project)) {
            is Root.Unavailable -> return refused(CheckoutRefusal.UNAVAILABLE, resolved.reason)
            is Root.Found -> resolved.dir
        }
        // Git's own rule for a ref name (no `~`, `^`, `:`, spaces, `.lock` suffix, ...), on the fully-qualified ref.
        if (!git.run(root, listOf("check-ref-format", HEADS + branch)).ok) {
            return refused(CheckoutRefusal.BAD_NAME, "git rejects the branch name '$shown'")
        }
        val previous = currentBranch(root)
        val exists = git.run(root, listOf("rev-parse", "--verify", "--quiet", "$HEADS$branch^{commit}")).ok
        if (create && exists) return refused(CheckoutRefusal.EXISTS, "a local branch '$shown' already exists", previous)
        if (!create && !exists) return refused(CheckoutRefusal.NO_BRANCH, "no local branch '$shown'", previous)
        if (!create && branch == previous) {
            return ProjectGitCheckoutResult(project.id, branch, create, switched = true, reason = "already on '$shown'", previous = previous, current = previous)
        }
        if (!create) {
            val holder = worktreeBranches(root)[branch]
            if (holder != null && !sameDir(File(holder), root)) {
                return refused(CheckoutRefusal.HELD_BY_WORKTREE, "'$shown' is checked out in another worktree: $holder", previous, held = holder)
            }
        }
        val porcelain = git.run(root, listOf("--no-optional-locks", "status", "--porcelain=v1", "-z", "--untracked-files=no"))
        if (!porcelain.ok) {
            return refused(CheckoutRefusal.GIT_FAILED, "git status failed: ${porcelain.err().lineSequence().firstOrNull().orEmpty()}", previous)
        }
        val dirty = WorktreeChanges.parse(porcelain.stdout).map { it.path }.distinct().size
        if (dirty > 0) {
            val files = if (dirty == 1) "1 file has" else "$dirty files have"
            return refused(CheckoutRefusal.DIRTY, "the working tree is not clean: $files uncommitted changes; commit them first", previous, dirty = dirty)
        }

        // The name passed every check above and cannot start with `-`; `--no-guess` stops a switch from
        // silently creating a tracking branch out of a remote one.
        val args = if (create) listOf("switch", "--quiet", "-c", branch) else listOf("switch", "--quiet", "--no-guess", branch)
        val result = git.run(root, args)
        if (!result.ok) {
            val why = result.err().lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            return refused(CheckoutRefusal.GIT_FAILED, "git switch failed: $why", previous)
        }
        return ProjectGitCheckoutResult(
            projectId = project.id, branch = branch, create = create, switched = true,
            previous = previous, current = currentBranch(root),
        )
    }

    private fun currentBranch(root: File): String? =
        git.run(root, listOf("symbolic-ref", "--quiet", "--short", "HEAD")).takeIf { it.ok }?.out()?.ifEmpty { null }

    private fun sameDir(a: File, b: File): Boolean =
        runCatching { a.canonicalFile == b.canonicalFile }.getOrDefault(a.absoluteFile == b.absoluteFile)

    // ---- internals ------------------------------------------------------------------------------

    private sealed interface Root {
        data class Found(val dir: File) : Root
        data class Unavailable(val reason: String) : Root
    }

    /** The repository top level of the project's directory, or why there is none. */
    private fun resolveRoot(project: Project): Root {
        val configured = project.directory.trim()
        if (configured.isEmpty()) return Root.Unavailable("this project has no directory configured")
        val dir = File(configured)
        if (!dir.isDirectory) return Root.Unavailable("the project directory does not exist: $configured")
        // BUG-PGIT-LOAD: this is every read's FIRST git call, so a git the daemon cannot start (not on its PATH:
        // `ProcessBuilder.start` throws) is caught here and answered as unavailable with the reason. Letting it
        // escape made the handler send an anonymous `error` frame the client could not put in any pane.
        // How the executable is located is not this class's business (GitCli); saying it failed is.
        val top = try {
            git.run(dir, listOf("rev-parse", "--show-toplevel"))
        } catch (e: IOException) {
            return Root.Unavailable("git could not be run by the daemon: ${e.message ?: e::class.simpleName}")
        }
        if (!top.ok || top.out().isEmpty()) {
            // Keep git's own line ("dubious ownership", "not a git repository (or any of the parent ...)"):
            // it is the part a user can act on.
            val why = top.err().lineSequence().firstOrNull { it.isNotBlank() }?.trim()
            return Root.Unavailable("the project directory is not a git repository: $configured" + (why?.let { " ($it)" } ?: ""))
        }
        return Root.Found(File(top.out()))
    }

    private fun parseLog(result: GitResult): List<GitCommit> =
        result.stdout.split(RECORD_SEPARATOR).mapNotNull { record ->
            val fields = record.trim('\n', '\r').split(FIELD_SEPARATOR)
            if (fields.size < 4) return@mapNotNull null
            val (hash, author, timestamp) = fields
            GitCommit(
                hash = hash,
                shortHash = hash.take(SHORT_HASH_LENGTH),
                author = author,
                timestamp = timestamp.toLongOrNull() ?: return@mapNotNull null,
                // A subject cannot hold a newline, but it may hold the field separator in theory; keep the rest.
                subject = fields.drop(3).joinToString(FIELD_SEPARATOR.toString()),
            )
        }

    companion object {
        const val DEFAULT_CHANGE_CAP = 500
        const val DEFAULT_PATCH_CAP = 256_000
        const val DEFAULT_BRANCH_CAP = 300
        /** Where a branch records the commit it was cut from (`<branch>=<sha>`), relative to the repository root. */
        const val EXPECTED_BASES_PATH = "superiorSoftLibrary/infantry/preflight/expected-bases.properties"
        /** The integration branch a ticket branch is cut from when it records no base of its own. */
        const val MVP_BRANCH = "feature-agenthq-mvp"
        private const val HEADS = "refs/heads/"
        private val FULL_SHA = Regex("^[0-9a-f]{40}$")
        private val WHITESPACE = Regex("\\s+")
        private const val SHORT_HASH_LENGTH = 8
        private const val FIELD_SEPARATOR = '\u001f'
        private const val RECORD_SEPARATOR = '\u001e'
        private const val LOG_FORMAT = "%H%x1f%an%x1f%at%x1f%s%x1e"

        /**
         * A path is handed to git only when it is repo-relative and cannot climb out of the repository:
         * no `..` segment, no absolute or drive-qualified form, no control character. Git is told
         * `--` before it as well, so a leading `-` is a file name, never an option.
         */
        internal fun checkRelativePath(raw: String): String {
            val path = raw.trim()
            require(path.isNotEmpty()) { "path is blank" }
            require(path.none { it.isISOControl() }) { "path contains a control character" }
            val normalised = path.replace('\\', '/')
            require(!normalised.startsWith("/")) { "path must be relative to the repository: '${path.take(80)}'" }
            require(!Regex("^[A-Za-z]:").containsMatchIn(normalised)) { "path must be relative to the repository: '${path.take(80)}'" }
            require(normalised.split('/').none { it == ".." }) { "path must not leave the repository: '${path.take(80)}'" }
            return normalised
        }

        /** A branch name has no control character, does not start with `-`, and has no `..` / `@{` / `\\`. */
        private fun isSafeBranchName(name: String): Boolean =
            name.isNotBlank() && name == name.trim() && name.none { it.isISOControl() } &&
                !name.startsWith("-") && !name.startsWith("/") && !name.startsWith("refs/") &&
                !name.contains("..") && !name.contains("@{") && !name.contains('\\') && name != "HEAD" && name != "@"

        /**
         * Refuse a branch name before git ever sees it. What passes is still only handed to git as
         * `refs/heads/<name>`, after `check-ref-format` and a lookup (see [resolveLocalBranch]).
         */
        internal fun checkBranchName(raw: String) {
            require(isSafeBranchName(raw)) { "not a valid local branch name: '${raw.filterNot { it.isISOControl() }.take(80)}'" }
        }

        /** A base is a branch name ([checkBranchName]) or a full 40-hex commit id. */
        internal fun checkBaseName(raw: String) {
            if (FULL_SHA.matches(raw)) return
            require(isSafeBranchName(raw)) { "not a valid base (local branch or full commit id): '${raw.filterNot { it.isISOControl() }.take(80)}'" }
        }
    }
}
