package com.superiorsoftllc.agenthq.worktree

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The outcome of one `git` invocation. A non-zero exit is DATA, never an exception: git reports
 * "branch already exists" / "not a git repository" on stderr with a normal exit, and the worktree
 * manager must be able to read that and react rather than unwind.
 */
data class GitResult(
    val args: List<String>,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    val ok: Boolean get() = exitCode == 0

    /** stdout without its trailing newline (git always ends a captured line with one). */
    fun out(): String = stdout.trim()

    /** stderr, falling back to stdout when git wrote its complaint to the other stream. */
    fun err(): String = stderr.trim().ifEmpty { stdout.trim() }

    override fun toString(): String = "git ${args.joinToString(" ")} -> $exitCode"
}

/**
 * The git seam (Phase 4).
 *
 * Shelling out to the real `git` is DELIBERATE, and stated so it is not "improved" later: linked
 * worktrees, branch bookkeeping and `rev-list` ahead/behind arithmetic are exactly the surface a
 * re-implementation (JGit) gets subtly wrong, and every tool in this space shells out. One narrow
 * method also makes the manager trivially testable with a fake and keeps every git detail in one file.
 */
fun interface GitCli {
    /** Run `git <args>` with [cwd] as the working directory. Never throws on a non-zero exit. */
    fun run(cwd: File, args: List<String>): GitResult
}

/**
 * [GitCli] over the OS `git`.
 *
 * Two robustness facts are load-bearing, the same ones the process runner learned in Phase 0:
 *  1. stdout and stderr are drained on SEPARATE threads -- a `rev-list` on a busy repo can fill a
 *     pipe while git writes a warning to stderr, and reading them in sequence would deadlock;
 *  2. a hung git (an index lock, a credential prompt) is bounded by [timeoutMs] and reported as
 *     exit code 124, so it can never hang an agent's session forever.
 *
 * [extraEnv] is how tests inject a committer identity (`GIT_AUTHOR_NAME`, ...) without touching the
 * machine's global git config.
 */
class SystemGitCli(
    private val timeoutMs: Long = 60_000,
    private val extraEnv: Map<String, String> = emptyMap(),
    /**
     * CLIPATH-1: null (the default) = resolved per run by the one resolver
     * ([com.superiorsoftllc.agenthq.executables.CliExecutables]: Settings override, `AGENTHQ_GIT_EXE`, the known install
     * locations in [com.superiorsoftllc.agenthq.executables.CliTools.GIT], PATH), so a Settings change applies to the
     * next git call. Not found keeps the bare `git`, as before, and the OS reports the failure.
     */
    private val executable: String? = null,
) : GitCli {

    override fun run(cwd: File, args: List<String>): GitResult {
        val exe = executable ?: com.superiorsoftllc.agenthq.executables.CliExecutables
            .resolve(com.superiorsoftllc.agenthq.executables.CliTools.GIT).path ?: "git"
        val builder = ProcessBuilder(listOf(exe) + args)
            .directory(cwd)
            .redirectErrorStream(false)
        if (extraEnv.isNotEmpty()) builder.environment().putAll(extraEnv)
        // These inherited overrides bypass the explicit cwd and can break discovery or redirect
        // worktree operations into another repository (including the daemon's long-GIT_DIR failure).
        listOf(
            "GIT_DIR",
            "GIT_WORK_TREE",
            "GIT_COMMON_DIR",
            "GIT_INDEX_FILE",
            "GIT_OBJECT_DIRECTORY",
            "GIT_ALTERNATE_OBJECT_DIRECTORIES",
            "GIT_NAMESPACE",
            "GIT_PREFIX",
        ).forEach(builder.environment()::remove)
        // AgentHQ has no terminal to answer a credential prompt; fail instead of blocking.
        builder.environment()["GIT_TERMINAL_PROMPT"] = "0"

        val process = builder.start()
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val outThread = Thread({ process.inputStream.bufferedReader().forEachLine { stdout.appendLine(it) } }, "agenthq-git-out")
        val errThread = Thread({ process.errorStream.bufferedReader().forEachLine { stderr.appendLine(it) } }, "agenthq-git-err")
        outThread.isDaemon = true
        errThread.isDaemon = true
        outThread.start()
        errThread.start()

        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            outThread.join(500)
            errThread.join(500)
            return GitResult(args, 124, stdout.toString(), "git timed out after ${timeoutMs}ms")
        }
        outThread.join(2_000)
        errThread.join(2_000)
        return GitResult(args, process.exitValue(), stdout.toString(), stderr.toString())
    }
}

/** True when [dir] is inside a git work tree (the main checkout or a linked worktree). */
fun GitCli.isRepo(dir: File): Boolean {
    if (!dir.isDirectory) return false
    val result = run(dir, listOf("rev-parse", "--is-inside-work-tree"))
    return result.ok && result.out() == "true"
}
