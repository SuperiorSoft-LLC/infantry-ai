package com.superiorsoftllc.agenthq.ui.net

import com.superiorsoftllc.agenthq.ui.model.ServerUpdateApi
import com.superiorsoftllc.agenthq.ui.model.ServerUpdateStatusWire
import com.superiorsoftllc.agenthq.ui.model.UpdateApiResult
import com.superiorsoftllc.agenthq.ui.model.UpdateApplyReplyWire
import com.superiorsoftllc.agenthq.ui.model.UpdateApplyRequestWire
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

/**
 * SERVER-UPDATE-4: the daemon update routes (`/update/status`, `check`, `apply`) (contract 1.110.0), called on the same host and port as the
 * WebSocket (`ws://h:p/ws` becomes `http://h:p`) with the same bearer token. The token goes only to that host.
 */
class HttpServerUpdateApi(
    wsUrl: String,
    private val token: String?,
    private val http: HttpClient = createPlatformHttpClient(),
) : ServerUpdateApi {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val base: String = httpBase(wsUrl)

    override suspend fun status() = call(HttpMethod.Get, "/update/status", null) { json.decodeFromString(ServerUpdateStatusWire.serializer(), it) }

    override suspend fun check() = call(HttpMethod.Post, "/update/check", "{}") { json.decodeFromString(ServerUpdateStatusWire.serializer(), it) }

    override suspend fun apply(confirm: Boolean) =
        call(HttpMethod.Post, "/update/apply", json.encodeToString(UpdateApplyRequestWire.serializer(), UpdateApplyRequestWire(confirm)), replyStatuses = true) {
            json.decodeFromString(UpdateApplyReplyWire.serializer(), it)
        }

    private suspend fun <T> call(
        method: HttpMethod,
        path: String,
        body: String?,
        replyStatuses: Boolean = false,
        parse: (String) -> T,
    ): UpdateApiResult<T> = try {
        val response = http.request(base + path) {
            this.method = method
            token?.takeIf { it.isNotBlank() }?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
        }
        val text = response.bodyAsText()
        when {
            response.status == HttpStatusCode.NotFound -> UpdateApiResult.Unsupported
            response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden ->
                UpdateApiResult.Failed("The server refused the token (HTTP ${response.status.value}).")
            // /update/apply answers 409 and 502 with a reply body that says why: that is an answer, not a transport failure.
            response.status.value in 200..299 || replyStatuses && response.status.value in listOf(409, 502) -> UpdateApiResult.Ok(parse(text))
            else -> UpdateApiResult.Failed("The server answered HTTP ${response.status.value}.")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        UpdateApiResult.Failed("Could not reach the server (${e::class.simpleName ?: "error"}).")
    }

    companion object {
        /** `ws://h:p/ws` -> `http://h:p`; `wss://` -> `https://`. A URL that is already http(s) loses only a trailing `/ws`. */
        fun httpBase(wsUrl: String): String {
            val u = wsUrl.trim().trimEnd('/')
            val swapped = when {
                u.startsWith("wss://") -> "https://" + u.removePrefix("wss://")
                u.startsWith("ws://") -> "http://" + u.removePrefix("ws://")
                else -> u
            }
            return swapped.removeSuffix("/ws")
        }
    }
}
