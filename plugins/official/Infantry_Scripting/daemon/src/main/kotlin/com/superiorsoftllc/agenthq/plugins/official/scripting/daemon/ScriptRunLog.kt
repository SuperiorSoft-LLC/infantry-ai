package com.superiorsoftllc.agenthq.plugins.official.scripting.daemon

import com.superiorsoftllc.agenthq.daemon.api.DaemonTool
import com.superiorsoftllc.agenthq.daemon.api.ToolCall
import com.superiorsoftllc.agenthq.daemon.api.ToolResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

/** SCRIPT-VIS-1: one action of a recorded run. */
data class RunAction(val tool: String, val ok: Boolean?, val text: String?)

/**
 * SCRIPT-VIS-1: one recorded script run. [trigger] is "<hook id> <event>" for a hook (`pong mail.incoming`, `h1 lane enter done`) or `manual`.
 * [session] is the session the run acted for (the mail's recipient, the hook's target), if any. [outcome]: handled | delivered | passed | ok | failed.
 */
data class RunRecord(
    val id: Long, val at: Long, val project: String, val script: String, val trigger: String, val session: String?,
    val outcome: String, val ok: Boolean, val actions: List<RunAction>, val log: List<String>, val summary: String, val error: String? = null,
) {
    /** The compact automation line a session view shows (never the agent's own output). */
    fun line(): String = "AUTOMATION · $script · $summary${if (!ok) " · FAILED" else ""}"

    fun toJson(): JsonObject = buildJsonObject {
        put("id", id); put("at", at); put("project", project); put("script", script); put("trigger", trigger)
        session?.let { put("session", it) }
        put("outcome", outcome); put("ok", ok); put("line", line()); put("summary", summary)
        error?.let { put("error", it) }
        put("actions", buildJsonArray { actions.forEach { a -> add(buildJsonObject { put("tool", a.tool); a.ok?.let { put("ok", it) }; a.text?.let { put("text", it.take(300)) } }) } })
        put("log", buildJsonArray { log.forEach { add(JsonPrimitive(it.take(300))) } })
    }
}

/**
 * SCRIPT-VIS-1: the newest [limit] run records per project, plus per-session wake and turns-saved counters, in memory (a restart clears
 * them). Wake reasons arrive from the daemon's `session.woken` event; `mail-handled` is a mail a hook handled with no wake = a turn saved.
 */
class ScriptRunLog(private val limit: () -> Int = { 200 }, private val clock: () -> Long = System::currentTimeMillis) {
    private val seq = java.util.concurrent.atomic.AtomicLong()
    private val runs = ConcurrentHashMap<String, ArrayDeque<RunRecord>>()
    private val wakes = ConcurrentHashMap<String, ConcurrentHashMap<String, Int>>() // session -> reason -> count
    private val sessionProject = ConcurrentHashMap<String, String>()
    private val lastWake = ConcurrentHashMap<String, WakeNote>()

    data class WakeNote(val at: Long, val reason: String, val ref: String?)

    fun record(project: String, script: String, trigger: String, session: String?, report: RunReport?, outcome: String, summary: String, error: String? = null): RunRecord {
        val actions = report?.records?.map { RunAction(it.tool, it.ok, it.text) }.orEmpty()
        val rec = RunRecord(seq.incrementAndGet(), clock(), project, script, trigger, session, outcome, error == null && report?.ok != false, actions, report?.logs.orEmpty(), summary, error ?: report?.error)
        session?.let { sessionProject[it] = project }
        val max = limit().coerceAtLeast(0)
        val q = runs.computeIfAbsent(project) { ArrayDeque() }
        synchronized(q) { q.addLast(rec); while (q.size > max) q.removeFirst() }
        return rec
    }

    /** [project] ties the session to a project for the project summary (the first sight wins); null when unknown. */
    fun wake(session: String, reason: String, ref: String?, project: String? = null) {
        project?.let { sessionProject.putIfAbsent(session, it) }
        wakes.computeIfAbsent(session) { ConcurrentHashMap() }.merge(reason, 1, Int::plus)
        lastWake[session] = WakeNote(clock(), reason, ref)
    }

    fun runs(project: String, session: String? = null, n: Int = 20): List<RunRecord> {
        val q = runs[project] ?: return emptyList()
        return synchronized(q) { q.toList() }.filter { session == null || it.session == session }.takeLast(n.coerceIn(1, 1000)).reversed()
    }

    /** Wakes by reason for one session; `turnsSaved` = mails a hook handled without a wake. */
    fun sessionStats(session: String): JsonObject = statsOf(wakes[session].orEmpty(), lastWake[session])

    /** Sessions not tied to a project (no run, no project on their wake) count in no project's summary. */
    fun projectStats(project: String): JsonObject {
        val total = HashMap<String, Int>()
        for ((s, m) in wakes) if (sessionProject[s] == project) m.forEach { (k, v) -> total.merge(k, v, Int::plus) }
        return statsOf(total, null)
    }

    private fun statsOf(by: Map<String, Int>, last: WakeNote?): JsonObject = buildJsonObject {
        put("turnsSaved", by[HANDLED] ?: 0)
        put("wakes", by.filterKeys { it != HANDLED }.values.sum())
        put("wakesByReason", buildJsonObject { by.filterKeys { it != HANDLED }.toSortedMap().forEach { (k, v) -> put(k, v) } })
        last?.let { put("lastWake", buildJsonObject { put("at", it.at); put("reason", it.reason); it.ref?.let { r -> put("ref", r) } }) }
    }

    /** `agenthq_script_stats`: read-only; no args = this project's summary plus the latest runs; `session` narrows to one session. */
    fun tool(): DaemonTool = object : DaemonTool {
        override val name = STATS
        override val description = "Script automation, read-only: turns saved (mails a script handled without waking the recipient), wakes by reason (owner, agent, mail, mail-hook-delivered, ...), and the latest script runs " +
            "(time, script, trigger, session, actions ok/failed, log, outcome). Pass session for one session's counts and runs. Open to every role."
        override val inputSchema: JsonObject = Json.parseToJsonElement(
            """{"type":"object","properties":{"session":{"type":"string","description":"One session id"},"project":{"type":"string","description":"Project id (default: yours)"},"limit":{"type":"integer","description":"Runs to list (default 20)"}}}""",
        ).jsonObject
        override val annotations: JsonObject = Json.parseToJsonElement("""{"readOnlyHint":true}""").jsonObject
        override fun call(call: ToolCall): ToolResult {
            fun s(k: String) = (call.arguments[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            val project = s("project") ?: call.clientProjectId ?: return ToolResult("$STATS needs a project (pass project, or run it from a project session)", isError = true)
            val session = s("session")
            val n = s("limit")?.toIntOrNull() ?: 20
            val out = buildJsonObject {
                put("project", project)
                put("stats", if (session != null) sessionStats(session) else projectStats(project))
                put("runs", buildJsonArray { runs(project, session, n).forEach { add(it.toJson()) } })
            }
            return ToolResult(out.toString())
        }
    }

    companion object {
        const val STATS = "agenthq_script_stats"
        /** The wake reason the daemon reports for a mail a hook handled (no wake happened = a turn saved). */
        const val HANDLED = "mail-handled"
    }
}
