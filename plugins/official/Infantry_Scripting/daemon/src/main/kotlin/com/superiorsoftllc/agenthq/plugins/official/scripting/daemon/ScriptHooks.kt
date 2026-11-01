package com.superiorsoftllc.agenthq.plugins.official.scripting.daemon

import com.superiorsoftllc.agenthq.daemon.api.Caller
import com.superiorsoftllc.agenthq.daemon.api.AutomationMark
import com.superiorsoftllc.agenthq.daemon.api.IncomingMail
import com.superiorsoftllc.agenthq.daemon.api.MailVerdict
import com.superiorsoftllc.agenthq.daemon.api.NamedEvent
import com.superiorsoftllc.agenthq.daemon.api.ToolInvoker
import com.superiorsoftllc.agenthq.daemon.api.Tools
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** SCRIPT-5 loop-guard limits; all are plugin settings (`scripting.hooks.*`), read at each event. */
data class HookGuardConfig(
    /** An event this many hops from the root event (a hook's action caused it, which caused it...) is refused. */
    val maxDepth: Int = 3,
    /** A hook fires at most this many times per [windowMs]; the next is refused (a trip) until the window clears. */
    val maxRuns: Int = 10,
    val windowMs: Long = 60_000,
    /** An event on an id a hook run acted on, arriving during the run or within this long after it, counts as that run's doing. */
    val attributionMs: Long = 5_000,
)

/**
 * SCRIPT-5: runs project hooks (see [HookStore]) when events happen, daemon-side: no model, no tokens.
 *
 * One subscription to the SCRIPT-3 bus. A hook fires when its `on` matches: a named event, or `lane enter <lane>` / `lane exit <lane>` (the daemon's
 * lane-change event `ticket.moved` whose `to` / `from` is that lane). The script gets the hook's own `args` overlaid with the event payload,
 * a ticket event enriched with the ticket's project, branch, tip sha and worker session, as `input.args` (plus `args.event`).
 * It runs as caller `script:<hook script>` with the identity of whoever added the hook, the SCRIPT-4 rules.
 *
 * LOOP GUARD. The bus carries no cause for events a tool call raises, so a run is attributed by what it touched: the ids its actions
 * name (and its triggering ticket). An event on one of those ids during the run or [HookGuardConfig.attributionMs] after is that
 * run's child (depth+1). Refused past [HookGuardConfig.maxDepth]; and a hook that fires more than [HookGuardConfig.maxRuns] times in
 * the window is refused. A refusal is logged AND reported (once per hook and window), like any failure.
 */
class ScriptHooks(
    private val store: HookStore,
    private val scripts: ScriptStore,
    private val engines: ScriptEngines,
    private val tools: Tools,
    private val dispatcher: ScriptDispatcher,
    private val projects: () -> List<String>,
    private val limits: () -> ScriptLimits,
    private val guard: () -> HookGuardConfig = { HookGuardConfig() },
    private val enabled: () -> Boolean = { true },
    /** (project, branch) -> the branch's tip sha, or null. */
    private val tip: (String, String) -> String? = { _, _ -> null },
    private val log: (String) -> Unit = {},
    /** SCRIPT-VIS-1: every run is recorded here. */
    private val runLog: ScriptRunLog = ScriptRunLog(),
    /** SCRIPT-VIS-1b: marks a session as worked on by automation while one of its hooks runs (null clears). Display only; a throw is ignored. */
    private val automation: (String, AutomationMark?) -> Unit = { _, _ -> },
    private val clock: () -> Long = System::currentTimeMillis,
    /** Where runs execute. Production: one daemon thread (events run in order); tests run inline. */
    private val executor: (() -> Unit) -> Unit = DEFAULT_EXECUTOR,
) {
    private class Run(val hookKey: String, val depth: Int, val touched: MutableSet<String>, startedAt: Long = 0L) { @Volatile var endedAt: Long? = null; @Volatile var touchedAt: Long = startedAt }

    private val runs = java.util.Collections.synchronizedList(ArrayList<Run>())
    private val fires = ConcurrentHashMap<String, ArrayDeque<Long>>()
    private val reported = ConcurrentHashMap<String, Long>()
    private val ignoredMail = ConcurrentHashMap<String, Boolean>()
    private val unreadable = ConcurrentHashMap<String, Boolean>()

    /**
     * CONN-1: returns at once. The bus delivers on the daemon's ONE shared observer thread (session hooks, every other subscriber), so
     * nothing here may block on a tool call (the ticket read goes to the Board plugin, whose guard allows 2 s), a file read or a script:
     * all of it runs on [executor]. The executor is one thread, so events still run in publish order.
     */
    fun onEvent(e: NamedEvent) {
        if (!enabled()) return
        if (!firstDelivery(e)) { log("hooks: event ${e.name} ${e.id} already handled, not re-run"); return }
        executor { runCatching { dispatch(e) }.onFailure { log("hooks: event ${e.name} failed: ${it.javaClass.simpleName}: ${it.message?.take(200)}") } }
    }

    /**
     * MAIL-HOOK-1: the pre-delivery event `mail.incoming`, run SYNCHRONOUSLY on the caller (the mail send, before any wake) for every
     * enabled hook on exactly that name in the recipient's project. The script gets the hook args overlaid with id, from, to, sessionId,
     * role, subject, body, threadId. The first run that queues `ih.handled()` returns handled; one that queues `ih.deliver_text{text=..}`
     * returns that text. Any failure, refusal or other result is null = deliver as today (the host's call timeout bounds this).
     */
    fun incoming(mail: IncomingMail): MailVerdict? {
        if (!enabled()) return null
        val payload = buildJsonObject {
            put("id", mail.id); put("from", mail.from); put("to", mail.to); put("subject", mail.subject); put("body", mail.body)
            mail.recipientSessionId?.let { put("sessionId", it) }; mail.recipientRole?.let { put("role", it) }
            mail.threadId?.let { put("threadId", it) }; mail.projectId?.let { put("projectId", it) }
        }
        val e = NamedEvent(INCOMING_EVENT, payload.toString(), "daemon", "mail-incoming:${mail.id}")
        for (p in projects().filter { mail.projectId == null || it == mail.projectId }) {
            val hooks = runCatching { store.list(p) }.getOrDefault(emptyList())
            for (hook in hooks) {
                if (!hook.enabled || hook.on !is HookOn.Named || (hook.on as HookOn.Named).pattern != INCOMING_EVENT) continue
                // MAIL-HOOK-1b: a mail hook is attached to exactly one session; a stored one without `to` is ignored (one log line).
                if (hook.to == null) { if (ignoredMail.putIfAbsent("$p/${hook.id}", true) == null) log("hooks: mail.incoming hook ${hook.id} ($p) has no session (to), ignored; re-add it with to=<session id>"); continue }
                if (hook.to != mail.recipientSessionId) continue
                if (admit(p, hook, 0) != null) continue
                val run = Run("$p/${hook.id}", 0, ConcurrentHashMap.newKeySet())
                val v = runCatching { executeIncoming(p, hook, e, payload, run) }.getOrNull()
                if (v != null) return v
            }
        }
        return null
    }

    private fun executeIncoming(project: String, hook: Hook, e: NamedEvent, payload: JsonObject, run: Run): MailVerdict? {
        try {
            val script = scripts.read(project, hook.script) ?: return failQuiet(project, hook, "no script '${hook.script}' in .agenthq/scripts/").let { null }
            val engine = engines.byExtension(script.extension) ?: return failQuiet(project, hook, "no engine loaded for .${script.extension}").let { null }
            val who = ScriptCaller(hook.ownerSession.orEmpty(), hook.ownerRole.orEmpty())
            hook.to?.let { mark(it, hook) }
            val report = dispatcher.run(engine, script.source, scriptArgs(project, hook, e, payload, null, 0), limits(), who, invokerOf(project, hook), false, progressTo = hook.to)
            val from = (payload["from"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val subj = (payload["subject"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: "mail"
            val trig = "${hook.id} ${e.name}"
            val mailId = (payload["id"] as? JsonPrimitive)?.contentOrNull
            if (!report.ok) { runLog.record(project, hook.script, trig, hook.to, report, "failed", "failed on $subj from $from", report.error, ref = mailId); failQuiet(project, hook, report.render(hook.script)); return null }
            if (report.handled) { runLog.record(project, hook.script, trig, hook.to, report, "handled", "handled $subj from $from", ref = mailId); return MailVerdict(handled = true) }
            val text = report.deliverText
            if (text != null) { runLog.record(project, hook.script, trig, hook.to, report, "delivered", "delivered rewritten $subj from $from", ref = mailId); return MailVerdict(text = text) }
            runLog.record(project, hook.script, trig, hook.to, report, "passed", "passed $subj from $from through", ref = mailId)
            return null
        } catch (t: Throwable) {
            runLog.record(project, hook.script, "${hook.id} ${e.name}", hook.to, null, "failed", "crashed", "${t.javaClass.simpleName}: ${t.message?.take(300)}", ref = (payload["id"] as? JsonPrimitive)?.contentOrNull)
            failQuiet(project, hook, "script crashed: ${t.javaClass.simpleName}: ${t.message?.take(300)}"); return null
        } finally { hook.to?.let { unmark(it) }; run.endedAt = clock() }
    }

    /** HOOK-DUP-1: idempotency. An event id, or the same ticket transition (ticket, seq, from, to) re-published under a new id by a retried delivery, runs hooks once. */
    private val seenKeys = object : LinkedHashMap<String, Boolean>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > 2048
    }

    private fun firstDelivery(e: NamedEvent): Boolean {
        val keys = ArrayList<String>()
        if (e.id.isNotBlank()) keys += "id:${e.id}"
        if (e.name == "ticket.moved") {
            val o = parse(e.payload)
            val seq = str(o, "seq")
            if (seq != null) keys += "moved:${str(o, "ticketId")}:$seq:${str(o, "from")}:${str(o, "to")}"
        }
        synchronized(seenKeys) {
            if (keys.any { it in seenKeys }) return false
            keys.forEach { seenKeys[it] = true }
        }
        return true
    }

    private fun dispatch(e: NamedEvent) {
        val payload = parse(e.payload)
        val candidates = projects().flatMap { p ->
            // A project whose hooks cannot be read (no directory, bad file) is skipped with one warning; it never fails the plugin.
            val hooks = try { store.list(p) } catch (x: Exception) {
                if (unreadable.putIfAbsent(p, true) == null) log("hooks: project $p skipped, its hooks cannot be read: ${x.message}")
                emptyList()
            }
            hooks.filter { it.enabled && matches(it.on, e.name, payload) }.map { p to it }
        }
        if (candidates.isEmpty()) return
        val ticketId = str(payload, "ticketId")
        val ticket = ticketId?.let { readTicket(it) }
        val project = str(payload, "projectId") ?: ticket?.let { str(it, "projectId") }
        val mine = if (project != null) candidates.filter { it.first == project } else candidates
        if (mine.isEmpty()) return
        val ids = listOfNotNull(ticketId, str(payload, "sessionId"))
        val depth = maxOf(e.depth, attributedDepth(ids))
        for ((p, hook) in mine) {
            val tickKey = if (e.name == HookOn.EXEC_TICK || e.name == TIMER_EVENT) "$p/${hook.id}" else null
            if (tickKey != null && inflight.putIfAbsent(tickKey, true) != null) { slowOnce(tickKey, hook); continue }
            val refusal = admit(p, hook, depth)
            if (refusal != null) {
                tickKey?.let { inflight.remove(it) }
                log("hook ${hook.id} ($p) refused on ${e.name}: $refusal")
                reportOnce(p, hook, ticketId, "refused", "Hook '${hook.id}' (on ${hook.on}) did not run for ${e.name}: $refusal. Script '${hook.script}' was NOT run.")
                continue
            }
            val args = scriptArgs(p, hook, e, payload, ticket, depth)
            val run = Run("$p/${hook.id}", depth, ConcurrentHashMap.newKeySet<String>().also { it.addAll(ids) }, clock())
            runs += run
            try { execute(p, hook, e, ticketId, args, run) } finally { tickKey?.let { inflight.remove(it) } }
        }
    }

    private val inflight = ConcurrentHashMap<String, Boolean>()
    private val slowSeen = ConcurrentHashMap<String, Boolean>()
    private fun slowOnce(key: String, hook: Hook) { if (slowSeen.putIfAbsent(key, true) == null) log("hook ${hook.id} ($key): a tick arrived while its previous run was still going; skipped (never stacked)") }

    private val lastTimer = ConcurrentHashMap<String, Long>()
    private val tickCount = ConcurrentHashMap<String, Long>()

    /**
     * SCRIPT-TICK-1: call about once a second. Fires every enabled `every <N>s` hook whose period (never below [minEverySec]) has passed,
     * as the event [TIMER_EVENT] {tick, at, hookId}, through the same guard, run log and failure reporting as any hook. A hook whose
     * previous run is still going is skipped, not stacked. A hook with `to` for a session that no longer exists is not run here
     * (it is removed with its session).
     */
    fun timerTick(minEverySec: Long) {
        if (!enabled()) return
        val now = clock()
        for (p in projects()) {
            val hooks = try { store.list(p) } catch (x: Exception) { continue }
            for (h in hooks) {
                val on = h.on as? HookOn.Every ?: continue
                if (!h.enabled) continue
                val key = "$p/${h.id}"
                val period = maxOf(on.seconds, minEverySec) * 1000
                val last = lastTimer.putIfAbsent(key, now) ?: continue
                if (now - last < period) continue
                lastTimer[key] = now
                val n = tickCount.merge(key, 1L) { a, b -> a + b }!!
                val payload = buildJsonObject { put("tick", n); put("at", now); put("hookId", h.id); put("projectId", p) }
                onEventNoDedup(NamedEvent(TIMER_EVENT, payload.toString(), "timer", "timer:$key:$n"))
            }
        }
    }

    private fun onEventNoDedup(e: NamedEvent) {
        executor { runCatching { dispatchTimer(e) }.onFailure { log("hooks: timer ${e.id} failed: ${it.javaClass.simpleName}: ${it.message?.take(200)}") } }
    }

    private fun dispatchTimer(e: NamedEvent) {
        val payload = parse(e.payload)
        val p = str(payload, "projectId") ?: return
        val hook = runCatching { store.list(p) }.getOrDefault(emptyList()).firstOrNull { it.id == str(payload, "hookId") && it.enabled && it.on is HookOn.Every } ?: return
        val key = "$p/${hook.id}"
        if (inflight.putIfAbsent(key, true) != null) { slowOnce(key, hook); return }
        try {
            val refusal = admit(p, hook, 0)
            if (refusal != null) { log("hook ${hook.id} ($p) refused on timer: $refusal"); reportOnce(p, hook, null, "refused", "Hook '${hook.id}' (on ${hook.on}) did not run: $refusal. Script '${hook.script}' was NOT run."); return }
            val run = Run(key, 0, ConcurrentHashMap.newKeySet())
            runs += run
            execute(p, hook, e, null, scriptArgs(p, hook, e, payload, null, 0), run)
        } finally { inflight.remove(key) }
    }

    /** Null to run; else why the guard refused. Counts the run against the hook's rate window when admitted. */
    private fun admit(project: String, hook: Hook, depth: Int): String? {
        val g = guard()
        if (depth > g.maxDepth) return "loop guard: event chain depth $depth exceeds the limit ${g.maxDepth} (its own actions keep re-triggering it)"
        val now = clock()
        val q = fires.computeIfAbsent("$project/${hook.id}") { ArrayDeque() }
        synchronized(q) {
            while (q.isNotEmpty() && now - q.first() >= g.windowMs) q.removeFirst()
            if (q.size >= g.maxRuns) return "rate limit: ${q.size} runs in the last ${g.windowMs / 1000}s (limit ${g.maxRuns}); the hook is tripped until the window clears"
            q.addLast(now)
        }
        return null
    }

    private fun attributedDepth(ids: List<String>): Int {
        if (ids.isEmpty()) return 0
        val now = clock()
        val window = guard().attributionMs
        var best = 0
        synchronized(runs) {
            runs.removeAll { r -> r.endedAt?.let { now - it > window } == true }
            for (r in runs) if (now - r.touchedAt <= window && r.touched.any { t -> ids.any { sameId(t, it) } }) best = maxOf(best, r.depth + 1)
        }
        return best
    }

    private fun mark(session: String, hook: Hook) { runCatching { automation(session, AutomationMark(hook.script, hook.id, clock())) } }
    private fun unmark(session: String) { runCatching { automation(session, null) } }

    private fun execute(project: String, hook: Hook, e: NamedEvent, ticketId: String?, args: Map<String, Any?>, run: Run) {
        var markedSession: String? = null
        try {
            val script = try { scripts.read(project, hook.script) } catch (ex: IllegalArgumentException) { return fail(project, hook, ticketId, "script name refused: ${ex.message}") }
                ?: return fail(project, hook, ticketId, "no script '${hook.script}' in .agenthq/scripts/")
            val engine = engines.byExtension(script.extension) ?: return fail(project, hook, ticketId, "no engine loaded for .${script.extension}")
            val invoker = invokerOf(project, hook)
            val who = ScriptCaller(hook.ownerSession.orEmpty(), hook.ownerRole.orEmpty())
            markedSession = (args["workerSession"] ?: args["sessionId"]) as? String
            markedSession?.let { mark(it, hook) }
            val report = dispatcher.run(engine, script.source, args, limits(), who, invoker, false, progressTo = hook.to) { planned ->
                run.touchedAt = clock()
                planned.forEach { a -> flatten(a.args).forEach { run.touched += it } }
            }
            val sess = (args["workerSession"] ?: args["sessionId"]) as? String
            runLog.record(project, hook.script, "${hook.id} ${hook.on}", sess, report, if (report.ok) "ok" else "failed", "ran for ${e.name}: ${report.records.size} action(s)", report.error)
            if (report.ok) log("hook ${hook.id} ($project) ran ${hook.script} for ${e.name}: ${report.outcome}, ${report.records.size} action(s)")
            else fail(project, hook, ticketId, report.render(hook.script))
        } catch (t: Throwable) {
            runLog.record(project, hook.script, "${hook.id} ${hook.on}", null, null, "failed", "crashed", "${t.javaClass.simpleName}: ${t.message?.take(300)}")
            fail(project, hook, ticketId, "script crashed: ${t.javaClass.simpleName}: ${t.message?.take(300)}")
        } finally {
            markedSession?.let { unmark(it) }
            run.endedAt = clock()
        }
    }

    /** MAIL-HOOK-1b: a mail.incoming failure is logged once per hook and reason, never mailed or commented (a failure mail costs a turn). */
    private fun failQuiet(project: String, hook: Hook, what: String) {
        val line = "hook ${hook.id} ($project) FAILED: ${reasonLine(what)}".take(400)
        if (quietSeen.put("$project/${hook.id}/$line", true) == null) log(line)
    }
    private val quietSeen = ConcurrentHashMap<String, Boolean>()

    private fun fail(project: String, hook: Hook, ticketId: String?, what: String) {
        log("hook ${hook.id} ($project) FAILED: ${reasonLine(what)}".take(400))
        report(project, hook, ticketId, "Hook '${hook.id}' (on ${hook.on}, script '${hook.script}') failed: ${reasonLine(what)}\n${what.take(1500)}")
    }

    /** The one-line reason of a failure: the report's `error:` line (Lua error with line, or the refused tool and its error), else its first line. */
    private fun reasonLine(what: String): String =
        (what.lineSequence().firstOrNull { it.startsWith("error:") }?.removePrefix("error:") ?: what.lineSequence().first()).trim().take(300)

    private fun reportOnce(project: String, hook: Hook, ticketId: String?, reason: String, text: String) {
        val key = "$project/${hook.id}/$reason"
        val now = clock()
        val last = reported[key]
        if (last != null && now - last < guard().windowMs) return
        reported[key] = now
        report(project, hook, ticketId, text)
    }

    /** A failure is never silent: a comment on the triggering ticket when there is one, else (or if that fails) mail to the hook's owner. */
    private fun report(project: String, hook: Hook, ticketId: String?, text: String) {
        val sys = ToolInvoker(Caller(owner = true), null, null, project, "script-hooks")
        if (ticketId != null) {
            val r = runCatching { tools.invoke("agenthq_ticket_comment", buildJsonObject { put("ticketId", ticketId); put("kind", "issue"); put("body", text) }, sys) }.getOrNull()
            if (r != null && !r.isError) return
        }
        val to = hook.ownerSession ?: hook.ownerRole ?: "pm"
        val r = runCatching { tools.invoke("agenthq_message", buildJsonObject { put("to", to); put("body", text) }, sys) }.getOrNull()
        if (r == null || r.isError) log("hook ${hook.id} ($project): report could not be delivered: ${r?.text?.take(200)}")
    }

    /** The identity a hook run has: its owner's session and role (SCRIPT-4: carried, never tested). Added by the owner: the owner. */
    private fun invokerOf(project: String, hook: Hook): ToolInvoker =
        ToolInvoker(if (hook.ownerIsOwner || hook.ownerSession == null) Caller(owner = true) else Caller(owner = false, agent = true, activeSession = hook.ownerSession),
            hook.ownerSession, hook.ownerRole, project, "script:${hook.script}")

    private fun readTicket(ticketId: String): JsonObject? {
        val r = runCatching { tools.invoke("agenthq_ticket_get", buildJsonObject { put("ticketId", ticketId) }, ToolInvoker(Caller(owner = true), null, null, null, "script-hooks")) }.getOrNull()
        if (r == null || r.isError) return null
        return parse(r.text).takeIf { it.isNotEmpty() }
    }

    /** `input.args`: the hook's args, overlaid by the event payload and the ticket's fields, plus `event` and `hook`. */
    private fun scriptArgs(project: String, hook: Hook, e: NamedEvent, payload: JsonObject, ticket: JsonObject?, depth: Int): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        fun put(k: String, v: String?) { if (!v.isNullOrBlank()) m[k] = v }
        @Suppress("UNCHECKED_CAST") (ScriptEngineWire.fromJson(hook.args) as? Map<String, Any?>)?.let { m.putAll(it) }
        @Suppress("UNCHECKED_CAST") (ScriptEngineWire.fromJson(payload) as? Map<String, Any?>)?.let { m.putAll(it) }
        put("projectId", project)
        if (ticket != null) {
            put("title", str(ticket, "title")); put("status", str(ticket, "status")); put("role", str(ticket, "role"))
            put("branch", str(ticket, "branch")); put("base", str(ticket, "base")); put("workerSession", str(ticket, "sessionId"))
            str(ticket, "branch")?.let { b -> put("tipSha", runCatching { tip(project, b) }.getOrNull()) }
        }
        if (hook.on is HookOn.Lane) { put("lane", str(payload, if (hook.on.exit) "from" else "to")); put("from", str(payload, "from")); put("to", str(payload, "to")) }
        m["event"] = mapOf("name" to e.name, "id" to e.id, "causeId" to e.causeId, "depth" to depth.toLong(), "source" to e.source)
        m["hook"] = hook.id
        return m
    }

    private fun parse(text: String): JsonObject = runCatching { Json.parseToJsonElement(text.ifBlank { "{}" }) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())
    private fun str(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun flatten(v: Any?): List<String> = when (v) {
        is String -> listOf(v)
        is Map<*, *> -> v.values.flatMap { flatten(it) }
        is Iterable<*> -> v.flatMap { flatten(it) }
        else -> emptyList()
    }

    companion object {
        const val INCOMING_EVENT = "mail.incoming"
        /** SCRIPT-TICK-1: the internal event an `every <N>s` hook runs for (not published on the bus). */
        const val TIMER_EVENT = "timer.tick"
        private val pool by lazy { Executors.newSingleThreadExecutor { r -> Thread(r, "script-hooks").apply { isDaemon = true } } }
        val DEFAULT_EXECUTOR: (() -> Unit) -> Unit = { job -> pool.execute { runCatching { job() } } }

        fun matches(on: HookOn, eventName: String, payload: JsonObject): Boolean = when (on) {
            is HookOn.Lane -> eventName == HookOn.LANE_EVENT && (payload[if (on.exit) "from" else "to"] as? JsonPrimitive)?.contentOrNull.equals(on.lane, ignoreCase = true)
            is HookOn.Every -> false
            is HookOn.Named -> when {
                on.pattern == "*" -> true
                on.pattern.endsWith(".*") -> eventName.startsWith(on.pattern.dropLast(1))
                on.pattern.endsWith(".") -> eventName.startsWith(on.pattern)
                else -> on.pattern == eventName
            }
        }

        /** Two ids name the same thing when equal, or one is the other's 8+ char prefix (`ticket-` stripped): ticket ids are given in both forms. */
        fun sameId(a: String, b: String): Boolean {
            val x = a.removePrefix("ticket-"); val y = b.removePrefix("ticket-")
            return x == y || (minOf(x.length, y.length) >= 8 && (x.startsWith(y) || y.startsWith(x)))
        }

        /** Best effort, no process: the tip of local branch [branch] read from `<project dir>/.git` (loose ref, then packed-refs). */
        fun gitTip(projectDir: File, branch: String): String? {
            if (branch.contains("..") || branch.startsWith("/")) return null
            val git = File(projectDir, ".git").takeIf { it.isDirectory } ?: return null
            runCatching { File(git, "refs/heads/$branch").takeIf { it.isFile }?.readText()?.trim() }.getOrNull()?.takeIf { it.length >= 40 }?.let { return it }
            return runCatching {
                File(git, "packed-refs").takeIf { it.isFile }?.readLines()?.firstOrNull { it.endsWith(" refs/heads/$branch") }?.substringBefore(' ')
            }.getOrNull()
        }
    }
}
