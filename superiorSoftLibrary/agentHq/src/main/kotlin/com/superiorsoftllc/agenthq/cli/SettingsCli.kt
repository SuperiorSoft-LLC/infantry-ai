package com.superiorsoftllc.agenthq.cli

import com.superiorsoftllc.agenthq.protocol.Envelope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * No-hard-coded-limits (contract 1.133.0): `agenthq settings` reads and writes the daemon's durable settings, and
 * `agenthq settings limits` lists every limit (cap, threshold, timeout) with its value, default and where to change it.
 * A refused value prints the daemon's refusal, which names the accepted range.
 */
internal object SettingsCli {
    const val USAGE = "usage: agenthq settings [get [<key>]] | set <key> <value> | limits"

    private val json = Json { ignoreUnknownKeys = true }

    /** The usage error for [args], or null when they are valid. */
    fun usageError(args: List<String>): String? {
        val ok = when (args.firstOrNull()?.lowercase()) {
            null -> true
            "get" -> args.size in 1..2
            "set" -> args.size == 3
            "limits" -> args.size == 1
            else -> false
        }
        return if (ok) null else USAGE
    }

    /** A typed value as JSON: a number, `true`/`false`, a JSON array/object, else a plain string. */
    fun parseValue(raw: String): JsonElement {
        val text = raw.trim()
        return runCatching { Json.parseToJsonElement(text) }.getOrNull()?.takeIf { it !is JsonPrimitive || !it.isString || text.startsWith("\"") }
            ?: JsonPrimitive(raw)
    }

    suspend fun run(stream: FrameStream, args: List<String>, out: Appendable, err: Appendable): Int {
        return try {
            when (args.firstOrNull()?.lowercase()) {
                null, "get" -> {
                    val key = args.getOrNull(1)?.trim().orEmpty()
                    val answer = stream.request(Envelope(Envelope.SETTINGS_GET, key), Envelope.SETTINGS, "settings_get", idempotent = true)
                    render(json.parseToJsonElement(answer.data).jsonObject, out)
                    0
                }
                "set" -> {
                    val key = args[1].trim()
                    val body = JsonObject(mapOf("key" to JsonPrimitive(key), "value" to parseValue(args[2])))
                    val answer = stream.request(Envelope(Envelope.SETTINGS_SET, body.toString()), Envelope.SETTINGS, "settings_set", idempotent = true)
                    val all = json.parseToJsonElement(answer.data).jsonObject
                    out.appendLine("$key = ${all[key] ?: "(set)"}")
                    0
                }
                "limits" -> {
                    val answer = stream.request(Envelope(Envelope.LIMIT_CATALOG, ""), Envelope.LIMIT_CATALOG, "limit_catalog", idempotent = true)
                    renderLimits(json.parseToJsonElement(answer.data).jsonObject, out)
                    0
                }
                else -> { err.appendLine(USAGE); 2 }
            }
        } catch (e: IllegalStateException) {
            err.appendLine("error: ${e.message}")
            1
        }
    }

    fun render(settings: JsonObject, out: Appendable) {
        for ((key, value) in settings.entries.sortedBy { it.key }) {
            // The portrait is a large base64 blob: say it is set instead of printing it.
            val shown = if (key == "user.portrait") "(set)" else value.toString()
            out.appendLine("$key = $shown")
        }
    }

    /** One block per limit: `key = value unit (default d; 0 = ...)`, then its description and env name. */
    fun renderLimits(catalog: JsonObject, out: Appendable) {
        val limits = catalog["limits"]?.jsonArray ?: return
        for (item in limits) {
            val o = item.jsonObject
            fun s(k: String) = o[k]?.jsonPrimitive?.contentOrNull
            val zero = s("zeroMeans")?.let { "; 0 = $it" }.orEmpty()
            val range = "${s("min")}..${s("max") ?: "no ceiling"}"
            out.appendLine("${s("key")} = ${s("value")} ${s("unit")} (default ${s("default")}, range $range$zero)")
            out.appendLine("    ${s("description")}")
            out.appendLine("    change: agenthq settings set ${s("key")} <n>  |  Settings > Limits  |  env ${s("env")}")
        }
    }
}
