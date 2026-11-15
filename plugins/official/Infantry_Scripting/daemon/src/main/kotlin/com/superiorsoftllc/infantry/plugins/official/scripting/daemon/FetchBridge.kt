// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.scripting.daemon

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import java.net.URI

/** SCRIPT-FETCH-1: the guard and limits of the HTTP bridge (plugin settings `scripting.fetch.*`); a bad value falls back to the default. */
data class FetchSettings(val enabled: Boolean, val allow: List<String>, val maxBytes: Int, val defaultTimeoutSec: Long, val maxTimeoutSec: Long) {
    companion object {
        fun read(config: (String) -> String?): FetchSettings {
            fun num(key: String, range: LongRange, d: Long) = config(key)?.trim()?.toLongOrNull()?.takeIf { it in range } ?: d
            val max = num("scripting.fetch.maxTimeoutSec", 1L..600L, 60L)
            return FetchSettings(
                config("scripting.fetch.enabled")?.trim()?.lowercase() == "true", ExecSettings.list(config("scripting.fetch.allow")),
                num("scripting.fetch.maxBytes", 1_024L..50_000_000L, 1_000_000L).toInt(),
                num("scripting.fetch.defaultTimeoutSec", 1L..600L, 15L).coerceAtMost(max), max,
            )
        }
    }
}

/**
 * SCRIPT-FETCH-1: capability `http.fetch`. Off by default; only a URL the allow-list names (a host `example.com`, a wildcard
 * `*.example.com`, or a URL prefix `https://api.example.com/v1/`) is fetched. Redirects are not followed (a redirect target could leave
 * the list); the response body is capped; secret header values come from the secrets store by name and never appear in a result or log.
 */
class FetchBridge(
    private val config: (String) -> String?,
    /** Secret [name] for [project] as a field map, or null. */
    private val secret: (name: String, project: String?) -> Map<String, String>?,
    private val log: (String) -> Unit,
    /** HTTP-SEAM-1: `DaemonApi.http`. This bridge is a front door to it: the allow-list and the script-facing shape stay here. */
    private val http: com.superiorsoftllc.infantry.daemon.api.http.Http,
) {

    fun fetch(args: JsonObject, project: String?): JsonElement {
        val s = FetchSettings.read(config)
        if (!s.enabled) throw IllegalStateException("http.fetch is off. Enable it with the setting scripting.fetch.enabled = true and list hosts in scripting.fetch.allow")
        val url = str(args["url"]) ?: throw IllegalArgumentException("url is required")
        val uri = runCatching { URI(withQuery(url, args["query"])) }.getOrNull()?.takeIf { it.scheme.equals("http", true) || it.scheme.equals("https", true) } ?: throw IllegalArgumentException("url must be an http(s) URL")
        if (!allowed(s.allow, uri)) throw IllegalStateException("${uri.host} is not in the allow-list scripting.fetch.allow")
        val method = (str(args["method"]) ?: "GET").uppercase()
        if (method !in METHODS) throw IllegalArgumentException("method must be one of ${METHODS.joinToString(", ")}")
        val timeout = ((args["timeoutSec"] as? JsonPrimitive)?.doubleOrNull?.toLong() ?: s.defaultTimeoutSec).coerceIn(1L, s.maxTimeoutSec)
        val secretHeaders = LinkedHashMap<String, String>()
        val headers = LinkedHashMap<String, String>()
        (args["headers"] as? JsonObject)?.forEach { (k, v) -> str(v)?.let { headers[k] = it } }
        (args["secretHeaders"] as? JsonObject)?.forEach { (header, ref) ->
            val name = str(ref) ?: throw IllegalArgumentException("secretHeaders.$header must be a secret name")
            val fields = secret(name, project) ?: throw IllegalArgumentException("no secret '$name'")
            secretHeaders[header] = fields["value"] ?: fields.values.singleOrNull() ?: throw IllegalArgumentException("secret '$name' has no single value field")
        }
        val body = (args["body"] as? JsonPrimitive)?.contentOrNull ?: args["body"]?.takeIf { it !is JsonNull }?.toString()
        // Redirects are not followed (a redirect target could leave the allow-list) and a script call is never retried.
        val r = http.request(com.superiorsoftllc.infantry.daemon.api.http.HttpSpec(
            url = uri.toString(), method = method, headers = headers, secretHeaders = secretHeaders,
            body = if (method in BODY_METHODS) body.orEmpty() else null,
            timeoutMs = timeout * MS_PER_SECOND, maxBytes = s.maxBytes.toLong(), maxRedirects = 0,
            retry = com.superiorsoftllc.infantry.daemon.api.http.RetryPolicy.NONE,
        ))
        log("http.fetch $method ${uri.scheme}://${uri.host}${uri.path.orEmpty()} -> ${r.status} ms=${r.elapsedMs}")
        return buildJsonObject {
            put("status", r.status)
            put("headers", buildJsonObject { r.headers.forEach { (k, v) -> put(k, v) } })
            put("body", r.body)
            put("truncated", r.truncated)
            if (!r.truncated) runCatching { Json.parseToJsonElement(r.body) }.getOrNull()?.takeIf { it is JsonObject || it is kotlinx.serialization.json.JsonArray }?.let { put("json", it) }
        }
    }

    /** Appends [query] (name -> string, number, boolean or a list of them) URL-encoded to [url], keeping any query already in it. */
    private fun withQuery(url: String, query: JsonElement?): String {
        val obj = query as? JsonObject ?: return url
        val pairs = ArrayList<String>()
        fun enc(v: String) = java.net.URLEncoder.encode(v, Charsets.UTF_8)
        obj.forEach { (k, v) ->
            val values = if (v is kotlinx.serialization.json.JsonArray) v.toList() else listOf(v)
            values.forEach { x -> (x as? JsonPrimitive)?.takeIf { x !is JsonNull }?.let { pairs += "${enc(k)}=${enc(it.content)}" } }
        }
        if (pairs.isEmpty()) return url
        val hash = url.indexOf('#')
        val base = if (hash >= 0) url.substring(0, hash) else url
        val frag = if (hash >= 0) url.substring(hash) else ""
        return base + (if (base.contains('?')) (if (base.endsWith("?") || base.endsWith("&")) "" else "&") else "?") + pairs.joinToString("&") + frag
    }

    private fun str(e: JsonElement?): String? = (e as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    companion object {
        const val ID = "http.fetch"
        private const val MS_PER_SECOND = 1_000L
        val METHODS = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")
        private val BODY_METHODS = setOf("POST", "PUT", "PATCH", "DELETE")

        /** An entry with `://` is a URL prefix; `*.host` matches subdomains; otherwise the host must equal it. An empty list allows nothing. */
        fun allowed(allow: List<String>, uri: URI): Boolean {
            val host = uri.host?.lowercase() ?: return false
            val url = uri.toString()
            return allow.any { e ->
                val p = e.trim()
                when {
                    p.contains("://") -> url.startsWith(p)
                    p.startsWith("*.") -> host.endsWith(p.drop(1).lowercase())
                    else -> host == p.lowercase()
                }
            }
        }
    }
}
