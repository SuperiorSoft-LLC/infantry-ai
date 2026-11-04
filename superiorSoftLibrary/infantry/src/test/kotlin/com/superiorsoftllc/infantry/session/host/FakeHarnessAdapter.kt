// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.session.host

import com.superiorsoftllc.infantry.harness.AgentProvider
import com.superiorsoftllc.infantry.harness.RunRequest
import com.superiorsoftllc.infantry.runner.ProcessListener
import com.superiorsoftllc.infantry.runner.ProcessRunner
import com.superiorsoftllc.infantry.runner.ProcessSpec
import com.superiorsoftllc.infantry.runner.RunningProcess
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * BUG-M2-11b-1: a persistent-mode harness that is deliberately NOT opencode, so every host test proves the lifecycle for
 * any harness. Its process is in memory; a test kills it, ends its turns, or lets [autoComplete] end them.
 */
class FakeHarnessAdapter(
    /** End each turn at once with one output line and COMPLETED. */
    var autoComplete: Boolean = false,
    /** What [abortTurn] answers. */
    var abortAccepted: Boolean = true,
    /** Exit on [close] (a graceful harness), or ignore it so the host has to kill after its grace. */
    var exitOnClose: Boolean = true,
    /** CLI-LIVE-1: the session type's own idle limit; null = the host's global keep-alive. */
    var idleLimit: Int? = null,
) : HarnessSessionAdapter {
    override val label = ID

    override fun idleMinutes(global: Int): Int = idleLimit ?: global

    val launches = mutableListOf<RunRequest>()
    val turns = mutableListOf<RunRequest>()
    val processes = mutableListOf<FakeProcess>()
    var closes = 0
    var aborts = 0

    /** Fail the next launch, after printing this on stderr. */
    var failNextLaunch: String? = null

    lateinit var sink: AdapterSink

    val current: FakeProcess get() = processes.last()

    override fun fingerprint(request: RunRequest, env: Map<String, String>): String = env["FP"].orEmpty()

    override fun launch(request: RunRequest, env: Map<String, String>, runner: ProcessRunner, sink: AdapterSink): HostedProcess {
        launches += request
        failNextLaunch?.let { failNextLaunch = null; sink.diagnostic(it); throw IllegalStateException("exited before ready") }
        this.sink = sink
        return FakeProcess(1000L + processes.size).also { processes += it }
    }

    override fun sendTurn(process: HostedProcess, request: RunRequest) {
        turns += request
        if (autoComplete) {
            sink.output("""{"reply":"${request.prompt}"}""")
            sink.turnEnded(TurnEnd.COMPLETED, 0)
        }
    }

    override fun abortTurn(process: HostedProcess): Boolean {
        aborts++
        // The harness reports its own word for an aborted turn; the host must still call it INTERRUPTED.
        if (abortAccepted) sink.turnEnded(TurnEnd.FAILED, null, "aborted by the harness")
        return abortAccepted
    }

    override fun close(process: HostedProcess): Boolean {
        closes++
        if (exitOnClose) (process as FakeProcess).die(0)
        return true
    }

    class FakeProcess(override val pid: Long) : HostedProcess {
        @Volatile private var alive = true
        private var handler: ((Int) -> Unit)? = null
        var killed = false

        override val isAlive: Boolean get() = alive

        override fun onExit(handler: (exitCode: Int) -> Unit) {
            this.handler = handler
        }

        override fun killTree() {
            killed = true
            die(137)
        }

        /** The OS process ends, for whatever reason. */
        fun die(code: Int) {
            if (!alive) return
            alive = false
            handler?.invoke(code)
        }
    }

    companion object {
        const val ID = "fake-harness"
    }
}

/** A provider whose persistent mode is [adapter]; its one-shot spec is a harmless argv for a counting runner. */
class FakeHarnessProvider(private val adapter: FakeHarnessAdapter) : AgentProvider {
    override val id = FakeHarnessAdapter.ID
    override fun spec(request: RunRequest) = ProcessSpec(listOf("fake-harness", request.prompt))
    override fun sessionAdapter(): HarnessSessionAdapter = adapter
    override fun sessionIdFromLine(line: String): String? = if (line.startsWith("{")) "fake-provider-session" else null
}

/** Counts every child it is asked to start; each one replies once and exits 0 when its stdin closes. */
class CountingRunner : ProcessRunner {
    override val id = "counting"
    val starts = AtomicInteger()

    override fun start(spec: ProcessSpec, listener: ProcessListener): RunningProcess {
        starts.incrementAndGet()
        return object : RunningProcess {
            @Volatile private var alive = true
            override val isAlive get() = alive
            override fun writeInput(text: String) = Unit
            override fun closeInput() {
                if (!alive) return
                listener.onStdout("""{"reply":"one-shot"}""")
                alive = false
                listener.onExit(0)
            }
            override fun killTree() {
                alive = false
            }
            override fun awaitExit() = 0
        }
    }
}

/** Every [HostEvents] call, as one readable line each. */
class RecordingEvents : HostEvents {
    val log: MutableList<String> = Collections.synchronizedList(mutableListOf())
    fun of(kind: String): List<String> = synchronized(log) { log.filter { it.startsWith("$kind ") }.map { it.removePrefix("$kind ") } }

    override fun stdout(sessionId: String, line: String) { log += "stdout $line" }
    override fun stderr(sessionId: String, line: String) { log += "stderr $line" }
    override fun bootLine(sessionId: String, text: String) { log += "chat $text" }
    override fun activity(sessionId: String, detail: String) { log += "activity $detail" }
    override fun providerSession(sessionId: String, id: String) { log += "provider $id" }
    override fun process(sessionId: String, pid: Long?) { log += "pid $pid" }
    override fun turnEnded(sessionId: String, end: TurnEnd, exitCode: Int?, processExited: Boolean, reason: String?) {
        log += "end $end code=$exitCode processExited=$processExited"
    }
    override fun processExited(sessionId: String, exitCode: Int) { log += "exit $exitCode" }
    override fun turnStarted(sessionId: String, reason: String?) { log += "start $reason" }
    override fun keptProcess(sessionId: String, kept: KeptProcess) { log += "kept $sessionId pid=${kept.pid} startedAt=${kept.startedAt} ${kept.label}" }
    override fun keptProcessEnded(sessionId: String, pid: Long) { log += "keptEnded $sessionId pid=$pid" }
}

/**
 * BUG-M2-11f: an OS process table a test stages. [started] maps a LIVE pid to its start instant; a pid not in it is gone.
 * [killTree] records what it was asked to kill and removes it, the way a real tree kill would.
 */
class FakeProcessTable(vararg live: Pair<Long, Long>) : ProcessTable {
    val started: MutableMap<Long, Long> = java.util.concurrent.ConcurrentHashMap(mapOf(*live))
    val killed: MutableList<Long> = Collections.synchronizedList(mutableListOf())

    override fun startInstant(pid: Long): Long? = started[pid]

    /** Pids a test stages as having a live parent (owned by some other, still-running process). */
    val withLiveParent: MutableSet<Long> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    override fun hasLiveParent(pid: Long): Boolean = pid in withLiveParent

    override fun killTree(pid: Long, startedAt: Long) {
        killed += pid
        started.remove(pid)
    }
}
