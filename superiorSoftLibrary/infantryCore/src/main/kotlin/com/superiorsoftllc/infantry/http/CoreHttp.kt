// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.http

import com.superiorsoftllc.infantry.daemon.api.http.Backoff
import com.superiorsoftllc.infantry.daemon.api.http.Http
import com.superiorsoftllc.infantry.daemon.api.http.HttpException
import com.superiorsoftllc.infantry.daemon.api.http.HttpResult
import com.superiorsoftllc.infantry.daemon.api.http.HttpSpec
import com.superiorsoftllc.infantry.daemon.api.http.RetryPolicy
import com.superiorsoftllc.infantry.daemon.api.http.Sleeper
import com.superiorsoftllc.infantry.settings.LimitDef
import com.superiorsoftllc.infantry.settings.LimitRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ThreadLocalRandom

/**
 * HTTP-SEAM-1 (docs/wiki/daemon-plugin-api.md "HTTP client"): THE daemon HTTP client behind `DaemonApi.http`.
 * Timeouts, redirects, the response size cap and the retry numbers are the `limits.http.*` settings; the proxy is the
 * `downloads.proxy` setting (else the JVM's). A secret header is never logged, never in an error, scrubbed from the
 * body, and dropped when a redirect leaves the first host. A file download is NOT this: it goes through `Downloads`.
 */
class CoreHttp(
    private val limit: (LimitDef) -> Long = { it.default },
    private val proxy: () -> String = { "" },
    private val sleeper: Sleeper = Sleeper { Thread.sleep(it) },
    private val random: () -> Double = { ThreadLocalRandom.current().nextDouble() },
    private val log: (String) -> Unit = {},
) : Http {
    @Volatile private var cached: Pair<String, HttpClient>? = null

    private fun client(): HttpClient {
        val key = proxy().trim() + "|" + limit(LimitRegistry.HTTP_CONNECT_TIMEOUT_SECONDS)
        cached?.takeIf { it.first == key }?.let { return it.second }
        val selector = key.substringBefore('|').takeIf { it.isNotEmpty() }?.let {
            ProxySelector.of(InetSocketAddress(it.substringBeforeLast(':'), it.substringAfterLast(':').toInt()))
        } ?: ProxySelector.getDefault()
        val builder = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(limit(LimitRegistry.HTTP_CONNECT_TIMEOUT_SECONDS)))
        if (selector != null) builder.proxy(selector)
        return builder.build().also { cached = key to it }
    }

    private fun defaultPolicy() = RetryPolicy(
        maxAttempts = limit(LimitRegistry.HTTP_RETRIES).toInt() + 1,
        baseDelayMs = limit(LimitRegistry.HTTP_BACKOFF_MS),
        maxDelayMs = limit(LimitRegistry.HTTP_BACKOFF_MAX_MS),
    )

    override fun request(spec: HttpSpec): HttpResult {
        val method = spec.method.trim().uppercase()
        val policy = spec.retry ?: if (method == "GET" || method == "HEAD") defaultPolicy() else RetryPolicy.NONE
        val started = System.nanoTime()
        val (result, attempts) = Backoff.run(policy, sleeper, random, statusOf = { r: HttpResult -> r.status }) { once(spec, method) }
        val ms = (System.nanoTime() - started) / NANOS_PER_MILLI
        log("http $method ${safeTarget(spec.url)} -> ${result.status} attempts=$attempts ms=$ms")
        return result.copy(attempts = attempts, elapsedMs = ms)
    }

    override fun json(spec: HttpSpec): JsonElement {
        val r = request(spec)
        if (!r.ok) throw HttpException("HTTP ${r.status} from ${safeTarget(spec.url)}", r.status)
        if (r.truncated) throw HttpException("the answer from ${safeTarget(spec.url)} is larger than limits.http.maxResponseKb", r.status)
        return try { Json.parseToJsonElement(r.body) } catch (e: Exception) {
            throw HttpException("the answer from ${safeTarget(spec.url)} is not JSON", r.status)
        }
    }

    private fun once(spec: HttpSpec, method: String): HttpResult {
        val first = try { URI(spec.url) } catch (e: Exception) { throw HttpException("bad URL") }
        if (first.scheme?.lowercase() !in SCHEMES || first.host.isNullOrBlank()) throw HttpException("url must be an http(s) URL")
        val maxRedirects = (spec.maxRedirects ?: limit(LimitRegistry.HTTP_REDIRECTS).toInt()).coerceAtLeast(0)
        val timeout = (if (spec.timeoutMs > 0) spec.timeoutMs else limit(LimitRegistry.HTTP_TIMEOUT_SECONDS) * MS_PER_SECOND)
        val cap = (if (spec.maxBytes > 0) spec.maxBytes else limit(LimitRegistry.HTTP_MAX_RESPONSE_KB) * KIB).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        var target = first
        var verb = method
        var body = spec.body
        var hops = 0
        while (true) {
            val rb = HttpRequest.newBuilder(target).timeout(Duration.ofMillis(timeout))
            for ((k, v) in spec.headers) rb.header(k, v)
            if (target.host.equals(first.host, ignoreCase = true)) for ((k, v) in spec.secretHeaders) rb.header(k, v)
            rb.method(verb, if (body != null && verb in BODY_METHODS) HttpRequest.BodyPublishers.ofString(body) else HttpRequest.BodyPublishers.noBody())
            val resp = try {
                client().send(rb.build(), HttpResponse.BodyHandlers.ofInputStream())
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt(); throw HttpException("interrupted")
            } catch (e: IOException) {
                throw HttpException("could not reach ${target.host} (${e::class.simpleName})")
            }
            val code = resp.statusCode()
            val location = resp.headers().firstValue("location").orElse(null)
            if (code in REDIRECTS && location != null && maxRedirects > 0) {
                resp.body().close()
                if (++hops > maxRedirects) throw HttpException("more than $maxRedirects redirects from ${first.host}")
                target = target.resolve(location)
                if (target.scheme?.lowercase() !in SCHEMES) throw HttpException("a redirect left http(s)")
                if (code != PRESERVE_METHOD_REDIRECT_307 && code != PRESERVE_METHOD_REDIRECT_308) { verb = "GET"; body = null }
                continue
            }
            val (bytes, truncated) = resp.body().use { readCapped(it, cap) }
            var text = String(bytes, Charsets.UTF_8)
            for (v in spec.secretHeaders.values) if (v.isNotEmpty()) text = text.replace(v, "***")
            val headers = LinkedHashMap<String, String>()
            resp.headers().map().forEach { (k, v) -> headers[k] = v.joinToString(", ") }
            return HttpResult(code, headers, text, truncated)
        }
    }

    private fun readCapped(input: InputStream, cap: Int): Pair<ByteArray, Boolean> {
        val buf = ByteArrayOutputStream()
        val chunk = ByteArray(CHUNK)
        while (buf.size() <= cap) {
            val n = input.read(chunk, 0, minOf(chunk.size, cap + 1 - buf.size()))
            if (n < 0) break
            buf.write(chunk, 0, n)
        }
        val truncated = buf.size() > cap
        return (if (truncated) buf.toByteArray().copyOf(cap) else buf.toByteArray()) to truncated
    }

    /** Scheme, host and path only: a query string can carry a token. */
    private fun safeTarget(url: String): String = runCatching { URI(url).let { "${it.scheme}://${it.host}${it.path.orEmpty()}" } }.getOrDefault("?")

    private companion object {
        val SCHEMES = setOf("http", "https")
        val BODY_METHODS = setOf("POST", "PUT", "PATCH", "DELETE")
        val REDIRECTS = setOf(301, 302, 303, 307, 308)
        const val PRESERVE_METHOD_REDIRECT_307 = 307
        const val PRESERVE_METHOD_REDIRECT_308 = 308
        const val NANOS_PER_MILLI = 1_000_000L
        const val MS_PER_SECOND = 1_000L
        const val KIB = 1_024L
        const val CHUNK = 8_192
    }
}
