// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.api.http

import kotlinx.serialization.json.JsonElement

/**
 * HTTP-SEAM-1 (DaemonApi 1.61.0, additive, docs/wiki/daemon-plugin-api.md "HTTP client"): one outgoing HTTP call.
 *
 * - [headers] are sent as given. [secretHeaders] (header name -> value, read by the caller with `DaemonApi.secret`) are sent
 *   too but never logged, never put in an error, scrubbed from the response body, and dropped when a redirect leaves the
 *   first host.
 * - [timeoutMs] / [maxBytes] / [maxRedirects]: 0 or null = the daemon's `limits.http.*` setting.
 * - [retry]: null = the daemon's default policy for a GET/HEAD and none for other methods (a POST is not safely repeatable).
 */
data class HttpSpec(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val secretHeaders: Map<String, String> = emptyMap(),
    val body: String? = null,
    val timeoutMs: Long = 0,
    val maxBytes: Long = 0,
    val maxRedirects: Int? = null,
    val retry: RetryPolicy? = null,
) {
    // A secret never reaches a log through a data-class toString.
    override fun toString(): String = "HttpSpec($method $url, headers=${headers.keys}, secretHeaders=${secretHeaders.keys})"
}

/** One answer. [truncated] = the body hit the size cap and was cut. [attempts] counts the tries made. */
data class HttpResult(
    val status: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: String = "",
    val truncated: Boolean = false,
    val attempts: Int = 1,
    val elapsedMs: Long = 0,
) {
    val ok: Boolean get() = status in 200..299
}

/** A call that got no answer (offline, timeout, a bad URL) or whose answer the seam refuses (a non-2xx for [Http.json]). Never carries a secret. */
class HttpException(message: String, val status: Int = 0, cause: Throwable? = null) : RuntimeException(message, cause)

interface Http {
    /** Sends [spec]; a non-2xx answer is a normal [HttpResult]. Throws [HttpException] when there is no answer. */
    fun request(spec: HttpSpec): HttpResult

    /** [request] and parse a 2xx body as JSON. A non-2xx status, a truncated body or bad JSON throws [HttpException]. */
    fun json(spec: HttpSpec): JsonElement
}

/** The default of a host without an HTTP client. */
object NoHttp : Http {
    override fun request(spec: HttpSpec): HttpResult = throw HttpException("this daemon has no HTTP client")
    override fun json(spec: HttpSpec): JsonElement = throw HttpException("this daemon has no HTTP client")
}
