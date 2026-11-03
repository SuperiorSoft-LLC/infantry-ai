// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.plugin

import com.superiorsoftllc.infantry.plugin.api.DaemonReply
import com.superiorsoftllc.infantry.ui.net.HttpServerUpdateApi
import com.superiorsoftllc.infantry.ui.net.createPlatformHttpClient
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
 * PLUGINS-1 / PLUGIN-ENDPOINTS-1 (D2a): the host's raw way to the daemon the window is connected to (was the public PluginDaemon). A module never calls it directly: its
 * `daemon` service wraps it in the route gate (declared routes, and the demo lock's). The window sets [connect].
 */
internal class DaemonLink {
    companion object {
        /** The window's link. Internal, and hidden from plugin jars by [PluginClassLoader]; only the DAEMON service factory and the host read it. */
        internal val window = DaemonLink()
    }

    /** DV-2: the controller's views service; null until a window has set it. */
    @kotlin.concurrent.Volatile var views: com.superiorsoftllc.infantry.plugin.api.ViewAccess? = null

    /**
     * PLUGIN-ENDPOINTS-2 (D3): reads one field of `plugin.<module>` over the window's owner connection (`plugin_secret_get`).
     * Only a module's EndpointAccess calls it, and it keeps the value in its own closure. Null until a window sets it.
     */
    @kotlin.concurrent.Volatile
    var pluginSecret: (suspend (module: String, field: String) -> Result<String?>)? = null

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

/** DV-2: the views service handed to a module before a window exists; each call goes to the window's current service. */
class DelegatingViews(private val target: () -> com.superiorsoftllc.infantry.plugin.api.ViewAccess?) : com.superiorsoftllc.infantry.plugin.api.ViewAccess {
    override suspend fun list() = target()?.list().orEmpty()
    override fun open(module: String, view: String, params: kotlinx.serialization.json.JsonObject) =
        target()?.open(module, view, params) ?: kotlinx.coroutines.flow.flowOf(com.superiorsoftllc.infantry.plugin.api.ViewState.Error("not connected to the daemon"))
    override suspend fun act(origin: com.superiorsoftllc.infantry.plugin.api.ViewOrigin, tool: String, args: kotlinx.serialization.json.JsonObject) =
        target()?.act(origin, tool, args) ?: Result.failure(IllegalStateException("not connected to the daemon"))
}
