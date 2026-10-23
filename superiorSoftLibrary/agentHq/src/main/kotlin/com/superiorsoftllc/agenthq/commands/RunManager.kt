package com.superiorsoftllc.agenthq.commands

import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.runner.ProcessListener
import com.superiorsoftllc.agenthq.runner.ProcessRunner
import com.superiorsoftllc.agenthq.runner.ProcessSpec
import com.superiorsoftllc.agenthq.runner.RunningProcess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

/** Contract 1.33.0 `run` frame. `state` is STARTING, RUNNING or FINISHED. */
@Serializable
data class RunRecord(
    val runId: String,
    val commandId: String,
    val state: String,
    val startedAt: Long,
    val pid: Long? = null,
    val lastSequence: Long = 0,
)

@Serializable
data class RunOutput(val runId: String, val sequence: Long, val stream: String, val line: String, val truncated: Boolean = false)

@Serializable
data class RunFinished(val runId: String, val exitCode: Int? = null, val outcome: String, val endedAt: Long, val detail: String? = null)

/**
 * IN-APP BUILD T3: runs ONE saved command at a time through a [ProcessRunner] (argv list, never a shell string).
 *
 * `run_output` sequences are 1..n per run with no gap or repeat; exactly one `run_finished` follows per run.
 * Frames go out through [emit] (broadcast); the `run` answer is handed back to the caller so it can be sent
 * BEFORE [Started.launch] lets any output flow.
 */
class RunManager(
    /** Read on every `run_start`, so a `command_save` / `command_delete` (RUN-CMD-1) is seen by the next run. */
    private val registry: () -> CommandRegistry,
    private val runner: ProcessRunner,
    private val emit: (Envelope) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** A fixed set of commands (the tests, and any caller with no store behind it). */
    constructor(registry: CommandRegistry, runner: ProcessRunner, emit: (Envelope) -> Unit, now: () -> Long = System::currentTimeMillis) :
        this({ registry }, runner, emit, now)

    /** The answer to a `run_start`, and the thunk that actually starts the child. */
    class Started internal constructor(val reply: Envelope, private val launcher: (() -> Unit)?) {
        fun launch() { launcher?.invoke() }
    }

    private val lock = Any()
    private var inFlight: Run? = null

    fun start(data: String): Started {
        val commandId = runCatching { wire.parseToJsonElement(data) }.getOrNull()
            ?.let { (it as? kotlinx.serialization.json.JsonObject)?.get("commandId") as? kotlinx.serialization.json.JsonPrimitive }
            ?.takeIf { it.isString }?.content
            ?: return refuse("run_start expects {\"commandId\":\"<id>\"}")
        val run: Run
        val command: SavedCommand
        // The lookup is under [lock] too, so a save or delete running in [unlessRunning] cannot slip between them.
        synchronized(lock) {
            command = registry().find(commandId) ?: return refuse("run_start refused: unknown command '$commandId'")
            if (inFlight != null) return refuse("run_start refused: a run is already in flight")
            run = Run(UUID.randomUUID().toString(), command, now())
            inFlight = run
        }
        val record = RunRecord(run.runId, command.id, "STARTING", run.startedAt)
        return Started(Envelope(Envelope.RUN, wire.encodeToString(RunRecord.serializer(), record))) { run.launch() }
    }

    /**
     * `run_stop "<runId> [force]"`: returns an ERROR frame on bad input, or null when a stop was begun.
     * The answer to a stop is the run's single `run_finished` (KILLED_GRACEFUL or KILLED_FORCED), sent from
     * a background thread so the socket handler never waits out the grace window.
     */
    fun stop(data: String): Envelope? {
        val parts = data.trim().split(Regex("[ \t]+"))
        if (parts.isEmpty() || parts[0].isEmpty() || parts.size > 2 || (parts.size == 2 && parts[1] != "force"))
            return Envelope(Envelope.ERROR, "run_stop expects '<runId> [force]'")
        val run = synchronized(lock) { inFlight }?.takeIf { it.runId == parts[0] }
            ?: return Envelope(Envelope.ERROR, "run_stop refused: no such run in flight")
        run.stop(force = parts.size == 2)
        return null
    }

    /**
     * RUN-CMD-1: runs [change] (a save or delete of [commandId]) only while that command has no run in flight, and
     * under the same lock `run_start` takes, so no run of it can start halfway through. Returns the refusal reason
     * when it is running, else whatever [change] returns (null = done, a string = its own refusal).
     */
    fun unlessRunning(commandId: String, change: () -> String?): String? = synchronized(lock) {
        if (inFlight?.command?.id == commandId) "command '$commandId' is running; stop it first" else change()
    }

    private fun refuse(why: String) = Started(Envelope(Envelope.ERROR, why), null)

    private inner class Run(val runId: String, val command: SavedCommand, val startedAt: Long) {
        private val outLock = Any()
        private var sequence = 0L
        private var capped = false
        private var finished = false
        @Volatile private var process: RunningProcess? = null
        @Volatile private var stopMode: String? = null // null, KILLED_GRACEFUL or KILLED_FORCED

        fun stop(force: Boolean) {
            val p = process ?: return // not started yet or spawn failed: finish() already owns the outcome
            synchronized(outLock) { if (finished || stopMode != null) return; stopMode = if (force) "KILLED_FORCED" else "KILLED_GRACEFUL" }
            Thread({
                if (!force) {
                    runCatching { p.terminate() }
                    val end = System.nanoTime() + command.graceMillis * 1_000_000
                    while (!isFinished() && p.isAlive && System.nanoTime() < end) Thread.sleep(10)
                    if (!p.isAlive) { settle(p); return@Thread }
                    synchronized(outLock) { if (!finished) stopMode = "KILLED_FORCED" }
                }
                runCatching { p.killTree() }
                settle(p)
            }, "agenthq-run-stop").apply { isDaemon = true }.start()
        }

        private fun isFinished() = synchronized(outLock) { finished }

        /** Give onExit a moment to report the real code; otherwise finish with a null one. Never a plain FAILED. */
        private fun settle(p: RunningProcess) {
            val end = System.nanoTime() + 2_000_000_000L
            while (!isFinished() && System.nanoTime() < end) Thread.sleep(10)
            finish(null, stopMode ?: "KILLED_FORCED", null)
        }

        fun launch() {
            val listener = object : ProcessListener {
                override fun onStdout(line: String) = lines("stdout", line)
                override fun onStderr(line: String) = lines("stderr", line)
                override fun onExit(exitCode: Int) = finish(exitCode, stopMode ?: if (exitCode == 0) "SUCCEEDED" else "FAILED", null)
            }
            val started: RunningProcess = try {
                runner.start(ProcessSpec(command.argv, command.workingDir), listener)
            } catch (e: Exception) {
                // Never the argv or the message (it can quote it): the class name only.
                finish(null, "FAILED", "spawn failed (${e::class.simpleName})")
                return
            }
            process = started
            runCatching { started.closeInput() }
        }

        /** Split on \n, \r and \r\n; a trailing break does not make an empty extra line. */
        private fun lines(stream: String, text: String) {
            val parts = text.split(BREAK)
            val usable = if (parts.size > 1 && parts.last().isEmpty()) parts.dropLast(1) else parts
            for (p in usable) output(stream, p)
        }

        private fun output(stream: String, raw: String) = synchronized(outLock) {
            if (finished || capped) return@synchronized
            if (sequence >= MAX_LINES) {
                capped = true
                sendOutput(stream, "[output truncated]", false)
                return@synchronized
            }
            val cut = raw.length > MAX_LINE_CHARS
            sendOutput(stream, if (cut) raw.substring(0, MAX_LINE_CHARS) else raw, cut)
        }

        private fun sendOutput(stream: String, line: String, truncated: Boolean) {
            sequence += 1
            emit(Envelope(Envelope.RUN_OUTPUT, wire.encodeToString(RunOutput.serializer(), RunOutput(runId, sequence, stream, line, truncated))))
        }

        fun finish(exitCode: Int?, outcome: String, detail: String?) {
            synchronized(outLock) {
                if (finished) return
                finished = true
                synchronized(lock) { if (inFlight === this) inFlight = null }
                emit(Envelope(Envelope.RUN_FINISHED, wire.encodeToString(RunFinished.serializer(), RunFinished(runId, exitCode, outcome, now(), detail))))
            }
        }
    }

    companion object {
        const val MAX_LINES = 10_000
        const val MAX_LINE_CHARS = 8 * 1024
        private val BREAK = Regex("\r\n|\r|\n")
        private val wire = Json { encodeDefaults = true }
    }
}
