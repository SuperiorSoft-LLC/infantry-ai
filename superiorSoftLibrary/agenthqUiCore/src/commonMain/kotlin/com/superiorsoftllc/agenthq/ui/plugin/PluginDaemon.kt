package com.superiorsoftllc.agenthq.ui.plugin

import com.superiorsoftllc.agenthq.plugin.api.DaemonReply
import com.superiorsoftllc.agenthq.ui.net.HttpServerUpdateApi
import com.superiorsoftllc.agenthq.ui.net.createPlatformHttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException

/**
 * PLUGINS-1: the plugins' raw way to the daemon the window is connected to. A module never calls it directly: its
 * `daemon` service wraps it in the route gate (declared routes, and the demo lock's). The window sets [connect].
 */
object PluginDaemon {
    private var base: String? = null
    private var token: String? = null
    private val http by lazy { createPlatformHttpClient() }

    fun connect(wsUrl: String, token: String?) {
        base = HttpServerUpdateApi.httpBase(wsUrl)
        this.token = token
    }

    /** [route] is `METHOD /path`. */
    suspend fun send(route: String, body: String?): DaemonReply {
        val base = base ?: return DaemonReply.Failed("not connected")
        val method = route.substringBefore(' ').uppercase()
        val path = route.substringAfter(' ', "").takeIf { it.startsWith("/") } ?: return DaemonReply.Failed("route '$route' is not METHOD /path")
        return try {
            val reply = http.request(base + path) {
                this.method = HttpMethod.parse(method)
                token?.takeIf { it.isNotBlank() }?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
            }
            DaemonReply.Ok(reply.status.value, reply.bodyAsText())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DaemonReply.Failed("could not reach the daemon (${e::class.simpleName ?: "error"})")
        }
    }
}
