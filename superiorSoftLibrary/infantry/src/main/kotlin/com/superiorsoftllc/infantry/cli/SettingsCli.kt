package com.superiorsoftllc.infantry.cli

import com.superiorsoftllc.infantry.protocol.Envelope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * No-hard-coded-limits (contract 1.133.0): `infantry settings` reads and writes the daemon's durable settings, and
 * `infantry settings limits` lists every limit (cap, threshold, timeout) with its value, default and where to change it.
 * A refused value prints the daemon's refusal, which names the accepted range.
 */
internal object SettingsCli {
    const val USAGE = "usage: infantry settings [get [<key>] [--project p] [--role r] [--session s]] | set <key> <value> [--scope app|project|role|session] [--project p] [--role r] [--session s] | explain <key> [--project p] [--role r] [--session s] | limits"

    private val json = Json { ignoreUnknownKeys = true }

    /** The usage error for [args], or null when they are valid. */
    fun usageError(args: List<String>): String? {
        val (positional, flags) = split(args.drop(1))
        val ok = when (args.firstOrNull()?.lowercase()) {
            null -> true
            "get" -> positional.size in 0..1
            "set" -> positional.size == 2
            "explain" -> positional.size == 1
            "limits" -> args.size == 1
            else -> false
        } && flags.keys.all { it in FLAGS }
        return if (ok) null else USAGE
    }

    private val FLAGS = setOf("scope", "project", "role", "session")

    /** SETTINGS-SCOPES-1: positional words and `--name value` flags of [args]. */
    fun split(args: List<String>): Pair<List<String>, Map<String, String>> {
        val positional = mutableListOf<String>()
        val flags = linkedMapOf<String, String>()
        var i = 0
        while (i < args.size) {
            val a = args[i]
            if (a.startsWith("--") && a.length > 2 && i + 1 < args.size) { flags[a.drop(2)] = args[i + 1]; i += 2 } else { positional += a; i++ }
        }
        return positional to flags
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
                null, "get", "explain" -> {
                    val explain = args.firstOrNull()?.lowercase() == "explain"
                    val (positional, flags) = split(args.drop(1))
                    val key = positional.firstOrNull()?.trim().orEmpty()
                    val scoped = explain || flags.keys.any { it != "scope" }
                    val data = if (!scoped) key else JsonObject(
                        buildMap {
                            put("key", JsonPrimitive(key))
                            flags.forEach { (k, v) -> if (k != "scope") put(k, JsonPrimitive(v)) }
                            if (explain) put("explain", JsonPrimitive("true"))
                        },
                    ).toString()
                    val answer = stream.request(Envelope(Envelope.SETTINGS_GET, data), Envelope.SETTINGS, "settings_get", idempotent = true)
                    val body = json.parseToJsonElement(answer.data).jsonObject
                    if (explain) out.appendLine(body["explain"]?.jsonPrimitive?.contentOrNull ?: body.toString())
                    else if (scoped) out.appendLine("$key = ${body["value"]}  (from ${body["scope"]?.jsonPrimitive?.contentOrNull})")
                    else render(body, out)
                    0
                }
                "set" -> {
                    val (positional, flags) = split(args.drop(1))
                    val key = positional[0].trim()
                    val scope = flags["scope"] ?: "app"
                    val fields = buildMap<String, JsonElement> {
                        put("key", JsonPrimitive(key))
                        put("value", parseValue(positional[1]))
                        if (scope != "app") put("scope", JsonPrimitive(scope))
                        listOf("project", "role", "session").forEach { f -> flags[f]?.let { put(f, JsonPrimitive(it)) } }
                    }
                    val answer = stream.request(Envelope(Envelope.SETTINGS_SET, JsonObject(fields).toString()), Envelope.SETTINGS, "settings_set", idempotent = true)
                    val all = json.parseToJsonElement(answer.data).jsonObject
                    if (scope == "app") out.appendLine("$key = ${all[key] ?: "(set)"}")
                    else out.appendLine("$key = ${all["value"]}  (from ${all["scope"]?.jsonPrimitive?.contentOrNull}; set at $scope)")
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
            out.appendLine("    change: infantry settings set ${s("key")} <n>  |  Settings > Limits  |  env ${s("env")}")
        }
    }
}
