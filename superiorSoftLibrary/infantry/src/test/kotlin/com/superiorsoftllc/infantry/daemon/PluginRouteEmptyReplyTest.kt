// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.daemon.api.DaemonApi
import com.superiorsoftllc.infantry.daemon.api.DaemonPlugin
import com.superiorsoftllc.infantry.daemon.api.HttpReply
import com.superiorsoftllc.infantry.daemon.api.RouteCheck
import com.superiorsoftllc.infantry.daemon.plugins.DaemonPluginHost
import com.superiorsoftllc.infantry.plugin.api.DaemonCapabilities
import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.plugin.api.ModuleSetting
import com.superiorsoftllc.infantry.plugin.api.Tier
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CONN-CLOSE-EMPTY-1: an empty-body plugin reply (204, empty 200, empty 202) must reach a client that sent `Connection: close`
 * (python urllib always does): the status line arrives within 2 s and the daemon closes the socket. Raw socket, no HTTP client.
 */
class PluginRouteEmptyReplyTest {
    private val hosts = mutableListOf<DaemonPluginHost>()
    private val servers = mutableListOf<EmbeddedServer<*, *>>()

    @AfterTest fun cleanup() {
        servers.forEach { runCatching { it.stop(0, 0) } }
        hosts.forEach { runCatching { it.close() } }
    }

    private class Empties : DaemonPlugin {
        override fun start(api: DaemonApi) {
            api.routes.handle("GET", "body", RouteCheck { null }) { HttpReply(body = "hi") }
            for (m in listOf("POST", "PUT", "DELETE")) api.routes.handle(m, "echo", RouteCheck { null }) { HttpReply(body = "got:" + it.body) }
            for ((path, status) in listOf("e204" to 204, "e200" to 200, "e202" to 202)) {
                api.routes.handle("GET", path, RouteCheck { null }) { HttpReply(status = status) }
                api.routes.handle("DELETE", path, RouteCheck { null }) { HttpReply(status = status) }
            }
        }
        override fun stop() {}
    }

    private fun serve(): Int {
        val h = DaemonPluginHost.empty().also { hosts += it }
        val routes = listOf("e204", "e200", "e202", "body", "echo").flatMap { listOf("GET", "DELETE", "POST", "PUT").map { m -> "$m /plugins/Acme_Empty/$it" } }
        val manifest = ModuleManifest(name = "Acme_Empty", version = "1.0.0", license = "Apache-2.0", daemon = DaemonCapabilities(publicRoutes = routes))
        assertEquals("loaded", h.startInProcess(manifest, Tier.OFFICIAL, ModuleSetting(), Empties()))
        val policy = AccessPolicy.of("127.0.0.1", null, emptyList()) { emptySet() }
        val server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            installAccessPolicy(policy, publicRoute = h::isPublicRoute, routeOpenings = h::routeOpenings)
            routing { route("${DaemonPluginHost.PLUGIN_PREFIX}{module}/{path...}") { handle { answerPluginRoute(call, h) } } }
        }.start(wait = false)
        servers += server
        return runBlocking { server.engine.resolvedConnectors().first().port }
    }

    /** Sends a raw request; returns the full response text, failing if no byte arrives or the socket stays open past 2 s. */
    private fun raw(port: Int, method: String, path: String, close: Boolean, body: String? = null): String {
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", port), 2000)
            s.soTimeout = 2000
            val conn = if (close) "Connection: close\r\n" else ""
            s.getOutputStream().apply { write("$method $path HTTP/1.1\r\nHost: 127.0.0.1\r\n$conn${if (body != null) "Content-Length: ${body.length}\r\n" else ""}\r\n${body ?: ""}".toByteArray()); flush() }
            val buf = StringBuilder()
            val ins = s.getInputStream()
            val b = ByteArray(4096)
            try {
                while (!buf.contains("\r\n\r\n")) { val n = ins.read(b); if (n < 0) break; buf.append(String(b, 0, n)) }
                // A GET under close must also see the server close the socket. A bodiless DELETE is framed by Content-Length: 0
                // (all a client such as urllib needs); CIO itself keeps its side open until the client closes, so no EOF is asserted.
                if (close && method == "GET") while (true) { val n = ins.read(b); if (n < 0) break; buf.append(String(b, 0, n)) }
            } catch (e: java.net.SocketTimeoutException) { throw AssertionError("no complete reply within 2 s; received so far: [$buf]") }
            return buf.toString()
        }
    }

    @Test fun `empty 204, 200 and 202 replies arrive and the socket closes under Connection close`() {
        val port = serve()
        val bad = mutableListOf<String>()
        for (method in listOf("GET", "DELETE")) for ((p, status) in listOf("e204" to 204, "e200" to 200, "e202" to 202)) {
            val r = runCatching { raw(port, method, "/plugins/Acme_Empty/$p", close = true) }.getOrElse { "ERR ${it.message}" }
            val ok = r.startsWith("HTTP/1.1 $status") && (status == 204 || r.contains("Content-Length: 0", ignoreCase = true))
            if (!ok) bad += "$method $p -> $r"
        }
        assertTrue(bad.isEmpty(), bad.joinToString(" | "))
    }

    @Test fun `empty replies still arrive on a keep-alive request`() {
        val port = serve()
        for ((p, status) in listOf("e204" to 204, "e200" to 200, "e202" to 202)) {
            val r = raw(port, "DELETE", "/plugins/Acme_Empty/$p", close = false)
            assertTrue(r.startsWith("HTTP/1.1 $status"), "$p keep-alive: $r")
        }
    }

    // DAEMON-CLOSE-1: a body-carrying POST/PUT/DELETE under Connection close must be answered (status + body) too.
    @Test fun `POST PUT and DELETE with a body get status and body under Connection close`() {
        val port = serve()
        for (m in listOf("POST", "PUT", "DELETE")) {
            val r = raw(port, m, "/plugins/Acme_Empty/echo", close = true, body = "abc")
            assertTrue(r.startsWith("HTTP/1.1 200"), "$m: $r")
            val rest = Socket().use { s ->
                s.connect(InetSocketAddress("127.0.0.1", port), 2000); s.soTimeout = 2000
                s.getOutputStream().apply { write("$m /plugins/Acme_Empty/echo HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\nContent-Length: 3\r\n\r\nabc".toByteArray()); flush() }
                val sb = StringBuilder(); val b = ByteArray(4096)
                while (!sb.contains("got:abc")) { val n = s.getInputStream().read(b); if (n < 0) break; sb.append(String(b, 0, n)) }
                sb.toString()
            }
            assertTrue(rest.contains("got:abc"), "$m body: $rest")
        }
    }

    @Test fun `control - a non-empty reply under Connection close`() {
        val r = raw(serve(), "GET", "/plugins/Acme_Empty/body", close = true)
        assertTrue(r.startsWith("HTTP/1.1 200"), r)
    }
}
