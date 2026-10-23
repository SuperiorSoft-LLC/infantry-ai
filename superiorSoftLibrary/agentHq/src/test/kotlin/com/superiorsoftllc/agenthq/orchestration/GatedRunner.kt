package com.superiorsoftllc.agenthq.orchestration

import com.superiorsoftllc.agenthq.runner.ProcessListener
import com.superiorsoftllc.agenthq.runner.ProcessRunner
import com.superiorsoftllc.agenthq.runner.ProcessSpec
import com.superiorsoftllc.agenthq.runner.RunningProcess
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Prompt tokens (opencode-shaped JSON lines, so the real EventNormalizer runs on them):
 *  - default        : step_start + one text line, waits [holdMs], exits 0
 *  - "FAIL_ALWAYS"  : ... then an error line, exits 3
 *  - "FAIL_ONCE"    : like FAIL_ALWAYS unless the prompt carries RetryFeedback.MARKER, then exits 0
 *  - "HANG"         : never exits on its own; only a kill ends it
 *  - "GRACEFUL"     : (with HANG) a SECOND closeInput() -- SessionManager.kill's graceful signal -- ends it with 0
 * killTree() ends it with 137. [live] / [maxLive] count processes between start() and their exit.
 */
class GatedRunner(private val holdMs: Long = 100) : ProcessRunner {
    override val id: String = "gated"

    val live = AtomicInteger(0)
    val maxLive = AtomicInteger(0)
    val processes = CopyOnWriteArrayList<GatedProcess>()
    /** Delivered prompts, in delivery order. */
    val prompts = CopyOnWriteArrayList<String>()
    private val counter = AtomicInteger(0)

    override fun start(spec: ProcessSpec, listener: ProcessListener): RunningProcess {
        val process = GatedProcess(this, listener, counter.incrementAndGet(), holdMs)
        val now = live.incrementAndGet()
        maxLive.accumulateAndGet(now) { a, b -> maxOf(a, b) }
        processes += process
        return process
    }

    internal fun ended() {
        live.decrementAndGet()
    }
}

class GatedProcess(
    private val runner: GatedRunner,
    private val listener: ProcessListener,
    val index: Int,
    private val holdMs: Long,
) : RunningProcess {
    private val alive = AtomicBoolean(true)
    private val input = StringBuilder()
    private val closes = AtomicInteger(0)

    @Volatile var prompt: String = ""
        private set

    override val isAlive: Boolean get() = alive.get()
    override val pid: Long? = null

    override fun writeInput(text: String) {
        synchronized(input) { input.append(text) }
    }

    override fun closeInput() {
        if (closes.incrementAndGet() == 1) {
            prompt = synchronized(input) { input.toString() }
            runner.prompts += prompt
            Thread({ play() }, "gated-$index").apply { isDaemon = true }.start()
        } else if (prompt.contains("GRACEFUL")) {
            end(0)
        }
    }

    override fun killTree() = end(137)

    override fun awaitExit(): Int = 0

    private fun play() {
        listener.onStdout("""{"type":"step_start","sessionID":"gated-$index"}""")
        listener.onStdout("""{"type":"text","text":"ran #$index"}""")
        if (prompt.contains("HANG")) return
        Thread.sleep(holdMs)
        val fail = prompt.contains("FAIL_ALWAYS") ||
            (prompt.contains("FAIL_ONCE") && !prompt.contains(RetryFeedback.MARKER))
        if (fail) {
            if (alive.get()) listener.onStdout("""{"type":"error","error":{"message":"gated failure #$index"}}""")
            end(3)
        } else {
            end(0)
        }
    }

    private fun end(code: Int) {
        if (!alive.compareAndSet(true, false)) return
        runner.ended()
        listener.onExit(code)
    }
}
