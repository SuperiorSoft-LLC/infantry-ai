package com.superiorsoftllc.agenthq.session.host

import com.superiorsoftllc.agenthq.harness.AgentProvider
import com.superiorsoftllc.agenthq.harness.RunRequest
import com.superiorsoftllc.agenthq.runner.ProcessListener
import com.superiorsoftllc.agenthq.runner.ProcessRunner
import com.superiorsoftllc.agenthq.runner.RunningProcess
import java.util.concurrent.atomic.AtomicBoolean

/**
 * BUG-M2-11b-1 (plan section 6.4): every harness without a persistent mode, and every harness when keep-alive is off.
 *
 * [launch] starts NO process: it returns a logical handle (pid null, alive until [close]), which is what gives a
 * one-shot session ONE `* session started` line like every other session. Each turn is today's spawn-per-reply path:
 * `provider.spec(request)` + the session env, `runner.start`, `provider.deliver`. The child's exit ends the turn:
 * COMPLETED on 0, FAILED otherwise.
 *
 * It uses the runner the daemon configured for the provider (piped, PTY or the stub's scripted one), so [needsPipes]
 * is false.
 */
class OneShotAdapter(private val provider: AgentProvider) : HarnessSessionAdapter {
    override val label: String get() = provider.id
    override val needsPipes: Boolean get() = false

    /** Constant: there is no process to re-bind. */
    override fun fingerprint(request: RunRequest, env: Map<String, String>): String = FINGERPRINT

    override fun launch(request: RunRequest, env: Map<String, String>, runner: ProcessRunner, sink: AdapterSink): HostedProcess =
        Handle(runner, env, sink)

    override fun sendTurn(process: HostedProcess, request: RunRequest) = (process as Handle).send(request)

    /** Tree-kills the turn's child; the turn ends INTERRUPTED. */
    override fun abortTurn(process: HostedProcess): Boolean = (process as Handle).abort()

    /** A graceful request: stdin EOF to an in-flight child. The host kills after its grace if the child lives on. */
    override fun close(process: HostedProcess): Boolean = (process as Handle).close()

    private class Turn(private val sink: AdapterSink) {
        private val ended = AtomicBoolean(false)
        val abortRequested = AtomicBoolean(false)
        /** An opencode stdout line that is an answer (`text`, `tool_use`) or an explanation (`error`). */
        @Volatile var produced = false
        val stderrTail: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf<String>())
        val isEnded: Boolean get() = ended.get()

        fun end(end: TurnEnd, exitCode: Int?, reason: String?) {
            if (ended.compareAndSet(false, true)) sink.turnEnded(end, exitCode, reason)
        }
    }

    private inner class Handle(
        private val runner: ProcessRunner,
        private val env: Map<String, String>,
        private val sink: AdapterSink,
    ) : HostedProcess {
        @Volatile private var closed = false
        @Volatile private var child: RunningProcess? = null
        @Volatile private var turn: Turn? = null

        override val pid: Long? get() = child?.takeIf { it.isAlive }?.pid
        override val isAlive: Boolean get() = !closed || child?.isAlive == true

        /** No OS process of its own, so it never reports an exit: a child's exit is a turn's end, not a crash. */
        override fun onExit(handler: (exitCode: Int) -> Unit) = Unit

        override fun killTree() {
            closed = true
            child?.let { runCatching { it.killTree() } }
            turn?.end(TurnEnd.FAILED, null, "stopped")
        }

        fun send(request: RunRequest) {
            val current = Turn(sink).also { turn = it; child = null }
            val spec = try {
                provider.spec(request)
            } catch (e: Exception) {
                current.end(TurnEnd.FAILED, null, e.message ?: e.javaClass.simpleName)
                return
            }
            val listener = object : ProcessListener {
                override fun onStdout(line: String) {
                    if (OUTPUT_MARKER.containsMatchIn(line)) current.produced = true
                    sink.output(line)
                }
                override fun onStderr(line: String) {
                    current.stderrTail += line
                    while (current.stderrTail.size > 10) current.stderrTail.removeAt(0)
                    sink.diagnostic(line)
                }
                override fun onExit(exitCode: Int) {
                    // opencode exiting 0 having printed no text, tool call or error is no answer: say so, never "succeeded".
                    if (exitCode == 0 && !current.abortRequested.get() && provider.id == "opencode" && !current.produced) {
                        val model = request.model?.takeIf { it.isNotBlank() } ?: runCatching { provider.defaultModel() }.getOrNull() ?: "default"
                        current.end(TurnEnd.FAILED, null, noReply(provider.id, model, current.stderrTail.toList()))
                        return
                    }
                    current.end(
                        when {
                            current.abortRequested.get() -> TurnEnd.INTERRUPTED
                            exitCode == 0 -> TurnEnd.COMPLETED
                            else -> TurnEnd.FAILED
                        },
                        exitCode,
                        null,
                    )
                }
            }
            val started = try {
                runner.start(spec.copy(env = spec.env + env), listener)
            } catch (e: Exception) {
                current.end(TurnEnd.FAILED, null, e.message ?: e.javaClass.simpleName)
                return
            }
            child = started
            // An abort or a stop that arrived while the child was starting stops it as soon as it exists.
            if (current.abortRequested.get() || closed) {
                runCatching { started.killTree() }
                current.end(if (current.abortRequested.get()) TurnEnd.INTERRUPTED else TurnEnd.FAILED, null, "stopped")
                return
            }
            sink.detail("process started (${started.pid?.let { "pid $it" } ?: "no OS process"})")
            try {
                provider.deliver(started, request)
            } catch (e: Exception) {
                runCatching { started.killTree() }
                current.end(TurnEnd.FAILED, null, e.message ?: e.javaClass.simpleName)
            }
        }

        fun abort(): Boolean {
            val current = turn ?: return true
            if (current.isEnded) return true
            current.abortRequested.set(true)
            child?.let { runCatching { it.killTree() } }
            current.end(TurnEnd.INTERRUPTED, null, null)
            return true
        }

        fun close(): Boolean {
            closed = true
            child?.takeIf { it.isAlive }?.let { runCatching { it.closeInput() } }
            return true
        }
    }

    private companion object {
        const val FINGERPRINT = "one-shot"
        /** H8b-3: the "no reply" reason (moved from the opencode serve adapter): names the model asked and the raw stderr, so a silent turn is never a plain success. */
        fun noReply(harness: String, model: String, stderr: List<String> = emptyList()): String =
            "no reply from $model: the turn ended with no assistant output" +
                stderr.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.joinToString(" | ", prefix = " ($harness stderr: ", postfix = ")").orEmpty()

        val OUTPUT_MARKER = Regex(""""type"\s*:\s*"(text|tool_use|error)"""")
    }
}
