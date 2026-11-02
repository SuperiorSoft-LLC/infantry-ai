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
    /** The mail id a mail hook ran for (lets a lost verdict find its record), else null. */
    val ref: String? = null,
    /** SCRIPT-LINE-TS-1: wall-clock run time, null when unknown (older records, a run that never started). */
    val durationMs: Long? = null,
) {
    /** The compact automation line a session view shows (never the agent's own output). */
    fun line(): String = "AUTOMATION · $script · $summary${if (!ok) " · FAILED" else ""}"

    /** SCRIPT-VIS-1b: the chat contribution of this run (a timeline_item payload), or null when the run is not tied to a session. */
    fun timelineJson(): JsonObject? {
        val sid = session ?: return null
        return buildJsonObject {
            put("id", "run-$project-$id"); put("sessionId", sid); put("at", at); put("source", "automation")
            put("label", "AUTOMATION · $script"); put("text", line()); put("ok", ok)
            durationMs?.let { put("meta", buildJsonObject { put("durationMs", it.toString()) }) }
            put("details", buildJsonArray {
                add(JsonPrimitive("trigger: $trigger"))
                actions.forEach { a -> add(JsonPrimitive("${a.tool} ${when (a.ok) { true -> "ok"; false -> "FAILED"; null -> "planned" }}${a.text?.let { ": ${it.take(160)}" }.orEmpty()}")) }
                log.forEach { add(JsonPrimitive("log: ${it.take(200)}")) }
                error?.let { add(JsonPrimitive("error: ${it.take(200)}")) }
            })
        }
    }

    fun toJson(): JsonObject = buildJsonObject {
        put("id", id); put("at", at); put("project", project); put("script", script); put("trigger", trigger)
        session?.let { put("session", it) }
        durationMs?.let { put("durationMs", it) }
        put("outcome", outcome); put("ok", ok); put("line", line()); put("summary", summary)
        error?.let { put("error", it) }
        ref?.let { put("ref", it) }
        put("actions", buildJsonArray { actions.forEach { a -> add(buildJsonObject { put("tool", a.tool); a.ok?.let { put("ok", it) }; a.text?.let { put("text", it.take(300)) } }) } })
        put("log", buildJsonArray { log.forEach { add(JsonPrimitive(it.take(300))) } })
    }
}

/** SCRIPT-VIS-1b: where run records survive a restart (the plugin's own storage; tests use a map). */
interface RunStore {
    fun save(rec: RunRecord)
    /** Drops all but the newest [keep] records of [project]. */
    fun trim(project: String, keep: Int)
    fun load(): List<RunRecord>
}

/** [RunStore] over the daemon's plugin storage: one table, the record as its JSON. */
class StorageRunStore(private val db: com.superiorsoftllc.agenthq.daemon.api.storage.PluginStorage) : RunStore {
    init { db.migrate(1, listOf("CREATE TABLE IF NOT EXISTS infantry_scripting_runs (id INTEGER NOT NULL, project TEXT NOT NULL, at INTEGER NOT NULL, json TEXT NOT NULL, PRIMARY KEY (project, id))")) }
    override fun save(rec: RunRecord) { db.execute("INSERT OR REPLACE INTO infantry_scripting_runs (id, project, at, json) VALUES (?, ?, ?, ?)", listOf(rec.id, rec.project, rec.at, RunRecords.encode(rec))) }
    override fun trim(project: String, keep: Int) { db.execute("DELETE FROM infantry_scripting_runs WHERE project = ? AND id NOT IN (SELECT id FROM infantry_scripting_runs WHERE project = ? ORDER BY id DESC LIMIT ?)", listOf(project, project, keep.toLong())) }
    override fun load(): List<RunRecord> = db.query("SELECT json FROM infantry_scripting_runs ORDER BY id").mapNotNull { RunRecords.decode(it["json"] as? String ?: return@mapNotNull null) }
}

/** The stored form of a [RunRecord] (the full log, unlike [RunRecord.toJson] which shortens text for the tool). */
object RunRecords {
    fun encode(r: RunRecord): String = buildJsonObject {
        put("id", r.id); put("at", r.at); put("project", r.project); put("script", r.script); put("trigger", r.trigger)
        r.session?.let { put("session", it) }; put("outcome", r.outcome); put("ok", r.ok); put("summary", r.summary)
        r.error?.let { put("error", it) }; r.ref?.let { put("ref", it) }; r.durationMs?.let { put("durationMs", it) }
        put("actions", buildJsonArray { r.actions.forEach { a -> add(buildJsonObject { put("tool", a.tool); a.ok?.let { put("ok", it) }; a.text?.let { put("text", it.take(300)) } }) } })
        put("log", buildJsonArray { r.log.forEach { add(JsonPrimitive(it.take(300))) } })
    }.toString()

    fun decode(text: String): RunRecord? = runCatching {
        val o = Json.parseToJsonElement(text).jsonObject
        fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
        RunRecord(
            id = s("id")!!.toLong(), at = s("at")!!.toLong(), project = s("project")!!, script = s("script")!!, trigger = s("trigger").orEmpty(), session = s("session"),
            durationMs = s("durationMs")?.toLongOrNull(), outcome = s("outcome").orEmpty(), ok = s("ok") == "true", summary = s("summary").orEmpty(), error = s("error"), ref = s("ref"),
            actions = (o["actions"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { e ->
                val a = e as? JsonObject ?: return@mapNotNull null
                RunAction((a["tool"] as? JsonPrimitive)?.contentOrNull.orEmpty(), (a["ok"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull(), (a["text"] as? JsonPrimitive)?.contentOrNull)
            },
            log = (o["log"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
        )
    }.getOrNull()
}

/**
 * SCRIPT-VIS-1: the newest [limit] run records per project, plus per-session wake and turns-saved counters, in memory (a restart clears
 * them). Wake reasons arrive from the daemon's `session.woken` event; `mail-handled` is a mail a hook handled with no wake = a turn saved.
 */
class ScriptRunLog(private val limit: () -> Int = { 200 }, private val clock: () -> Long = System::currentTimeMillis, private val store: RunStore? = null) {
    private val seq = java.util.concurrent.atomic.AtomicLong()
    private val runs = ConcurrentHashMap<String, ArrayDeque<RunRecord>>()
    private val wakes = ConcurrentHashMap<String, ConcurrentHashMap<String, Int>>() // session -> reason -> count
    private val sessionProject = ConcurrentHashMap<String, String>()
    private val lastWake = ConcurrentHashMap<String, WakeNote>()

    /** SCRIPT-VIS-1b: told every new or amended record (the plugin pushes it to the session's chat). A throwing listener is ignored. */
    @Volatile var onRecord: ((RunRecord) -> Unit)? = null

    init {
        // SCRIPT-VIS-1b: records survive a restart; a store that cannot be read starts an empty log (the log is a display aid, never a gate).
        runCatching { store?.load() }.getOrNull()?.forEach { r ->
            runs.computeIfAbsent(r.project) { ArrayDeque() }.addLast(r)
            r.session?.let { sessionProject[it] = r.project }
            if (r.id > seq.get()) seq.set(r.id)
        }
        val max = limit().coerceAtLeast(0)
        runs.values.forEach { q -> while (q.size > max) q.removeFirst() }
    }

    data class WakeNote(val at: Long, val reason: String, val ref: String?)

    fun record(project: String, script: String, trigger: String, session: String?, report: RunReport?, outcome: String, summary: String, error: String? = null, ref: String? = null, quiet: Boolean = false): RunRecord {
        val actions = report?.records?.map { RunAction(it.tool, it.ok, it.text) }.orEmpty()
        val rec = RunRecord(seq.incrementAndGet(), clock(), project, script, trigger, session, outcome, error == null && report?.ok != false, actions, report?.logs.orEmpty(), summary, error ?: report?.error, ref, report?.durationMs)
        session?.let { sessionProject[it] = project }
        val max = limit().coerceAtLeast(0)
        val q = runs.computeIfAbsent(project) { ArrayDeque() }
        synchronized(q) { q.addLast(rec); while (q.size > max) q.removeFirst() }
        runCatching { store?.save(rec); store?.trim(project, max) }
        if (!quiet) runCatching { onRecord?.invoke(rec) }
        return rec
    }

    /** HOOK-LINE-1: the records of the mail hooks that ran for mail [ref], oldest first. */
    fun byRef(ref: String): List<RunRecord> = runs.values.flatMap { q -> synchronized(q) { q.filter { it.ref == ref } } }.sortedBy { it.id }

    /**
     * HOOK-LINE-1: ONE chat line per mail with its final outcome; the per-hook records are its expandable details (they are recorded quiet).
     * [verdict] is "handled", "rewritten" or "delivered". Never says "passed through" when a later hook handled the mail.
     */
    fun mailSummary(ref: String, subject: String, session: String, verdict: String) {
        val recs = byRef(ref)
        if (recs.isEmpty()) return
        val last = recs.last()
        val failed = recs.filter { !it.ok }
        val text = when {
            verdict == "handled" -> "mail $subject to $session: handled by ${last.script} (not delivered, no turn)"
            verdict == "rewritten" -> "mail $subject to $session: delivered, rewritten by ${last.script}"
            failed.isNotEmpty() -> "mail $subject to $session: hook ${failed.joinToString { it.script }} FAILED (${failed.first().error?.take(120) ?: "error"}) -> delivered, recipient woken"
            else -> "mail $subject to $session: delivered (no hook handled it)"
        }
        val ok = failed.isEmpty()
        val item = buildJsonObject {
            put("id", "mail-$ref-${recs.first().project}"); put("sessionId", session); put("at", clock()); put("source", "automation")
            put("label", "AUTOMATION · mail"); put("text", "AUTOMATION · $text${if (!ok) " · FAILED" else ""}"); put("ok", ok)
            put("details", buildJsonArray { recs.forEach { add(JsonPrimitive("${it.script}: ${it.outcome} - ${it.summary}${it.error?.let { e -> " (${e.take(160)})" }.orEmpty()}")) } })
        }
        runCatching { onStats?.invoke(item) }
    }

    /**
     * SCRIPT-VIS-1b: the host dropped the verdict of the mail hook run for mail [ref] (timeout, CPU limit, throw), so the mail was delivered
     * although the script said it handled it. The record is amended to say so (outcome verdict-lost, failed); no turn was saved.
     */
    fun verdictLost(ref: String, reason: String) {
        for ((_, q) in runs) {
            val amended = synchronized(q) {
                val i = q.indexOfLast { it.ref == ref }
                if (i < 0) null else q[i].let { r ->
                    r.copy(outcome = "verdict-lost", ok = false, summary = "verdict lost (${r.outcome}); the mail was delivered anyway", error = "host dropped the verdict: $reason").also { q[i] = it }
                }
            } ?: continue
            runCatching { store?.save(amended) }
            runCatching { onRecord?.invoke(amended) }
            return
        }
    }

    /** SCRIPT-VIS-1c: told with a session's stats item after each wake / handled mail (the plugin pushes it to the UI). A throwing listener is ignored. */
    @Volatile var onStats: ((JsonObject) -> Unit)? = null

    /**
     * SCRIPT-VIS-1c: the session's stats as a timeline item (source `automation-stats`, never drawn as a chat row): meta carries turnsSaved,
     * wakes, wakesByReason (`owner:1,mail:2`), lastWake, and the project's turnsSaved / wakes. Null when the session has no wake yet.
     */
    fun statsItem(session: String): JsonObject? {
        val by = wakes[session] ?: return null
        val mine = sessionStats(session)
        val proj = sessionProject[session]?.let { projectStats(it) }
        return buildJsonObject {
            put("id", "stats-$session"); put("sessionId", session); put("at", lastWake[session]?.at ?: clock()); put("source", STATS_SOURCE)
            put("label", "AUTOMATION stats")
            put("text", "turns saved ${mine["turnsSaved"]} · wakes ${mine["wakes"]}")
            put("meta", buildJsonObject {
                put("turnsSaved", mine["turnsSaved"]!!.toString()); put("wakes", mine["wakes"]!!.toString())
                put("wakesByReason", by.filterKeys { it != HANDLED }.toSortedMap().entries.joinToString(",") { "${it.key}:${it.value}" })
                lastWake[session]?.let { put("lastWake", it.reason) }
                proj?.let { put("projectTurnsSaved", it["turnsSaved"]!!.toString()); put("projectWakes", it["wakes"]!!.toString()) }
            })
        }
    }

    /** The newest contributions for the chat of every session (all projects), oldest first, at most [n]; the stats items come last. */
    fun timelineItems(n: Int = 500): List<JsonObject> =
        (runs.values.flatMap { q -> synchronized(q) { q.toList() } }.mapNotNull { r -> r.timelineJson()?.let { r.at to it } } + synchronized(notes) { notes.toList() }).sortedBy { it.first }.map { it.second }.takeLast(n) +
            wakes.keys.sorted().mapNotNull { statsItem(it) }

    private val notes = ArrayDeque<Pair<Long, JsonObject>>()

    /** SCRIPT-CHAT-1: a chat note (agenthq_chat_note) kept so a reload of the chat shows it again; memory only, newest 200. */
    fun addNote(item: JsonObject) {
        val at = (item["at"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: clock()
        synchronized(notes) { notes.addLast(at to item); while (notes.size > 200) notes.removeFirst() }
    }

    /** [project] ties the session to a project for the project summary (the first sight wins); null when unknown. */
    fun wake(session: String, reason: String, ref: String?, project: String? = null) {
        project?.let { sessionProject.putIfAbsent(session, it) }
        wakes.computeIfAbsent(session) { ConcurrentHashMap() }.merge(reason, 1, Int::plus)
        lastWake[session] = WakeNote(clock(), reason, ref)
        runCatching { statsItem(session)?.let { onStats?.invoke(it) } }
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
        /** SCRIPT-VIS-1c: the timeline item source of a session's stats (the UI reads it for the badge line and the panel). */
        const val STATS_SOURCE = "automation-stats"
        /** The wake reason the daemon reports for a mail a hook handled (no wake happened = a turn saved). */
        const val HANDLED = "mail-handled"
    }
}
