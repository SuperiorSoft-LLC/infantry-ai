// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.downloads

import com.superiorsoftllc.infantry.daemon.api.downloads.DownloadResponse
import com.superiorsoftllc.infantry.daemon.api.downloads.DownloadTransport
import com.superiorsoftllc.infantry.settings.LimitDef
import com.superiorsoftllc.infantry.settings.LimitRegistry
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * CORE-PROGRESS-DOWNLOAD-1: the core [DownloadTransport]: HTTP(S) through java.net.http, plus `file:` URLs and plain paths.
 *
 * - Proxy: [proxy] (`downloads.proxy`, `host:port`) when set, else the JVM's [ProxySelector.getDefault] (the
 *   `https.proxyHost` / `http.proxyHost` properties, and the OS proxy with `java.net.useSystemProxies=true`).
 * - Redirects are followed here (up to `limits.downloads.redirects`) so a secret header goes only to the first host.
 * - `Range: bytes=<from>-` asks for the rest when [open] is given an offset; the answer's status says if it was honoured.
 */
class HttpDownloadTransport(
    private val limit: (LimitDef) -> Long = { it.default },
    private val proxy: () -> String = { "" },
) : DownloadTransport {
    @Volatile private var cached: Pair<String, HttpClient>? = null

    private fun client(): HttpClient {
        val key = proxy().trim() + "|" + limit(LimitRegistry.DOWNLOAD_CONNECT_TIMEOUT_SECONDS)
        cached?.takeIf { it.first == key }?.let { return it.second }
        val selector = key.substringBefore('|').takeIf { it.isNotEmpty() }?.let {
            ProxySelector.of(InetSocketAddress(it.substringBeforeLast(':'), it.substringAfterLast(':').toInt()))
        } ?: ProxySelector.getDefault()
        val builder = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(limit(LimitRegistry.DOWNLOAD_CONNECT_TIMEOUT_SECONDS)))
        if (selector != null) builder.proxy(selector)
        val c = builder.build()
        cached = key to c
        return c
    }

    override fun open(url: String, headers: Map<String, String>, secretHeaders: Map<String, String>, from: Long): DownloadResponse {
        val scheme = runCatching { URI(url).scheme }.getOrNull()?.lowercase()
        if (scheme != "http" && scheme != "https") return openFile(if (scheme == "file") File(URI(url)) else File(url), from)
        val first = URI(url)
        var target = first
        val maxRedirects = limit(LimitRegistry.DOWNLOAD_REDIRECTS).toInt()
        var hops = 0
        while (true) {
            val rb = HttpRequest.newBuilder(target).GET().timeout(Duration.ofSeconds(limit(LimitRegistry.DOWNLOAD_RESPONSE_TIMEOUT_SECONDS)))
            for ((k, v) in headers) rb.header(k, v)
            if (target.host.equals(first.host, ignoreCase = true)) for ((k, v) in secretHeaders) rb.header(k, v)
            if (from > 0) rb.header("Range", "bytes=$from-")
            val resp = try {
                client().send(rb.build(), HttpResponse.BodyHandlers.ofInputStream())
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt(); throw IOException("interrupted")
            } catch (io: IOException) {
                throw IOException("could not reach ${target.host} (${io.message ?: io::class.simpleName})")
            }
            val status = resp.statusCode()
            if (status in REDIRECTS) {
                resp.body().close()
                val location = resp.headers().firstValue("Location").orElse(null) ?: throw IOException("HTTP $status from ${target.host} with no Location")
                if (++hops > maxRedirects) throw IOException("more than $maxRedirects redirects from ${first.host}")
                target = target.resolve(location)
                continue
            }
            val length = resp.headers().firstValueAsLong("Content-Length").orElse(-1L)
            val retryAfter = resp.headers().firstValue("Retry-After").orElse(null)?.trim()?.toLongOrNull()?.let { it * 1000L } ?: -1L
            return DownloadResponse(status, length, resp.body(), retryAfter)
        }
    }

    private fun openFile(file: File, from: Long): DownloadResponse {
        if (!file.isFile) return DownloadResponse(NOT_FOUND, -1, java.io.ByteArrayInputStream(ByteArray(0)))
        val ins = file.inputStream()
        if (from > 0) ins.skip(from)
        return DownloadResponse(if (from > 0) PARTIAL else OK, (file.length() - from).coerceAtLeast(0), ins)
    }

    private companion object {
        val REDIRECTS = setOf(301, 302, 303, 307, 308)
        const val OK = 200
        const val PARTIAL = 206
        const val NOT_FOUND = 404
    }
}
