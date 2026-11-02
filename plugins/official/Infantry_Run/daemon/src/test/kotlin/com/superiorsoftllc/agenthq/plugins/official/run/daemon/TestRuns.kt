package com.superiorsoftllc.agenthq.plugins.official.run.daemon

import com.superiorsoftllc.agenthq.daemon.api.runs.RunHandle
import com.superiorsoftllc.agenthq.daemon.api.runs.RunListener
import com.superiorsoftllc.agenthq.daemon.api.runs.RunSpec
import com.superiorsoftllc.agenthq.daemon.api.runs.Runs
import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.runner.ProcessListener
import com.superiorsoftllc.agenthq.runner.ProcessRunner
import com.superiorsoftllc.agenthq.runner.ProcessSpec
import com.superiorsoftllc.agenthq.runner.TerminalListener
import com.superiorsoftllc.agenthq.runner.TerminalRunner
import java.io.File

/** Test double of the `api.runs` surface over a [ProcessRunner] (and an optional pty [TerminalRunner]); no cwd check (core's LocalRuns tests that). */
class TestRuns(private val pipes: ProcessRunner, private val pty: TerminalRunner? = null, private val secrets: (String) -> Map<String, String>? = { null }) : Runs {
    override val workRoot: File? get() = null
    override val configDir: File get() = File(".")
    override fun secretFields(name: String) = secrets(name)
    override fun start(spec: RunSpec, listener: RunListener): RunHandle {
        val dir = File(spec.cwd ?: ".")
        val ps = ProcessSpec(spec.argv, dir, spec.env)
        val t = pty
        val p0 = if (spec.terminal && t != null) try {
            t.start(ps, spec.cols, spec.rows, object : TerminalListener {
                override fun onBytes(bytes: ByteArray) = listener.onBytes(bytes)
                override fun onExit(exitCode: Int) = listener.onExit(exitCode)
            })
        } catch (e: LinkageError) { null } else null
        if (p0 != null) {
            val p = p0
            return object : RunHandle {
                override val pid get() = p.pid
                override val isAlive get() = p.isAlive
                override val terminal get() = true
                override fun write(bytes: ByteArray) = p.write(bytes)
                override fun resize(cols: Int, rows: Int) = p.resize(cols, rows)
                override fun terminate() = p.terminate()
                override fun killTree() = p.killTree()
            }
        }
        val p = pipes.start(ps, object : ProcessListener {
            override fun onStdout(line: String) = listener.onLine("stdout", line)
            override fun onStderr(line: String) = listener.onLine("stderr", line)
            override fun onExit(exitCode: Int) = listener.onExit(exitCode)
        })
        runCatching { p.closeInput() }
        return object : RunHandle {
            override val pid: Long? get() = runCatching { p.pid }.getOrNull()
            override val isAlive get() = p.isAlive
            override val terminal get() = false
            override fun write(bytes: ByteArray) = throw IllegalStateException("the run has no terminal")
            override fun resize(cols: Int, rows: Int) {}
            override fun terminate() = p.terminate()
            override fun killTree() = p.killTree()
        }
    }
}

/** The old test call shape (a runner, optionally a pty runner) over [TestRuns]. */
fun RunManager(
    registry: () -> CommandRegistry, runner: ProcessRunner, emit: (Envelope) -> Unit, now: () -> Long = System::currentTimeMillis,
    terminal: TerminalRunner? = null, limits: RunManager.RunLimits = RunManager.RunLimits(), restricted: () -> Boolean = { false },
    secrets: (String) -> Map<String, String>? = { null },
) = RunManager(registry, TestRuns(runner, terminal, secrets), emit, now, limits = limits, restricted = restricted, secrets = secrets)

fun RunManager(registry: CommandRegistry, runner: ProcessRunner, emit: (Envelope) -> Unit, now: () -> Long = System::currentTimeMillis) =
    RunManager({ registry }, runner, emit, now)
