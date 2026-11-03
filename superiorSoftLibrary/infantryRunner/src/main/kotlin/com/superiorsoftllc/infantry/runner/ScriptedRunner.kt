package com.superiorsoftllc.infantry.runner

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A [ProcessRunner] that spawns NOTHING: it replays a scripted child in memory.
 *
 * This is the transport the Phase 9 stub provider returns from `AgentProvider.transport()`, so
 * `INFANTRY_PROVIDER=stub` boots a daemon with no OpenCode and no Claude on the machine. It exists
 * for one reason: the UI track must be able to build and test against the daemon's real
 * lifecycle/stream/protocol machinery without a provider CLI, and without anyone writing a fake
 * daemon that then diverges from the real one.
 *
 * WHAT IT FAITHFULLY REPRODUCES (so a UI developed against it is not surprised by reality):
 *  - the delivery contract -- nothing is emitted until stdin reaches EOF, exactly as
 *    `opencode run` and `claude -p` behave (Phase 0 finding #2);
 *  - line-oriented [ProcessListener] callbacks, one per scripted line, on their own thread, so the
 *    daemon's drain/merge logic is exercised the same way;
 *  - [RunningProcess.awaitExit] that blocks until every line has been delivered;
 *  - [RunningProcess.killTree] that stops the replay, so a kill mid-run leaves a session
 *    `EXITED` with `KillOutcome.FORCED` rather than a half-written script;
 *  - a `null` pid, because there is no OS child -- which is also what a future HTTP provider does.
 *
 * @param script the lines to replay. See [StubScript].
 * @param delayMs pause before each line, so a client actually sees a stream rather than one bulk
 *   send. Zero is allowed and still correct, just unstreamed.
 */
class ScriptedRunner(
    private val script: StubScript = StubScript.default(),
    private val delayMs: Long = 25L,
) : ProcessRunner {
    override val id: String = "scripted"

    /** Every process this runner started, for tests that assert on what was delivered. */
    val processes: MutableList<ScriptedProcess> = mutableListOf()

    override fun start(spec: ProcessSpec, listener: ProcessListener): RunningProcess {
        val process = ScriptedProcess(listener, script, delayMs)
        processes += process
        return process
    }
}

/** A scripted run, ready to replay. */
class ScriptedProcess(
    private val listener: ProcessListener,
    private val script: StubScript,
    private val delayMs: Long,
) : RunningProcess {

    private val alive = AtomicBoolean(true)
    private val finished = CountDownLatch(1)
    private val input = StringBuilder()

    /** Exactly what was written to this "child's" stdin -- what a test asserts delivery against. */
    val deliveredInput: String get() = input.toString()

    override val isAlive: Boolean get() = alive.get()

    /** No OS child exists, so there is no pid. */
    override val pid: Long? = null

    override fun writeInput(text: String) {
        input.append(text)
    }

    override fun closeInput() {
        Thread({ replay() }, "scripted-run").apply { isDaemon = true }.start()
    }

    override fun killTree() {
        if (alive.compareAndSet(true, false)) {
            finished.countDown()
            // A forced kill has no exit code to report; the daemon records the outcome anyway.
        }
    }

    override fun awaitExit(): Int {
        val ok = finished.await(30, TimeUnit.SECONDS)
        return if (ok) script.exitCode else 1
    }

    private fun replay() {
        try {
            val echo = if (script.echoPrompt) listOf(echoLine(deliveredInput)) else emptyList()
            for (line in echo + script.stdoutLines) {
                if (!alive.get()) return
                if (delayMs > 0) Thread.sleep(delayMs)
                if (!alive.get()) return
                listener.onStdout(line)
            }
            for (line in script.stderrLines) {
                if (!alive.get()) return
                listener.onStderr(line)
            }
            listener.onExit(script.exitCode)
        } finally {
            alive.set(false)
            finished.countDown()
        }
    }
}

/**
 * One provider-shaped `text` line carrying exactly [text] -- the stdin the child received, JSON-escaped
 * by the serializer so quotes, braces and non-ASCII survive the round trip unchanged.
 */
fun echoLine(text: String): String =
    JsonObject(mapOf("type" to JsonPrimitive("text"), "text" to JsonPrimitive(text))).toString()

/**
 * The script a [ScriptedRunner] replays.
 *
 * Lines are the PROVIDER's own shapes, not neutral ones -- that is the point: the stub goes through
 * the same [com.superiorsoftllc.infantry.protocol.EventNormalizer] a real provider does, so a UI
 * built against the stub is exercised on real normalisation, not on a parallel fake vocabulary that
 * would drift the moment a provider is swapped in.
 *
 * @param name shown in logs, so a client can tell the stub from a real provider by name.
 * @param stdoutLines replayed as provider stdout, one [ProcessListener.onStdout] per line.
 * @param stderrLines replayed after stdout; providers put hook/diagnostic noise here.
 * @param exitCode what the run reports. Non-zero lands the session `FAILED`, exactly as a real
 *   non-zero exit does.
 */
data class StubScript(
    val name: String = "stub",
    val stdoutLines: List<String> = emptyList(),
    val stderrLines: List<String> = emptyList(),
    val exitCode: Int = 0,
    /**
     * Opt-in fidelity probe: replay the stdin the child received as a first `text` line, so a client
     * sees exactly what the provider got. Off by default, so no existing script changes.
     */
    val echoPrompt: Boolean = false,
) {
    companion object {
        /**
         * The script a client sees by default: a session id, streamed text, a stderr diagnostic,
         * and a clean finish. Enough to exercise spawn -> stream -> finish with no provider.
         */
        fun default(): StubScript = StubScript(
            stdoutLines = listOf(
                """{"type":"step_start","sessionID":"stub-session-1"}""",
                """{"type":"text","text":"[stub] daemon is running without a provider CLI."}""",
                """{"type":"text","text":"Every frame on this socket is the real protocol; only the text is scripted."}""",
                """{"type":"step_finish"}""",
            ),
            stderrLines = listOf("[stub] no provider process was started"),
            exitCode = 0,
        )

        /**
 * A script that fails part-way, so the failure path is testable with no CLI either.
 */
        fun failing(): StubScript = StubScript(
            stdoutLines = listOf(
                """{"type":"step_start","sessionID":"stub-session-fail"}""",
                """{"type":"text","text":"[stub] starting the scripted run"}""",
                """{"type":"error","error":{"message":"scripted failure"}}""",
            ),
            exitCode = 3,
        )
    }
}
