package com.superiorsoftllc.agenthq.plugins.official.scripting.daemon

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * SCRIPT-JOB-1: turns the output lines of a background job into `agenthq_progress` reports. Runs on the job's own reader thread only.
 * Understands `##infantry` marker lines (same grammar as `agenthq run`) and two optional data regexes: [stepRegex] ("step 2/5: name",
 * groups 1=n 2=N 3=name) and [percentRegex] (group 1 = the number). Updates are rate limited: at most one per [minGapMs]; in between the
 * latest one is kept and sent by the next line past the gap or by [finish]. Plan / done / fail are never delayed.
 */
class JobProgress(
    private val label: String,
    steps: List<String>,
    private val markers: Boolean,
    percentRegex: String?,
    stepRegex: String?,
    private val report: (JsonObject) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val minGapMs: Long = 1000,
) {
    private val percent = percentRegex?.let { runCatching { Regex(it) }.getOrNull() }
    private val step = stepRegex?.let { runCatching { Regex(it) }.getOrNull() }
    private var pending: JsonObject? = null
    private var lastSent = Long.MIN_VALUE / 2
    private var planned = false
    @Volatile var percentNow: Double? = null; private set
    @Volatile var stepNow: String? = null; private set
    @Volatile var ended = false; private set
    var sent = 0; private set

    init {
        if (steps.isNotEmpty()) { send(obj("plan") { put("steps", buildJsonArray { steps.forEach { add(JsonPrimitive(it)) } }) }); planned = true }
        else send(obj("status") {})
    }

    private fun obj(mode: String, extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject {
        put("mode", mode); if (label.isNotBlank()) put("label", label); extra()
    }

    private fun send(o: JsonObject) { lastSent = clock(); sent++; runCatching { report(o) } }

    private fun soft(o: JsonObject) {
        if (ended) return
        if (clock() - lastSent >= minGapMs) { pending = null; send(o) } else pending = o
    }

    fun line(raw: String) {
        if (ended) return
        val t = raw.trim()
        if (markers && t.startsWith(PREFIX)) { marker(t.removePrefix(PREFIX).trim()); return }
        step?.find(t)?.let { m ->
            val n = m.groupValues.getOrNull(1)?.toIntOrNull(); val total = m.groupValues.getOrNull(2)?.toIntOrNull()
            val name = m.groupValues.getOrNull(3)?.trim()?.takeIf { it.isNotEmpty() }
            if (n != null && total != null && total > 0) {
                if (!planned) { send(obj("plan") { put("count", total) }); planned = true }
                val finished = stepNow
                stepNow = name
                if (n > 1) { percentNow = (n - 1) * 100.0 / total; soft(obj("step") { finished?.let { put("step", it) } }) }
                return
            }
        }
        percent?.find(t)?.let { m ->
            val p = m.groupValues.getOrNull(1)?.toDoubleOrNull()?.takeIf { it in 0.0..100.0 } ?: return
            percentNow = p; soft(obj("percent") { put("percent", p) })
        }
    }

    private fun marker(rest: String) {
        val verb = rest.substringBefore(' ').lowercase()
        val words = rest.substringAfter(' ', "").trim()
        val parts = words.split(Regex("\\s+")).filter { it.isNotEmpty() }
        when (verb) {
            "progress" -> parts.firstOrNull()?.removeSuffix("%")?.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { p ->
                percentNow = p; soft(obj("percent") { put("percent", p) })
            }
            "plan" -> {
                val n = parts.singleOrNull()?.toIntOrNull()
                val names = words.split('|').map { it.trim() }.filter { it.isNotEmpty() }
                if (n != null && n > 0) { planned = true; send(obj("plan") { put("count", n) }) }
                else if (names.isNotEmpty()) { planned = true; send(obj("plan") { put("steps", buildJsonArray { names.forEach { add(JsonPrimitive(it)) } }) }) }
            }
            "step" -> { stepNow = words.ifBlank { null } ?: stepNow; soft(obj("step") { if (words.isNotBlank()) put("step", words) }) }
            "bytes", "count" -> {
                val done = parts.getOrNull(0)?.toLongOrNull() ?: return
                val total = parts.getOrNull(1)?.toLongOrNull()
                soft(obj(verb) { put("done", done); total?.let { put("total", it) } })
            }
            "status", "estimate" -> soft(obj("status") { if (words.isNotBlank()) put("label", words) })
            "done" -> finish(0, null)
            "fail" -> finish(1, words.ifBlank { null })
            "clear" -> if (!ended) { pending = null; send(obj("clear") {}) }
        }
    }

    /** The job ended: flush the kept update, then done (exit 0) or fail with the reason. Idempotent. */
    fun finish(exitCode: Int, reason: String?) {
        if (ended) return
        pending?.let { pending = null; send(it) }
        ended = true
        if (exitCode == 0) send(obj("done") {}) else send(buildJsonObject { put("mode", "fail"); put("label", reason ?: "exit code $exitCode") })
    }

    companion object { const val PREFIX = "##infantry" }
}
