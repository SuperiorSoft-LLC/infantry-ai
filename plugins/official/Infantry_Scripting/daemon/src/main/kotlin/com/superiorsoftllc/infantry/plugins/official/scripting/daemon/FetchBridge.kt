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
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

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
) {
    private val client: HttpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(10)).build()

    fun fetch(args: JsonObject, project: String?): JsonElement {
        val s = FetchSettings.read(config)
        if (!s.enabled) throw IllegalStateException("http.fetch is off. Enable it with the setting scripting.fetch.enabled = true and list hosts in scripting.fetch.allow")
        val url = str(args["url"]) ?: throw IllegalArgumentException("url is required")
        val uri = runCatching { URI(url) }.getOrNull()?.takeIf { it.scheme.equals("http", true) || it.scheme.equals("https", true) } ?: throw IllegalArgumentException("url must be an http(s) URL")
        if (!allowed(s.allow, uri)) throw IllegalStateException("${uri.host} is not in the allow-list scripting.fetch.allow")
        val method = (str(args["method"]) ?: "GET").uppercase()
        if (method != "GET" && method != "POST") throw IllegalArgumentException("method must be GET or POST")
        val timeout = ((args["timeoutSec"] as? JsonPrimitive)?.doubleOrNull?.toLong() ?: s.defaultTimeoutSec).coerceIn(1L, s.maxTimeoutSec)
        val secretValues = ArrayList<String>()
        val rb = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(timeout))
        (args["headers"] as? JsonObject)?.forEach { (k, v) -> str(v)?.let { rb.header(k, it) } }
        (args["secretHeaders"] as? JsonObject)?.forEach { (header, ref) ->
            val name = str(ref) ?: throw IllegalArgumentException("secretHeaders.$header must be a secret name")
            val fields = secret(name, project) ?: throw IllegalArgumentException("no secret '$name'")
            val value = fields["value"] ?: fields.values.singleOrNull() ?: throw IllegalArgumentException("secret '$name' has no single value field")
            secretValues += value
            rb.header(header, value)
        }
        val body = (args["body"] as? JsonPrimitive)?.contentOrNull ?: args["body"]?.takeIf { it !is JsonNull }?.toString()
        rb.method(method, if (method == "POST") HttpRequest.BodyPublishers.ofString(body.orEmpty()) else HttpRequest.BodyPublishers.noBody())
        val started = System.nanoTime()
        val resp = client.send(rb.build(), HttpResponse.BodyHandlers.ofInputStream())
        val (text, truncated) = resp.body().use { readCapped(it, s.maxBytes) }
        var out = text
        for (v in secretValues) if (v.isNotEmpty()) out = out.replace(v, "***")
        log("http.fetch $method ${uri.scheme}://${uri.host}${uri.path.orEmpty()} -> ${resp.statusCode()} ms=${(System.nanoTime() - started) / 1_000_000}")
        return buildJsonObject {
            put("status", resp.statusCode())
            put("headers", buildJsonObject { resp.headers().map().forEach { (k, v) -> put(k, v.joinToString(", ")) } })
            put("body", out)
            put("truncated", truncated)
            if (!truncated) runCatching { Json.parseToJsonElement(out) }.getOrNull()?.takeIf { it is JsonObject || it is kotlinx.serialization.json.JsonArray }?.let { put("json", it) }
        }
    }

    private fun readCapped(input: java.io.InputStream, cap: Int): Pair<String, Boolean> {
        val buf = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (buf.size() <= cap) {
            val n = input.read(chunk, 0, minOf(chunk.size, cap + 1 - buf.size()))
            if (n < 0) break
            buf.write(chunk, 0, n)
        }
        val truncated = buf.size() > cap
        val bytes = buf.toByteArray().let { if (truncated) it.copyOf(cap) else it }
        return String(bytes, Charsets.UTF_8) to truncated
    }

    private fun str(e: JsonElement?): String? = (e as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    companion object {
        const val ID = "http.fetch"

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
