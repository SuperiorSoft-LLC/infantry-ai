package com.superiorsoftllc.agenthq.plugins.official.scripting.daemon

import com.superiorsoftllc.agenthq.daemon.api.ServiceEndpoint
import com.superiorsoftllc.agenthq.daemon.api.ServicesApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The extension point's wire format. Plugins have separate class loaders, so engines and Scripting talk through the core's name-keyed
 * string endpoints: id `scripting.engine/<engineId>`, operations `describe` -> {id, extensions} and `run` ->
 * request {source, input, limits, caller}, answer {outcome, actions:[{name,args}], logs, error}. A third-party engine may speak this
 * directly or shade this file and call [publish].
 */
object ScriptEngineWire {
    const val PREFIX = "scripting.engine/"

    /** Publish [engine] under the extension point; the handle unpublishes it. */
    fun publish(services: ServicesApi, engine: ScriptEngine): AutoCloseable = services.publishEndpoint(PREFIX + engine.id, asEndpoint(engine))

    fun asEndpoint(engine: ScriptEngine) = ServiceEndpoint { op, request ->
        when (op) {
            "describe" -> buildJsonObject { put("id", engine.id); put("extensions", JsonArray(engine.extensions.map(::JsonPrimitive))) }.toString()
            "binding" -> engine.binding
            "check" -> engine.check(request).orEmpty()
            "run" -> {
                val r = Json.parseToJsonElement(request).jsonObject
                val l = r["limits"]?.jsonObject
                val d = ScriptLimits()
                val limits = ScriptLimits(
                    l?.get("maxSteps")?.jsonPrimitive?.longOrNull ?: d.maxSteps,
                    l?.get("timeoutMs")?.jsonPrimitive?.longOrNull ?: d.timeoutMs,
                    l?.get("maxAllocBytes")?.jsonPrimitive?.longOrNull ?: d.maxAllocBytes,
                )
                val c = r["caller"]?.jsonObject
                val caller = ScriptCaller(c?.get("id")?.jsonPrimitive?.contentOrNull.orEmpty(), c?.get("role")?.jsonPrimitive?.contentOrNull.orEmpty())
                @Suppress("UNCHECKED_CAST")
                val input = (fromJson(r["input"] ?: JsonNull) as? Map<String, Any?>).orEmpty()
                encode(engine.run(r["source"]?.jsonPrimitive?.contentOrNull.orEmpty(), input, limits, caller)).toString()
            }
            else -> error("unknown operation $op")
        }
    }

    /** An engine reached through its endpoint; null when its `describe` answer is unusable. */
    fun remote(endpoint: ServiceEndpoint): ScriptEngine? = runCatching {
        val d = Json.parseToJsonElement(endpoint.call("describe", "")).jsonObject
        val engineId = d["id"]!!.jsonPrimitive.content
        val ext = d["extensions"]?.jsonArray?.map { it.jsonPrimitive.content.lowercase() }.orEmpty()
        object : ScriptEngine {
            override val id = engineId
            override val extensions = ext
            override val binding: String get() = runCatching { endpoint.call("binding", "") }.getOrDefault("")
            override fun check(source: String): String? = runCatching { endpoint.call("check", source).takeIf { it.isNotEmpty() } }.getOrNull()
            override fun run(source: String, input: Map<String, Any?>, limits: ScriptLimits, caller: ScriptCaller): ScriptResult = try {
                val req = buildJsonObject {
                    put("source", source)
                    put("input", toJson(input))
                    put("limits", buildJsonObject { put("maxSteps", limits.maxSteps); put("timeoutMs", limits.timeoutMs); put("maxAllocBytes", limits.maxAllocBytes) })
                    put("caller", buildJsonObject { put("id", caller.id); put("role", caller.role) })
                }
                decode(Json.parseToJsonElement(endpoint.call("run", req.toString())).jsonObject)
            } catch (e: Exception) {
                ScriptResult(ScriptOutcome.ERROR, error = "engine $engineId failed: ${(e.message ?: e.javaClass.simpleName).take(300)}")
            }
        }
    }.getOrNull()

    private fun encode(r: ScriptResult): JsonObject = buildJsonObject {
        put("outcome", r.outcome.name)
        put("actions", JsonArray(r.actions.map { a -> buildJsonObject { put("name", a.name); put("args", toJson(a.args)) } }))
        put("logs", JsonArray(r.logs.map(::JsonPrimitive)))
        r.error?.let { put("error", it) }
    }

    private fun decode(o: JsonObject): ScriptResult = ScriptResult(
        ScriptOutcome.valueOf(o["outcome"]!!.jsonPrimitive.content),
        o["actions"]?.jsonArray?.map { a ->
            val ao = a.jsonObject
            @Suppress("UNCHECKED_CAST")
            QueuedAction(ao["name"]!!.jsonPrimitive.content, (fromJson(ao["args"] ?: JsonNull) as? Map<String, Any?>).orEmpty())
        }.orEmpty(),
        o["logs"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
        o["error"]?.jsonPrimitive?.contentOrNull,
    )

    fun toJson(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is Boolean -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        is Map<*, *> -> JsonObject(v.entries.associate { it.key.toString() to toJson(it.value) })
        is Iterable<*> -> JsonArray(v.map(::toJson))
        else -> JsonPrimitive(v.toString())
    }

    fun fromJson(e: JsonElement): Any? = when (e) {
        is JsonNull -> null
        is JsonObject -> e.mapValues { fromJson(it.value) }
        is JsonArray -> e.map(::fromJson)
        is JsonPrimitive -> if (e.isString) e.content else e.booleanOrNull ?: e.longOrNull ?: e.doubleOrNull
    }
}
