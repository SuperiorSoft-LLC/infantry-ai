// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.session.host

import com.superiorsoftllc.infantry.harness.AgentProvider
import com.superiorsoftllc.infantry.harness.RunRequest
import com.superiorsoftllc.infantry.runner.ProcessRunner
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * BUG-M2-11b-1 (docs/INFANTRY-SESSION-HOST-PLAN.md sections 3-5): process lifecycle for EVERY session, whatever the
 * harness. Launch, reuse, abort, stop, one restart after a crash, shutdown, and the one chat boot line. The protocol is
 * the adapter's ([HarnessSessionAdapter]); which adapter is [select]'s. Nothing here names a harness.
 *
 * Invariants (plan section 5): at most one hosted process per session; a launch only on a prompt and at most one per
 * [runTurn]; a turn is never retried; every [runTurn] ends in exactly one [HostEvents.turnEnded]; one chat line per
 * launch, none per reused turn; every process comes from [runnerFor] or [lineRunner]; [stop] is idempotent and
 * [shutdown] leaves nothing alive; [events] is never called while [lock] is held.
 *
 * Threading: [runTurn] runs the launch and the send on the caller's thread. Every adapter 11b-1 ships (the one-shot
 * one) launches nothing, so nothing blocks; a persistent adapter's boot moves to an executor with 11b-2.
 */
class SessionHost(
    /** The runner the daemon configured for a provider: piped, PTY or the stub's scripted one. */
    private val runnerFor: (AgentProvider) -> ProcessRunner,
    /** Piped; given to every adapter with [HarnessSessionAdapter.needsPipes]. */
    private val lineRunner: ProcessRunner,
    private val policy: () -> LifecyclePolicy,
    private val events: HostEvents,
    private val select: (AgentProvider, LifecyclePolicy) -> HarnessSessionAdapter = ::selectAdapter,
    /** How long [stop] waits after a graceful close before it kills. */
    private val killGraceMs: Long = 2_000,
    /** How long an accepted abort may take to end the turn before the host kills. */
    private val abortGraceMs: Long = 5_000,
    /** BUG-M2-11f: the host clock the idle stop reads. Injected so a test moves time instead of waiting for it. */
    private val clock: () -> Long = System::currentTimeMillis,
    /** BUG-M2-11f: how often [stopIdle] runs on its own once a process is kept. 0 or less = never; a caller drives it. */
    private val idleSweepMs: Long = 30_000,
    /** BUG-M2-11f: where a kept process's start instant comes from, for the reaping record. */
    private val processTable: ProcessTable = OsProcessTable,
    /** PROMPT-3: where an adapter relays a Tool approval `ask` prompt (the daemon's PendingPrompts); read at each use. */
    private val prompts: () -> com.superiorsoftllc.infantry.prompts.PromptAsker = { com.superiorsoftllc.infantry.prompts.PromptAsker.CANNOT_PROMPT },
) {
    private class Turn {
        val ended = AtomicBoolean(false)
        @Volatile var abortRequested = false
    }

    private class Slot(val sessionId: String, val adapter: HarnessSessionAdapter, val key: String) {
        lateinit var process: HostedProcess
        @Volatile var turn: Turn? = null

        /** The host stopped or killed it on purpose: its exit is not a crash. */
        @Volatile var released = false

        /** BUG-M2-11f: host-clock millis since the slot has had no turn; null while a turn is starting or running. */
        @Volatile var idleSince: Long? = null

        /** BUG-M2-11f: the OS pid reported through [HostEvents.keptProcess], or null for a handle with no OS process. */
        @Volatile var keptPid: Long? = null
    }

    /** A launch in progress: what an abort or a stop asked for before the process existed. */
    private class Launching {
        @Volatile var abortRequested = false
        @Volatile var stopRequested = false
    }

    private val lock = Any()
    private val slots = HashMap<String, Slot>()
    private val launching = HashMap<String, Launching>()

    /** Sessions whose kept process died unasked: their next launch says "restarted after a crash". */
    private val crashed = HashSet<String>()

    private val timer: ScheduledExecutorService by lazy {
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "infantry-session-host").also { it.isDaemon = true } }
    }

    private val sweeping = AtomicBoolean(false)

    /** Run one turn of [sessionId] on [provider]. At most ONE launch per call; never a retry. */
    fun runTurn(sessionId: String, provider: AgentProvider, request: RunRequest, env: Map<String, String>) {
        val adapter = select(provider, policy())
        val key = adapter.javaClass.name + "\u0000" + adapter.label + "\u0000" + adapter.fingerprint(request, env)
        var reuse: Slot? = null
        var replaced: Slot? = null
        val wasCrashed: Boolean
        var restartWhy: String? = null
        val pending = Launching()
        synchronized(lock) {
            val existing = slots[sessionId]
            if (existing != null && existing.key == key && existing.process.isAlive && !existing.released) {
                // Busy from here, under the lock: the idle sweep must not stop a process a prompt just claimed.
                existing.idleSince = null
                reuse = existing
            } else if (existing != null) {
                // CLI-LIVE-1: say WHY a live process was replaced, so a restart that should not happen is visible.
                restartWhy = when {
                    existing.key != key -> "launch settings changed (fingerprint differs at ${firstDifference(existing.key, key)})"
                    !existing.process.isAlive -> "the process had exited"
                    else -> "the process was released"
                }
                existing.released = true
                slots.remove(sessionId)
                replaced = existing
            }
            wasCrashed = crashed.remove(sessionId)
            if (reuse == null) launching[sessionId] = pending
        }
        reuse?.let { return send(it, request) }

        replaced?.let { stopProcess(it, force = false) }
        val verb = when {
            wasCrashed -> "restarted after a crash"
            replaced != null -> "restarted"
            request.resumeSessionId != null -> "resumed"
            else -> "started"
        }
        val slot = Slot(sessionId, adapter, key)
        val sink = SlotSink(slot)
        val runner = if (adapter.needsPipes) lineRunner else runnerFor(provider)
        val process = try {
            adapter.launch(request, env, runner, sink)
        } catch (e: Exception) {
            synchronized(lock) { launching.remove(sessionId) }
            val tail = sink.stderrTail().ifBlank { e.message ?: e.javaClass.simpleName }
            events.bootLine(sessionId, "the ${adapter.label} process did not start: $tail")
            events.turnEnded(sessionId, TurnEnd.FAILED, null, false, "the ${adapter.label} process did not start")
            return
        }
        slot.process = process
        val raced = synchronized(lock) {
            launching.remove(sessionId)
            if (!pending.stopRequested) slots[sessionId] = slot
            pending
        }
        if (raced.stopRequested) {
            slot.released = true
            runCatching { process.killTree() }
            events.turnEnded(sessionId, TurnEnd.FAILED, null, false, "stopped")
            return
        }
        // A pid at launch = an OS process kept between turns (a one-shot handle launches nothing). Recorded BEFORE the exit
        // subscription, so a process that dies at once still clears its record.
        process.pid?.let { pid ->
            slot.keptPid = pid
            events.keptProcess(sessionId, KeptProcess(pid, runCatching { processTable.startInstant(pid) }.getOrNull(), adapter.label))
            startSweep()
        }
        process.onExit { code -> onProcessExit(slot, code) }
        val model = request.model?.takeIf { it.isNotBlank() } ?: runCatching { provider.defaultModel() }.getOrNull() ?: "default"
        events.bootLine(sessionId, "* session $verb (${adapter.label} · $model)")
        restartWhy?.let { events.activity(sessionId, "the ${adapter.label} process was replaced: $it") }
        process.pid?.let { events.activity(sessionId, "the ${adapter.label} process is pid $it") }
        if (raced.abortRequested) {
            slot.idleSince = clock()
            events.turnEnded(sessionId, TurnEnd.INTERRUPTED, null, false, null)
            return
        }
        send(slot, request)
    }

    private fun send(slot: Slot, request: RunRequest) {
        val turn = Turn()
        val superseded = synchronized(lock) { slot.turn.also { slot.turn = turn } }
        // BUG-M2-11c-FLAKE: an interrupted turn the manager already settled, whose provider had not confirmed the stop
        // before this prompt. It is over: its abort grace must not kill the process now serving the new turn.
        superseded?.ended?.set(true)
        try {
            slot.adapter.sendTurn(slot.process, request)
        } catch (e: Exception) {
            endTurn(slot, turn, TurnEnd.FAILED, null, e.message ?: e.javaClass.simpleName, processExited = false)
            return
        }
        if (!turn.ended.get()) slot.process.pid?.let { events.process(slot.sessionId, it) }
    }

    private fun endTurn(slot: Slot, turn: Turn?, end: TurnEnd, exitCode: Int?, reason: String?, processExited: Boolean) {
        if (turn == null || !turn.ended.compareAndSet(false, true)) return
        synchronized(lock) {
            if (slot.turn === turn) {
                slot.turn = null
                slot.idleSince = clock()
            }
        }
        events.turnEnded(slot.sessionId, if (turn.abortRequested) TurnEnd.INTERRUPTED else end, exitCode, processExited, reason)
        // Plan section 8: a busy session picks up the setting at the end of its turn. 0 = stop now, off this thread (it
        // may be the adapter's own reader, which a stop interrupts). A later N is the sweep's.
        if (slot.keptPid != null && !slot.released && runCatching { policy().keepAliveMinutes }.getOrNull() == 0) policyChanged()
    }

    /**
     * BUG-M2-11f (plan section 8): stop every kept process that has been idle [LifecyclePolicy.keepAliveMinutes] or
     * longer, on the host clock; 0 stops every kept process. Quietly: an idle stop is the host's own decision, so there is
     * NO chat line and NO [HostEvents.processExited] (`exit` frame), only an Activity detail; the next prompt launches
     * and resumes ("* session resumed"). A handle with no OS process (one-shot) is left alone: it holds nothing.
     * Returns the sessions it stopped.
     */
    fun stopIdle(): List<String> {
        val global = policy().keepAliveMinutes
        val now = clock()
        fun limit(slot: Slot): Long = slot.adapter.idleMinutes(global).toLong() * MS_PER_MINUTE
        val due = synchronized(lock) {
            slots.values.filter { slot ->
                val since = slot.idleSince
                since != null && slot.turn == null && !slot.released && slot.keptPid != null && slot.process.isAlive &&
                    now - since >= limit(slot)
            }.onEach { slot ->
                slots.remove(slot.sessionId)
                slot.released = true
            }
        }
        for (slot in due) {
            stopProcess(slot, force = false)
            val label = slot.adapter.label
            val idle = (now - (slot.idleSince ?: now)) / MS_PER_MINUTE
            events.activity(
                slot.sessionId,
                if (global == 0) "stopped the $label process: keep-alive is off"
                else "stopped the $label process after $idle idle minutes",
            )
        }
        return due.map { it.sessionId }
    }

    /** BUG-M2-11f: the keep-alive setting changed; re-evaluate every kept process now, on the host's own thread. */
    /** The NAME of the first launch input that differs (the text before its `=`, never a value: env can hold secrets). */
    private fun firstDifference(old: String, new: String): String {
        val a = old.split('\u0000', ',').map { it.trim() }
        val b = new.split('\u0000', ',').map { it.trim() }
        val at = (0 until maxOf(a.size, b.size)).firstOrNull { a.getOrNull(it) != b.getOrNull(it) } ?: return "?"
        val item = (b.getOrNull(at) ?: a.getOrNull(at) ?: "?")
        return "item $at '${item.substringBefore('=').take(40)}'"
    }

    fun policyChanged() {
        runCatching { timer.execute { runCatching { stopIdle() } } }
    }

    private fun startSweep() {
        if (idleSweepMs <= 0 || !sweeping.compareAndSet(false, true)) return
        runCatching {
            timer.scheduleWithFixedDelay({ runCatching { stopIdle() } }, idleSweepMs, idleSweepMs, TimeUnit.MILLISECONDS)
        }
    }

    private fun onProcessExit(slot: Slot, code: Int) {
        val turn: Turn?
        val label = slot.adapter.label
        slot.keptPid?.let { events.keptProcessEnded(slot.sessionId, it) }
        synchronized(lock) {
            turn = slot.turn
            if (slots[slot.sessionId] === slot) slots.remove(slot.sessionId)
            if (!slot.released) crashed.add(slot.sessionId)
        }
        if (slot.released) {
            endTurn(slot, turn, TurnEnd.FAILED, code, "stopped", processExited = false)
            return
        }
        if (turn != null && !turn.ended.get() && !turn.abortRequested) {
            events.bootLine(slot.sessionId, "the $label process exited (code $code) during the turn; the next message restarts it")
            endTurn(slot, turn, TurnEnd.FAILED, code, "the $label process exited (code $code)", processExited = true)
        } else if (turn != null && !turn.ended.get()) {
            endTurn(slot, turn, TurnEnd.INTERRUPTED, code, null, processExited = false)
        } else {
            events.activity(slot.sessionId, "the $label process exited (code $code)")
            events.processExited(slot.sessionId, code)
        }
    }

    /** The owner's interrupt: ask the adapter to stop the turn and keep the process; kill when it cannot. */
    fun abortTurn(sessionId: String) {
        val slot: Slot?
        val turn: Turn?
        synchronized(lock) {
            launching[sessionId]?.abortRequested = true
            slot = slots[sessionId]
            turn = slot?.turn
        }
        if (slot == null || turn == null || turn.ended.get()) return
        turn.abortRequested = true
        val accepted = runCatching { slot.adapter.abortTurn(slot.process) }.getOrDefault(false)
        if (!accepted) return killForAbort(slot, turn)
        if (!turn.ended.get()) {
            timer.schedule({ if (!turn.ended.get()) killForAbort(slot, turn) }, abortGraceMs, TimeUnit.MILLISECONDS)
        }
    }

    private fun killForAbort(slot: Slot, turn: Turn) {
        synchronized(lock) {
            slot.released = true
            if (slots[slot.sessionId] === slot) slots.remove(slot.sessionId)
        }
        endTurn(slot, turn, TurnEnd.INTERRUPTED, null, null, processExited = false)
        runCatching { slot.process.killTree() }
    }

    /**
     * Stop [sessionId]'s process (close, kill, delete, watchdog): a graceful close, then a tree kill after the grace;
     * [force] kills at once. [onForce] runs just before a kill, so the caller can record that force was needed before
     * the turn's end reaches it. Idempotent: a session with no process is a no-op.
     */
    fun stop(sessionId: String, force: Boolean = false, onForce: () -> Unit = {}) {
        val slot = synchronized(lock) {
            launching[sessionId]?.stopRequested = true
            crashed.remove(sessionId)
            slots.remove(sessionId)?.also { it.released = true }
        } ?: return
        stopProcess(slot, force, onForce)
        endTurn(slot, slot.turn, TurnEnd.FAILED, null, "stopped", processExited = false)
    }

    private fun stopProcess(slot: Slot, force: Boolean, onForce: () -> Unit = {}) {
        slot.released = true
        val process = slot.process
        if (!force && process.isAlive && runCatching { slot.adapter.close(process) }.getOrDefault(false)) {
            val deadline = System.nanoTime() + killGraceMs * 1_000_000L
            while (process.isAlive && System.nanoTime() < deadline) Thread.sleep(10)
        }
        if (process.isAlive) {
            onForce()
            runCatching { process.killTree() }
        }
    }

    /** Daemon stop: nothing survives it. A process with a turn in flight is killed at once; an idle one gets its grace. */
    fun shutdown() {
        val all = synchronized(lock) {
            launching.values.forEach { it.stopRequested = true }
            slots.values.toList().also { slots.clear(); crashed.clear() }
        }
        all.forEach { it.released = true }
        val (busy, idle) = all.partition { it.turn != null }
        busy.forEach { runCatching { it.process.killTree() } }
        idle.forEach { slot -> if (slot.process.isAlive) runCatching { slot.adapter.close(slot.process) } }
        val deadline = System.nanoTime() + killGraceMs * 1_000_000L
        while (idle.any { it.process.isAlive } && System.nanoTime() < deadline) Thread.sleep(10)
        all.forEach { if (it.process.isAlive) runCatching { it.process.killTree() } }
        all.forEach { endTurn(it, it.turn, TurnEnd.FAILED, null, "the daemon stopped", processExited = false) }
        runCatching { timer.shutdownNow() }
    }

    /** True while [sessionId] has a hosted process (a one-shot session's logical handle counts). */
    fun hasProcess(sessionId: String): Boolean = synchronized(lock) { slots[sessionId]?.process?.isAlive == true }

    private inner class SlotSink(private val slot: Slot) : AdapterSink {
        private val tail = ArrayDeque<String>()

        fun stderrTail(): String = synchronized(tail) { tail.joinToString("\n") }.take(STDERR_TAIL_CHARS)

        override fun output(line: String) = events.stdout(slot.sessionId, line)

        override fun diagnostic(line: String) {
            synchronized(tail) {
                tail.addLast(line)
                while (tail.size > STDERR_TAIL_LINES) tail.removeFirst()
            }
            events.stderr(slot.sessionId, line)
        }

        override fun detail(text: String) = events.activity(slot.sessionId, text)

        override fun providerSession(id: String) = events.providerSession(slot.sessionId, id)

        override fun turnEnded(end: TurnEnd, exitCode: Int?, reason: String?) =
            endTurn(slot, slot.turn, end, exitCode, reason, processExited = false)

        override val sessionId: String get() = slot.sessionId

        override val prompts: com.superiorsoftllc.infantry.prompts.PromptAsker get() = this@SessionHost.prompts()
        override fun requestApproval(tool: String, input: String) = events.requestApproval(slot.sessionId, tool, input)
    }

    private companion object {
        const val MS_PER_MINUTE = 60_000L
        const val STDERR_TAIL_LINES = 20
        const val STDERR_TAIL_CHARS = 1_000
    }
}
