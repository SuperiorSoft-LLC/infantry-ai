// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.pace.daemon

import com.superiorsoftllc.infantry.daemon.api.DaemonApi
import com.superiorsoftllc.infantry.daemon.api.DaemonPlugin
import com.superiorsoftllc.infantry.daemon.api.DaemonTool
import com.superiorsoftllc.infantry.daemon.api.Frame
import com.superiorsoftllc.infantry.daemon.api.HttpReply
import com.superiorsoftllc.infantry.daemon.api.ToolCall
import com.superiorsoftllc.infantry.daemon.api.ToolResult
import com.superiorsoftllc.infantry.daemon.api.usage.Meters
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.io.File

/**
 * PACE-TREND-1: Infantry_Pace's daemon half. While the plugin is enabled it samples the plan meters (`api.meters`, the data
 * SOURCE; no dependency on Stats) every [SAMPLE_PERIOD_MS], keeps the history in its own folder, prunes by `pace.retentionDays`
 * on start and hourly, and answers `pace_get` frames and the `infantry_pace` tool. Disabled or removed = nothing is recorded
 * and core is unchanged.
 */
class PaceDaemonPlugin : DaemonPlugin {
    private var recorder: PaceRecorder? = null
    private val closers = mutableListOf<AutoCloseable>()

    override fun start(api: DaemonApi) {
        val store = PaceStore(File(api.stateDir, "samples.jsonl"))
        val settings = { PaceSettings.from(api::config) }
        val rec = PaceRecorder(store, api.meters, settings)
        recorder = rec
        rec.pruneNow()
        closers += api.schedule("pace-sample", SAMPLE_PERIOD_MS) { rec.tick() }
        closers += api.schedule("pace-prune", PRUNE_PERIOD_MS) { rec.pruneNow() }
        api.frames.handle("pace_get") { frame: Frame, _ ->
            val answer = try {
                PaceReport.build(store, settings(), PaceQueries.parse(frame.data), System.currentTimeMillis()).toString()
            } catch (e: Exception) {
                return@handle Frame("error", "pace_get failed: ${e.message ?: e::class.simpleName}", frame.sessionId, frame.requestId)
            }
            Frame("pace_report", answer, frame.sessionId, frame.requestId)
        }
        // USAGE-FIT: GET /plugins/<id>/usagefit[?provider=claude], the tokens-per-point analysis the Stats section draws. Read-only.
        val fit = UsageFitService(store, api.meters) { api.config("pace.fitMinSteps")?.toIntOrNull()?.coerceIn(2, 200) ?: UsageFitService.DEFAULT_MIN_STEPS }
        api.routes.handle("GET", "usagefit") { call ->
            try {
                HttpReply(200, fit.answer(call.query["provider"] ?: "claude", System.currentTimeMillis()).toString(), "application/json")
            } catch (e: Exception) {
                HttpReply(500, "usagefit failed: ${e.message ?: e::class.simpleName}")
            }
        }
        api.tools.register(PaceTool(store, settings))
        rec.tick()
    }

    override fun stop() {
        closers.forEach { runCatching { it.close() } }
        closers.clear()
        recorder = null
    }

    companion object {
        const val SAMPLE_PERIOD_MS = 60_000L
        const val PRUNE_PERIOD_MS = 3_600_000L
    }
}

/** Reads the meters and records each reading; prunes by retention. */
class PaceRecorder(private val store: PaceStore, private val meters: Meters, private val settings: () -> PaceSettings) {
    /** One pass: every readable window becomes a sample unless it is a repeat. Returns how many were kept. */
    private var cursor = System.currentTimeMillis()

    fun tick(): Int = try {
        // TOKEN-9: tokens spent since the last KEPT sample, per session; the cursor moves only when a sample was kept.
        val reqs = meters.requests(cursor + 1)
        val tokens = reqs.groupBy { it.sessionId }.map { (sid, l) ->
            SessionTokens(sid, l.first().role, l.sumOrNull { it.input }, l.sumOrNull { it.output }, l.sumOrNull { it.cacheRead }, l.sumOrNull { it.cacheWrite })
        }
        val kept = meters.read().count { store.record(it, tokens) }
        if (kept > 0) reqs.maxOfOrNull { it.ts }?.let { cursor = maxOf(cursor, it) }
        kept
    } catch (e: Exception) {
        0 // a missing grant or a meter read failing must never take the plugin down; the next tick tries again
    }

    fun pruneNow(): Int = store.prune(settings().retentionDays)
}

private fun <T> List<T>.sumOrNull(f: (T) -> Long?): Long? = mapNotNull(f).takeIf { it.isNotEmpty() }?.sum()

object PaceQueries {
    private val json = Json { ignoreUnknownKeys = true }

    /** Parses a `pace_get` body (empty = everything). `sinceHours` is relative to now. */
    fun parse(data: String, now: Long = System.currentTimeMillis()): PaceQuery {
        if (data.isBlank()) return PaceQuery()
        val o: JsonObject = json.parseToJsonElement(data).jsonObject
        val since = o["sinceMs"]?.jsonPrimitive?.longOrNull
            ?: o["sinceHours"]?.jsonPrimitive?.doubleOrNull?.let { now - (it * 3_600_000.0).toLong() }
            ?: 0L
        return PaceQuery(
            provider = o["provider"]?.jsonPrimitive?.contentOrNull,
            window = o["window"]?.jsonPrimitive?.contentOrNull,
            sinceMs = since.coerceAtLeast(0),
            includeSamples = o["samples"]?.jsonPrimitive?.contentOrNull == "true",
            maxPoints = o["maxPoints"]?.jsonPrimitive?.intOrNull?.coerceIn(2, 5000) ?: 400,
        )
    }
}

class PaceTool(private val store: PaceStore, private val settings: () -> PaceSettings) : DaemonTool {
    override val name = "infantry_pace"
    override val description =
        "Read the pacing history the daemon recorded from the plan meters: per provider window the percent used, the pace delta, " +
            "the projected landing at reset, the momentum (RISING / FLAT / FALLING) and the forecast to reset. " +
            "Text summary first, then the JSON (points are newest-kept and thinned)."
    override val inputSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("provider", buildJsonObject { put("type", "string"); put("description", "Only this meter (claude, codex, opencode-go, ...)") })
            put("samples", buildJsonObject { put("type", "string"); put("description", "TOKEN-9: \"true\" adds the newest 200 raw samples per series (raw percent, previous, delta, tokens per session since the last sample); default off") })
            put("window", buildJsonObject { put("type", "string"); put("description", "Only this window label (e.g. '5h limit')") })
            put("sinceHours", buildJsonObject { put("type", "number"); put("description", "Only points from the last N hours (default: all)") })
        })
        put("required", buildJsonArray {})
        put("additionalProperties", false)
    }
    override val annotations = buildJsonObject {
        put("readOnlyHint", true); put("destructiveHint", false); put("idempotentHint", true); put("openWorldHint", false)
    }

    override fun call(call: ToolCall): ToolResult = try {
        val report = PaceReport.build(store, settings(), PaceQueries.parse(call.arguments.toString()), System.currentTimeMillis())
        val text = report["text"]!!.jsonPrimitive.content
        ToolResult(text + "\n\n" + report.toString())
    } catch (e: Exception) {
        ToolResult(e.message ?: "infantry_pace failed", isError = true)
    }
}
