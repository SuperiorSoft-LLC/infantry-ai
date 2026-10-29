package com.superiorsoftllc.agenthq.runner

import java.io.File

/**
 * STALL-1 (ticket 802c64d5) item 2, the FORCED-BY-DEFAULT runner: a shim directory the daemon puts FIRST on every worker
 * session's PATH. A bare `gradle`, `mvn`, `npm`, `yarn` or `pnpm` the worker (or its harness's shell, foreground or
 * background) runs therefore goes through `agenthq bg run --attached --shim <name> -- <name> ...`: the watched runner
 * (START line, `still running` every minute, END sentinel with exit code and byte count, log under the bg log dir,
 * registered so the daemon tells the session when it ends while the session is at rest). The worker chooses nothing.
 *
 * What a PATH shim cannot catch, and so is NOT forced (docs/STALL.md): a program named by a path (`./gradlew`,
 * `gradlew.bat` from the cwd, which cmd.exe and the shells resolve before PATH), and any program not in [PROGRAMS].
 * Those are covered by the daemon's process-tree watch (layer 5) and the stall detector instead, without an exit code.
 *
 * A nested call (npm running npm) and a session with the watch off (`limits.watch.forced` = 0, env `AGENTHQ_WATCH=off`)
 * run the real program directly: the CLI checks `AGENTHQ_WATCHED` / `AGENTHQ_WATCH` before it wraps anything.
 */
object WatchShims {
    val PROGRAMS = listOf("gradle", "mvn", "npm", "yarn", "pnpm")

    /**
     * Writes the shims into [dir] (a `.cmd` and an extensionless `sh` script per program on Windows, where Git Bash runs
     * the latter; the `sh` script alone elsewhere). [launcher] is the argv that starts the `agenthq` CLI. Returns [dir].
     */
    fun install(dir: File, launcher: List<String>, windows: Boolean = isWindows()): File {
        dir.mkdirs()
        for (p in PROGRAMS) {
            val args = listOf("bg", "run", "--attached", "--shim", p, "--", p)
            val sh = File(dir, p)
            sh.writeText(
                "#!/bin/sh\n# Infantry AI STALL-1 watch shim (generated; the daemon rewrites it at start)\n" +
                    "exec " + (launcher + args).joinToString(" ") { shQuote(toShPath(it, windows)) } + " \"\$@\"\n",
            )
            sh.setExecutable(true)
            if (windows) {
                val first = launcher.first()
                val call = if (first.endsWith(".cmd", true) || first.endsWith(".bat", true)) "call " else ""
                File(dir, "$p.cmd").writeText(
                    "@echo off\r\nrem Infantry AI STALL-1 watch shim (generated; the daemon rewrites it at start)\r\n" +
                        call + (launcher + args).joinToString(" ") { cmdQuote(it) } + " %*\r\n" +
                        "exit /b %ERRORLEVEL%\r\n",
                )
            }
        }
        return dir
    }

    /** The CLI launcher argv: the installed `agenthq` script in [binDir] when there is one, else this JVM + its classpath. */
    fun launcher(binDir: File?, windows: Boolean = isWindows()): List<String> {
        val script = binDir?.let { d ->
            (if (windows) listOf("agenthq.cmd", "agenthq.bat") else listOf("agenthq")).map { File(d, it) }.firstOrNull { it.isFile }
        }
        if (script != null) return listOf(script.absolutePath)
        val java = ProcessHandle.current().info().command().orElse("java")
        return listOf(java, "-cp", System.getProperty("java.class.path"), "com.superiorsoftllc.agenthq.cli.CliMainKt")
    }

    /**
     * The session env: [dir] first on PATH (before [inheritedPath], the PATH the session would get otherwise) and the
     * shim dir named, or, with [forced] false, `AGENTHQ_WATCH=off` and PATH untouched.
     */
    fun sessionEnv(dir: File?, inheritedPath: String?, forced: Boolean): Map<String, String> {
        if (!forced || dir == null) return mapOf("AGENTHQ_WATCH" to "off")
        val path = dir.absolutePath + (inheritedPath?.takeIf { it.isNotBlank() }?.let { File.pathSeparator + it } ?: "")
        return mapOf("PATH" to path, "AGENTHQ_SHIM_DIR" to dir.absolutePath, "AGENTHQ_WATCH" to "on")
    }

    private fun isWindows() = System.getProperty("os.name").lowercase().contains("win")

    private fun cmdQuote(s: String) = if (s.isNotEmpty() && s.none { it == ' ' || it == '&' || it == '(' || it == ')' || it == ';' }) s else "\"$s\""

    private fun shQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"

    /** `C:\x\y` -> `/c/x/y` for Git Bash; other strings unchanged. */
    private fun toShPath(s: String, windows: Boolean): String {
        if (!windows || s.length < 3 || s[1] != ':' || (s[2] != '\\' && s[2] != '/')) return s
        if (s.contains(';')) return s // a classpath: the JVM reads it, not the shell
        return "/" + s[0].lowercaseChar() + s.substring(2).replace('\\', '/')
    }
}
