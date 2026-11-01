package com.superiorsoftllc.agenthq.testrun

import java.io.File
import java.nio.file.FileSystems
import java.util.concurrent.TimeUnit

/** FULLSUITE-2: runs the command through the shell at low priority (nice / start /low), no gating, output bounded. */
class ProcessSuiteExec(private val windows: Boolean = System.getProperty("os.name").lowercase().contains("win")) : SuiteExec {
    override fun run(command: String, dir: File, timeoutMinutes: Long): SuiteExec.Out {
        val argv = if (windows) listOf("cmd", "/c", "start", "\"\"", "/LOW", "/WAIT", "/B", "cmd", "/c", command) else listOf("nice", "-n", "19", "sh", "-c", command)
        val p = ProcessBuilder(argv).directory(dir).redirectErrorStream(true).start()
        val out = StringBuilder()
        val reader = Thread { p.inputStream.bufferedReader().useLines { ls -> ls.forEach { if (out.length < MAX_OUT) out.appendLine(it) } } }.apply { isDaemon = true; start() }
        val done = if (timeoutMinutes > 0) p.waitFor(timeoutMinutes, TimeUnit.MINUTES) else { p.waitFor(); true }
        if (!done) { p.descendants().forEach { it.destroyForcibly() }; p.destroyForcibly(); p.waitFor() }
        reader.join(2000)
        return SuiteExec.Out(if (done) p.exitValue() else 124, if (done) out.toString() else out.toString() + "timed out after $timeoutMinutes min\n")
    }

    private companion object { const val MAX_OUT = 200_000 }
}

/** FULLSUITE-2: git-backed environment: the project's repo, a detached worktree of the branch under [workRoot]. */
class GitSuiteEnv(
    private val repo: File,
    private val workRoot: File,
    private val area: (String) -> List<String> = { emptyList() },
    private val clock: () -> Long = System::currentTimeMillis,
) : SuiteEnv {
    private fun git(dir: File, vararg a: String): String {
        val p = ProcessBuilder(listOf("git") + a).directory(dir).redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { "git ${a.joinToString(" ")} failed: ${text.trim().take(300)}" }
        return text.trim()
    }

    override fun prepareWorktree(branch: String): File {
        if (!File(workRoot, ".git").exists()) { workRoot.parentFile?.mkdirs(); git(repo, "worktree", "add", "--detach", workRoot.absolutePath, branch) }
        else git(workRoot, "checkout", "--detach", branch)
        return workRoot
    }

    override fun headSha(dir: File) = git(dir, "rev-parse", "HEAD")

    override fun results(dir: File, glob: String?): List<File> {
        if (glob.isNullOrBlank()) return emptyList()
        val m = FileSystems.getDefault().getPathMatcher("glob:$glob")
        return dir.walkTopDown().filter { it.isFile && m.matches(dir.toPath().relativize(it.toPath())) }.toList()
    }

    override fun suspectedArea(className: String) = area(className)

    override fun freeMemoryPercent(): Int = (java.lang.management.ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean)
        ?.let { (it.freeMemorySize * 100 / it.totalMemorySize).toInt() } ?: 100

    override fun now() = clock()
}
