package com.superiorsoftllc.agenthq.triggers

import com.superiorsoftllc.agenthq.attention.AttentionKind
import com.superiorsoftllc.agenthq.attention.AttentionStore
import com.superiorsoftllc.agenthq.backlog.PromptLaunch
import com.superiorsoftllc.agenthq.backlog.TicketAnswer
import com.superiorsoftllc.agenthq.persistence.TriggerRepository
import com.superiorsoftllc.agenthq.protocol.TicketStatus
import com.superiorsoftllc.agenthq.protocol.TriggerAction
import com.superiorsoftllc.agenthq.protocol.TriggerEventView
import com.superiorsoftllc.agenthq.protocol.TriggerSave
import com.superiorsoftllc.agenthq.protocol.TriggerView
import com.superiorsoftllc.agenthq.session.SessionEvent
import com.superiorsoftllc.agenthq.session.SessionRecord
import com.superiorsoftllc.agenthq.session.SessionState
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** TRIGGER-NUDGE: one `prompt` action fire, as the daemon delivers it. */
data class TriggerNudge(val triggerId: String, val triggerName: String, val projectId: String?, val agentId: String, val prompt: String)

/** TRIGGER-NUDGE: what delivering a [TriggerNudge] did. */
sealed interface PromptDelivery {
    /** Queued as mail [mailId] for a session of the target (delivered when it is at rest). */
    data class Mailed(val mailId: String) : PromptDelivery
    /** No session of the target existed, so one was started with the prompt as its first turn. */
    data class Started(val sessionId: String) : PromptDelivery
    /** No session to mail and none could be started because the target is busy elsewhere. Never a trip. */
    data class Busy(val detail: String) : PromptDelivery
    data class Refused(val detail: String) : PromptDelivery
}

/**
 * Story 9.5 wiring (contract 1.14.0): the daemon's triggers, durable and on the wire.
 *
 * Each trigger gets its own [TriggerGuard] (coalescing window, one run in flight, one follow-up slot,
 * hourly ceiling, trip on any non-success) -- the domain rules exactly as 9.5a/b built them. This class
 * adds what the domain left out: storage of the trigger and of every event's disposition, the action
 * (`start_next`: an UNATTENDED ticket hand-out, so the launch gate applies), run outcomes read off the
 * session stream, attention on a trip, and restart honesty (a trigger running when the daemon stopped
 * comes back TRIPPED, "daemon restarted mid-run", PHASE9-SPEC 5.5). Nothing is ever retried; only
 * [arm] re-arms a tripped trigger.
 *
 * LOCK ORDER, deliberately: session events arrive while the session manager holds its own lock, so this
 * class NEVER calls the board or the session manager while holding its lock. A launch runs on [launches],
 * a dedicated thread, and only takes this lock to wait for the call that released it and to record what
 * happened. A session that ends before its launch is recorded is caught by [exits].
 */
class TriggerService(
    private val repo: TriggerRepository,
    private val attention: AttentionStore,
    private val startNext: (agentFilter: String?) -> TicketAnswer?,
    private val sessions: (String) -> SessionRecord?,
    /** P9.5d: the `prompt` action's launch. The default refuses, so a service built without one cannot pretend to serve it. */
    private val startPrompt: (agentId: String, prompt: String) -> PromptLaunch = { _, _ -> PromptLaunch.Refused("prompt actions are not wired") },
    /**
     * TRIGGER-NUDGE (contract 1.105.0): the `prompt` action's delivery as MAIL into the trigger's project. When set it
     * replaces [startPrompt]: a run is over once the mail is queued (or a session started), so a busy target can never
     * trip the trigger. Null keeps the pre-1.105.0 behaviour (a fresh session per fire, refused when busy).
     */
    private val deliverPrompt: ((TriggerNudge) -> PromptDelivery)? = null,
    /** TRIGGER-NUDGE: true while the mail with this id is still waiting for its recipient (queued, not delivered). */
    private val mailPending: (String) -> Boolean = { false },
    private val clock: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val pumpMs: Long = 1_000,
    /**
     * TRIGGER-NUDGE: false = the pump waits for [start]. The daemon starts it only once the mailbox and the sessions are
     * loaded, so a catch-up fire on boot mails a session that exists into a mailbox that remembers its queue.
     */
    autoStart: Boolean = true,
) {
    /** Pushes a trigger after every change (save, fire, run end, trip, arm, disable). */
    @Volatile
    var eventSink: (TriggerView) -> Unit = {}

    private val guards = HashMap<String, TriggerGuard>()
    private val runTriggers = ConcurrentHashMap<String, String>() // runId -> triggerId, while in flight
    private val runEvents = ConcurrentHashMap<String, List<String>>() // runId -> the event ids it carries
    private val runSessions = ConcurrentHashMap<String, String>() // sessionId -> runId
    private val exits = ConcurrentHashMap<String, TriggerOutcome>() // sessionId -> outcome, ended before mapped
    private val dispositionOverrides = HashMap<String, TriggerEventDisposition>()
    private val intervals = HashMap<String, Long>() // schedule triggerId -> period in ms (persisted in triggers.interval_ms, restored on start)
    private val nextDueAt = HashMap<String, Long>() // schedule triggerId -> when the next event is due
    @Volatile private var closing = false
    private val started = java.util.concurrent.atomic.AtomicBoolean(false) // declared before init, which may call start()

    private val launches = Executors.newSingleThreadExecutor { r -> Thread(r, "agenthq-trigger-launch").also { it.isDaemon = true } }
    private val pump = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "agenthq-trigger-pump").also { it.isDaemon = true } }

    init {
        // H13a: a schedule's period outlives the daemon. Restored first, so the next due time is one interval after
        // this start, the same rule save() applies.
        // TRIGGER-NUDGE: the next due time is persisted too. A slot that fell due while the daemon was down is still
        // in the past, so the pump's first tick fires it ONCE (and the schedule resumes one interval after that); a row
        // stored before 1.105.0 has none and keeps the old rule.
        val startedAt = clock()
        for ((id, interval) in repo.intervals()) {
            intervals[id] = interval
            val row = repo.get(id)
            nextDueAt[id] = row?.nextFireAt ?: (startedAt + interval)
            if (row != null && row.nextFireAt == null) repo.upsert(row.copy(nextFireAt = nextDueAt[id]), interval)
        }
        // PHASE9-SPEC 5.5: a run in flight when the last daemon stopped ended unobserved: that is a failure.
        // TRIGGER-NUDGE: except a prompt-as-mail run, which only ever spans one hand-off to the mailbox: whether or not that
        // mail was queued, the next slot nudges again, so tripping it would silence a recurring nudge for nothing.
        for (t in repo.all().filter { it.state == RUNNING }) {
            if (t.action.kind == "prompt" && deliverPrompt != null) store(t.copy(state = ARMED)) else tripStored(t, "daemon restarted mid-run")
        }
        if (autoStart) start()
    }

    /** Start the pump (schedules and coalescing windows). Idempotent. */
    fun start() {
        if (started.compareAndSet(false, true)) pump.scheduleWithFixedDelay(::pumpWindows, pumpMs, pumpMs, TimeUnit.MILLISECONDS)
    }

    fun list(): List<TriggerView> = repo.all()

    /**
     * Under the lock, unlike [list]: a run settles in two writes inside one locked block -- the trigger row
     * (whose push is a client's cue to re-read the events) and then the event's relabel (`started` ->
     * `nothing_to_start` or `refused_launch_gate`, see [launch]). A lock-free read served between them
     * returns the stale `started`, and no later push corrects it (BUG-BoardUiFlakes: the desktop's Events
     * list stuck on `started`). Taking the lock makes this read wait for the whole block. [list] stays
     * lock-free: 63a8314f writes the trigger row first, so a reader that saw the final disposition already
     * finds it settled.
     */
    @Synchronized
    fun events(triggerId: String): List<TriggerEventView> {
        require(repo.get(triggerId.trim()) != null) { "no trigger '${triggerId.take(80)}'" }
        return repo.events(triggerId.trim())
    }

    fun trippedCount(): Int = repo.all().count { it.state == TRIPPED }

    /**
     * A committed ticket transition to done: fire every ship trigger as source `ship`, without
     * consulting the board. The done event may arrive while the board holds its own lock, so this
     * never calls the board (the class lock order forbids a board call under this lock, not the
     * reverse); each fire takes this lock on its own and the launch runs on [launches].
     */
    fun onTicketDone() {
        for (t in repo.all().filter { it.kind == "ship" }) {
            runCatching { fire(t.id, source = "ship") }
                .onFailure { System.err.println("[agentHq] ship fire for ${t.id} failed: ${it.message}") }
        }
    }

    /** The board's sink for a committed column change: only a move INTO done is a ship event; other columns record nothing. */
    fun onTicketTransition(ticketId: String, status: String) {
        if (status == TicketStatus.DONE) onTicketDone()
    }

    /** P9.6: a repeated webhook delivery: one row per delivery id (replaced, never multiplied), so a replay flood cannot evict real events. */
    @Synchronized
    fun recordDuplicate(triggerId: String, deliveryId: String, digest: String?) {
        require(repo.get(triggerId) != null) { "no trigger '${triggerId.take(80)}'" }
        record("$DUPLICATE_EVENT_PREFIX$triggerId-$deliveryId", triggerId, TriggerEventDisposition.DUPLICATE, "webhook", deliveryId = deliveryId, digest = digest)
    }

    /** Create (no id) or edit a trigger. Unserved kinds and actions are refused, never accepted and ignored. */
    @Synchronized
    fun save(request: TriggerSave): TriggerView {
        val name = request.name.trim()
        require(name.isNotEmpty()) { "name is blank" }
        require(name.length <= 100) { "name is longer than 100 characters" }
        require(request.kind == "manual" || request.kind == "schedule" || request.kind == "ship" || request.kind == "webhook") { "kind '${request.kind.take(40)}' is not served yet (served: manual, schedule, ship, webhook)" }
        validateAction(request.action)
        val projectId = request.projectId?.trim()?.takeIf { it.isNotEmpty() }
        require(projectId == null || request.action.kind == "prompt") { "projectId is only valid on action 'prompt'" }
        require(request.coalesceMs >= 0) { "coalesceMs must be >= 0" }
        require(request.ceilingRuns >= 1) { "ceilingRuns must be >= 1" }
        require(request.ceilingWindowMs >= 1_000) { "ceilingWindowMs must be >= 1000" }
        require(request.intervalMs == null || request.intervalMs!! >= MIN_INTERVAL_MS) {
            "a schedule interval must be at least 1 minute (intervalMs >= $MIN_INTERVAL_MS), got ${request.intervalMs}"
        }
        val existing = request.id?.trim()?.takeIf { it.isNotEmpty() }?.let { id ->
            repo.get(id) ?: throw IllegalArgumentException("no trigger '${id.take(80)}'")
        }
        val secret = request.webhookSecret
        if (request.kind == "webhook") {
            require(secret != null || (existing != null && repo.webhookSecret(existing.id) != null)) { "kind 'webhook' needs a webhookSecret" }
            if (secret != null) validateWebhookSecret(secret)
        } else {
            require(secret == null) { "webhookSecret is only valid on kind 'webhook'" }
        }
        val state = when {
            !request.enabled -> DISABLED
            existing == null || existing.state == DISABLED -> ARMED
            else -> existing.state
        }
        val view = TriggerView(
            id = existing?.id ?: "trg-" + idFactory().take(8),
            name = name,
            kind = request.kind,
            action = request.action.copy(agentId = request.action.agentId?.trim()?.takeIf { it.isNotEmpty() }),
            enabled = request.enabled,
            state = state,
            coalesceMs = request.coalesceMs,
            ceilingRuns = request.ceilingRuns,
            ceilingWindowMs = request.ceilingWindowMs,
            createdAt = existing?.createdAt ?: clock(),
            trippedAt = existing?.trippedAt?.takeIf { state == TRIPPED },
            trippedReason = existing?.trippedReason?.takeIf { state == TRIPPED },
            intervalMs = request.intervalMs.takeIf { request.kind == "schedule" },
            projectId = projectId,
            lastFiredAt = existing?.lastFiredAt,
        )
        // New settings take effect on a fresh guard -- unless a run is in flight, which keeps its own.
        if (runTriggers.values.none { it == view.id }) guards.remove(view.id)
        val interval = request.intervalMs
        if (view.kind == "schedule" && interval != null) {
            // An edit that keeps the period keeps the slot it was counting down to (a rename must not push it back).
            val keep = existing?.takeIf { it.kind == "schedule" && it.intervalMs == interval }?.let { nextDueAt[it.id] }
            intervals[view.id] = interval
            nextDueAt[view.id] = keep ?: (clock() + interval)
        } else {
            intervals.remove(view.id)
            nextDueAt.remove(view.id)
        }
        // A trigger that stops mailing forgets the mail it was waiting on.
        if (view.action.kind != "prompt" && existing != null) repo.setPendingMail(view.id, null)
        // The secret is written before the push, so a client told about a webhook trigger can never reach a
        // hook that has no secret yet; it is dropped when the trigger stops being a webhook.
        if (view.kind == "webhook") secret?.let { repo.setWebhookSecret(view.id, it) } else repo.deleteWebhookSecret(view.id)
        return store(view.copy(nextFireAt = nextDueAt[view.id]))
    }

    /**
     * P9.6: the HMAC secret of a served webhook trigger, or null when there is no such trigger, it is not a
     * webhook, or it has no secret. One answer for all three, so a caller cannot tell them apart.
     */
    fun webhookSecret(triggerId: String): String? {
        val t = repo.get(triggerId) ?: return null
        return if (t.kind == "webhook") repo.webhookSecret(t.id) else null
    }

    /** One manual event: folded, started, refused or suppressed -- and always recorded. */
    @Synchronized
    fun fire(triggerId: String, source: String = "manual", deliveryId: String? = null, digest: String? = null): TriggerView {
        val t = repo.get(triggerId.trim()) ?: throw IllegalArgumentException("no trigger '${triggerId.take(80)}'")
        val eventId = idFactory()
        if (!t.enabled || t.state == DISABLED) return t.also { record(eventId, t.id, TriggerEventDisposition.SUPPRESSED_DISABLED, source, deliveryId = deliveryId, digest = digest) }
        if (t.state == TRIPPED) return t.also { record(eventId, t.id, TriggerEventDisposition.SUPPRESSED_TRIPPED, source, deliveryId = deliveryId, digest = digest) }
        // TRIGGER-NUDGE: at most one undelivered prompt mail per trigger. While it waits, a new event adds nothing.
        if (t.action.kind == "prompt" && deliverPrompt != null) {
            val pending = repo.pendingMail(t.id)
            if (pending != null) {
                if (mailPending(pending)) return t.also { record(eventId, t.id, TriggerEventDisposition.COALESCED_PENDING, source, deliveryId = deliveryId, digest = digest) }
                repo.setPendingMail(t.id, null)
            }
        }
        val result = guardFor(t).fire(eventId, t.id)
        val disposition = dispositionOverrides.remove(eventId) ?: when (result) {
            TriggerFireResult.STARTED -> TriggerEventDisposition.STARTED
            TriggerFireResult.FOLDED -> TriggerEventDisposition.FOLDED
            TriggerFireResult.FOLLOWUP_FOLDED -> TriggerEventDisposition.FOLLOWUP_FOLDED
            TriggerFireResult.REFUSED_CEILING -> TriggerEventDisposition.REFUSED_CEILING
            TriggerFireResult.SUPPRESSED_TRIPPED -> TriggerEventDisposition.SUPPRESSED_TRIPPED
            TriggerFireResult.SUPPRESSED_DISABLED -> TriggerEventDisposition.SUPPRESSED_DISABLED
        }
        record(eventId, t.id, disposition, source, deliveryId = deliveryId, digest = digest)
        if (result == TriggerFireResult.STARTED || result == TriggerFireResult.FOLDED || result == TriggerFireResult.FOLLOWUP_FOLDED) {
            repo.get(t.id)?.let { store(it.copy(lastFiredAt = clock())) }
        }
        return sync(t.id)
    }

    /**
     * Re-arm a TRIPPED trigger: the explicit, separate act. Acknowledging its attention item does not do it. Since
     * 1.105.0 it also ENABLES a DISABLED trigger (the UI's enable toggle); an armed or running one is refused.
     */
    @Synchronized
    fun arm(triggerId: String): TriggerView {
        val t = repo.get(triggerId.trim()) ?: throw IllegalArgumentException("no trigger '${triggerId.take(80)}'")
        require(t.state == TRIPPED || t.state == DISABLED) { "trigger '${t.id}' is ${t.state}, not tripped or disabled" }
        guards.remove(t.id)
        return store(t.copy(enabled = true, state = ARMED, trippedAt = null, trippedReason = null))
    }

    @Synchronized
    fun disable(triggerId: String): TriggerView {
        val t = repo.get(triggerId.trim()) ?: throw IllegalArgumentException("no trigger '${triggerId.take(80)}'")
        return store(t.copy(enabled = false, state = DISABLED))
    }

    @Synchronized
    fun delete(triggerId: String) {
        require(repo.delete(triggerId.trim())) { "no trigger '${triggerId.take(80)}'" }
        guards.remove(triggerId.trim())
        intervals.remove(triggerId.trim())
        nextDueAt.remove(triggerId.trim())
    }

    /** A daemon arm: a trigger's run ends when its session's run does. */
    fun onSessionEvent(event: SessionEvent) {
        if (closing) return
        val (sessionId, outcome) = when (event) {
            is SessionEvent.Exit -> event.sessionId to outcomeOf(event.sessionId, event.killOutcome != null)
            is SessionEvent.Status -> if (event.session.state == SessionState.FAILED && event.session.runCount == 0) {
                event.session.id to TriggerOutcome.START_REFUSED
            } else return
            else -> return
        }
        synchronized(this) {
            if (closing) return
            val runId = runSessions.remove(sessionId)
            if (runId == null) {
                if (exits.size > 1_000) exits.clear()
                exits[sessionId] = outcome
            } else {
                complete(runId, outcome)
            }
        }
    }

    /** Stop observing: a graceful shutdown kills runs, and that must read as "restarted mid-run" next boot. */
    fun close() {
        closing = true
        pump.shutdownNow()
        launches.shutdownNow()
    }

    // --- internals ---------------------------------------------------------------------------------

    private fun guardFor(t: TriggerView): TriggerGuard = guards.getOrPut(t.id) {
        TriggerGuard(
            clock = TriggerClock(clock),
            launch = { run ->
                runTriggers[run.runId] = run.triggerId
                runEvents[run.runId] = listOf(run.events.firstEventId, run.events.lastEventId).distinct()
                launches.execute { launch(t.id, run) }
            },
            onTrip = { trip -> tripped(trip) },
            coalesceMs = t.coalesceMs,
            ceilingRuns = t.ceilingRuns,
            ceilingWindowMs = t.ceilingWindowMs,
        )
    }

    /**
     * Served actions, and what each one requires. Anything else is refused by name, and a field that belongs to
     * another action is refused too (a `prompt` on `start_next` would be silently dropped otherwise).
     */
    private fun validateAction(action: TriggerAction) {
        when (action.kind) {
            "start_next" -> require(action.prompt == null) { "action 'start_next' takes no prompt (use action 'prompt')" }
            "prompt" -> {
                require(!action.agentId.isNullOrBlank()) { "action 'prompt' needs an agentId (a profile id or role:<role>)" }
                val prompt = action.prompt
                require(!prompt.isNullOrBlank()) { "action 'prompt' needs a non-blank prompt" }
                require(prompt.length <= TriggerAction.MAX_PROMPT_CHARS) {
                    "action 'prompt' text is longer than ${TriggerAction.MAX_PROMPT_CHARS} characters"
                }
            }
            else -> throw IllegalArgumentException("action '${action.kind.take(40)}' is not served yet (served: start_next, prompt)")
        }
    }

    /** On [launches]: run the `prompt` action with NO lock held, then record what happened under the lock. */
    private fun launchPrompt(trigger: TriggerView, run: TriggerRun) {
        val launch: PromptLaunch = try {
            startPrompt(trigger.action.agentId.orEmpty(), trigger.action.prompt.orEmpty())
        } catch (e: Exception) {
            System.err.println("[agentHq] trigger ${trigger.id} prompt start failed: ${e.message}")
            PromptLaunch.Refused("prompt start failed: ${e.message}")
        }
        synchronized(this) {
            if (closing) return // same rule as the start_next branch: an interrupted start stays RUNNING for the next boot to claim
            when (launch) {
                is PromptLaunch.Started -> {
                    val ended = exits.remove(launch.sessionId)
                    if (ended != null) complete(run.runId, ended) else runSessions[launch.sessionId] = run.runId
                }
                is PromptLaunch.Refused -> finishRefused(run, launch.detail)
            }
        }
    }

    /**
     * TRIGGER-NUDGE, on [launches]: deliver the prompt with NO lock held, then settle the run under the lock. The run
     * is over as soon as the mail is queued or the session started; only a refusal (no such role, a mail limit, ...)
     * trips, never a busy target. Only a STARTED session counts toward the hourly ceiling: a mailed (or deferred) fire
     * refunds its start, so any interval -- one minute included -- runs all hour without tripping.
     */
    private fun launchNudge(trigger: TriggerView, run: TriggerRun, deliver: (TriggerNudge) -> PromptDelivery) {
        val delivery: PromptDelivery = try {
            deliver(TriggerNudge(trigger.id, trigger.name, trigger.projectId, trigger.action.agentId.orEmpty(), trigger.action.prompt.orEmpty()))
        } catch (e: Exception) {
            System.err.println("[agentHq] trigger ${trigger.id} prompt delivery failed: ${e.message}")
            PromptDelivery.Refused("prompt delivery failed: ${e.message}")
        }
        synchronized(this) {
            if (closing) return
            when (delivery) {
                is PromptDelivery.Mailed -> {
                    repo.setPendingMail(trigger.id, delivery.mailId)
                    // Mail started no session: it does not count toward the starts-per-hour ceiling.
                    guards[trigger.id]?.refundStart(run.runId)
                    complete(run.runId, TriggerOutcome.SUCCEEDED)
                    redispose(run, TriggerEventDisposition.MAILED)
                }
                is PromptDelivery.Started -> complete(run.runId, TriggerOutcome.SUCCEEDED)
                is PromptDelivery.Busy -> {
                    guards[trigger.id]?.refundStart(run.runId) // nothing was started either
                    complete(run.runId, TriggerOutcome.SUCCEEDED)
                    redispose(run, TriggerEventDisposition.DEFERRED_BUSY)
                }
                is PromptDelivery.Refused -> finishRefused(run, delivery.detail)
            }
        }
    }

    /** On [launches]: run the action with NO lock held, then record what happened under the lock. */
    private fun launch(triggerId: String, run: TriggerRun) {
        // BUG-TriggerDaemonFlake: first wait for the call that released this run (fire, the pump, or a
        // completion starting the follow-up) to leave the lock. The guard hands the run over BEFORE that call
        // commits the trigger's own rows (the event, RUNNING), and the hand-out below runs the board's
        // read-then-write transaction: a trigger commit landing between its read and its write aborts it with
        // SQLITE_BUSY_SNAPSHOT (busy_timeout does not apply), after the session was already spawned. Taking
        // and dropping the lock orders the hand-out after those commits; the action still runs with NO lock.
        synchronized(this) { if (closing) return }
        val trigger = repo.get(triggerId) ?: return
        if (trigger.action.kind == "prompt") {
            val deliver = deliverPrompt ?: return launchPrompt(trigger, run)
            return launchNudge(trigger, run, deliver)
        }
        val answer: TicketAnswer? = try {
            startNext(trigger.action.agentId)
        } catch (e: Exception) {
            System.err.println("[agentHq] trigger $triggerId start_next failed: ${e.message}")
            synchronized(this) {
                if (closing) return
                finishRefused(run, "start_next failed: ${e.message}")
            }
            return
        }
        synchronized(this) {
            // PHASE9-SPEC 5.5: a start still in flight when the daemon stopped was INTERRUPTED by the
            // shutdown, not refused. Recording START_REFUSED here would overwrite the RUNNING row, and the
            // next boot's init (state == RUNNING) would then miss it -- the run would never come back
            // TRIPPED "daemon restarted mid-run". Leave it RUNNING for the next boot to claim.
            if (closing) return
            when {
                answer == null -> {
                    // 63a8314f: settle the run FIRST, label its event after. `list()` reads the repository
                    // without taking this lock, so the two writes are not atomic to it: with the
                    // disposition written first, a client that waits for `nothing_to_start` and then reads
                    // the trigger (TriggerDaemonTest's own protocol) can be served between them and see the
                    // pre-transition `running` left by fire(). Settling the state first closes that window;
                    // the disposition is the audit row and is only readable once the state it announces is
                    // durable. `events()` takes this lock, so a client that re-reads them on the `armed`
                    // push waits for the relabel below instead of getting `started` (BUG-BoardUiFlakes).
                    complete(run.runId, TriggerOutcome.SUCCEEDED)
                    redispose(run, TriggerEventDisposition.NOTHING_TO_START)
                }
                answer.ticket.start?.reason == "started" && answer.ticket.sessionId != null -> {
                    val sessionId = answer.ticket.sessionId!!
                    val ended = exits.remove(sessionId)
                    if (ended != null) complete(run.runId, ended) else runSessions[sessionId] = run.runId
                }
                else -> finishRefused(run, answer.ticket.start?.detail ?: answer.ticket.start?.reason ?: "start refused")
            }
        }
    }

    private fun finishRefused(run: TriggerRun, detail: String) {
        // 63a8314f: same rule as the nothing_to_start branch -- the row settles (here, to TRIPPED via
        // complete) before the disposition that announces the refusal becomes readable.
        val refusedByLaunchGate = detail.contains("launch gate")
        complete(run.runId, TriggerOutcome.START_REFUSED)
        if (refusedByLaunchGate) redispose(run, TriggerEventDisposition.REFUSED_LAUNCH_GATE)
    }

    /** Must hold the lock. Feeds the outcome to the guard (which may trip), persists dropped follow-ups. */
    private fun complete(runId: String, outcome: TriggerOutcome) {
        val triggerId = runTriggers.remove(runId) ?: return
        runEvents.remove(runId)
        val guard = guards[triggerId] ?: return
        guard.complete(runId, outcome)
        guard.dispositions.filter { it.triggerId == triggerId && it.disposition == TriggerEventDisposition.DROPPED_AFTER_FAILURE }
            .forEach {
                // Keep what the row already says about where it came from: a dropped webhook event is still that delivery.
                val prior = repo.events(triggerId).firstOrNull { e -> e.eventId == it.eventId }
                record(it.eventId, triggerId, TriggerEventDisposition.DROPPED_AFTER_FAILURE, prior?.source ?: "manual", at = it.receivedAt, deliveryId = prior?.deliveryId, digest = prior?.digest)
            }
        sync(triggerId)
    }

    /** Must hold the lock: called by the guard inside fire/complete/pump. */
    private fun tripped(trip: TriggerTrip) {
        val t = repo.get(trip.triggerId) ?: return
        store(t.copy(state = TRIPPED, trippedAt = clock(), trippedReason = trip.detail))
        runCatching { trip.reportTo(attention) }.onFailure { System.err.println("[agentHq] trip attention failed: ${it.message}") }
    }

    private fun tripStored(t: TriggerView, reason: String) {
        store(t.copy(state = TRIPPED, trippedAt = clock(), trippedReason = reason))
        runCatching {
            attention.raise(kind = AttentionKind.TRIGGER_TRIPPED, triggerId = t.id, outcome = "INTERRUPTED", detail = "Trigger '${t.name}' tripped: $reason; nothing was retried.")
        }
    }

    /** Re-label the events a run carries (first and last of its window) with what actually happened. */
    private fun redispose(run: TriggerRun, disposition: TriggerEventDisposition) {
        for (eventId in listOf(run.events.firstEventId, run.events.lastEventId).distinct()) {
            val existing = repo.events(run.triggerId).firstOrNull { it.eventId == eventId }
            if (existing == null) dispositionOverrides[eventId] = disposition // fire() records it in a moment
            else record(eventId, run.triggerId, disposition, existing.source, at = existing.receivedAt, deliveryId = existing.deliveryId, digest = existing.digest)
        }
    }

    /** The stored state follows the guard: tripped wins, then a run in flight, else armed. */
    private fun sync(triggerId: String): TriggerView {
        val t = repo.get(triggerId) ?: throw IllegalArgumentException("no trigger '$triggerId'")
        if (t.state == TRIPPED || t.state == DISABLED) return t
        val running = runTriggers.values.any { it == triggerId }
        val next = if (running) RUNNING else ARMED
        return if (next == t.state) t else store(t.copy(state = next))
    }

    internal fun pumpWindows() {
        if (closing) return
        runCatching {
            synchronized(this) {
                fireDueSchedules()
                for ((id, guard) in guards.toMap()) if (guard.pump()) sync(id)
            }
        }.onFailure { System.err.println("[agentHq] trigger pump failed: ${it.message}") }
    }

    /**
     * Must hold the lock. Each schedule whose interval has elapsed fires once, as source `schedule`. The next
     * due time is one interval after NOW, not after the last due time: a stalled pump or a long downtime folds
     * into a single event instead of a burst of catch-up events.
     */
    private fun fireDueSchedules() {
        val now = clock()
        for ((id, due) in nextDueAt.toMap()) {
            if (now < due) continue
            val interval = intervals[id] ?: continue
            nextDueAt[id] = now + interval
            repo.get(id)?.let { store(it.copy(nextFireAt = now + interval)) }
            runCatching { fire(id, source = "schedule") }
                .onFailure { System.err.println("[agentHq] schedule fire for $id failed: ${it.message}") }
        }
    }

    private fun outcomeOf(sessionId: String, killed: Boolean): TriggerOutcome {
        val record = sessions(sessionId)
        return when {
            killed -> TriggerOutcome.KILLED
            record?.state == SessionState.IDLE && record.lastExitCode == 0 -> TriggerOutcome.SUCCEEDED
            else -> TriggerOutcome.FAILED
        }
    }

    private fun store(view: TriggerView): TriggerView {
        repo.upsert(view, intervals[view.id])
        runCatching { eventSink(view) }
        return view
    }

    private fun validateWebhookSecret(secret: String) {
        require(secret.length in MIN_WEBHOOK_SECRET..MAX_WEBHOOK_SECRET) { "webhookSecret must be $MIN_WEBHOOK_SECRET..$MAX_WEBHOOK_SECRET characters" }
        require(secret.none { it.isWhitespace() || it.isISOControl() }) { "webhookSecret must not contain whitespace or control characters" }
    }

    private fun record(
        eventId: String, triggerId: String, disposition: TriggerEventDisposition, source: String,
        at: Long = clock(), deliveryId: String? = null, digest: String? = null,
    ) {
        repo.insertEvent(TriggerEventView(eventId, triggerId, at, source, disposition.name.lowercase(), deliveryId, digest))
    }

    companion object {
        /** Event-id prefix of the single `duplicate` row kept per webhook delivery id. */
        const val DUPLICATE_EVENT_PREFIX = "dup-"

        /** Floor for a schedule trigger's period: one minute. Rows saved before the floor keep their stored period. */
        const val MIN_INTERVAL_MS = 60_000L
        private const val MIN_WEBHOOK_SECRET = 32
        private const val MAX_WEBHOOK_SECRET = 256
        private const val ARMED = "armed"
        private const val RUNNING = "running"
        private const val TRIPPED = "tripped"
        private const val DISABLED = "disabled"
    }
}
