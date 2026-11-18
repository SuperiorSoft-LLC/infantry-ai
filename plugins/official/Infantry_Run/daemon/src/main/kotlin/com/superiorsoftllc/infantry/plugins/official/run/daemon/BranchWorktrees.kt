// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.run.daemon

import com.superiorsoftllc.infantry.daemon.api.runs.RunListener
import com.superiorsoftllc.infantry.daemon.api.runs.RunSpec
import com.superiorsoftllc.infantry.daemon.api.runs.Runs
import kotlinx.serialization.Serializable
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * PREVIEW-BRANCH-1: the run-time variables of a saved command. A command whose cwd or argv names [WORKTREE] or [BRANCH] is a
 * BRANCH command: each start names a branch and runs in that branch's checkout. In a branch command's argv, [ROOT] (the project
 * folder, absolute) reaches a file of the main checkout (a script that a branch cut earlier does not have).
 */
object BranchVars {
    const val WORKTREE = "{worktree}"
    const val BRANCH = "{branch}"
    const val ROOT = "{root}"

    /** True when [argv] or [cwd] names [WORKTREE] or [BRANCH]. */
    fun isBranchCommand(argv: List<String>, cwd: String?): Boolean =
        (listOfNotNull(cwd) + argv).any { it.contains(WORKTREE) || it.contains(BRANCH) }

    /** True when [text] names any variable. */
    fun uses(text: String?): Boolean = text != null && (text.contains(WORKTREE) || text.contains(BRANCH) || text.contains(ROOT))

    /**
     * A templated cwd is `{worktree}` or `{worktree}/<relative path>`: no other variable, no `..`, nothing absolute after it.
     * Null when it is valid, else why not.
     */
    fun cwdRefusal(cwd: String): String? {
        val t = cwd.trim().replace('\\', '/')
        if (!t.startsWith(WORKTREE)) return "a cwd with a variable must start with $WORKTREE"
        val rest = t.removePrefix(WORKTREE)
        if (rest.isNotEmpty() && !rest.startsWith("/")) return "a cwd with a variable must be $WORKTREE or $WORKTREE/<path>"
        if (uses(rest)) return "a cwd names $WORKTREE once and no other variable"
        if (rest.split('/').any { it == ".." }) return "a cwd may not leave the worktree (..)"
        return null
    }

    /** [text] with the variables replaced; a variable whose value is null is left as written. */
    fun substitute(text: String, worktree: String?, branch: String?, root: String?): String {
        var s = text
        if (worktree != null) s = s.replace(WORKTREE, worktree)
        if (branch != null) s = s.replace(BRANCH, branch)
        if (root != null) s = s.replace(ROOT, root)
        return s
    }
}

/** One local branch for the picker: its name and, when it is checked out in a worktree other than the main one, that folder. */
@Serializable
data class BranchInfo(val name: String, val worktree: String? = null)

/** The checkout a branch run uses: [dir], and whether this run [created] it (then the run removes it when it ends). */
class PreparedWorktree(val dir: File, val created: Boolean, val branch: String)

/** The result of one git call: exit code (-1 = it did not finish in time or did not start) and its output lines. */
class GitResult(val code: Int, val out: List<String>)

/** How [BranchWorktrees] runs git: argv after `git`, in [dir], for a run of [projectId]. */
fun interface Git {
    fun run(projectId: String?, dir: File, args: List<String>): GitResult
}

/**
 * Git over the plugin's ONE process seam, `api.runs` (piped, no pty), waiting at most [timeoutMillis]. A call that does not end
 * in time is killed and answers -1.
 */
class RunsGit(private val runs: Runs, private val timeoutMillis: () -> Long = { DEFAULT_GIT_TIMEOUT_MILLIS }) : Git {
    override fun run(projectId: String?, dir: File, args: List<String>): GitResult {
        val lines = java.util.Collections.synchronizedList(ArrayList<String>())
        val done = CountDownLatch(1)
        var code = -1
        val handle = try {
            runs.start(RunSpec(listOf("git", "-C", dir.path) + args, null, projectId = projectId), object : RunListener {
                override fun onLine(stream: String, line: String) { lines += line }
                override fun onExit(exitCode: Int) { code = exitCode; done.countDown() }
            })
        } catch (e: Exception) {
            return GitResult(-1, listOf("git could not start (${e::class.simpleName})"))
        }
        if (!done.await(timeoutMillis(), TimeUnit.MILLISECONDS)) {
            runCatching { handle.killTree() }
            return GitResult(-1, lines.toList() + "git did not finish in ${timeoutMillis()} ms")
        }
        return GitResult(code, lines.toList())
    }

    companion object {
        /** A `git worktree add` of a large repository checks out every file; two minutes covers it on a slow disk. */
        const val DEFAULT_GIT_TIMEOUT_MILLIS = 120_000L
    }
}

/**
 * PREVIEW-BRANCH-1: a branch's checkout for a branch run. An existing worktree on the branch (a ticket's) is used as it is;
 * otherwise a DETACHED worktree at the branch tip is made under `<root>/.worktrees/preview-<slug>` (detached, so it works while
 * the branch is checked out elsewhere) and removed when the run ends. The main checkout (the owner's working tree) is never used,
 * checked out over or removed; [remove] refuses anything that is not a `preview-` folder under `<root>/.worktrees`.
 */
class BranchWorktrees(private val git: Git) {

    /** Local branches, each with its worktree when one (other than the main checkout and [root]) has it checked out. */
    fun branches(root: File, projectId: String?): Result<List<BranchInfo>> {
        val refs = git.run(projectId, root, listOf("for-each-ref", "--format=%(refname:short)", "refs/heads"))
        if (refs.code != 0) return Result.failure(IllegalStateException("git could not list branches: ${refs.out.lastOrNull() ?: "exit ${refs.code}"}"))
        val trees = worktrees(root, projectId)
        return Result.success(refs.out.map { it.trim() }.filter { it.isNotEmpty() }.map { BranchInfo(it, trees[it]?.path) })
    }

    /** Finds or makes the checkout for [branch]. A failure's message is safe to show (a git line, never an argv of the run). */
    fun prepare(root: File, projectId: String?, branch: String): Result<PreparedWorktree> {
        if (!isBranchName(branch)) return no("'$branch' is not a branch name")
        val verify = git.run(projectId, root, listOf("rev-parse", "--verify", "--quiet", "refs/heads/$branch"))
        if (verify.code != 0) return no("no local branch '$branch'")
        worktrees(root, projectId)[branch]?.let { return Result.success(PreparedWorktree(it, created = false, branch = branch)) }
        val dir = File(File(root, WORKTREES_DIR), PREFIX + slug(branch))
        // A leftover of a run that died with the daemon: remove it, so the preview is the branch tip as it is now.
        if (dir.exists() && !remove(root, projectId, dir)) return no("the old preview folder ${dir.name} could not be removed")
        val add = git.run(projectId, root, listOf("worktree", "add", "--detach", dir.path, "refs/heads/$branch"))
        if (add.code != 0 || !dir.isDirectory) {
            if (dir.exists()) remove(root, projectId, dir)
            return no("git worktree add failed: ${add.out.lastOrNull { it.isNotBlank() } ?: "exit ${add.code}"}")
        }
        return Result.success(PreparedWorktree(canonical(dir), created = true, branch = branch))
    }

    /**
     * Removes the preview worktree [dir] (git first, then the folder, then `git worktree prune`). Only a `preview-` folder
     * directly under `<root>/.worktrees` is touched; anything else answers false without a change. True when it is gone.
     */
    fun remove(root: File, projectId: String?, dir: File): Boolean {
        if (!isPreviewDir(root, dir)) return false
        repeat(REMOVE_ATTEMPTS) { attempt ->
            git.run(projectId, root, listOf("worktree", "remove", "--force", dir.path))
            if (dir.exists()) runCatching { dir.deleteRecursively() }
            if (!dir.exists()) { git.run(projectId, root, listOf("worktree", "prune")); return true }
            // Windows keeps a file locked for a moment after the process that held it died.
            if (attempt < REMOVE_ATTEMPTS - 1) Thread.sleep(REMOVE_RETRY_MILLIS)
        }
        git.run(projectId, root, listOf("worktree", "prune"))
        return !dir.exists()
    }

    /** branch name -> worktree folder, for every worktree with a branch except the first (main) one and [root] itself. */
    private fun worktrees(root: File, projectId: String?): Map<String, File> {
        val list = git.run(projectId, root, listOf("worktree", "list", "--porcelain"))
        if (list.code != 0) return emptyMap()
        val rootPath = canonical(root).path
        val out = LinkedHashMap<String, File>()
        var first = true
        var path: String? = null
        var branch: String? = null
        fun flush() {
            val p = path
            val b = branch
            if (p != null && !first && b != null) {
                val f = canonical(File(p))
                if (f.path != rootPath && f.isDirectory) out.putIfAbsent(b, f)
            }
            if (p != null) first = false
            path = null; branch = null
        }
        for (raw in list.out + "") {
            val line = raw.trim()
            when {
                line.isEmpty() -> flush()
                line.startsWith("worktree ") -> { if (path != null) flush(); path = line.removePrefix("worktree ") }
                line.startsWith("branch refs/heads/") -> branch = line.removePrefix("branch refs/heads/")
            }
        }
        return out
    }

    private fun isPreviewDir(root: File, dir: File): Boolean {
        val parent = canonical(File(root, WORKTREES_DIR))
        val d = canonical(dir)
        return d.parentFile?.path == parent.path && d.name.startsWith(PREFIX) && d.name.length > PREFIX.length
    }

    private fun no(why: String) = Result.failure<PreparedWorktree>(IllegalArgumentException(why))

    companion object {
        const val WORKTREES_DIR = ".worktrees"
        const val PREFIX = "preview-"
        /** Tries to remove a preview worktree before giving up (a Windows file lock outlives its process briefly). */
        const val REMOVE_ATTEMPTS = 5
        const val REMOVE_RETRY_MILLIS = 1_000L
        /** The longest branch name and folder slug accepted (git's own limit is far higher; a Windows path is not). */
        const val MAX_BRANCH_CHARS = 200
        const val MAX_SLUG_CHARS = 60

        private val BRANCH = Regex("^[A-Za-z0-9][A-Za-z0-9._/-]*$")

        /** A safe local branch name: starts with a letter or digit, no `..`, `//`, trailing `/` `.` or `.lock`, no option look-alike. */
        fun isBranchName(s: String): Boolean =
            s.length <= MAX_BRANCH_CHARS && BRANCH.matches(s) && !s.contains("..") && !s.contains("//") &&
                !s.endsWith("/") && !s.endsWith(".") && !s.endsWith(".lock")

        /** The preview folder name part for [branch]: lower case, `[a-z0-9._-]`, at most [MAX_SLUG_CHARS]. */
        fun slug(branch: String): String =
            branch.lowercase().replace(Regex("[^a-z0-9._-]+"), "-").trim('-', '.').take(MAX_SLUG_CHARS).ifEmpty { "branch" }

        private fun canonical(f: File): File = runCatching { f.canonicalFile }.getOrDefault(f.absoluteFile)
    }
}
