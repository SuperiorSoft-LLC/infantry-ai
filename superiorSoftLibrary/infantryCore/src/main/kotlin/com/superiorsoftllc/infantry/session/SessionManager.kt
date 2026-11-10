// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.session

import com.superiorsoftllc.infantry.daemon.api.sessions.SessionBinding
import com.superiorsoftllc.infantry.protocol.MessageOrigin
import com.superiorsoftllc.infantry.daemon.AccessPolicy
import com.superiorsoftllc.infantry.harness.AgentProvider
import com.superiorsoftllc.infantry.harness.RunRequest
import com.superiorsoftllc.infantry.runner.ProcessRunner
import com.superiorsoftllc.infantry.runner.PipedProcessRunner
import com.superiorsoftllc.infantry.runner.RunningProcess
import com.superiorsoftllc.infantry.session.host.HostEvents
import com.superiorsoftllc.infantry.session.host.KeptProcess
import com.superiorsoftllc.infantry.session.host.LifecyclePolicy
import com.superiorsoftllc.infantry.session.host.OsProcessTable
import com.superiorsoftllc.infantry.session.host.ProcessTable
import com.superiorsoftllc.infantry.session.host.SessionHost
import com.superiorsoftllc.infantry.session.host.TurnEnd
import com.superiorsoftllc.infantry.usage.GateDecision
import com.superiorsoftllc.infantry.usage.LaunchOrigin
import com.superiorsoftllc.infantry.worktree.WorktreeProvisioner
import com.superiorsoftllc.infantry.worktree.WorktreeRecord
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What [SessionManager.loadAndReconcile] found when it met persisted sessions at startup.
 *
 * [exited] counts sessions that were mid-run under the previous daemon and whose child is gone now
 * (declared [SessionState.EXITED]); [orphaned] counts sessions whose child is STILL alive (declared
 * [SessionState.ORPHANED]); [unchanged] counts sessions that were already at rest.
 */
data class ReconcileReport(
    val exited: Int,
    val orphaned: Int,
    val unchanged: Int,
    /** BUG-M2-11f: kept processes a previous daemon left behind, tree-killed because pid AND start instant matched. */
    val reaped: Int = 0,
    /**
     * RESUME-NOTIFY-1: sessions that were MID-TURN when the previous daemon stopped (a run in flight, no end recorded) and are at
     * rest now (EXITED, or IDLE after their kept process was reaped): their turn was cut. They go out on the `daemon.started` event ([DaemonStartedEvent]); a script decides what happens.
     */
    val cutTurns: List<String> = emptyList(),
    /** RESUME-NOTIFY-1: mid-turn sessions whose child outlived the daemon ([orphaned]): still running, so never re-prompted. */
    val orphanedIds: List<String> = emptyList(),
) {
    val total: Int get() = exited + orphaned + unchanged

    override fun toString(): String = "exited=$exited orphaned=$orphaned unchanged=$unchanged reaped=$reaped cutTurns=${cutTurns.size}"
}

/**
 * Phase 3: the lifecycle owner.
 *
 * [SessionManager] turns a provider + a runner into managed sessions. It is the ONLY place that
 * decides what a session's state is; a provider never does. That is exactly the boundary the ticket
 * demands ("a provider implements the lifecycle; it does not define it"): [AgentProvider] is used
 * as-is -- its argv, its stdin delivery, its session-id parser -- and gained nothing.
 *
 * Responsibilities:
 *  - create sessions and drive runs, observing state from process facts (spawn, output, exit code);
 *  - persist every durable change, force-writing the moment a provider session id appears;
 *  - kill a run, escalating to a forced tree kill, and record which happened ([KillOutcome]);
 *  - resume a session by re-invoking its provider with the stored session id;
 *  - reconcile orphaned sessions at startup.
 *
 * Threading: stdout and stderr are drained on separate threads, so every mutation goes through the
 * synchronized [update] step. A session has at most one run in flight.
 *
 * @param killGraceMs how long a graceful kill waits for the process to exit before force.
 * @param pidProbe how to ask whether an os pid is still alive; overridden in tests so reconciliation
 *   can be exercised without real processes. Defaults to the JDK's ProcessHandle.
 *
 * Contract 1.3.0: one manager can drive SEVERAL providers. [provider] is the default, run on
 * [runner] exactly as before; each of [additionalProviders] is a provider a spawn may name instead.
 * A session records its provider when it is created, and every later run and resume of that
 * session goes back to that provider, never to whichever is the default now.
 */
class SessionManager(
    private val provider: AgentProvider,
    private val runner: ProcessRunner,
    private val store: SessionStore,
    /** Phase 4: provisions a git worktree per session when a repo is given. Null = no isolation. */
    private val worktrees: WorktreeProvisioner? = null,
    private val killGraceMs: Long = 2_000,
    private val pidProbe: (Long) -> Boolean = { pid ->
        ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
    },
    /** Where a session with no worktree and no explicit dir runs. BUG-WT-1: the ticket board reads it to refuse a git checkout. */
    val defaultWorkingDir: File = File(System.getProperty("user.dir")),
    private val clock: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    /**
     * Extra environment for every spawned child, merged OVER the provider's own env so the daemon's
     * values win. Phase 7 uses it to hand each agent the bearer token as `INFANTRY_TOKEN`, so the curl a
     * delivered prompt carries can authenticate without the literal token being written into the prompt.
     */
    private val childEnv: Map<String, String> = emptyMap(),
    /** FIRST-RUN-KEYS-1: the API key a harness declares, as `{ENV: key}`, read at every launch so a restarted session gets a key set meanwhile. */
    private val credentialEnv: (providerId: String) -> Map<String, String> = { emptyMap() },
    /**
     * Contract 1.3.0: the providers a spawn may name besides [provider] (`INFANTRY_PROVIDERS`). Ids must be
     * distinct from each other and from the default's.
     */
    additionalProviders: List<AgentProvider> = emptyList(),
    /**
     * What an additional provider with no transport of its own (a real CLI) runs on. Defaults to
     * [runner]; the daemon passes the configured CLI runner when the default brings its own transport
     * (the stub), because a scripted transport cannot spawn a CLI.
     */
    cliRunner: ProcessRunner = runner,
    private val deadlineMs: Long = DEFAULT_UNATTENDED_DEADLINE_MS,
    private val stallMs: Long = DEFAULT_UNATTENDED_STALL_MS,
    private val watchdogPollMs: Long = DEFAULT_WATCHDOG_POLL_MS,
    /** AR-3b: what the layer that binds sessions to work answers (twin rule, deadline, lock, progress); [SessionBindingPolicy.None] = nothing is bound. */
    val bindingPolicy: SessionBindingPolicy = SessionBindingPolicy.None,
    /** Ticket 7e9bed2a: how long after a watchdog kill the run may stay registered before the kill is retried with force. */
    private val killRetryMs: Long = DEFAULT_KILL_RETRY_MS,
    /** Ticket 7e9bed2a: a BUSY session with no output for this long is FLAGGED (never killed for it). */
    private val silentMs: Long = DEFAULT_SILENT_MS,
    /** How often [sweepSilent] runs on its own; 0 = only when a test calls it. */
    private val silentPollMs: Long = DEFAULT_SILENT_POLL_MS,
    /** MCP-RECONNECT-1: how often [healMcp] looks for sessions showing mcp=disconnected; 0 = never (tests call it). */
    private val mcpHealPollMs: Long = DEFAULT_MCP_HEAL_POLL_MS,
    /** MCP-RECONNECT-1: the two plain lines told to a session whose tools go away and come back; one place, overridable. */
    private val mcpLines: McpLines = McpLines(),
    /** Where background work is read from: the OS process tree under each session's kept harness process. */
    private val processSource: ProcessSource = OsProcessSource,
    /** How often [scanBackgroundWork] runs on its own; 0 = only when a test calls it. */
    private val backgroundPollMs: Long = DEFAULT_BACKGROUND_POLL_MS,
    private val harnesses: com.superiorsoftllc.infantry.harness.HarnessRegistry? = null,
    /** TRAITS-T4: where each agent/role session's frozen [RoleInstance] is kept. The daemon passes its database. */
    private val instances: SessionInstanceRepository = InMemorySessionInstances(),
    /** BUG-M2-11b-1: the one lifecycle setting, read on every launch decision (plan section 8). */
    private val lifecyclePolicy: () -> LifecyclePolicy = { LifecyclePolicy.DEFAULT },
    /** BUG-M2-11b-1: the piped runner every line-protocol adapter spawns through (plan D10). */
    lineRunner: ProcessRunner = PipedProcessRunner(),
    /** BUG-M2-11f: start instants and tree kills for kept processes (recorded at launch, reaped at start). */
    private val processTable: ProcessTable = OsProcessTable,
    /** BUG-M2-11f: how often the host's idle sweep runs; 0 = only when a test or the setting asks. */
    idleSweepMs: Long = 30_000,
    /** BUG-REAP-OWNER: this state dir's id, stamped on every kept process and required to match before one is reaped. Null = never reap. */
    private val instanceId: String? = null,
    /**
     * Ticket 66153313: the build limits env of every run (priority, Gradle worker cap, test forks, in-process Kotlin), read
     * per run so a settings change applies to the next launch. Worker sessions only: the owner's own Gradle never sees it.
     */
    private val buildEnv: (com.superiorsoftllc.infantry.daemon.api.processes.LaunchInfo) -> Map<String, String> = { emptyMap() },
    /**
     * No-hard-coded-limits S2: the live `limits.watchdog.*` settings, read at every pass so a change applies at once. Null
     * (tests) = the fixed [deadlineMs], [stallMs], [killRetryMs] and [silentMs] above.
     */
    private val limitSource: ((com.superiorsoftllc.infantry.settings.LimitDef) -> Long)? = null,
    /**
     * PROC-LEAK-1 (ticket 55312934): every harness pid a session reports is put in that session's OS container (a Windows Job
     * Object), so all it starts stays attributable after its parent exits. [com.superiorsoftllc.infantry.runner.SessionContainers.None] = no container.
     */
    private val containers: com.superiorsoftllc.infantry.runner.SessionContainers = com.superiorsoftllc.infantry.runner.SessionContainers.None,
) {
    /** PROC-LEAK-1: [pid] into [sessionId]'s container; a refusal (no container, an OS error) leaves the tree walk as attribution. */
    private fun contain(sessionId: String, pid: Long) {
        runCatching { containers.assign(sessionId, pid) }
    }

    val providerId: String get() = provider.id

    /** [def] in ms from [limitSource] (0 on a disable-able limit = never reached), else [fixed]. */
    private fun liveMs(def: com.superiorsoftllc.infantry.settings.LimitDef, fixed: Long): Long {
        val source = limitSource ?: return fixed
        val minutes = runCatching { source(def) }.getOrNull() ?: return fixed
        return com.superiorsoftllc.infantry.settings.LimitRegistry.minutesMs(minutes, def)
    }

    private fun minutesText(ms: Long): String = if (ms == Long.MAX_VALUE) "none" else "${ms / 60_000} min"

    /** A configured provider and the transport its runs go through. */
    private class Configured(val provider: AgentProvider, val runner: ProcessRunner)

    /** Every provider a session may run on, by id, the default first. Fixed for the manager's life. */
    private val configured: Map<String, Configured> = LinkedHashMap<String, Configured>().apply {
        // The default keeps the injected runner untouched, so a single-provider manager behaves
        // exactly as it did before 1.3.0 (tests inject a gated or no-op runner here).
        put(provider.id, Configured(provider, runner))
        for (extra in additionalProviders) {
            require(extra.id !in this) { "provider '${extra.id}' is configured twice" }
            put(extra.id, Configured(extra, extra.transport() ?: cliRunner))
        }
    }

    /** H2: whether a spawn naming [id] resolves right now (a configured provider or a registered harness). */
    fun hasProvider(id: String): Boolean = lookup(id.trim().lowercase()) != null

    /** The providers a spawn may name (contract 1.3.0), the default first. */
    val providers: List<AgentProvider> get() = configured.values.map { it.provider }

    /**
     * Check a spawn's choice without creating anything, so a client hears about a bad `new` frame
     * when it sends it, not one prompt later. Throws [IllegalArgumentException] with the reason;
     * [startNew] applies the same check, and is the one that counts.
     */
    fun checkSpawn(providerId: String?, model: String?) {
        // H2: naming no provider on a zero-harness daemon is saveable (a profile, a role binding); only the START refuses.
        if (!(providerId.isNullOrBlank() && provider is com.superiorsoftllc.infantry.harness.NoHarnessProvider && harnesses?.harnessIds().isNullOrEmpty())) resolveProvider(providerId)
        spawnModel(model)?.let { requireAllowedModel(it, null) }
    }

    /**
     * LIMITS: the model allowlist in force, read at every check; the daemon points it at the
     * setting `limits.models.allowed`. [com.superiorsoftllc.infantry.settings.ModelAllowlist.NONE] = every model.
     */
    @Volatile
    var allowedModels: () -> com.superiorsoftllc.infantry.settings.ModelAllowlist = { com.superiorsoftllc.infantry.settings.ModelAllowlist.NONE }

    /** LIMITS: the role allowlist in force (`limits.roles.allowed`), read at every check; NONE = every role. */
    @Volatile
    var allowedRoles: () -> com.superiorsoftllc.infantry.settings.ModelAllowlist = { com.superiorsoftllc.infantry.settings.ModelAllowlist.NONE }

    /**
     * LIMITS: refuse [role] when a role allowlist is in force and does not name it. A session with no role (an
     * anonymous console) is not a role choice and passes. Throws [IllegalArgumentException]; no restriction = no-op.
     */
    fun requireAllowedRole(role: String?) {
        val wanted = role?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val allowlist = allowedRoles()
        require(allowlist.allows(wanted)) { "role '${wanted.take(80)}' is not allowed on this server. ${allowlist.describe()}" }
    }

    /** LIMITS: `limits.concurrency.maxSessions` in force, read at every start; [Int.MAX_VALUE] = no cap. */
    @Volatile
    var maxSessions: () -> Int = { Int.MAX_VALUE }

    /**
     * HARNESS-GAP G18: what masks known secret values in everything a session shows or keeps (stdout / stderr lines, Activity
     * details, turn prompts, the conversation log a harness switch carries, a terminal's screen). The daemon sets it to the
     * keystore's [com.superiorsoftllc.infantry.keystore.SecretMask]; the default masks nothing.
     */
    @Volatile
    var outputMask: com.superiorsoftllc.infantry.keystore.SecretMask = com.superiorsoftllc.infantry.keystore.SecretMask.NONE

    /**
     * LIMITS: refuse [model] when an allowlist is in force and does not name it. A null [model] is the harness
     * default: [provider] (when given) resolves it, and a default that is unknown or not allowed is refused too, since
     * the run would spend it. Throws [IllegalArgumentException] naming the allowed models; no restriction = no-op.
     */
    fun requireAllowedModel(model: String?, provider: AgentProvider?) {
        val allowlist = allowedModels()
        if (!allowlist.restricts) return
        val explicit = model?.trim()?.takeIf { it.isNotEmpty() }
        if (explicit != null) {
            require(allowlist.allows(explicit)) { "model '${explicit.take(80)}' is not allowed on this server. ${allowlist.describe()}" }
            return
        }
        if (provider == null) return
        val default = runCatching { provider.defaultModel() }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        require(default != null && allowlist.allows(default)) {
            "no model picked, and the default model of '${provider.id}' (${default ?: "its CLI's own"}) is not allowed on this server: " +
                "pick one. ${allowlist.describe()}"
        }
    }

    /**
     * LIMITS: whether [sessionId]'s CLI process is still alive between turns (kept by `session.keepAliveMinutes`).
     * A seam so a test can say which sessions hold one; the daemon reads the session host.
     */
    @Volatile
    var hasLiveProcess: (String) -> Boolean = { host.hasProcess(it) }

    /** LIMITS: [record] is OPEN: working on a turn, or resting with its CLI process kept alive. */
    private fun isOpen(record: SessionRecord): Boolean =
        runs.containsKey(record.id) || record.state in WORKING || runCatching { hasLiveProcess(record.id) }.getOrDefault(false)

    /** " (an idle agent is closed after N minutes)", from `session.keepAliveMinutes`; empty when it cannot be read. */
    private fun keepAliveText(): String = runCatching { lifecyclePolicy().keepAliveMinutes }.getOrNull()
        ?.let { " (an idle agent is closed after $it minute${if (it == 1) "" else "s"}, setting session.keepAliveMinutes)" }.orEmpty()

    /** COPY-1: the active policy preset's `sessionCapText` (the daemon installs it); null = [SESSION_CAP_REFUSED]. */
    @Volatile var sessionCapText: () -> String? = { null }

    /** LIMITS: how many sessions are OPEN now (working, or idle with a live kept process); `maxSessions` bounds it. */
    fun openCount(): Int = sessions.values.count(::isOpen)

    /**
     * LIMITS: the refusal for one more open session, or null while `limits.concurrency.maxSessions` has room.
     * [sessionId] (a session about to take a turn) passes when it is already open: it holds its slot already.
     */
    fun sessionCapRefusal(sessionId: String? = null): String? {
        val cap = maxSessions()
        if (cap == Int.MAX_VALUE) return null
        val self = sessionId?.let { sessions[it] }
        if (self != null && isOpen(self)) return null
        val open = openCount()
        if (open < cap) return null
        val lead = runCatching { sessionCapText() }.getOrNull()?.takeIf { it.isNotBlank() } ?: SESSION_CAP_REFUSED
        return "$lead: $open agent${if (open == 1) " is" else "s are"} open, the most this server runs at once. " +
            "Stop one (the Stop button in its chat), or wait for it to time out${keepAliveText()}, then try again. " +
            com.superiorsoftllc.infantry.settings.LimitRegistry.MAX_SESSIONS.hint()
    }

    /**
     * BUG-PROF-1: the provider a spawn naming [providerId] would run on -- a configured one OR a registry harness this
     * manager adopts on demand (WIRE-4), the same resolution [checkSpawn] uses. [providers] lists only the configured
     * ones, so a lookup there misses `claude` / `pi` on a daemon configured with opencode alone. Null = unknown.
     */
    fun providerFor(providerId: String): AgentProvider? =
        providerId.trim().lowercase().takeIf { it.isNotEmpty() }?.let { lookup(it)?.provider }

    /**
     * Where lifecycle events go. The daemon installs this to broadcast frames to connected clients;
     * the manager itself never touches a socket. Called on a state transition, on every streamed
     * line, and on exit -- never for a bare heartbeat.
     */
    @Volatile
    var eventSink: ((SessionEvent) -> Unit)? = null

    /**
     * DEADLOCK-1 (ticket f3389c81): events raised while this thread holds the manager lock, delivered in order once the
     * outermost [locked] section releases it. The sink runs every listener (plugins included); a listener that takes
     * another lock (the board's) while this one is held closes a cycle with any caller that holds that lock and asks
     * this manager for something (`TicketBoard.move -> handOut -> startNew`).
     */
    private class Outgoing(val event: SessionEvent, val key: String, val owner: Thread, @Volatile var ready: Boolean) {
        /** Raised while its thread held the manager lock (DEADLOCK-1 deferred it); see [deliveringLockedEvent]. */
        val raisedUnderLock = !ready
        /** Its owner could not deliver it (an earlier event of its session was still held by another thread): the helper does. */
        var orphan = false
    }

    /** CHAT-ORDER-1: every undelivered event in the order it was raised, whichever thread raised it. Guarded by itself. */
    private val outbox = ArrayList<Outgoing>()
    /** QA-FIX-C: sessions whose event is out of the outbox but still in [eventSink]; their next event waits for it. Guarded by [outbox]. */
    private val deliveringKeys = HashMap<String, Int>()
    private fun takeAt(i: Int): Outgoing = outbox.removeAt(i).also { deliveringKeys.merge(it.key, 1, Int::plus) }
    private val heldHere = ThreadLocal.withInitial { ArrayList<Outgoing>() }
    /** True while this thread is inside [eventSink]: an event a listener raises then is delivered at once, as before. */
    private val delivering = ThreadLocal.withInitial { false }
    private val deliveringLocked = ThreadLocal.withInitial { false }

    /**
     * QA-FIX-A: true while the CALLING thread delivers an event that was raised under the manager lock. DEADLOCK-1 delivers such an
     * event after the lock is released, so `Thread.holdsLock` no longer tells a listener what it told it before: that the event
     * came from inside a locked section, and that a listener which takes its own lock first (the board) must queue it, not apply
     * it inline (`BoardSessions.holdsSessionsLock`).
     */
    fun deliveringLockedEvent(): Boolean = deliveringLocked.get()
    private val helperRunning = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Hand [event] to [eventSink]. An event raised under the lock is deferred until the outermost [locked] section ends
     * (DEADLOCK-1), and CHAT-ORDER-1: it keeps its place in the outbox, so an event another thread raises meanwhile for the SAME
     * session (the reply a live process printed for the prompt this section just sent) is delivered AFTER it, never before.
     *
     * QA-FIX-A: a thread delivers only events it raised itself, and never waits. The single drain of CHAT-ORDER-1 delivered other
     * threads' events on whichever thread flushed, so a stub run's Exit reached the board on the board's own thread in the middle
     * of a hand-out (TicketBoard.move -> handOut), before the move committed in_progress, and exitBehavior=done never fired; and
     * one session's held event stalled every other session's (an Exit must reach the sink while another thread sits in the launch
     * gate, DEADLOCK-1). Now the order is kept per session; an event queued behind another thread's held event of its session is
     * left to a helper thread that holds no lock of its caller's, and delivers it once that earlier event is out.
     */
    private fun emit(raw: SessionEvent) {
        val event = masked(raw)
        // QA-FIX-C: a listener that re-enters the manager (a done hook's ticket_start -> handOut -> startNew) raises events
        // under the lock too; those are deferred like any other (DEADLOCK-1), only an unlocked one goes out at once.
        if (delivering.get() && !Thread.holdsLock(this)) {
            runCatching { eventSink?.invoke(event) }
            return
        }
        val item = Outgoing(event, event.orderKey(), Thread.currentThread(), ready = !Thread.holdsLock(this))
        synchronized(outbox) { outbox.add(item) }
        if (!item.ready) {
            heldHere.get().add(item)
            return
        }
        deliverMine()
    }

    /** HARNESS-GAP G18: [event] with every known secret value masked; the provider itself still gets what it was given. */
    private fun masked(event: SessionEvent): SessionEvent {
        val m = outputMask
        if (!m.active) return event
        return when (event) {
            is SessionEvent.Stdout -> event.copy(line = m.mask(event.line))
            is SessionEvent.Stderr -> event.copy(line = m.mask(event.line))
            is SessionEvent.Activity -> event.copy(detail = m.mask(event.detail))
            is SessionEvent.Turn -> event.copy(prompt = m.mask(event.prompt))
            else -> event
        }
    }

    /** The outermost lock section ended: its deferred events are deliverable, in their original place. */
    private fun flushEvents() {
        val mine = heldHere.get()
        if (mine.isEmpty()) return
        synchronized(outbox) { mine.forEach { it.ready = true } }
        mine.clear()
        deliverMine()
    }

    /** The session an event belongs to: delivery order is kept per session. */
    private fun SessionEvent.orderKey(): String = when (this) {
        is SessionEvent.Status -> session.id
        is SessionEvent.Turn -> sessionId
        is SessionEvent.Stdout -> sessionId
        is SessionEvent.Stderr -> sessionId
        is SessionEvent.Exit -> sessionId
        is SessionEvent.Activity -> sessionId
        is SessionEvent.ProcessExit -> sessionId
    }

    /** Under the outbox lock: true when no earlier undelivered event of [index]'s session is still in the outbox. */
    private fun firstOfItsSession(index: Int): Boolean {
        val key = outbox[index].key
        return key !in deliveringKeys && (0 until index).none { outbox[it].key == key }
    }

    /**
     * Deliver this thread's ready events in order, at once. One that waits behind an earlier event of its session becomes an
     * orphan, and so does every later event of this thread for that session (they keep their order); the helper delivers them.
     */
    private fun deliverMine() {
        val me = Thread.currentThread()
        while (true) {
            val next = synchronized(outbox) {
                var found: Outgoing? = null
                val blockedKeys = HashSet<String>()
                for (i in outbox.indices) {
                    val item = outbox[i]
                    if (item.owner !== me || !item.ready || item.orphan) continue
                    if (item.key in blockedKeys || !firstOfItsSession(i)) { item.orphan = true; blockedKeys += item.key; continue }
                    found = takeAt(i)
                    break
                }
                found
            } ?: break
            deliverOne(next)
        }
        wakeHelper()
    }

    /** Start the helper when an orphan can go now; it stops when none can. */
    private fun wakeHelper() {
        val due = synchronized(outbox) { outbox.indices.any { outbox[it].orphan && firstOfItsSession(it) } }
        if (!due || !helperRunning.compareAndSet(false, true)) return
        Thread({
            while (true) {
                val next = synchronized(outbox) {
                    val i = outbox.indices.firstOrNull { outbox[it].orphan && firstOfItsSession(it) }
                    if (i == null) { helperRunning.set(false); null } else takeAt(i)
                } ?: break
                deliverOne(next)
            }
            wakeHelper() // an orphan that became due while this thread was stopping
        }, "session-events").apply { isDaemon = true }.start()
    }

    private fun deliverOne(item: Outgoing) {
        // Nested: a listener's own locked section flushes its deferred events while the outer delivery is still running.
        val wasDelivering = delivering.get()
        val wasLocked = deliveringLocked.get()
        delivering.set(true)
        deliveringLocked.set(item.raisedUnderLock)
        try {
            runCatching { eventSink?.invoke(item.event) }
                .onFailure { System.err.println("[infantry] session event listener failed: ${it.message}") }
        } finally {
            delivering.set(wasDelivering)
            deliveringLocked.set(wasLocked)
            synchronized(outbox) { deliveringKeys.computeIfPresent(item.key) { _, n -> (n - 1).takeIf { it > 0 } } }
        }
        // Removing it may have made an orphan of its session due.
        wakeHelper()
    }

    /**
     * The manager's one lock (it replaces `@Synchronized`, reentrant like it). Events raised inside are sent after the
     * outermost section releases the lock, also when the section returns early or throws.
     */
    private inline fun <T> locked(block: () -> T): T {
        try {
            return synchronized(this) { block() }
        } finally {
            if (!Thread.holdsLock(this)) flushEvents()
        }
    }

    @Volatile
    var watchdogSink: ((SessionRecord, WatchdogExpiry) -> Unit)? = null

    /** BG-NOTIFY (ticket 245a7d31): told of every scan pass and of every job dropped without its `done` (the daemon's job notifier). */
    @Volatile
    var bgJobListener: BgJobListener? = null

    /**
     * PROMPT-3: where a session adapter relays a Tool approval `ask` prompt; the daemon points it at its PendingPrompts.
     * Unset = nothing can prompt (approvals denied, questions unanswered).
     */
    @Volatile
    var promptAsker: com.superiorsoftllc.infantry.prompts.PromptAsker = com.superiorsoftllc.infantry.prompts.PromptAsker.CANNOT_PROMPT

    /**
     * Story 9.7b: the launch gate (provider limit state + operator budget), installed by the daemon. Null =
     * no gate. Consulted by [startNew] for an UNATTENDED launch, which it refuses; a HUMAN launch is only
     * ever warned, and the caller asks [launchCheck] for that warning itself.
     */
    @Volatile
    var launchGate: ((provider: String, origin: LaunchOrigin, binding: SessionBinding?) -> GateDecision)? = null

    /**
     * INSTALL-CWD-1: set (installed app only) = a session given no worktree, no repo and no explicit dir runs in its
     * project's own directory, and one whose project has none is refused; null = [defaultWorkingDir], as before.
     */
    var projectFolders: ProjectFolders? = null

    /**
     * SESSION-CWD-1: the dev daemon's lookup (used only when [projectFolders] is null). A session that NAMES a project
     * with an existing directory runs there instead of in the daemon's own directory; no project, a project without
     * a directory or an unknown one keep [defaultWorkingDir].
     */
    var namedProjectFolders: ProjectFolders? = null

    /** What the launch gate says about starting a run on [providerId] (null = the default provider) now. */
    fun launchCheck(providerId: String?, origin: LaunchOrigin, binding: SessionBinding? = null): GateDecision {
        val provider = resolveProvider(providerId).provider.id
        return launchGate?.invoke(provider, origin, binding) ?: GateDecision.Allow
    }

    /**
     * TRAITS-T4 (docs/INFANTRY-ROLE-TRAITS.md section 3): how a spawn that names an agent or a role resolves its
     * [RoleInstance] when the caller passed none (the daemon installs
     * [com.superiorsoftllc.infantry.agents.AgentProfiles.instanceFor]). The fallback makes [startNew] the one chokepoint:
     * a spawn path that forgets to resolve its instance still gets one. Null = none installed (an embedded manager).
     */
    @Volatile
    var instanceFor: ((agentId: String?, role: String?) -> RoleInstance?)? = null

    /** Contract 1.115.0: the global Assistant's working directory (under the app's data dir); null = none configured, which refuses the `assistant` frame. */
    @Volatile
    var assistantDir: java.io.File? = null

    /**
     * P10-K13 (docs/INFANTRY-PHASE10-KNOWLEDGE.md K-D11): the effective skill ids a spawn for [agentId] on [ticketId]
     * would freeze NOW (the daemon installs [com.superiorsoftllc.infantry.agents.SkillAttachment.effective] over the live
     * agent, project settings and ticket). Called once per bound spawn, by [freezeInstance]; the result is stamped on the
     * instance and never recomputed. Null = none installed: the instance keeps the skill ids it was built with.
     */
    @Volatile
    var skillsFor: ((agentId: String?, binding: SessionBinding?) -> List<String>)? = null

    /** P10-K13: the skill ids [sessionId] was spawned with -- the frozen instance's, never the live ticket's. */
    fun skillIds(sessionId: String): List<String> = instance(sessionId)?.skillIds.orEmpty()

    /** The instance a spawn for [agentId]/[role] would freeze NOW; null for an anonymous spawn or with no resolver. */
    fun resolveInstance(agentId: String?, role: String?): RoleInstance? {
        if (agentId.isNullOrBlank() && role.isNullOrBlank()) return null
        return instanceFor?.invoke(agentId, role)
    }

    /**
     * TRAITS-T5: the frozen instance of [sessionId] -- what EVERY first turn of a bound session reads its persona and
     * traits from, never the live profile. Null for an anonymous or unknown session.
     *
     * A session with an agent or a role but no row was spawned by a daemon from before 29.sqm (a bound console persisted
     * in `sessions.json`). It is frozen NOW, once, so its first turn still carries a persona and every later read sees
     * that same row.
     */
    fun instance(sessionId: String): RoleInstance? { return locked {
        instances.get(sessionId)?.let { return it }
        val session = sessions[sessionId] ?: return null
        val legacy = resolveInstance(session.agentId, session.role) ?: return null
        instances.insert(sessionId, withSkills(legacy.copy(agentId = session.agentId, role = session.role), bindingOf(session)), clock())
        return instances.get(sessionId)
    } }

    /**
     * Writes the spawn's instance once; an anonymous spawn (no agent, no role) writes nothing, so it has no skills either
     * (K-D11). P10-K13: the effective skill ids for [ticketId] are resolved here, at spawn, and frozen with the row.
     */
    private fun freezeInstance(sessionId: String, at: Long, agentId: String?, role: String?, given: RoleInstance?, binding: SessionBinding?) {
        if (agentId.isNullOrBlank() && role.isNullOrBlank()) return
        val frozen = given ?: resolveInstance(agentId, role) ?: return
        instances.insert(sessionId, withSkills(frozen.copy(agentId = agentId, role = role), binding), at)
    }

    /**
     * P10-K13: [instance] with the skill ids [skillsFor] resolves now; unchanged when no resolver is installed.
     * P10-K15: public so a caller that renders the first turn BEFORE [startNew] renders the skills the row will freeze.
     */
    fun withSkills(instance: RoleInstance, binding: SessionBinding?): RoleInstance =
        skillsFor?.invoke(instance.agentId, binding)?.let { instance.copy(skillIds = it) } ?: instance

    private val sessions = ConcurrentHashMap<String, SessionRecord>()

    /** The runs currently in flight, keyed by session id. Absent = no run in flight. */
    private val runs = ConcurrentHashMap<String, ActiveRun>()

    /** BUG-M2-11c-INTERRUPT: sessions whose current turn was interrupted; cleared when the next turn starts. */
    private val interruptedTurns: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** HARNESS-CARRY-1: each session's conversation as Infantry saw it, the source of truth a provider switch carries. */
    private val conversation = ConversationLog()

    /** HARNESS-CARRY-1: sessions switched to another harness whose next run must be seeded with the conversation so far. */
    private val carryPending: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * TICKET-HANDOVER: sessions held by a harness switch in progress ([com.superiorsoftllc.infantry.daemon.SessionHandover]).
     * While held, only the handover may start a turn ([resumeHeld]); every other [resume] is refused as a run in flight,
     * so mail or a prompt cannot slip a turn onto the old harness between the handoff turn and the switch.
     */
    private val held: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** TICKET-HANDOVER: hold [sessionId] for a switch; false when it is already held (a switch is in progress). */
    fun hold(sessionId: String): Boolean = held.add(sessionId)

    /** TICKET-HANDOVER: end the hold [hold] took. */
    fun release(sessionId: String) {
        held.remove(sessionId)
    }

    /** TICKET-HANDOVER: true while a harness switch holds [sessionId]. */
    fun isHeld(sessionId: String): Boolean = sessionId in held

    /** TICKET-HANDOVER: true while [sessionId] has a turn in flight. */
    fun inFlight(sessionId: String): Boolean = runs.containsKey(sessionId) || sessions[sessionId]?.live == true

    /** GAP 855db590: how many assistant outputs and tool calls [sessionId] produced in this daemon's life (0 = a silent session). */
    fun workEvents(sessionId: String): Int = conversation.workEvents(sessionId)

    /** TICKET-HANDOVER: the visible text of [sessionId]'s latest reply as Infantry recorded it, or null. */
    fun lastReply(sessionId: String): String? = conversation.history(sessionId).lastOrNull()?.reply?.takeIf { it.isNotBlank() }

    /**
     * BUG-PERM-1: each session's own Tool approval, chosen at spawn. Held in memory: after a daemon restart a resumed
     * session falls back to the global value (Settings / env / deny).
     */
    private val toolApprovalOverrides = ConcurrentHashMap<String, com.superiorsoftllc.infantry.harness.ToolApproval>()

    /** BUG-PERM-1: the Tool approval [sessionId]'s next run gets, with where it came from. */
    fun toolApprovalFor(sessionId: String): ToolApprovals.Resolution = ToolApprovals.current(toolApprovalOverrides[sessionId])

    /** ASK-TIMER (1.139.0): each session's own prompt timer, chosen at spawn; held in memory like [toolApprovalOverrides]. */
    private val promptTimeoutOverrides = ConcurrentHashMap<String, com.superiorsoftllc.infantry.prompts.PromptTimeoutOverride>()

    /** ASK-TIMER: set (or with null clear) [sessionId]'s own prompt timer over the global settings. */
    fun setPromptTimeoutOverride(sessionId: String, override: com.superiorsoftllc.infantry.prompts.PromptTimeoutOverride?) {
        if (override == null || (override.timeoutSec == null && override.onTimeout == null)) promptTimeoutOverrides.remove(sessionId)
        else promptTimeoutOverrides[sessionId] = override
    }

    /** ASK-TIMER: the prompt timer of [sessionId]: [global] with the session's override over it. */
    fun promptTimeoutFor(sessionId: String, global: com.superiorsoftllc.infantry.prompts.PromptTimeoutPolicy): com.superiorsoftllc.infantry.prompts.PromptTimeoutPolicy =
        promptTimeoutOverrides[sessionId]?.over(global) ?: global

    /** Throttle so a chatty stdout does not write the store once per line. */
    private val lastPersisted = ConcurrentHashMap<String, Long>()

    private val watchdog = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "infantry-session-watchdog").also { it.isDaemon = true }
    }.also { executor ->
        require(deadlineMs > 0 && stallMs > 0 && watchdogPollMs > 0)
        executor.scheduleWithFixedDelay(::checkUnattendedRuns, watchdogPollMs, watchdogPollMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (silentPollMs > 0) {
            executor.scheduleWithFixedDelay({ runCatching { sweepSilent() } }, silentPollMs, silentPollMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
        if (mcpHealPollMs > 0) {
            executor.scheduleWithFixedDelay({ runCatching { healMcp() } }, mcpHealPollMs, mcpHealPollMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
        if (backgroundPollMs > 0) {
            executor.scheduleWithFixedDelay(
                { runCatching { scanBackgroundWork() } }, backgroundPollMs, backgroundPollMs, java.util.concurrent.TimeUnit.MILLISECONDS,
            )
        }
    }

    /**
     * Background work: for every session with a kept harness process and NO turn in flight, read the live descendants of
     * that process ([BackgroundWorkScanner]) and set or clear [SessionRecord.backgroundWork]. A turn in flight is already
     * busy, so it is left alone; the next scan after it ends picks up whatever it launched. Called on a timer.
     */
    fun scanBackgroundWork() {
        expireBgJobs()
        runCatching { trackTreeJobs() }
        bgJobListener?.let { l -> runCatching { l.scanned() } }
        runCatching { refreshAutoProgress() }
        for ((id, record) in sessions.entries.toList()) {
            val kept = record.keptProcess
            val found = if (kept == null || runs.containsKey(id)) null
            else BackgroundWorkScanner.scan(processSource, kept.pid, kept.startedAt)
            val before = record.backgroundWork
            if (runs.containsKey(id) && before == null) continue
            // The same process keeps its start time and label identity; only a change is written.
            val next = if (found != null && before != null && found.pid == before.pid) found.copy(startedAt = before.startedAt) else found
            if (next == before) continue
            update(id) { it.copy(backgroundWork = next) }
        }
    }

    /** SCRIPT-VIS-1b: display-only mark that a script hook works for session [id]; the state is untouched. */
    fun setAutomation(id: String, mark: SessionAutomation?) { update(id) { it.copy(automation = mark) } }

    private val autoSeen = java.util.concurrent.ConcurrentHashMap<String, Pair<Boolean?, Int?>>()

    /**
     * PROGRESS-ALL: for each working ticket session, refresh the commit count on its branch and notice a change of the stale
     * flag; a change is pushed as a `session` frame (the bar itself is derived on every read, see [AutoProgress]).
     */
    fun refreshAutoProgress() {
        val live = sessions.values.filter { bindingOf(it) != null && (it.state == SessionState.BUSY || it.state == SessionState.STARTING) }
        autoSeen.keys.retainAll(live.map { it.id }.toSet())
        for (r in live) {
            bindingPolicy.progressCount(r)?.let { AutoProgress.commits[r.id] = it }
            val seen = r.id to (SessionActivityLog.overlay(r).progress?.stale to AutoProgress.commits[r.id])
            val before = autoSeen.put(seen.first, seen.second)
            if (before != null && before != seen.second) emit(SessionEvent.Status(r))
        }
    }

    /**
     * `infantry bg run`: register [job] on [sessionId], or refresh it (an upsert, so a wrapper outliving a daemon restart
     * re-registers by its next heartbeat). The daemon stamps [BgJob.lastBeat]. False = no such session.
     */
    fun bgJobBeat(sessionId: String, job: BgJob): Boolean {
        if (sessions[sessionId] == null) return false
        val beat = job.copy(lastBeat = clock())
        update(sessionId) { r -> r.copy(bgJobs = r.bgJobs.filter { it.id != beat.id } + beat) }
        return true
    }

    /** WAITING-FLAG-1: the `limits.waiting.*` settings, read at every call so a change applies at once. */
    private fun waitingConfig(): WaitingRules.Config {
        val l = com.superiorsoftllc.infantry.settings.LimitRegistry
        fun lim(d: com.superiorsoftllc.infantry.settings.LimitDef) = runCatching { limitSource?.invoke(d) }.getOrNull() ?: d.default
        return WaitingRules.Config(
            lim(l.WAITING_DEFAULT_MINUTES), lim(l.WAITING_GRACE_MINUTES), lim(l.WAITING_CLEAR_ON_OUTPUT) != 0L, lim(l.WAITING_OUTPUT_GRACE_SECONDS),
            lim(l.WAITING_MAX_MINUTES), lim(l.WAITING_REASON_CHARS).toInt(),
        )
    }

    /** WAITING-FLAG-1: apply one waiting [req] to [sessionId]. Null when there is no such session; throws [IllegalArgumentException] for a bad request. */
    fun setWaiting(sessionId: String, req: WaitingRequest): SessionRecord? {
        val current = sessions[sessionId] ?: return null
        if (req.op == "get") return current
        val cfg = waitingConfig()
        WaitingRules.apply(current.waiting, req, clock(), cfg) // validate first, so a bad request changes nothing
        return update(sessionId) { it.copy(waiting = WaitingRules.apply(it.waiting, req, clock(), cfg)) }
    }

    /**
     * Contract 1.117.0: fold one progress [report] into [sessionId]'s [SessionRecord.progress]; the change reaches every
     * client as a `session` push. Null when there is no such session; throws [IllegalArgumentException] for a bad report.
     */
    fun reportProgress(sessionId: String, report: ProgressReport): SessionRecord? {
        val current = sessions[sessionId] ?: return null
        if (report.op == "get") return current
        val project = current.projectId
        val learned = { label: String -> progressEstimates.estimate(project, label) }
        ProgressReducer.apply(current.progress, report, clock(), learned) // validate outside the lock, so a bad report changes nothing
        var finished: Pair<String, Long>? = null
        val now = clock()
        val record = update(sessionId) {
            val next = ProgressReducer.apply(it.progress, report, now, learned)
            finished = ProgressReducer.finished(it.progress, next, now)
            it.copy(progress = next)
        }
        finished?.let { (label, ms) -> progressEstimates.record(project, label, ms) }
        return record
    }

    /**
     * An agent `sleep N` seen in its tool call: shown as an indeterminate "waiting" job whose time estimate is the sleep, so
     * the 1.123.0 countdown counts it down (`estimateSource` = `sleep`, no new wire field). The agent's own progress report
     * wins: nothing is shown over a job it reported, and [clearWait] removes only a wait this set. No such session = null.
     */
    fun setWait(sessionId: String, hint: WaitHint): SessionRecord? {
        val current = sessions[sessionId] ?: return null
        if (current.state in setOf(SessionState.EXITED, SessionState.ORPHANED, SessionState.FAILED)) return current
        val p = current.progress
        if (p != null && p.state == SessionProgress.RUNNING && p.estimateSource != SessionProgress.SOURCE_SLEEP) return current
        // TODO-PROGRESS-1: a to-do bar outranks the sleep hint, finished or not (the list is the agent's own plan).
        if (p?.origin == SessionProgress.ORIGIN_TODO) return current
        return update(sessionId) {
            it.copy(progress = SessionProgress(
                mode = "indeterminate", label = SessionProgress.WAIT_LABEL, startedAt = hint.startedAt, updatedAt = hint.startedAt,
                labelStartedAt = hint.startedAt, estimateMs = hint.seconds * 1000, estimateSource = SessionProgress.SOURCE_SLEEP,
                estimated = true, lastOutputAt = hint.startedAt,
            ))
        }
    }

    /**
     * TODO-PROGRESS-1 (contract 1.128.0): the agent wrote its to-do list ([items] = text to status, the FULL list); the
     * bar follows it as `count` done of total ([TodoProgress]) unless the agent's own running report holds it.
     * A change reaches clients as a `session` push. No such session = null.
     */
    fun setTodoProgress(sessionId: String, items: List<Pair<String, String>>): SessionRecord? {
        val current = sessions[sessionId] ?: return null
        if (current.state in setOf(SessionState.EXITED, SessionState.ORPHANED, SessionState.FAILED)) return current
        val now = clock()
        if (TodoProgress.apply(current.progress, items, now) == current.progress) return current
        return update(sessionId) { it.copy(progress = TodoProgress.apply(it.progress, items, now)) }
    }

    /** The tool call returned (or the turn moved on): drop a wait [setWait] put there; any other progress is left alone. */
    fun clearWait(sessionId: String): SessionRecord? {
        val current = sessions[sessionId] ?: return null
        if (current.progress?.estimateSource != SessionProgress.SOURCE_SLEEP) return current
        return update(sessionId) { if (it.progress?.estimateSource == SessionProgress.SOURCE_SLEEP) it.copy(progress = null) else it }
    }

    /** PROGRESS-S5: past durations for the opt-in estimated mode. The daemon sets a file-backed one in its state dir. */
    @Volatile var progressEstimates: ProgressEstimates = InMemoryProgressEstimates()

    /** The wrapper's command ended: drop the job. Returns the job dropped (null when it was not registered). */
    fun bgJobDone(sessionId: String, jobId: String): BgJob? {
        val job = sessions[sessionId]?.bgJobs?.firstOrNull { it.id == jobId }
        update(sessionId) { r -> if (r.bgJobs.none { it.id == jobId }) r else r.copy(bgJobs = r.bgJobs.filter { it.id != jobId }) }
        return job
    }

    /**
     * HARNESS-GAP G16: end job [jobId]: every recorded member of its tree, then the wrapper's live tree. Each pid is killed
     * only while its start instant still matches the one recorded (a recycled pid is left alone). Null = no such job.
     */
    fun bgJobStop(jobId: String, table: com.superiorsoftllc.infantry.session.host.ProcessTable = com.superiorsoftllc.infantry.session.host.OsProcessTable): String? {
        val job = bgJobs(null).firstOrNull { it.id == jobId } ?: return null
        var killed = 0
        for (m in job.members) {
            val start = m.startedAt ?: continue
            if (table.startInstant(m.pid) != start || m.pid == job.pid) continue
            if (ProcessGuard.protectedPids.contains(m.pid)) continue
            runCatching { table.killTree(m.pid, start); killed++ }
        }
        // The wrapper started before it registered; a pid whose start is later than that is another process now.
        val wrapperStart = job.pidStart ?: table.startInstant(job.pid)?.takeIf { it <= job.startedAt }
        if (wrapperStart != null && table.startInstant(job.pid) == wrapperStart) {
            runCatching { table.killTree(job.pid, wrapperStart); killed++ }
        }
        return "stopped job $jobId ($killed process tree(s) ended)"
    }

    /** Jobs of [sessionId], or of every session when null. */
    fun bgJobs(sessionId: String?): List<BgJob> =
        (if (sessionId != null) listOfNotNull(sessions[sessionId]) else sessions.values.toList()).flatMap { it.bgJobs }

    /** STALL-1: the top-level work processes last seen under each session's kept harness process. */
    private val treeSeen = ConcurrentHashMap<String, Map<Long, ProcInfo>>()

    /**
     * STALL-1 layer 5, for processes the daemon did NOT launch (a harness's own background shell, a `gradlew` the worker
     * ran in its tool): every scan reads the top-level work processes under each kept harness process, in a turn or not,
     * and tells [bgJobListener] when one that lived at least `limits.watch.treeJobSeconds` (30) is gone. Its exit code is unknown.
     */
    fun trackTreeJobs() {
        val now = clock()
        val def = com.superiorsoftllc.infantry.settings.LimitRegistry.WATCH_TREE_JOB_SECONDS
        val minLived = (limitSource?.let { src -> runCatching { src(def) }.getOrNull() } ?: def.default) * 1000L
        for ((id, record) in sessions.entries.toList()) {
            val kept = record.keptProcess
            if (kept == null) { treeSeen.remove(id); continue }
            val tops = BackgroundWorkScanner.tops(processSource, kept.pid, kept.startedAt).associateBy { it.pid }
            val before = treeSeen.put(id, tops) ?: continue
            // The harness itself gone = the session ended; that is the finish notice's, not a job end.
            if (tops.isEmpty() && !pidProbe(kept.pid)) continue
            for ((pid, p) in before) {
                if (pid in tops) continue
                if (now - (p.startedAt ?: now) < minLived) continue
                bgJobListener?.let { l -> runCatching { l.treeJobEnded(id, p, now) } }
            }
        }
        treeSeen.keys.retainAll(sessions.keys)
    }

    /** STALL-1: the session list's watch label ("watching gate (quiet 2m)", "stalled", ...); written only on a change. */
    fun setWatchLabel(sessionId: String, label: String?) {
        val current = sessions[sessionId] ?: return
        if (current.watchLabel == label) return
        update(sessionId) { it.copy(watchLabel = label) }
    }

    /** Drop jobs whose wrapper pid is gone or whose heartbeat went quiet for [BG_JOB_STALE_MS]. Called on the scan timer. */
    fun expireBgJobs() {
        val now = clock()
        for ((id, record) in sessions.entries.toList()) {
            if (record.bgJobs.isEmpty()) continue
            val live = record.bgJobs.filter { pidProbe(it.pid) && now - it.lastBeat <= BG_JOB_STALE_MS }
            if (live.size != record.bgJobs.size) {
                update(id) { it.copy(bgJobs = live) }
                val gone = record.bgJobs - live.toSet()
                bgJobListener?.let { l -> gone.forEach { j -> runCatching { l.expired(id, j) } } }
            }
        }
    }

    /** One turn in flight. Its process belongs to the [SessionHost], whatever the harness. */
    private class ActiveRun(val startedAt: Long) {
        val killRequested = AtomicBoolean(false)

        /** Set by [interrupt]: the current turn is being stopped, the session itself survives at rest. */
        val interruptRequested = AtomicBoolean(false)

        /** True once the run printed any stdout; a failed run with none gets the auth hint (UI-BOOT-1). */
        @Volatile var sawStdout = false
        val forced = AtomicBoolean(false)
        val watchdogFired = AtomicBoolean(false)
        @Volatile var watchdogKillAt = 0L
        val watchdogRetried = AtomicBoolean(false)
    }

    // --- startup / reconciliation ---------------------------------------------------------------

    /**
     * Load persisted sessions and reconcile them against reality.
     *
     * Ownership decision, stated once and relied on everywhere: the daemon owns process trees while
     * it is alive (and kills them on a graceful shutdown). If it dies without cleanup, it does NOT
     * kill recorded pids on startup -- a pid can be reused by an unrelated process, and killing on
     * that guess would be worse than the alternative. The one exception (BUG-M2-11f) is a KEPT
     * process ([SessionRecord.keptProcess]), killed only when its pid AND start instant both still
     * match, which rules the reuse out; that record comes back IDLE and resumable. Otherwise it probes:
     *
     *  - child gone  -> the run is over; the session becomes [SessionState.EXITED] (resumable, its
     *    [SessionRecord.providerSessionId] kept);
     *  - child alive -> it outlived the daemon; the session becomes [SessionState.ORPHANED]. The
     *    daemon does not adopt it (it cannot reattach to the pipe), and since both CLIs are one-shot
     *    the orphan terminates itself; its pid is kept for visibility.
     *
     * A session already at rest ([SessionState.IDLE], or any terminal state from before) is left
     * exactly as it was.
     */
    fun loadAndReconcile(): ReconcileReport { return locked {
        var exited = 0
        var orphaned = 0
        var unchanged = 0
        var reapedCount = 0
        val cutTurns = ArrayList<String>()
        val orphanedIds = ArrayList<String>()
        val loaded = loadOrNoSessions()
        for (loadedRecord in loaded) {
            // BUG-M2-11f: a kept process is either reaped now or already gone; either way the record no longer has one.
            val kept = loadedRecord.keptProcess
            val reaped = kept != null && reap(loadedRecord.id, kept)
            // Background work is derived, never trusted from disk: its wire state read BUSY, so the real one is IDLE.
            // STALL-1: a watched (`--notify`) job is KEPT across the restart: its runner re-registers by its next beat, or the pid
            // probe drops it and the watcher reports it ("exit N" from its exit file, else "runner lost, exit unknown").
            val persisted = loadedRecord.copy(
                keptProcess = null, backgroundWork = null, automation = null, watch = null, progress = null, waiting = null, watchLabel = null, mcp = null, mcpChangedAt = null,
                bgJobs = loadedRecord.bgJobs.filter { it.notify != null }.map { it.copy(lastBeat = clock()) },
            ).let {
                if ((loadedRecord.backgroundWork != null || loadedRecord.bgJobs.isNotEmpty()) && it.state == SessionState.BUSY && it.childPid == null) it.copy(state = SessionState.IDLE) else it
            }
            val midRun = persisted.state == SessionState.STARTING ||
                persisted.state == SessionState.BUSY ||
                persisted.state == SessionState.WAITING_INPUT
            val pid = persisted.childPid
            when {
                // The record comes back IDLE and resumable: the next prompt launches and resumes the provider session.
                reaped -> {
                    sessions[persisted.id] = persisted.copy(
                        state = SessionState.IDLE,
                        childPid = null,
                        lastHeartbeat = clock(),
                        isProcessAlive = false,
                    )
                    reapedCount++
                    if (midRun) cutTurns += persisted.id
                }
                midRun && pid != null && pidProbe(pid) -> {
                    sessions[persisted.id] = persisted.copy(
                        state = SessionState.ORPHANED,
                        lastHeartbeat = clock(),
                        isProcessAlive = true,
                    )
                    orphaned++
                    orphanedIds += persisted.id
                }
                midRun -> {
                    sessions[persisted.id] = persisted.copy(
                        state = SessionState.EXITED,
                        childPid = null,
                        lastHeartbeat = clock(),
                        isProcessAlive = false,
                    )
                    exited++
                    cutTurns += persisted.id
                }
                else -> {
                    sessions[persisted.id] = persisted.copy(
                        isProcessAlive = false,
                    )
                    unchanged++
                }
            }
        }
        // CLI-PTY-5: every session with a conversation to resume is DORMANT until something attaches its CLI or runs a turn.
        for ((id, record) in sessions.toMap()) {
            val dormant = record.providerSessionId != null && record.state != SessionState.ORPHANED
            if (record.dormant != dormant) sessions[id] = record.copy(dormant = dormant)
        }
        if (loaded.isNotEmpty()) writeAll()
        return ReconcileReport(exited, orphaned, unchanged, reapedCount, cutTurns, orphanedIds)
    } }

    /**
     * BUG-M2-11f: tree-kill [kept] only when it is provably the process this daemon's predecessor kept: the pid is alive
     * AND its start instant is the recorded one. A recycled pid (same number, another start instant) is never touched,
     * and neither is a record with no start instant. True = killed.
     */
    private fun reap(sessionId: String, kept: KeptProcess): Boolean {
        // BUG-REAP-OWNER: only a process THIS state dir kept. A copied or foreign sessions.json, or an older file with no
        // owner, names processes some other live daemon owns; and a live parent means it is not a dead predecessor's orphan.
        val me = instanceId ?: return false
        if (kept.ownerId != me) return false
        if (runCatching { processTable.hasLiveParent(kept.pid) }.getOrDefault(true)) return false
        val recorded = kept.startedAt ?: return false
        val actual = runCatching { processTable.startInstant(kept.pid) }.getOrNull() ?: return false
        if (actual != recorded) return false
        runCatching { processTable.killTree(kept.pid, recorded) }
            .onFailure { System.err.println("[infantry] reaping ${kept.label} pid ${kept.pid} failed: ${it.message}") }
        val line = "reaped stale ${kept.label} process pid ${kept.pid}"
        println("[infantry] $line (session $sessionId)")
        emit(SessionEvent.Activity(sessionId, kept.label, line))
        return true
    }

    // --- reads ----------------------------------------------------------------------------------

    /**
     * DAEMON-PLUGINS-1 (`spawnGate` hook): asked before [startNew] creates anything (role, project); a non-null answer is the
     * refusal. The default refuses nothing.
     */
    @Volatile var spawnGate: (role: String?, projectId: String?, binding: SessionBinding?) -> String? = { _, _, _ -> null }

    fun list(): List<SessionRecord> = sessions.values.sortedBy { it.spawnedAt }

    fun get(id: String): SessionRecord? = sessions[id]

    // --- create + run ---------------------------------------------------------------------------

    /**
     * Create a fresh session and, when [prompt] is non-blank, immediately start its first run.
     *
     * Phase 4: when [repo] is given and a [WorktreeProvisioner] is configured, the session gets its
     * OWN worktree FIRST and runs in it -- isolation is established before any provider process
     * exists, so two sessions on one repository never share a checkout. Provisioning failure FAILS
     * the session (state [SessionState.FAILED], [SessionRecord.lastError] set) rather than falling
     * back to the shared checkout, which is exactly the collision this phase exists to prevent.
     *
     * [branchHint] (a task label) only shapes the branch slug; the manager still owns the name and
     * guarantees uniqueness with the session id.
     *
     * Contract 1.3.0: [providerId] names the provider to run on (null or blank = the default) and
     * [model] the model its runs name (null or blank = the provider's configured default). Both are
     * checked BEFORE anything exists: a provider this manager was not configured with, or a model
     * that cannot be put on a command line, throws [IllegalArgumentException] with no session, no
     * worktree and no process created, and never falls back to the default provider.
     *
     * Contract 1.4.0: [agentId] and [role] attribute the session to the agent profile it was spawned
     * from. They are recorded as given; resolving a profile is the caller's job
     * ([com.superiorsoftllc.infantry.agents.AgentProfiles.resolveSpawn]).
     */
    fun startNew(
        prompt: String,
        workingDir: File? = null,
        repo: File? = null,
        branchHint: String? = null,
        baseRef: String? = null,
        providerId: String? = null,
        model: String? = null,
        agentId: String? = null,
        role: String? = null,
        ticketId: String? = null,
        contextSources: List<ContextSource>? = null,
        projectId: String? = null,
        unattended: Boolean = false,
        /**
         * Story 9.7b: who is starting this session. [LaunchOrigin.UNATTENDED] (a drained queue, a trigger)
         * is refused by the launch gate when the provider is exhausted or its budget spent: an
         * [IllegalArgumentException] naming the reason, with nothing created. Null or HUMAN: no gate here.
         */
        launchOrigin: LaunchOrigin? = null,
        /**
         * BRANCH-PER-TASK 1/3: a worktree the caller already cut and owns (a ticket's own branch). The session
         * runs in it and no second worktree is provisioned for the same work. Null = provision as before.
         */
        preWorktree: WorktreeRecord? = null,
        /** CP-4(e): the reasoning effort the first run starts with (null = the harness default). */
        effort: String? = null,
        /** BUG-PERM-1: this session's own Tool approval over the global value; null = the global value. */
        toolApproval: com.superiorsoftllc.infantry.harness.ToolApproval? = null,
        /**
         * TRAITS-T4: the role instance this session is a clone of, frozen here once. A caller that composes the first
         * turn BEFORE this call passes the instance it rendered, so the stored row is exactly what the session read.
         * Null = resolved through [instanceFor] when the spawn names an agent or a role; an anonymous spawn has none.
         */
        instance: RoleInstance? = null,
        /** Contract 1.115.0: create the installation-wide Assistant; set on the FIRST `session` frame, so no client ever sees it as an ordinary agent. */
        global: Boolean = false,
        /** CONTEXT-LIMIT-1: the session's context limit in tokens ([ContextLimits]); null = the harness's own window. */
        contextLimit: Int? = null,
    ): SessionRecord { return locked {
        // DAEMON-PLUGINS-1: a plugin's spawn gate binds every path (frame, mail, trigger, ticket) that creates a session.
        val binding = bindingPolicy.bindingFor(ticketId)
        spawnGate(role, projectId, binding)?.let { throw IllegalArgumentException(it) }
        busyTwin(null, agentId, binding)?.let { throw IllegalArgumentException(bindingPolicy.twinRefusal("start", agentId, binding, it)) }
        val chosen = resolveProvider(providerId).provider
        val sessionModel = spawnModel(model)
        // LIMITS: both refused BEFORE anything exists (no-ops while neither setting is set).
        requireAllowedModel(sessionModel, chosen)
        if (!global) requireAllowedRole(role)
        sessionCapRefusal()?.let { throw SessionCapException(it) }
        val sessionEffort = effort?.trim()?.takeIf { it.isNotEmpty() }?.also {
            require(modelProblem(it) == null) { "effort '${it.take(40)}' is not a usable level" }
        }
        ContextLimits.problem(contextLimit)?.let { throw IllegalArgumentException(it) }
        val limitSupport = contextLimit?.let { chosen.contextLimit().support.wire }
        val effortSupport = sessionEffort?.let { chosen.effort().support.wire } // EFFORT-1
        if (launchOrigin == LaunchOrigin.UNATTENDED) {
            val decision = launchGate?.invoke(chosen.id, LaunchOrigin.UNATTENDED, binding)
            if (decision is GateDecision.Refused) throw IllegalArgumentException("launch gate refused: ${decision.reason}")
        }
        // INSTALL-CWD-1: resolved BEFORE anything exists, so a project with no directory leaves nothing behind.
        val workingDir = workingDir
            ?: if (preWorktree == null && repo == null) {
                projectFolders?.require(projectId)
                    ?: projectId?.takeIf { it.isNotBlank() }
                        ?.let { namedProjectFolders?.find(it) }?.takeIf { it.isDirectory }
            } else null
        val id = idFactory()
        val now = clock()
        toolApproval?.let { toolApprovalOverrides[id] = it }

        var worktree: WorktreeRecord? = null
        val provisioner = worktrees
        if (preWorktree != null) {
            // BRANCH-PER-TASK 1/3: the caller (a ticket start) already cut this worktree and owns its branch.
            worktree = preWorktree.copy(sessionId = id)
            provisioner?.adopt(worktree)
        } else if (repo != null && provisioner != null) {
            val provisioned = runCatching { provisioner.create(id, repo, branchHint, baseRef) }
            if (provisioned.isFailure) {
                val failed = SessionRecord(
                    id = id,
                    provider = chosen.id,
                    workingDir = (workingDir ?: defaultWorkingDir).absolutePath,
                    spawnedAt = now,
                    lastHeartbeat = now,
                    state = SessionState.FAILED,
                    lastError = "worktree provisioning failed: ${provisioned.exceptionOrNull()?.message}",
                    model = sessionModel,
                    agentId = agentId,
                    role = role,
                    ticketId = ticketId,
                    contextSources = contextSources.orEmpty(),
                    projectId = projectId,
                    unattended = unattended,
                    global = global,
                    isProcessAlive = false,
                )
                freezeInstance(id, now, agentId, role, instance, binding)
                sessions[id] = failed
                persist(id, force = true)
                emit(SessionEvent.Status(failed))
                return failed
            }
            worktree = provisioned.getOrNull()
        }

        val effectiveDir = worktree?.let { File(it.path) } ?: (workingDir ?: defaultWorkingDir)
        val record = SessionRecord(
            id = id,
            provider = chosen.id,
            workingDir = effectiveDir.absolutePath,
            spawnedAt = now,
            lastHeartbeat = now,
            // M2-12: a prompt is a turn in progress, so the agent is WORKING (BUSY) at once; no prompt = no run: at rest.
            state = if (prompt.isBlank()) SessionState.IDLE else SessionState.BUSY,
            worktreePath = worktree?.path,
            worktreeBranch = worktree?.branch,
            repoRoot = worktree?.repoRoot,
            worktreeBaseRef = worktree?.baseRef,
            model = sessionModel,
            effort = sessionEffort,
            contextLimit = contextLimit,
            contextLimitSupport = limitSupport,
            effortSupport = effortSupport,
            agentId = agentId,
            role = role,
            ticketId = ticketId,
            contextSources = contextSources.orEmpty(),
            projectId = projectId,
            unattended = unattended,
            global = global,
            isProcessAlive = false,
        )
        // P10-K13: frozen BEFORE the record is visible, so nobody who can see the session sees it without its instance.
        freezeInstance(record.id, now, agentId, role, instance, binding)
        sessions[record.id] = record
        // TRAITS-T6: an unattended clone of a role with a `report` trait owes a REPORT BACK from spawn (`pending`).
        ReportBack.initial(instances.get(record.id), unattended)?.let { sessions[record.id] = record.copy(report = it) }
        persist(record.id, force = true)
        emit(SessionEvent.Status(record))
        if (prompt.isNotBlank()) startRun(record.id, prompt)
        return sessions.getValue(record.id)
    } }

    /**
     * Resume a finished or dead session and run [prompt] as its next turn. This does not spawn
     * anything by itself -- it clears the terminal state and starts a run whose [RunRequest]
     * carries the stored [SessionRecord.providerSessionId], so the CLI continues the same
     * conversation. That is the whole of "resume with context intact": the context lives in the
     * CLI's own session store, which is exactly why the provider session id is the durable field
     * that matters.
     *
     * Fails loudly for a session with no provider session id (nothing to resume), and refuses while a
     * run is already in flight.
     *
     * Contract 1.3.0: a session resumes on the provider that started it. When this manager is not
     * configured with that provider (the daemon restarted with a different `INFANTRY_PROVIDER`), the
     * resume is refused here: the provider session id belongs to that CLI, and handing it to another
     * one could only fail, later and less clearly.
     */
    fun resume(sessionId: String, prompt: String, origin: MessageOrigin = MessageOrigin.Owner, wakeReason: String? = null, wakeRef: String? = null): SessionRecord { return locked {
        if (sessionId in held) throw RunInFlightException(sessionId, "session $sessionId is switching harness; send again once it has switched")
        return resumeHeld(sessionId, prompt, origin, wakeReason, wakeRef)
    } }

    /**
     * SCRIPT-VIS-1: told every time a prompt wakes a session: (session id, reason, ref). Reason is the caller's [resume] `wakeReason`
     * (mail, mail-hook-delivered, ...) or derived from the origin (owner, agent, external). Generic: the sink decides what to do with it.
     */
    @Volatile
    var wakeSink: ((sessionId: String, reason: String, ref: String?) -> Unit)? = null

    /** TICKET-HANDOVER: [resume] for the switch that holds [sessionId] (the only caller allowed past the hold). */
    fun resumeHeld(sessionId: String, prompt: String, origin: MessageOrigin = MessageOrigin.Owner, wakeReason: String? = null, wakeRef: String? = null): SessionRecord { return locked {
        val session = sessions[sessionId] ?: error("unknown session $sessionId")
        if (runs.containsKey(sessionId)) throw RunInFlightException(sessionId, "session $sessionId already has a run in flight")
        busyTwin(sessionId, session.agentId, bindingOf(session))?.let {
            throw RunInFlightException(it.id, bindingPolicy.twinRefusal("resume of $sessionId", session.agentId, bindingOf(session), it))
        }
        // GAP-006b: a session AT REST (IDLE) that never opened a provider session has no conversation to lose, so its next
        // turn is a FIRST turn (never refused as "not resumable"); a ticket worker's carries the ticket's brief ahead of the
        // message, so typing into a phantom worker's chat starts that ticket's own work, never a blank instance. A FAILED
        // one stays refused: its harness already failed to start once, and mail to it is a visible permanent failure.
        require(session.providerSessionId != null || session.state == SessionState.IDLE) {
            "session $sessionId is not resumable: no provider session id was ever observed " +
                "(state=${session.state})"
        }
        val firstTurn = session.providerSessionId == null
        val delivered = if (firstTurn && !session.neverRun && prompt.isNotBlank()) withBrief(session, prompt) else prompt
        require(lookup(session.provider) != null) {
            "session $sessionId runs on provider '${session.provider}', which this daemon is not " +
                "configured with (configured: ${configured.keys.joinToString()})"
        }
        if (prompt.isNotBlank()) {
            // LIMITS: a turn spends the session's model and takes a working slot; refused before anything changes.
            requireAllowedModel(session.model, lookup(session.provider)?.provider)
            if (!session.global) requireAllowedRole(session.role)
            sessionCapRefusal(sessionId)?.let { throw SessionCapException(it) }
        }
        val revived = session.copy(
            state = SessionState.IDLE,
            killOutcome = null,
            lastError = null,
            resumeCount = session.resumeCount + 1,
            lastHeartbeat = clock(),
            isProcessAlive = false,
        )
        sessions[sessionId] = revived
        persist(sessionId, force = true)
        emit(SessionEvent.Status(revived))
        if (delivered.isNotBlank()) {
            startRun(sessionId, delivered, resuming = !firstTurn, origin = origin)
            val reason = wakeReason ?: when (origin) { is MessageOrigin.Owner -> "owner"; is MessageOrigin.Agent -> "agent"; else -> "external" }
            runCatching { wakeSink?.invoke(sessionId, reason, wakeRef) }
        }
        return sessions.getValue(sessionId)
    } }

    /**
     * AR-3a: the first-turn context providers, in registration order. A layer above the core registers one (the board adds the
     * ticket's start brief, GAP-006b); a session with no binding gets none. Read by [resumeHeld] for a first turn.
     */
    private val firstTurnProviders = java.util.concurrent.CopyOnWriteArrayList<com.superiorsoftllc.infantry.daemon.api.sessions.FirstTurnContextProvider>()

    fun addFirstTurnProvider(provider: com.superiorsoftllc.infantry.daemon.api.sessions.FirstTurnContextProvider) {
        firstTurnProviders += provider
    }

    /** The binding of [session] as the policy names it (the record keeps its wire fields), null when it has none. */
    fun bindingOf(session: SessionRecord): SessionBinding? = bindingPolicy.bindingFor(session.ticketId)

    private val SEP = "\n\n---\n\n"

    internal fun withBrief(session: SessionRecord, prompt: String): String {
        val binding = bindingOf(session) ?: return prompt
        val sections = firstTurnProviders.mapNotNull { p ->
            runCatching { p.section(session.id, binding) }
                .onFailure { System.err.println("[infantry] first-turn section for ${session.id} not composed: ${it.message}") }
                .getOrNull()?.takeIf { it.isNotBlank() }
        }
        if (sections.isEmpty()) return prompt
        return sections.joinToString(SEP) + SEP + prompt
    }

    /**
     * CP-4 slice d, `/model`: the session's NEXT run names [model]. Validated like a spawn's model (a syntax check; the
     * CLI answers whether it exists). A run already in flight is untouched. Throws [IllegalArgumentException] naming
     * the problem, changing nothing.
     */
    fun setModel(sessionId: String, model: String): SessionRecord { return locked {
        val wanted = spawnModel(model) ?: throw IllegalArgumentException("a model name is required")
        requireAllowedModel(wanted, null)
        return changeSetting(sessionId) { it.copy(model = wanted) }
    } }

    /**
     * CP-4 slice d, `/effort`: the session's NEXT run carries [effort]. Whether the level is one the harness has is
     * the caller's check (the catalog's values); this only refuses what could not be one CLI argument.
     */
    fun setEffort(sessionId: String, effort: String): SessionRecord { return locked {
        val wanted = effort.trim()
        require(wanted.isNotEmpty() && modelProblem(wanted) == null) { "effort '${wanted.take(40)}' is not a usable level" }
        return changeSetting(sessionId) { it.copy(effort = wanted, effortSupport = resolveProvider(it.provider).provider.effort().support.wire) }
    } }

    /**
     * Role-first S3 (`agent_swap`, outcome `kept`): the session's NEXT run names [model] and [effort] (null = the
     * harness default), through the same internal path `/model` and `/effort` take. A run in flight is untouched: it
     * finishes on the executor it started with. Throws [IllegalArgumentException], changing nothing.
     */
    fun retarget(sessionId: String, model: String?, effort: String?): SessionRecord { return locked {
        val wantedModel = spawnModel(model)
        requireAllowedModel(wantedModel, null)
        val wantedEffort = effort?.trim()?.takeIf { it.isNotEmpty() }?.also {
            require(modelProblem(it) == null) { "effort '${it.take(40)}' is not a usable level" }
        }
        return changeSetting(sessionId) { it.copy(model = wantedModel, effort = wantedEffort, effortSupport = wantedEffort?.let { _ -> resolveProvider(it.provider).provider.effort().support.wire }) }
    } }

    /**
     * TICKET-HANDOVER: the checks [reharness] makes on its target ([providerId], [model], [effort]), with nothing written,
     * so a switch that only reharnesses later (after a handoff turn) is refused NOW. Throws [IllegalArgumentException].
     */
    fun checkSwitch(providerId: String, model: String?, effort: String?) {
        resolveProvider(providerId)
        spawnModel(model)?.let { requireAllowedModel(it, null) }
        effort?.trim()?.takeIf { it.isNotEmpty() }?.let {
            require(modelProblem(it) == null) { "effort '${it.take(40)}' is not a usable level" }
        }
    }

    /**
     * UX-HARNESS-2 (`session_swap`): restart [sessionId] on another harness ([providerId], [model], [effort]; a null
     * model / effort = that harness's default). The record keeps its id, project, working directory and history, and
     * becomes a never-run session on the new harness: its provider session id is dropped (it belonged to the old CLI),
     * so the NEXT turn is a first run there. HARNESS-CARRY-1: that first run is seeded with the conversation so far
     * (the Infantry transcript, through the new harness's [com.superiorsoftllc.infantry.harness.ConversationCarry]), so the
     * conversation continues. The process the host keeps alive for the old harness is stopped. Same harness = only the
     * model / effort change ([retarget]). Refused while a turn is in flight. Throws [IllegalArgumentException],
     * changing nothing.
     */
    fun reharness(sessionId: String, providerId: String, model: String?, effort: String?, carry: Boolean = true): SessionRecord { return locked {
        val session = sessions[sessionId] ?: throw IllegalArgumentException("unknown session '$sessionId'")
        require(!runs.containsKey(sessionId) && !session.live) { "a reply is still running; wait for it to finish or stop it, then switch" }
        val target = resolveProvider(providerId).provider.id
        if (target == session.provider) return retarget(sessionId, model, effort)
        val wantedModel = spawnModel(model)
        requireAllowedModel(wantedModel, null)
        val wantedEffort = effort?.trim()?.takeIf { it.isNotEmpty() }?.also {
            require(modelProblem(it) == null) { "effort '${it.take(40)}' is not a usable level" }
        }
        host.stop(sessionId)
        // HARNESS-CARRY-1: the conversation follows the session onto the new harness, seeded on its first run.
        // TICKET-HANDOVER: [carry] false = the first run brings its own context (the ticket brief and a handoff note).
        if (carry) carryPending += sessionId else carryPending -= sessionId
        return changeSetting(sessionId) {
            it.copy(
                provider = target, model = wantedModel, effort = wantedEffort,
                effortSupport = wantedEffort?.let { _ -> resolveProvider(target).provider.effort().support.wire },
                // CONTEXT-LIMIT-1: the limit follows the session; how it is honoured is the NEW harness's.
                contextLimitSupport = it.contextLimit?.let { _ -> resolveProvider(target).provider.contextLimit().support.wire },
                providerSessionId = null, runCount = 0, state = SessionState.IDLE,
                killOutcome = null, lastError = null, lastExitCode = null, childPid = null, keptProcess = null,
                isProcessAlive = false, lastHeartbeat = clock(),
            )
        }
    } }

    /**
     * SWAP-AGENT-1 (`session_swap` with a role): [sessionId] now runs as [role] (null = no role), in place. Its frozen
     * instance (TRAITS-T4) is replaced by the one a spawn as [role] would freeze NOW, and the record becomes a never-run
     * session on its CURRENT harness, so the NEXT turn is a first turn: the new role's persona, identity, traits and
     * skills, then (HARNESS-CARRY-1) the conversation so far. The old role's CLI conversation is never resumed: it was
     * held under the old persona. The same role is a no-op. Refused while a turn is in flight. Whether [role] exists is
     * the caller's check. Throws [IllegalArgumentException], changing nothing.
     */
    fun rerole(sessionId: String, role: String?): SessionRecord { return locked {
        val session = sessions[sessionId] ?: throw IllegalArgumentException("unknown session '$sessionId'")
        require(!runs.containsKey(sessionId) && !session.live) { "a reply is still running; wait for it to finish or stop it, then switch" }
        val wanted = role?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (wanted == session.role?.takeIf { it.isNotBlank() }) return session
        requireAllowedRole(wanted)
        val next = resolveInstance(session.agentId, wanted)?.let { withSkills(it.copy(agentId = session.agentId, role = wanted), bindingOf(session)) }
        host.stop(sessionId)
        instances.delete(sessionId)
        next?.let { instances.insert(sessionId, it, clock()) }
        carryPending += sessionId
        return changeSetting(sessionId) {
            it.copy(
                role = wanted, providerSessionId = null, runCount = 0, state = SessionState.IDLE,
                killOutcome = null, lastError = null, lastExitCode = null, childPid = null, keptProcess = null,
                isProcessAlive = false, lastHeartbeat = clock(),
            )
        }
    } }

    /**
     * ROLE-NOTES-1 (`session_restart`): reboot [sessionId] in place. Same id, role, provider, model, effort, project and
     * working directory; the old agent process is stopped, the frozen instance is re-resolved NOW (a persona or trait edit
     * lands), and the record becomes a never-run session, so the NEXT turn is composed anew as a first turn (role notes,
     * project notes, identity). The conversation is not carried: the point is a fresh start. A turn in flight is refused
     * unless [force], which stops it first. Session notes are kept (this is no kill). Throws [IllegalArgumentException],
     * changing nothing.
     */
    fun restart(sessionId: String, force: Boolean = false): SessionRecord { return locked {
        val session = sessions[sessionId] ?: throw IllegalArgumentException("unknown session '$sessionId'")
        val busy = runs.containsKey(sessionId) || session.live
        require(!busy || force) {
            "session '$sessionId' is mid-turn (state=${session.state}); wait for the reply to finish, or confirm with force to stop it first"
        }
        // force means the turn is stuck: kill the process tree at once rather than waiting out the grace period.
        if (busy) killRun(sessionId, force = true)
        host.stop(sessionId)
        closed(sessionId, "session restarted")
        val next = resolveInstance(session.agentId, session.role)?.let { withSkills(it.copy(agentId = session.agentId, role = session.role), bindingOf(session)) }
        instances.delete(sessionId)
        next?.let { instances.insert(sessionId, it, clock()) }
        return changeSetting(sessionId) {
            it.copy(
                providerSessionId = null, runCount = 0, state = SessionState.IDLE,
                killOutcome = null, lastError = null, lastExitCode = null, childPid = null, keptProcess = null,
                isProcessAlive = false, lastHeartbeat = clock(),
            )
        }
    } }

    /** MCP-RECONNECT-1: the plain lines. [down] when the link goes away, [up] when it is back. No diagnosis, no ports. */
    data class McpLines(
        val down: String = "Your Infantry tools are disconnected; the daemon is reconnecting them, wait. If they stay away, run: infantry session mcp-reconnect --self",
        val up: String = "Your Infantry tools are connected again.",
        val failed: String = "Your Infantry tools are still disconnected after the daemon's reconnect tries. Run: infantry session mcp-reconnect --self",
    )

    private class McpHeal(var attempts: Int = 0, var nextAt: Long = 0, var told: Boolean = false, var agentTold: Boolean = false, var gaveUp: Boolean = false)

    private val mcpHeal = ConcurrentHashMap<String, McpHeal>()

    /** MCP-RECONNECT-1: told once per session that stayed disconnected through every try (the daemon wires it to a chat note for the session and its PM). */
    @Volatile
    var mcpHealFailedSink: ((SessionRecord, Int) -> Unit)? = null

    /** MCP-RECONNECT-1: record the harness's MCP state for [sessionId] (what the live process reports). */
    fun reportMcpState(sessionId: String, state: String, reason: String? = null) = hostEvents.mcpState(sessionId, state, reason)

    /** MCP-RECONNECT-1: the line reaches the transcript AND the agent, like queue_add (an idle session gets it as a turn at once, a busy one at turn end). */
    private fun tellAgent(sessionId: String, text: String) {
        bootLine(sessionId, text)
        runCatching { queueAdd(sessionId, text, healSeconds(com.superiorsoftllc.infantry.settings.LimitRegistry.CHAT_QUEUE_MAX).toInt()) }
    }

    private fun healSeconds(def: com.superiorsoftllc.infantry.settings.LimitDef): Long =
        limitSource?.let { runCatching { it(def) }.getOrNull() } ?: def.default

    /**
     * MCP-RECONNECT-1: the daemon heals a session whose MCP link shows `disconnected`, with no agent involved. Every sweep: tell the
     * session ONE plain line, then reconnect (live in place, else a process restart) with exponential backoff; a busy session that
     * cannot be reconnected live is retried at turn end (the sweep keeps looking, a refused busy try does not count). After
     * [com.superiorsoftllc.infantry.settings.LimitRegistry.MCP_HEAL_ATTEMPTS] tries, [mcpHealFailedSink] is told once.
     */
    fun healMcp() {
        val now = clock()
        val lr = com.superiorsoftllc.infantry.settings.LimitRegistry
        val max = healSeconds(lr.MCP_HEAL_ATTEMPTS).toInt()
        for (s in sessions.values.toList()) {
            val st = mcpHeal[s.id]
            if (s.mcp != MCP_DISCONNECTED) {
                if (s.mcp == MCP_RECONNECTING) continue
                if (st != null) {
                    mcpHeal.remove(s.id)
                    if (st.told && s.mcp == MCP_CONNECTED) tellAgent(s.id, mcpLines.up)
                }
                continue
            }
            val h = st ?: McpHeal().also { mcpHeal[s.id] = it }
            if (!h.told) { h.told = true; bootLine(s.id, mcpLines.down) }
            if (h.gaveUp || now < h.nextAt) continue
            if (h.attempts >= max) {
                h.gaveUp = true
                tellAgent(s.id, mcpLines.failed)
                runCatching { mcpHealFailedSink?.invoke(s, h.attempts) }
                continue
            }
            h.attempts++
            val base = healSeconds(lr.MCP_HEAL_DELAY_SECONDS)
            val cap = healSeconds(lr.MCP_HEAL_MAX_DELAY_SECONDS)
            h.nextAt = now + minOf(cap, base shl (h.attempts - 1).coerceAtMost(20)) * 1000
            try {
                reconnectMcp(s.id, force = false)
            } catch (_: IllegalArgumentException) {
                h.attempts--           // mid-turn: not a failed try; looked at again at turn end
                h.nextAt = now + base * 1000
            } catch (e: Exception) {
                System.err.println("[infantry] mcp heal failed for ${s.id}: ${e.message}")
            }
            // The agent hears the "wait" line only if the link is still down after the try (a queued line starts a turn on an idle session).
            if (!h.agentTold && sessions[s.id]?.mcp.let { it == MCP_DISCONNECTED || it == MCP_RECONNECTING }) {
                h.agentTold = true
                runCatching { queueAdd(s.id, mcpLines.down, healSeconds(lr.CHAT_QUEUE_MAX).toInt()) }
            }
        }
    }

    /**
     * CLI-PTY-3 (`session_act mcp_reconnect`): bring [sessionId]'s MCP servers back without losing the session. Only ever called by that
     * action: the daemon has no policy of its own for when to reconnect. First the live process is asked to reconnect in place (the
     * harness's own reconnect); when it cannot, the process is stopped and the record keeps its provider session, so the next turn
     * resumes the same conversation (same id, model and context) with fresh servers. A turn in flight is refused unless [force].
     * Returns one line saying what happened; throws [IllegalArgumentException] for an unknown session or a refused busy one.
     */
    fun reconnectMcp(sessionId: String, force: Boolean = false): String { return locked {
        val session = sessions[sessionId] ?: throw IllegalArgumentException("unknown session '$sessionId'")
        when (host.reconnectMcp(sessionId)) {
            null -> {
                update(sessionId) { if (it.mcp == null) it else it.copy(mcp = null, mcpChangedAt = clock()) }
                return "no live process: the next turn starts its MCP servers fresh"
            }
            true -> {
                update(sessionId) { it.copy(mcp = MCP_CONNECTED, mcpChangedAt = clock()) }
                bootLine(sessionId, "infantry tools reconnected")
                return "reconnected in place"
            }
            false -> Unit
        }
        val busy = runs.containsKey(sessionId)
        require(!busy || force) { "session '$sessionId' is mid-turn (state=${session.state}); wait for the turn to end, or pass force to stop it first" }
        update(sessionId) { it.copy(mcp = MCP_RECONNECTING, mcpChangedAt = clock()) }
        // force on a busy session: the turn ends as an owner interrupt would end it (the session stays usable, not killed).
        if (busy) runCatching { interrupt(sessionId) }
        host.stop(sessionId, force = busy)
        bootLine(sessionId, "infantry tools reconnected: the process was restarted; the next turn resumes this conversation with fresh MCP servers")
        return "process restarted; the next turn resumes provider session ${session.providerSessionId ?: "(none yet)"}"
    } }

    /**
     * TRAITS-T6: record [report] as [sessionId]'s REPORT BACK state, persisted and pushed like any other record change.
     * Only [com.superiorsoftllc.infantry.mailbox.ReportBackMonitor] writes it.
     */
    fun setReport(sessionId: String, report: SessionReport): SessionRecord = changeSetting(sessionId) { it.copy(report = report) }

    /** PIPELINE-WATCHDOG: store the derived [AgentWatch]; an unchanged value writes and pushes nothing. */
    fun setWatch(sessionId: String, watch: AgentWatch?) { locked {
        val current = sessions[sessionId] ?: return
        if (current.watch == watch) return
        changeSetting(sessionId) { it.copy(watch = watch) }
    } }

    /**
     * BUG-M2-15b (contract 1.61.0, `session_project`): bind an UNBOUND session (one recorded before spawns carried a
     * project) to [projectId], persisted and pushed like any other record change. Binding to the project it already
     * has is a no-op answer; moving a session that is bound to ANOTHER project is refused, since mail routing and the
     * access policy were set up for that one. Throws [IllegalArgumentException], changing nothing.
     */
    fun setProject(sessionId: String, projectId: String): SessionRecord { return locked {
        val wanted = projectId.trim()
        require(wanted.isNotEmpty()) { "a project id is required" }
        val session = sessions[sessionId] ?: throw IllegalArgumentException("unknown session '$sessionId'")
        val current = session.projectId?.takeIf { it.isNotBlank() }
        require(current == null || current == wanted) { "session '$sessionId' is already bound to project '$current'" }
        return changeSetting(sessionId) { it.copy(projectId = wanted) }
    } }

    /** Contract 1.114.0: record which session spawned [sessionId]; a no-op for a blank or unknown id. */
    fun setSpawner(sessionId: String, spawnerId: String?) {
        val spawner = spawnerId?.trim()?.takeIf { it.isNotEmpty() } ?: return
        if (!sessions.containsKey(sessionId)) return
        changeSetting(sessionId) { it.copy(spawnerSessionId = spawner) }
    }

    /** Apply [transform], persist, and tell listeners: a setting changes no state, so [update] would stay silent. */
    private fun changeSetting(sessionId: String, transform: (SessionRecord) -> SessionRecord): SessionRecord {
        val session = sessions[sessionId] ?: throw IllegalArgumentException("unknown session '$sessionId'")
        val next = transform(session)
        sessions[sessionId] = next
        persist(sessionId, force = true)
        emit(SessionEvent.Status(next))
        return next
    }

    /** The [RunRequest] and environment a turn of [session] (or an attach, with a blank [prompt]) launches its process with. */
    private fun launchInputs(session: SessionRecord, provider: AgentProvider, prompt: String): Pair<RunRequest, Map<String, String>> {
        val request = RunRequest(
            prompt = prompt,
            workingDir = File(session.workingDir),
            resumeSessionId = session.providerSessionId,
            model = session.model,
            // EFFORT-1: only to a harness that applies it; an unsupported one keeps its own default (recorded as such).
            effort = session.effort?.takeIf { provider.effort().applies },
            toolApproval = toolApprovalFor(session.id).mode,
            // CONTEXT-LIMIT-1: only to a harness that applies it; an unsupported one keeps its own window (recorded as such).
            contextLimit = session.contextLimit?.takeIf { provider.contextLimit().applies },
        )
        // Contract AGENT-UX-13: every run carries the agent's OWN identity -- its session id is the
        // `from` of any handoff it initiates -- and the daemon's mail base (`childEnv`, set by the
        // daemon) so it can address a colleague without a delivered prompt having taught it who it is.
        // BUG-808ce596: and WHO it runs as -- its role and bound agent profile, when it has them -- on every
        // run, so a role-only spawn (the New agent modal) knows its role from the env too.
        val limitsEnv = runCatching { buildEnv(com.superiorsoftllc.infantry.daemon.api.processes.LaunchInfo(session.id, session.role, session.projectId)) }.onFailure { System.err.println("[infantry] build limits env failed: ${it.message}") }.getOrDefault(emptyMap())
        runCatching { GradleProgressInit.install() }
        val runEnv = PathEnv.withBinOnPath(childEnv + limitsEnv + runCatching { credentialEnv(session.provider) }.getOrDefault(emptyMap()) + buildMap {
            put(GradleProgressInit.ENV, "1")
            put(AccessPolicy.SESSION_ID_ENV, session.id)
            session.projectId?.let { put(AccessPolicy.PROJECT_ID_ENV, it) }
            session.agentId?.takeIf { it.isNotBlank() }?.let { put(AccessPolicy.AGENT_ID_ENV, it) }
            session.role?.takeIf { it.isNotBlank() }?.let { put(AccessPolicy.ROLE_ENV, it) }
        })
        return request to runEnv
    }

    /**
     * CLI-PTY-5: attach [sessionId]'s CLI to its provider conversation WITHOUT a turn: no prompt is sent and nothing runs, so
     * no tokens are spent beyond the process start. A harness that keeps a process between turns (interactive claude, opencode
     * serve) launches it now with `--resume`, so the next message goes straight into a warm process; a one-shot harness has
     * nothing to start, and its next turn resumes as always. Either way the session stops being [SessionRecord.dormant].
     * Refused while a run is in flight; a session with no provider conversation only clears the flag.
     */
    fun attach(sessionId: String): SessionRecord { return locked {
        if (sessionId in held) throw RunInFlightException(sessionId, "session $sessionId is switching harness; resume it once it has switched")
        val session = sessions[sessionId] ?: throw IllegalArgumentException("unknown session '$sessionId'")
        if (runs.containsKey(sessionId)) throw RunInFlightException(sessionId, "session $sessionId already has a run in flight")
        val target = lookup(session.provider)
        require(target != null) {
            "session $sessionId runs on provider '${session.provider}', which this daemon is not configured with (configured: ${configured.keys.joinToString()})"
        }
        val revived = session.copy(
            state = if (session.state == SessionState.EXITED || session.state == SessionState.FAILED || session.state == SessionState.ORPHANED) SessionState.IDLE else session.state,
            dormant = false, killOutcome = null, lastError = null, lastHeartbeat = clock(),
        )
        sessions[sessionId] = revived
        persist(sessionId, force = true)
        emit(SessionEvent.Status(revived))
        if (session.providerSessionId == null || host.hasProcess(sessionId)) return sessions.getValue(sessionId)
        val (request, env) = launchInputs(revived, target.provider, "")
        emit(SessionEvent.Activity(sessionId, target.provider.id, "Resuming session $sessionId: attaching provider session ${session.providerSessionId} (no turn sent)"))
        host.attach(sessionId, target.provider, request, env)
        return sessions.getValue(sessionId)
    } }

    private fun startRun(sessionId: String, prompt: String, resuming: Boolean = false, origin: MessageOrigin = MessageOrigin.Owner) {
        val session = sessions[sessionId] ?: return
        if (runs.containsKey(sessionId)) {
            throw RunInFlightException(sessionId, "session $sessionId already has a run in flight (one run per session; concurrency is a later phase)")
        }
        // Contract 1.11.0: the turn is accepted, so it goes on the session's stream NOW -- before the
        // provider is resolved or spawned -- and therefore ahead of anything this run prints. A run that
        // then fails to start still shows what was asked, followed by why it never ran.
        emit(SessionEvent.Turn(sessionId, prompt, origin))
        // The session's OWN provider and transport (contract 1.3.0). startNew and resume have already
        // refused an unconfigured provider; this guard only keeps a run from ever reaching another CLI.
        val target = lookup(session.provider) ?: run {
            failRun(sessionId, IllegalStateException("provider '${session.provider}' is not configured on this daemon"))
            return
        }
        val provider = target.provider
        // HARNESS-CARRY-1: the first run after a provider switch takes over the conversation so far, in the way the new
        // harness supports (ConversationCarry). Only the RUN gets it: the turn frame above shows what was asked, so the
        // transcript stays one conversation. The turn joins the log after the history is read, so it is not carried twice.
        val history = if (carryPending.remove(sessionId)) conversation.history(sessionId) else emptyList()
        conversation.turn(sessionId, prompt)
        val delivered = if (history.isEmpty()) prompt else provider.conversationCarry().seed(history, prompt)
        val (request, runEnv) = launchInputs(session, provider, delivered)
        val active = ActiveRun(clock())
        interruptedTurns.remove(sessionId)
        runs[sessionId] = active
        // UI-BOOT-1: say what is about to run, so the chat is not silent until the first reply.
        // RESUME-1: a resume announces itself with a resume-specific line, so continuing a session is
        // visibly distinct from the "resuming provider session <id>" line that every run emits once it
        // has a provider session id -- that line cannot tell the user a resume actually happened.
        // BUG-G2: name the model the run really asks for; a session with none uses the provider's configured
        // default, which the old line printed as a bare "default" even when INFANTRY_OPENCODE_MODEL was set.
        val defaultModel = runCatching { provider.defaultModel() }.getOrNull()
        val shownModel = session.model?.takeIf { it.isNotBlank() }
            ?: defaultModel?.let { "$it (provider default)" }
            ?: "default"
        // CLI-LIVE-1: "starting <cli>" is said only when a process really starts; a wake into a live process is a message, not a start.
        val liveProcess = host.hasLiveTurnProcess(sessionId)
        val bootDetails = buildList {
            if (resuming) add("Resuming session $sessionId...")
            if (!liveProcess) add("starting ${provider.id} model $shownModel in ${session.workingDir}")
            session.providerSessionId?.let { add("resuming provider session $it") }
        }
        // M2-12: the status follows the TURN, not the process. The turn was accepted above, so the agent is WORKING
        // (BUSY) from now until the turn ends; booting a process for it is plumbing and never a STARTING flip.
        update(sessionId) {
            it.copy(state = SessionState.BUSY, dormant = false, childPid = null, runCount = it.runCount + 1, lastHeartbeat = clock(), isProcessAlive = true, backgroundWork = null, waiting = null, killedBy = null, killReason = null)
        }
        // BUG-M2-11b-1: the host owns the process and the ONE chat line per launch; the per-turn plumbing is Activity.
        bootDetails.forEach { emit(SessionEvent.Activity(sessionId, provider.id, it)) }
        host.runTurn(sessionId, provider, request, runEnv)
    }

    /**
     * CLI-PTY-4: the session's live process began a turn nobody sent (an interactive CLI answering a background task that ended). It is a
     * run like any other from here: the session reads BUSY, mail waits for it (busy = retry), the watchdog and the feed see it, and its end
     * ([HostEvents.turnEnded]) settles it. A run already in flight keeps it.
     */
    private fun startOwnTurn(sessionId: String, reason: String?) {
        val provider: String? = locked {
            val session = sessions[sessionId] ?: return@locked null
            if (runs.containsKey(sessionId)) return@locked null
            interruptedTurns.remove(sessionId)
            runs[sessionId] = ActiveRun(clock())
            update(sessionId) {
                it.copy(state = SessionState.BUSY, runCount = it.runCount + 1, lastHeartbeat = clock(), isProcessAlive = true, backgroundWork = null, waiting = null, killedBy = null, killReason = null)
            }
            session.provider
        }
        if (provider != null) emit(SessionEvent.Activity(sessionId, provider, "the CLI began a turn of its own" + reason?.let { ": $it" }.orEmpty()))
    }

    /** Ticket 7e9bed2a: told once per silence when a BUSY session has had no output for [silentMs] and is not provably dead. */
    @Volatile
    var silentSink: ((SessionRecord, Long) -> Unit)? = null

    /** The heartbeat a silent session was last flagged at, so one silence is flagged once. */
    private val flaggedSilent = ConcurrentHashMap<String, Long>()

    /**
     * Ticket 7e9bed2a: BUSY but silent. Only FLAGS ([silentSink]); a session is ended here ONLY when it is provably dead: its
     * record claims a turn with no run in this daemon and no live pid ([isStaleLive]), or its run names a child pid that is gone.
     * Work that is visibly running (a registered bg job, a live gate run for it) is never silent. A live pid is never killed.
     */
    fun sweepSilent() {
        val now = clock()
        val silentMs = liveMs(com.superiorsoftllc.infantry.settings.LimitRegistry.SILENT_MINUTES, silentMs)
        for (session in sessions.values.toList()) {
            if (!session.isBusy()) { flaggedSilent.remove(session.id); continue }
            if (now - session.lastHeartbeat < silentMs) continue
            if (session.bgJobs.isNotEmpty() or (session.backgroundWork != null)) continue
            if (bindingPolicy.lockHeld(session.id, bindingOf(session))) continue
            val pid = session.childPid
            val runPresent = runs.containsKey(session.id)
            val provablyDead = if (!runPresent) isStaleLive(session.id, session) else pid != null && !pidProbe(pid)
            val minutes = (now - session.lastHeartbeat) / 60_000
            if (provablyDead) {
                println("[infantry] silent sweeper: session ${session.id} BUSY for $minutes min with no live run or pid; marked exited")
                runCatching { kill(session.id, by = "silent sweeper", reason = "BUSY with no output for $minutes min and no live run or process") }
                    .onFailure { System.err.println("[infantry] silent sweeper stop failed for ${session.id}: ${it.message}") }
                continue
            }
            if (flaggedSilent.put(session.id, session.lastHeartbeat) == session.lastHeartbeat) continue
            runCatching { silentSink?.invoke(session, minutes) }
                .onFailure { System.err.println("[infantry] silent flag failed for ${session.id}: ${it.message}") }
        }
    }

    private fun checkUnattendedRuns() {
        val now = clock()
        val killRetryMs = liveMs(com.superiorsoftllc.infantry.settings.LimitRegistry.KILL_RETRY_MINUTES, killRetryMs)
        val stallMs = liveMs(com.superiorsoftllc.infantry.settings.LimitRegistry.STALL_MINUTES, stallMs)
        val deadlineMs = liveMs(com.superiorsoftllc.infantry.settings.LimitRegistry.UNATTENDED_DEADLINE_MINUTES, deadlineMs)
        for ((sessionId, run) in runs) {
            val session = sessions[sessionId] ?: continue
            if (run.watchdogFired.get()) {
                // Ticket 7e9bed2a: a kill that left the run registered (it threw, or the process would not die) is retried once, forced.
                val at = run.watchdogKillAt
                if (UnattendedRunWatchdog.retryDue(at, now, killRetryMs, run.watchdogRetried.get()) && run.watchdogRetried.compareAndSet(false, true)) {
                    runCatching { kill(sessionId, force = true, by = KILLED_BY_WATCHDOG, reason = "the first kill did not end the run within ${minutesText(killRetryMs)}; retried forced (setting limits.watchdog.killRetryMinutes)") }
                        .onFailure { System.err.println("[infantry] watchdog forced retry failed for $sessionId: ${it.message}") }
                }
                continue
            }
            val deadline = bindingPolicy.deadlineMs(bindingOf(session), ::liveMs) ?: deadlineMs
            // Ticket 7e9bed2a: silence is not a stall while a registered bg job or a live gate run for this session works.
            val alive = session.bgJobs.isNotEmpty() or bindingPolicy.lockHeld(session.id, bindingOf(session))
            val expiry = UnattendedRunWatchdog.expiry(
                UnattendedRunWatchdog.Run(run.startedAt, session.lastHeartbeat, session.unattended),
                now, deadline, stallMs, alive,
            ) ?: continue
            if (!run.watchdogFired.compareAndSet(false, true)) continue
            run.watchdogKillAt = now
            val why = when (expiry) {
                WatchdogExpiry.STALLED -> "stalled: no output for ${(now - session.lastHeartbeat) / 60_000} min (limit ${minutesText(stallMs)}, setting limits.watchdog.stallMinutes)"
                WatchdogExpiry.DEADLINE -> "deadline: running for ${(now - run.startedAt) / 60_000} min (limit ${minutesText(deadline)}, setting " +
                    (bindingPolicy.deadlineSetting(bindingOf(session)) ?: "limits.watchdog.deadlineMinutes") + ")"
            }
            runCatching { watchdogSink?.invoke(session, expiry) }
                .onFailure { System.err.println("[infantry] watchdog attention failed: ${it.message}") }
            runCatching { kill(sessionId, by = KILLED_BY_WATCHDOG, reason = why) }
                .onFailure { System.err.println("[infantry] watchdog stop failed for $sessionId: ${it.message}") }
        }
    }

    /**
     * Any streamed line advances the heartbeat and, if this is the first output of a run, moves the
     * session from [SessionState.STARTING] to [SessionState.BUSY]. A provider session id observed on
     * the line is force-persisted (via [update]) so it survives a crash mid-run.
     */
    private fun heartbeat(sessionId: String, providerSessionId: String?) { locked {
        update(sessionId) { current ->
            current.copy(
                lastHeartbeat = clock(),
                state = if (current.state == SessionState.STARTING) SessionState.BUSY else current.state,
                providerSessionId = providerSessionId ?: current.providerSessionId,
                isProcessAlive = true,
            )
        }
    } }

    /**
     * BUG-M2-11b-1: a hosted turn ended ([HostEvents.turnEnded]). A turn that never got a child (its spec or its spawn
     * failed) fails the session with the reason and no `finished`, as before the host.
     */
    private fun finishHosted(sessionId: String, end: TurnEnd, exitCode: Int?, processExited: Boolean, reason: String?) { locked {
        val run = runs[sessionId] ?: return
        if (!runs.remove(sessionId, run)) return
        // 1.117.0: a turn that ended with no progress report in it drops the old report, unless a registered
        // background job (`infantry bg run`) is still running the measured work.
        update(sessionId) { r ->
            val p = r.progress
            if (p == null || p.updatedAt >= run.startedAt || r.bgJobs.isNotEmpty()) r else r.copy(progress = null)
        }
        when {
            run.interruptRequested.get() || end == TurnEnd.INTERRUPTED -> settleInterrupted(sessionId, exitCode)
            run.killRequested.get() -> settle(sessionId, run, exitCode, processExited = false)
            exitCode == null && end == TurnEnd.FAILED -> failRun(sessionId, IllegalStateException(reason ?: "the turn failed"), processExited)
            else -> settle(sessionId, run, exitCode ?: if (end == TurnEnd.COMPLETED) 0 else 1, processExited)
        }
    } }

    private fun settle(sessionId: String, run: ActiveRun, exitCode: Int?, processExited: Boolean) {
        if (run.interruptRequested.get()) {
            settleInterrupted(sessionId, exitCode)
            return
        }
        val state: SessionState
        val outcome: KillOutcome?
        when {
            run.killRequested.get() -> {
                state = SessionState.EXITED
                outcome = if (run.forced.get()) KillOutcome.FORCED else KillOutcome.GRACEFUL
            }
            exitCode == null -> {
                state = SessionState.FAILED
                outcome = null
            }
            exitCode == 0 -> {
                state = SessionState.IDLE
                outcome = null
            }
            else -> {
                state = SessionState.FAILED
                outcome = null
            }
        }
        // FIRSTRUN-FAIL-1: a failed turn always says why, in the record, the transcript and the `finished` frame.
        val reason = if (state != SessionState.FAILED) null
            else if (exitCode == null) "the process ended without an exit code" else "the process exited with code $exitCode"
        if (state == SessionState.FAILED && !run.sawStdout) {
            bootLine(
                sessionId,
                "run failed (exit $exitCode). If this is an auth problem run `opencode auth login` / `claude login`.",
            )
        } else if (reason != null) {
            bootLine(sessionId, "turn failed: $reason")
        }
        update(sessionId) {
            it.copy(
                state = state,
                lastExitCode = exitCode,
                killOutcome = outcome,
                lastError = reason ?: it.lastError,
                childPid = null,
                lastHeartbeat = clock(),
                isProcessAlive = false,
            )
        }
        emit(SessionEvent.Exit(sessionId, exitCode, outcome, processExited = processExited, reason = reason))
        if (state == SessionState.FAILED) closed(sessionId, "turn failed", resumable = true)
        if (state == SessionState.IDLE || state == SessionState.FAILED) scheduleQueueDrain(sessionId)
    }

    // --- CHAT-QUEUE-1: messages held while a turn runs ------------------------------------------------

    /** What [queueForce] did with the message: delivered into the running turn, or (nothing was running) sent as a turn of its own. */
    enum class ForceOutcome { INJECTED, SENT_AS_TURN }

    /** The messages held for [sessionId], oldest first (empty for an unknown session). */
    fun queued(sessionId: String): List<QueuedTurn> = sessions[sessionId]?.queued.orEmpty()

    /** Whether [sessionId]'s harness takes a message into a running turn (MID_TURN_INPUT), so [queueForce] can deliver it mid-turn. */
    fun midTurnInput(sessionId: String): Boolean =
        sessions[sessionId]?.let { s -> runCatching { lookup(s.provider)?.provider?.midTurnInput() }.getOrNull() } ?: false

    /**
     * Hold [text] for [sessionId] while its turn runs; the daemon sends the oldest held message as the next turn when the session comes to
     * rest. A session with no turn in flight takes it as a turn now (null is returned: nothing was queued). Refused: an unknown session, a
     * blank text, a full queue ([maxQueued], setting `limits.chat.queueMax`).
     */
    fun queueAdd(sessionId: String, text: String, maxQueued: Int): QueuedTurn? { return locked {
        val session = sessions[sessionId] ?: throw IllegalArgumentException("unknown session $sessionId")
        require(text.isNotBlank()) { "nothing to queue" }
        if (!runs.containsKey(sessionId) && sessionId !in held) {
            resume(sessionId, text)
            return null
        }
        require(session.queued.size < maxQueued) { "the queue of $sessionId is full ($maxQueued messages)" }
        val item = QueuedTurn("q-" + java.util.UUID.randomUUID().toString().take(8), text, clock())
        update(sessionId) { it.copy(queued = it.queued + item) }
        return item
    } }

    /** Take the held message [id] back: it is never delivered. Refused (IllegalArgumentException) when [sessionId] holds no such message. */
    fun queueRemove(sessionId: String, id: String): QueuedTurn { return locked {
        val session = sessions[sessionId] ?: throw IllegalArgumentException("unknown session $sessionId")
        val item = session.queued.firstOrNull { it.id == id } ?: throw IllegalArgumentException("no queued message $id on $sessionId")
        update(sessionId) { r -> r.copy(queued = r.queued.filterNot { it.id == id }) }
        return item
    } }

    /**
     * Deliver the held message [id] NOW. While a turn runs it goes INTO that turn through the harness (MID_TURN_INPUT): the turn is not
     * stopped, the agent sees it at its next step, and it shows in the transcript at that place. With no turn running it is sent as the next
     * turn. Refused, the message staying queued: an unknown message, a harness that cannot take input mid-turn, or a process that could not
     * take it right now (IllegalStateException, with the reason).
     */
    fun queueForce(sessionId: String, id: String, origin: MessageOrigin = MessageOrigin.Owner): ForceOutcome { return locked {
        val session = sessions[sessionId] ?: throw IllegalArgumentException("unknown session $sessionId")
        val item = session.queued.firstOrNull { it.id == id } ?: throw IllegalArgumentException("no queued message $id on $sessionId")
        if (!runs.containsKey(sessionId) && sessionId !in held) {
            update(sessionId) { r -> r.copy(queued = r.queued.filterNot { it.id == id }) }
            try {
                resume(sessionId, item.text, origin, wakeReason = "queue")
            } catch (e: Exception) {
                update(sessionId) { r -> r.copy(queued = listOf(item) + r.queued) }
                throw e
            }
            return ForceOutcome.SENT_AS_TURN
        }
        check(midTurnInput(sessionId)) { "the ${session.provider} harness cannot take a message mid-turn; it stays queued for the next turn" }
        check(host.injectInput(sessionId, item.text)) { "the agent's process could not take the message right now; it stays queued" }
        update(sessionId) { r -> r.copy(queued = r.queued.filterNot { it.id == id }) }
        emit(SessionEvent.Turn(sessionId, item.text, origin, midTurn = true))
        return ForceOutcome.INJECTED
    } }

    /**
     * At rest for the queue: IDLE, or a FAILED turn that [resume] still takes (it has a provider conversation to go on with).
     * A FAILED session that never opened one is refused by [resume], so its messages stay held rather than fail one by one.
     */
    private fun drainable(session: SessionRecord): Boolean =
        session.state == SessionState.IDLE || (session.state == SessionState.FAILED && session.providerSessionId != null)

    /** The session came to rest: send its oldest held message as the next turn, off the thread that settled the turn. */
    private fun scheduleQueueDrain(sessionId: String) {
        if (sessions[sessionId]?.queued.isNullOrEmpty()) return
        runCatching { watchdog.execute { runCatching { drainQueue(sessionId) } } }
    }

    /**
     * Send [sessionId]'s oldest held message as its next turn, when the session is at rest. A refusal because another run started first
     * (mail, typically) puts it back at the front for the next time the session comes to rest; any other refusal is said on the stream and
     * it stays held.
     */
    internal fun drainQueue(sessionId: String) { locked {
        val session = sessions[sessionId] ?: return
        val head = session.queued.firstOrNull() ?: return
        if (runs.containsKey(sessionId) || sessionId in held || !drainable(session)) return
        update(sessionId) { r -> r.copy(queued = r.queued.drop(1)) }
        try {
            resume(sessionId, head.text, MessageOrigin.Owner, wakeReason = "queue")
        } catch (e: Exception) {
            update(sessionId) { r -> r.copy(queued = listOf(head) + r.queued) }
            if (e !is RunInFlightException) bootLine(sessionId, "a queued message was not sent: ${e.message ?: e.javaClass.simpleName}; it stays queued")
        }
    } }

    /**
     * Interrupt the CURRENT turn of [sessionId] and return the session to rest.
     *
     * Distinct from [kill]: the process tree of the in-flight run is stopped at once, but the session is
     * NOT ended. It comes to rest as [SessionState.IDLE] with the same id, the provider session id it
     * already has (so the next turn resumes the same conversation) and its transcript. No kill outcome is
     * recorded, because nothing was killed on the session's behalf. Pending mail queued for it is handed
     * over by the usual at-rest pump, because the transition to IDLE is a status event like any other.
     *
     * Refuses (IllegalStateException, nothing touched) an unknown id or a session with no run in flight.
     * A second interrupt of the same turn is a no-op that returns the current record.
     */
    fun interrupt(sessionId: String): SessionRecord {
        val run = runs[sessionId]
            ?: error("session $sessionId is not busy: nothing to interrupt (state=${sessions[sessionId]?.state ?: "unknown session"})")
        if (!run.interruptRequested.compareAndSet(false, true)) return sessions.getValue(sessionId)
        interruptedTurns.add(sessionId)
        // Tree kill, not stdin EOF: the point of an interrupt is to stop a long scan NOW. (closeInput would
        // start a scripted replay in tests and asks for a graceful stop the owner did not ask for.)
        host.abortTurn(sessionId)
        // A runner that never reports its exit after a tree kill must not leave the session looking busy.
        if (runs.remove(sessionId, run)) settleInterrupted(sessionId, exitCode = null)
        return sessions.getValue(sessionId)
    }

    /**
     * Bring an interrupted session to rest. The caller has already removed its [ActiveRun], which is what
     * makes this the single terminal transition for that turn. No `Exit` event: an interrupt is not a run
     * outcome (FINISHED would read as FAILED with no exit code), so the transcript gets a visible line
     * and the IDLE status is the at-rest signal.
     */
    private fun settleInterrupted(sessionId: String, exitCode: Int?) { locked {
        bootLine(sessionId, "turn interrupted by the owner; the session is at rest and keeps its conversation")
        update(sessionId) {
            it.copy(
                state = SessionState.IDLE,
                killOutcome = null,
                lastExitCode = exitCode,
                childPid = null,
                lastHeartbeat = clock(),
                isProcessAlive = false,
            )
        }
        scheduleQueueDrain(sessionId)
    } }

    /** UI-BOOT-1: an additive STDERR line (no new frame type) explaining what the daemon is doing. */
    private fun bootLine(sessionId: String, text: String) {
        emit(SessionEvent.Stderr(sessionId, "[infantry] $text"))
    }

    /**
     * A turn that failed with no exit code (the harness reported an error stop, or the run never started). HARNESS-GAP G9
     * (ticket b908f8fa): it ends like any other failed turn -- an `Exit` (code null) so the finish notice and its listeners
     * see it, and the queue drain, so messages held for the session are not stranded behind the failure.
     */
    private fun failRun(sessionId: String, e: Exception, processExited: Boolean = false) {
        val reason = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
        // FIRSTRUN-FAIL-1: the reason reaches the transcript and the `finished` frame, not only the record.
        bootLine(sessionId, "turn failed: $reason")
        update(sessionId) {
            it.copy(
                state = SessionState.FAILED,
                lastError = reason,
                lastExitCode = null,
                childPid = null,
                lastHeartbeat = clock(),
                isProcessAlive = false,
            )
        }
        emit(SessionEvent.Exit(sessionId, code = null, processExited = processExited, reason = reason))
        closed(sessionId, "turn failed", resumable = true)
        scheduleQueueDrain(sessionId)
    }

    // --- kill -----------------------------------------------------------------------------------

    /**
     * Stop a session. Returns the session afterwards, or null when the id is unknown.
     *
     * Escalation, recorded honestly:
     *  1. with [force] off: request a graceful stop -- the only graceful signal these one-shot CLIs
     *     expose is stdin EOF, so stdin is closed -- and wait up to [killGraceMs];
     *  2. if the process is still alive (or [force] was on), kill the whole process tree
     *     ([RunningProcess.killTree]) and mark the outcome [KillOutcome.FORCED].
     *
     * A kill is a deliberate end, so the resulting state is always [SessionState.EXITED]; [KillOutcome]
     * records whether force was needed. Killing an idle session simply marks it exited -- "kill" must
     * always resolve to a terminal session, never a no-op that leaves a half-dead agent looking alive.
     * A session that has already ended (EXITED, FAILED, ORPHANED) is returned unchanged.
     *
     * Fails loudly for an unknown id rather than returning a session that does not exist.
     */
    fun kill(sessionId: String, force: Boolean = false, by: String = KILLED_BY_CLIENT, reason: String? = null): SessionRecord {
        noteKill(sessionId, by, reason)
        return try {
            killRun(sessionId, force)
        } finally {
            // Closing a session stops the process the host keeps alive for it.
            host.stop(sessionId, force)
            if (sessions.containsKey(sessionId)) {
                endedNotice(sessionId)
                closed(sessionId, "session killed")
            }
        }
    }

    /** Ticket 7e9bed2a: record WHO is ending [sessionId] before the end lands, so the exit notice can say it. A session already ended keeps its first answer. */
    private fun noteKill(sessionId: String, by: String, reason: String?) {
        if (!sessions.containsKey(sessionId)) return
        update(sessionId) { if (it.state in ENDED) it else it.copy(killedBy = by, killReason = reason) }
    }

    /**
     * Ticket b785f383: called when a session closes (kill, remove, restart, a turn ending FAILED), so the daemon can kill
     * the background processes it left (see the process-watch plugin). Set once by the daemon; never fails the caller.
     */
    @Volatile
    var onSessionClosed: (SessionClosed) -> Unit = {}
        set(value) {
            field = { c -> runCatching { value(c) } }
        }

    /**
     * [resumable]: a failed turn, not a closed session: the harness the host still holds and every registered bg job (the
     * next turn may want them) stay, with their trees; only the strays go.
     */
    private fun closed(sessionId: String, reason: String, record: SessionRecord? = sessions[sessionId], resumable: Boolean = false) {
        val keep = record?.let { r ->
            if (resumable) ProcessGuard.keepPidsOf(r) + r.bgJobs.map { it.pid } else ProcessGuard.keepPidsOf(r.copy(keptProcess = null))
        }.orEmpty()
        onSessionClosed(SessionClosed(sessionId, clock(), keep, reason))
    }

    /**
     * NOTES-1: called when a session is killed or removed, so what is scoped to that one session (its session
     * notes) goes with it. Set once by the daemon; a failure here never fails the kill or the removal.
     */
    @Volatile
    var onSessionEnded: (String) -> Unit = {}
        set(value) {
            field = { id -> runCatching { value(id) } }
        }

    /** DP-7d (DaemonApi 1.18.0): extra listeners told after [onSessionEnded] when a session is killed or removed; one failing never stops the rest. */
    private val endedListeners = java.util.concurrent.CopyOnWriteArrayList<(String) -> Unit>()

    fun addSessionEndedListener(listener: (String) -> Unit): AutoCloseable {
        endedListeners += listener
        return AutoCloseable { endedListeners -= listener }
    }

    private fun endedNotice(sessionId: String) {
        onSessionEnded(sessionId)
        for (l in endedListeners) runCatching { l(sessionId) }
    }

    private fun killRun(sessionId: String, force: Boolean): SessionRecord {
        val run = runs[sessionId]
        if (run == null) {
            val existing = sessions[sessionId] ?: error("unknown session $sessionId")
            return if (existing.state in ENDED) {
                existing
            } else {
                // BUG-KILL-IDLE-1: an IDLE session, or a record that claims to be running with no live run, is
                // ended here. Left IDLE it kept looking alive and kept its parent's pod slot in SpawnGuard.
                markKilled(sessionId, KillOutcome.GRACEFUL) ?: existing
            }
        }

        run.killRequested.set(true)
        // BUG-M2-11b-1: the host owns a hosted turn's process, and the grace: close, then a tree kill after it.
        host.stop(sessionId, force, onForce = { run.forced.set(true) })

        // If the runner never reported exit (it should), do not leave the session looking alive.
        if (runs.containsKey(sessionId)) {
            markKilled(
                sessionId,
                if (run.forced.get()) KillOutcome.FORCED else KillOutcome.GRACEFUL,
                expectedRun = run,
            )
        }
        return sessions.getValue(sessionId)
    }

    // --- delete -----------------------------------------------------------------------------------

    /**
     * AGENT-UX-11 (contract 1.26.0): permanently remove [sessionId]'s durable record -- the daemon's
     * in-memory copy and, on persist, its row in `sessions.json`.
     *
     * Refuses, WITHOUT touching anything, an unknown id or a session that is [SessionRecord.live]
     * (a run in flight): deleting the record out from under a live run would orphan it with nothing
     * left to reconcile against on the next startup. Throws [IllegalArgumentException] with the
     * reason; the daemon turns that into an `error` frame.
     *
     * Deliberately out of scope, left to their own dedicated commands: the git worktree on disk
     * (`worktree_remove`) and the provider CLI's own conversation store (the CLI's data, not ours).
     */
    fun delete(sessionId: String) { locked {
        val existing = sessions[sessionId] ?: throw IllegalArgumentException("unknown session '$sessionId'")
        // BASICS-KILL-1: a record that claims a run but has none in this daemon and no live process is stale: ended here.
        if (existing.live && isStaleLive(sessionId, existing)) {
            noteKill(sessionId, "stale record cleanup", "the record claimed a run but the daemon has none and its pid is gone")
            markKilled(sessionId, KillOutcome.GRACEFUL)
        }
        if (sessions.getValue(sessionId).live) throw IllegalArgumentException("session '$sessionId' is still running (${existing.state}); kill it first, then remove it")
        host.stop(sessionId)
        val closing = sessions[sessionId]
        sessions.remove(sessionId)
        conversation.drop(sessionId)
        carryPending.remove(sessionId)
        lastPersisted.remove(sessionId)
        instances.delete(sessionId) // TRAITS-T4: the instance goes with its record
        endedNotice(sessionId)
        closed(sessionId, "session removed", closing)
        writeAll()
    } }

    /**
     * BASICS-KILL-1: [record] reads live (STARTING / BUSY / WAITING_INPUT, or a pid marked alive) while this daemon has
     * NO run for it and its pid, if any, is gone. Nothing can bring such a record to rest by itself, so kill and remove
     * treat it as ended instead of refusing it forever.
     */
    fun isStaleLive(sessionId: String, record: SessionRecord = sessions[sessionId] ?: error("unknown session $sessionId")): Boolean =
        record.live && !runs.containsKey(sessionId) && record.childPid.let { it == null || !pidProbe(it) }

    /** BASICS-KILL-1 (rule now the binding policy's, AR-3b): the OTHER busy session a start or resume under [binding] would run next to, or null. */
    fun busyTwin(exceptId: String?, agentId: String?, binding: SessionBinding?): SessionRecord? =
        bindingPolicy.twin(exceptId, agentId, binding, sessions.values)

    private fun markKilled(
        sessionId: String,
        outcome: KillOutcome,
        expectedRun: ActiveRun? = null,
    ): SessionRecord? { return locked {
        if (expectedRun != null) {
            // finish() and kill() may race after the process stops. Only the winner that removes this
            // exact ActiveRun owns the terminal update and Exit event.
            if (!runs.remove(sessionId, expectedRun)) return sessions[sessionId]
        } else {
            runs.remove(sessionId)
        }
        val killed = update(sessionId) {
            it.copy(
                state = SessionState.EXITED,
                killOutcome = outcome,
                lastExitCode = null,
                childPid = null,
                lastHeartbeat = clock(),
                isProcessAlive = false,
            )
        }
        if (killed != null) emit(SessionEvent.Exit(sessionId, code = null, killOutcome = outcome))
        return killed
    } }

    // --- shutdown -------------------------------------------------------------------------------

    /** Graceful shutdown: kill every live tree so a clean stop does not leave orphans behind. */
    fun shutdown() {
        watchdog.shutdownNow()
        for (id in runs.keys) noteKill(id, "daemon shutdown", null)
        for (run in runs.values) {
            run.killRequested.set(true)
            run.forced.set(true)
        }
        // BUG-M2-11b-1: every hosted process, busy or kept, is stopped; a busy turn's end lands as a forced kill.
        host.shutdown()
        for ((id, run) in runs) markKilled(id, KillOutcome.FORCED, expectedRun = run)
    }

    // --- session host ---------------------------------------------------------------------------

    /** BUG-M2-11b-1: what the host reports, applied to the session's record and stream. Never under the host's lock. */
    private val hostEvents = object : HostEvents {
        override fun stdout(sessionId: String, line: String) {
            runs[sessionId]?.sawStdout = true
            val provider = sessions[sessionId]?.let { lookup(it.provider)?.provider }
            heartbeat(sessionId, provider?.sessionIdFromLine(line))
            if (sessionId !in interruptedTurns) conversation.output(sessionId, outputMask.mask(line))
            emit(SessionEvent.Stdout(sessionId, line, afterInterrupt = sessionId in interruptedTurns))
        }

        override fun stderr(sessionId: String, line: String) {
            heartbeat(sessionId, null)
            emit(SessionEvent.Stderr(sessionId, line))
        }

        override fun bootLine(sessionId: String, text: String) = this@SessionManager.bootLine(sessionId, text)

        override fun mask(text: String): String = outputMask.mask(text)

        override fun maskStream(): (String?) -> String = outputMask.stream().let { st -> { chunk -> if (chunk == null) st.flush() else st.chunk(chunk) } }

        override fun activity(sessionId: String, detail: String) {
            val tool = sessions[sessionId]?.provider ?: return
            emit(SessionEvent.Activity(sessionId, tool, detail))
        }

        override fun providerSession(sessionId: String, id: String) = heartbeat(sessionId, id)

        override fun process(sessionId: String, pid: Long?) {
            if (pid == null || runs[sessionId] == null) return
            contain(sessionId, pid)
            update(sessionId) { it.copy(childPid = pid, lastHeartbeat = clock(), isProcessAlive = true) }
        }

        override fun turnEnded(sessionId: String, end: TurnEnd, exitCode: Int?, processExited: Boolean, reason: String?) =
            finishHosted(sessionId, end, exitCode, processExited, reason)

        override fun turnStarted(sessionId: String, reason: String?) = startOwnTurn(sessionId, reason)

        override fun processExited(sessionId: String, exitCode: Int) {
            emit(SessionEvent.ProcessExit(sessionId, exitCode))
        }

        override fun keptProcess(sessionId: String, kept: KeptProcess) {
            contain(sessionId, kept.pid)
            update(sessionId) { it.copy(keptProcess = kept.copy(ownerId = instanceId)) }
        }

        override fun keptProcessEnded(sessionId: String, pid: Long) {
            // CLI-PTY-3: the link of a process that is gone means nothing; a reconnect in progress keeps its mark until the next process reports.
            update(sessionId) {
                if (it.keptProcess?.pid != pid) it
                else if (it.mcp == MCP_RECONNECTING) it.copy(keptProcess = null)
                else it.copy(keptProcess = null, mcp = null, mcpChangedAt = it.mcp?.let { clock() } ?: it.mcpChangedAt)
            }
        }

        // DENIED-LABEL-1: the card's last action and the chat notice for a tool the harness refused (the same text a Denial record gets).
        override fun denied(sessionId: String, tool: String, prompted: Boolean) {
            val mode = toolApprovalFor(sessionId).mode
            SessionActivityLog.event(sessionId, ToolApprovalNotice.blockedAction(tool, mode, prompted))
            stderr(sessionId, "[infantry] " + ToolApprovalNotice.text(get(sessionId)?.provider ?: "harness", tool, mode, prompted))
        }

        override fun mcpState(sessionId: String, state: String, reason: String?) {
            val s = state.trim().lowercase()
            if (sessions[sessionId]?.mcp == s) return
            update(sessionId) { it.copy(mcp = s, mcpChangedAt = clock()) } ?: return
            activity(sessionId, "MCP server $s" + reason?.let { ": $it" }.orEmpty())
        }

        // PROMPT-4: the adapter calls this on a thread of its own; it blocks until the prompt resolves (turn end included).
        override fun requestApproval(sessionId: String, tool: String, input: String): com.superiorsoftllc.infantry.prompts.Decision =
            try {
                kotlinx.coroutines.runBlocking { promptAsker.requestApproval(sessionId, tool, input) }
            } catch (_: InterruptedException) {
                com.superiorsoftllc.infantry.prompts.Decision.DENY
            } catch (_: kotlinx.coroutines.CancellationException) {
                com.superiorsoftllc.infantry.prompts.Decision.DENY
            }
    }

    private val host = SessionHost(
        runnerFor = { p -> lookup(p.id)?.runner ?: cliRunnerForHarnesses },
        lineRunner = lineRunner,
        policy = lifecyclePolicy,
        events = hostEvents,
        killGraceMs = killGraceMs,
        clock = clock,
        idleSweepMs = idleSweepMs,
        processTable = processTable,
        prompts = { promptAsker },
    )

    /**
     * BUG-M2-11f (plan section 8): `session.keepAliveMinutes` changed. Kept processes that are already idle longer than
     * the new value stop now (0 stops them all); a busy session picks the value up at the end of its turn. Asynchronous:
     * a stop waits out a grace, and the caller is a client's request loop.
     */
    fun lifecyclePolicyChanged() = host.policyChanged()

    /** BUG-M2-11f: run the host's idle sweep now, on the caller's thread; the sessions it stopped. For tests. */
    internal fun stopIdleNow(): List<String> = host.stopIdle()

    // --- internals ------------------------------------------------------------------------------

    private val cliRunnerForHarnesses: ProcessRunner = cliRunner
    private val adopted = java.util.concurrent.ConcurrentHashMap<String, Pair<com.superiorsoftllc.infantry.harness.Harness, Configured>>()

    /** HARNESS-PLUGIN-3 H4: a harness module's `harness.aliases` (alias -> id), consulted when no provider or harness has the id itself. */
    @Volatile var harnessAliases: (String) -> String? = { null }

    /**
     * A configured provider, else a registry harness adapted into one (WIRE-4). Both stay valid. HARNESS-PLUGIN-3 H4: the
     * adapted one is only reused while the registry still holds THAT harness, so an unloaded (or reloaded) module's harness
     * is gone (or new) for the next spawn.
     */
    private fun lookup(id: String): Configured? {
        configured[id]?.let { return it }
        val harness = harnesses?.resolve(id) as? com.superiorsoftllc.infantry.harness.CliHarness
        if (harness == null) {
            adopted.remove(id)
            return harnessAliases(id)?.takeIf { it != id && (it in configured || harnesses?.resolve(it) != null) }?.let { lookup(it) }
        }
        adopted[id]?.let { (h, c) -> if (h === harness) return c }
        val p = com.superiorsoftllc.infantry.harness.HarnessProvider(harness)
        val c = Configured(p, p.transport() ?: cliRunnerForHarnesses)
        adopted[id] = harness to c
        return c
    }

    /** The configured provider [id] names; null or blank = the default. Unknown = refused, never substituted. */
    private fun resolveProvider(id: String?): Configured {
        val wanted = id?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            ?: return configured.getValue(provider.id).let { c ->
                // SPAWN-HARNESS-1: the default is "none" while a registry harness (claude on PATH...) can run: use the first one.
                if (c.provider is com.superiorsoftllc.infantry.harness.NoHarnessProvider) {
                    harnesses?.harnessIds()?.firstNotNullOfOrNull { hid -> lookup(hid) } ?: c
                } else c
            }.also {
                // H2: the default is "none" when no harness is installed; say what to do instead of failing in a launch.
                if (it.provider is com.superiorsoftllc.infantry.harness.NoHarnessProvider) {
                    throw IllegalArgumentException(
                        com.superiorsoftllc.infantry.harness.NoHarnessProvider.NOT_INSTALLED +
                            " (checked: the spawn named no provider, the project routing rules and the role/agent default named none, " +
                            "and the harness registry holds ${harnesses?.harnessIds().orEmpty().ifEmpty { listOf("no harness") }.joinToString()} that can run)",
                    )
                }
            }
        return lookup(wanted) ?: throw IllegalArgumentException(
            if (provider is com.superiorsoftllc.infantry.harness.NoHarnessProvider && configured.size == 1) {
                "harness '${wanted.take(80)}' is not installed. ${com.superiorsoftllc.infantry.harness.NoHarnessProvider.NOT_INSTALLED}"
            } else {
                // H2: "not installed" is the truth (the old "not configured" wording stays for callers that match on it).
                "provider '${wanted.take(80)}' is not configured on this daemon: harness '${wanted.take(80)}' is not installed " +
                    "(configured: ${configured.keys.filter { it != com.superiorsoftllc.infantry.harness.NoHarnessProvider.ID }.joinToString()})"
            },
        )
    }

    /** The model a spawn names, trimmed; null or blank = the provider's default. See [modelProblem]. */
    private fun spawnModel(model: String?): String? {
        val wanted = model?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        modelProblem(wanted)?.let { throw IllegalArgumentException("model '${wanted.take(80)}' refused: $it") }
        return wanted
    }

    /**
     * Apply [transform] and persist. State changes are broadcast AND force-written; a newly observed
     * provider session id and the child pid are force-written too (both are crash-durability facts:
     * the id is what resume needs, the pid is what startup reconciliation probes); plain heartbeats
     * are throttled on disk. The write happens AFTER the map assignment so the snapshot includes
     * [next].
     */
    private fun update(sessionId: String, transform: (SessionRecord) -> SessionRecord): SessionRecord? { return locked {
        val current = sessions[sessionId] ?: return null
        // 1.117.0: a session that ended (killed, orphaned) has no job left to measure.
        val next = transform(current).let {
            if (it.progress != null && (it.state == SessionState.EXITED || it.state == SessionState.ORPHANED)) it.copy(progress = null) else it
        }
        if (next == current) return current
        // live is a computed property based on state and isProcessAlive; no need to copy it.
        // But we need to force persist when the computed live value changes.
        val liveChanged = (next.state == SessionState.STARTING ||
            next.state == SessionState.BUSY ||
            next.state == SessionState.WAITING_INPUT ||
            (next.childPid != null && next.isProcessAlive)
        ) != (current.state == SessionState.STARTING ||
            current.state == SessionState.BUSY ||
            current.state == SessionState.WAITING_INPUT ||
            (current.childPid != null && current.isProcessAlive)
        )
        sessions[sessionId] = next
        val stateChanged = next.state != current.state
        val identityChanged = next.providerSessionId != current.providerSessionId
        val pidChanged = next.childPid != current.childPid
        // BUG-M2-11f: the kept process must be on disk before the daemon can die, or the next start cannot reap it.
        val keptChanged = next.keptProcess != current.keptProcess
        // STALL-2: a watched job registered or ended is on disk at once, so a daemon restart keeps the watch (a beat that only
        // moves lastBeat is not: the reload stamps a fresh beat anyway).
        val jobsChanged = next.bgJobs.map { it.id } != current.bgJobs.map { it.id }
        // CHAT-QUEUE-1: the held messages are on disk and on every client at once.
        val queueChanged = next.queued != current.queued
        persist(sessionId, force = stateChanged || identityChanged || pidChanged || liveChanged || keptChanged || jobsChanged || queueChanged)
        // M2-12: a turn is BUSY from its start, so the first output no longer changes the state; the provider session
        // id it carries (what resume and the interrupt snapshot need) must still reach clients as its own status.
        val backgroundChanged = next.backgroundWork != current.backgroundWork || next.bgJobs.map { it.id } != current.bgJobs.map { it.id }
        val progressChanged = next.progress != current.progress
        // CLI-PTY-3: the MCP link is pushed as a status of its own (scripts get `session.mcp` from it).
        val mcpChanged = next.mcp != current.mcp
        if (stateChanged || identityChanged || backgroundChanged || progressChanged || mcpChanged || queueChanged) emit(SessionEvent.Status(next))
        return next
    } }

    private fun persist(sessionId: String, force: Boolean) {
        val now = clock()
        val last = lastPersisted[sessionId] ?: 0L
        if (!force && now - last < HEARTBEAT_PERSIST_MS) return
        lastPersisted[sessionId] = now
        writeAll()
    }

    /**
     * [SessionStore.load] for the start-up read, with an unreadable store answered as no sessions. The daemon still
     * starts, and [JsonSessionStore] refuses to overwrite a store it could not read, so the sessions it holds survive
     * for the next start.
     */
    private fun loadOrNoSessions(): List<SessionRecord> =
        try {
            store.load()
        } catch (_: SessionStoreUnreadableException) {
            emptyList()
        }

    /** True while writes are failing: the failure is reported when it starts, and once more when it recovers. */
    @Volatile private var persistFailing = false

    /**
     * Writes the whole snapshot. A write that keeps failing is reported ONCE, when it starts failing, and once more when
     * it recovers. A refusal that lasts for the life of the process (see [JsonSessionStore.saveAll]) would otherwise
     * print a line per write, every second per session: noise that teaches people to ignore stderr.
     */
    private fun writeAll() {
        runCatching { store.saveAll(sessions.values) }
            .onSuccess {
                if (persistFailing) {
                    persistFailing = false
                    System.err.println("[infantry] session persist recovered: sessions are being written again")
                }
            }
            .onFailure {
                if (!persistFailing) {
                    persistFailing = true
                    System.err.println("[infantry] session persist failed: ${it.message}")
                }
            }
    }

    private companion object {
        const val HEARTBEAT_PERSIST_MS = 1_000L
        const val MCP_CONNECTED = "connected"
        const val MCP_RECONNECTING = "reconnecting"
        const val MCP_DISCONNECTED = "disconnected"
        val DEFAULT_MCP_HEAL_POLL_MS = com.superiorsoftllc.infantry.settings.LimitRegistry.MCP_HEAL_POLL_SECONDS.default * 1000L
        val DEFAULT_UNATTENDED_DEADLINE_MS = com.superiorsoftllc.infantry.settings.LimitRegistry.UNATTENDED_DEADLINE_MINUTES.default * 60_000L
        val DEFAULT_UNATTENDED_STALL_MS = com.superiorsoftllc.infantry.settings.LimitRegistry.STALL_MINUTES.default * 60_000L
        const val DEFAULT_WATCHDOG_POLL_MS = 1_000L
        val DEFAULT_SILENT_MS = com.superiorsoftllc.infantry.settings.LimitRegistry.SILENT_MINUTES.default * 60_000L
        const val DEFAULT_SILENT_POLL_MS = 60_000L
        val DEFAULT_KILL_RETRY_MS = com.superiorsoftllc.infantry.settings.LimitRegistry.KILL_RETRY_MINUTES.default * 60_000L
        const val KILLED_BY_WATCHDOG = "watchdog"
        const val KILLED_BY_CLIENT = "client request"

        const val DEFAULT_BACKGROUND_POLL_MS = 2_000L

        /** A registered bg job whose heartbeat is older than this is dropped (the wrapper beats every 15 s). */
        const val BG_JOB_STALE_MS = 120_000L

        /** The ended states; the same set SpawnGuard and the MCP write tools treat as holding no slot. */
        val ENDED = setOf(SessionState.EXITED, SessionState.FAILED, SessionState.ORPHANED)
    }
}

/** The longest model name a spawn may give (contract 1.3.0); real ids are well under 100 characters. */
internal const val MAX_MODEL_LENGTH = 200

/**
 * Why [model] cannot go onto a provider CLI's command line, or null when it can (contract 1.3.0).
 *
 * A SYNTAX check, not a catalogue: whether a model exists is the CLI's answer, and an unknown but
 * well-formed model fails visibly in its own run. What is refused is what could change the command
 * line itself, or is plainly not a model id: a leading '-' (the CLI would read a flag), a double
 * quote (Windows rebuilds the command line as one string, and a stray quote merges the arguments
 * after it), a control character, whitespace (no model id has any), or an absurd length.
 */
internal fun modelProblem(model: String): String? = when {
    model.length > MAX_MODEL_LENGTH -> "longer than $MAX_MODEL_LENGTH characters"
    model.startsWith("-") -> "starts with '-', which the CLI would read as a flag"
    model.any { it.isWhitespace() || it.isISOControl() || it == '"' } ->
        "contains whitespace, a double quote or a control character"
    else -> null
}

/**
 * ticket-b5aa01bc: a turn refused because [sessionId] already has a run in flight. Nothing was started or echoed, so the
 * caller can offer the same turn again once that run ends. Still an [IllegalStateException], as `check` threw before.
 */
class RunInFlightException(val sessionId: String, message: String) : IllegalStateException(message)

/**
 * LIMITS: how a `limits.concurrency.maxSessions` refusal starts (the UI shows it as it is).
 * COPY-1: neutral, as every deployment shows it; a policy preset's `sessionCapText` replaces it.
 */
const val SESSION_CAP_REFUSED = "Session limit reached"

/** LIMITS: the states of a session working on a turn, counted against `limits.concurrency.maxSessions`. */
private val WORKING = setOf(SessionState.STARTING, SessionState.BUSY, SessionState.WAITING_INPUT)

/** LIMITS: a start refused by `limits.concurrency.maxSessions`; nothing was created or started. */
class SessionCapException(message: String) : IllegalArgumentException(message)
