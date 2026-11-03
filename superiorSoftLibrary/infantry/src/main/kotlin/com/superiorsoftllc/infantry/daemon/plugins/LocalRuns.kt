package com.superiorsoftllc.infantry.daemon.plugins

import com.superiorsoftllc.infantry.daemon.api.runs.RunHandle
import com.superiorsoftllc.infantry.daemon.api.runs.RunListener
import com.superiorsoftllc.infantry.daemon.api.runs.RunSpec
import com.superiorsoftllc.infantry.daemon.api.runs.Runs
import com.superiorsoftllc.infantry.runner.ProcessListener
import com.superiorsoftllc.infantry.runner.ProcessRunner
import com.superiorsoftllc.infantry.runner.ProcessSpec
import com.superiorsoftllc.infantry.runner.RunningProcess
import com.superiorsoftllc.infantry.runner.TerminalListener
import com.superiorsoftllc.infantry.runner.TerminalProcess
import com.superiorsoftllc.infantry.runner.TerminalRunner
import java.io.File

/**
 * CMD-1: the local binding of [Runs] over infantryRunner (a pty runner and a pipe runner). The cwd is resolved against a root
 * (the project's directory, else [workRoot]) and refused when it leaves it, before anything starts.
 */
class LocalRuns(
    override val workRoot: File?,
    override val configDir: File,
    private val projectDirectory: (String) -> String?,
    private val pipes: ProcessRunner,
    private val pty: TerminalRunner?,
    private val secrets: (String) -> Map<String, String>?,
) : Runs {
    override fun secretFields(name: String): Map<String, String>? = secrets(name)

    /** The canonical directory a run in [projectId] (else [workRoot]) is rooted at. */
    private fun root(projectId: String?): File {
        val raw = if (projectId.isNullOrBlank()) workRoot?.path else projectDirectory(projectId)
        requireNotNull(raw?.takeIf { it.isNotBlank() }) { if (projectId.isNullOrBlank()) "no working root is set" else "project '${projectId.take(80)}' has no directory" }
        return runCatching { File(raw).toPath().toRealPath().toFile() }.getOrNull()?.takeIf { it.isDirectory }
            ?: throw IllegalArgumentException("the run root does not exist")
    }

    /** [cwd] relative to [root], canonical, inside it; else [IllegalArgumentException]. */
    internal fun resolve(root: File, cwd: String?): File {
        val rel = cwd?.takeIf { it.isNotBlank() } ?: return root
        val bad = rel.split('/', '\\').any { it == ".." } || rel.startsWith("/") || rel.startsWith("\\") || File(rel).isAbsolute || Regex("^[A-Za-z]:").containsMatchIn(rel)
        require(!bad) { "the working directory is outside the project" }
        val resolved = runCatching { File(root, rel).toPath().toRealPath().toFile() }.getOrNull()
            ?: throw IllegalArgumentException("the working directory does not exist")
        require(resolved.toPath().startsWith(root.toPath())) { "the working directory is outside the project" }
        return resolved
    }

    override fun start(spec: RunSpec, listener: RunListener): RunHandle {
        require(spec.argv.isNotEmpty() && spec.argv.none { it.isBlank() }) { "a run needs a non-empty argv" }
        val dir = resolve(root(spec.projectId), spec.cwd)
        val ps = ProcessSpec(spec.argv, dir, spec.env)
        val terminal = pty
        if (spec.terminal && terminal != null) {
            val started = try {
                terminal.start(ps, spec.cols, spec.rows, object : TerminalListener {
                    override fun onBytes(bytes: ByteArray) = listener.onBytes(bytes)
                    override fun onExit(exitCode: Int) = listener.onExit(exitCode)
                })
            } catch (e: LinkageError) { null } // no pty library on this host: pipes
            if (started != null) return PtyHandle(started)
        }
        val p = pipes.start(ps, object : ProcessListener {
            override fun onStdout(line: String) = listener.onLine("stdout", line)
            override fun onStderr(line: String) = listener.onLine("stderr", line)
            override fun onExit(exitCode: Int) = listener.onExit(exitCode)
        })
        runCatching { p.closeInput() }
        return PipeHandle(p)
    }

    private class PtyHandle(private val p: TerminalProcess) : RunHandle {
        override val pid get() = p.pid
        override val isAlive get() = p.isAlive
        override val terminal get() = true
        override fun write(bytes: ByteArray) = p.write(bytes)
        override fun resize(cols: Int, rows: Int) = p.resize(cols, rows)
        override fun terminate() = p.terminate()
        override fun killTree() = p.killTree()
    }

    private class PipeHandle(private val p: RunningProcess) : RunHandle {
        override val pid: Long? get() = runCatching { p.pid }.getOrNull()
        override val isAlive get() = p.isAlive
        override val terminal get() = false
        override fun write(bytes: ByteArray) = throw IllegalStateException("the run has no terminal")
        override fun resize(cols: Int, rows: Int) {}
        override fun terminate() = p.terminate()
        override fun killTree() = p.killTree()
    }
}
