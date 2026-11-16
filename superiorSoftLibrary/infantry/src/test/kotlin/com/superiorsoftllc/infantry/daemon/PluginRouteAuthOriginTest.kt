// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.daemon.api.DaemonApi
import com.superiorsoftllc.infantry.daemon.api.DaemonPlugin
import com.superiorsoftllc.infantry.daemon.api.HttpCall
import com.superiorsoftllc.infantry.daemon.api.HttpReply
import com.superiorsoftllc.infantry.daemon.api.RouteCheck
import com.superiorsoftllc.infantry.daemon.plugins.DaemonPluginHost
import com.superiorsoftllc.infantry.plugin.api.DaemonCapabilities
import com.superiorsoftllc.infantry.plugin.api.DaemonHttpDeclaration
import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.plugin.api.ModuleSetting
import com.superiorsoftllc.infantry.plugin.api.Tier
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PLUGIN-AUTH-ORIGIN-1: a module's `daemon.http` opt-ins on its PUBLIC routes, over a real socket through the daemon's own access
 * policy intercept and plugin-route dispatch ([installAccessPolicy], [answerPluginRoute]):
 * - `passAuthorization`: the handler receives Authorization only when its module opted in; the daemon's token is refused there and
 *   still stripped from a route behind the token;
 * - `allowedOrigins`: a listed origin is admitted (with CORS headers) and its preflight answered; an unlisted one stays 403;
 * - a module without the block behaves exactly as before; `allowNonLoopbackHost` skips only the Host check.
 */
class PluginRouteAuthOriginTest {
    private val token = "owner-token-owner-token-owner-token-0123"
    private val allowed = "http://localhost:5173"
    private val hosts = mutableListOf<DaemonPluginHost>()
    private val servers = mutableListOf<EmbeddedServer<*, *>>()
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    @AfterTest fun cleanup() {
        servers.forEach { runCatching { it.stop(0, 0) } }
        hosts.forEach { runCatching { it.close() } }
    }

    /** Echoes whether the handler saw an Authorization header. `echo` is public (own check: pass), `inside` is behind the token. */
    private class Echo : DaemonPlugin {
        override fun start(api: DaemonApi) {
            val echo = { call: HttpCall ->
                val auth = call.headers.entries.firstOrNull { it.key.equals("Authorization", ignoreCase = true) }?.value
                HttpReply(body = "auth=${auth ?: "none"}")
            }
            api.routes.handle("POST", "echo", RouteCheck { null }, echo)
            api.routes.handle("GET", "inside", echo)
        }
        override fun stop() {}
    }

    private fun manifest(name: String, http: DaemonHttpDeclaration) = ModuleManifest(
        name = name, version = "1.0.0", license = "Apache-2.0",
        daemon = DaemonCapabilities(publicRoutes = listOf("POST /plugins/$name/echo"), http = http),
    )

    private val opened = DaemonHttpDeclaration(passAuthorization = true, allowedOrigins = listOf(allowed))

    /** The daemon's HTTP front (token daemon on loopback) with Acme_Open (opted in) and Acme_Closed (no block); its port. */
    private fun serve(): Int {
        val h = DaemonPluginHost.empty().also { hosts += it }
        assertEquals("loaded", h.startInProcess(manifest("Acme_Open", opened), Tier.OFFICIAL, ModuleSetting(), Echo()))
        assertEquals("loaded", h.startInProcess(manifest("Acme_Closed", DaemonHttpDeclaration()), Tier.OFFICIAL, ModuleSetting(), Echo()))
        val policy = AccessPolicy.of("127.0.0.1", token, emptyList()) { emptySet() }
        val server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            installAccessPolicy(policy, publicRoute = h::isPublicRoute, routeOpenings = h::routeOpenings)
            routing { route("${DaemonPluginHost.PLUGIN_PREFIX}{module}/{path...}") { handle { answerPluginRoute(call, h) } } }
        }.start(wait = false)
        servers += server
        return runBlocking { server.engine.resolvedConnectors().first().port }
    }

    private fun send(port: Int, method: String, path: String, vararg headers: Pair<String, String>): HttpResponse<String> {
        val b = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).timeout(Duration.ofSeconds(5))
            .method(method, if (method == "POST") HttpRequest.BodyPublishers.ofString("{}") else HttpRequest.BodyPublishers.noBody())
        headers.forEach { (k, v) -> b.header(k, v) }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString())
    }

    @Test fun `a public route receives Authorization only when its module opted in`() {
        val port = serve()
        val open = send(port, "POST", "/plugins/Acme_Open/echo", "Authorization" to "Bearer customer-key")
        assertEquals(200, open.statusCode(), open.body())
        assertEquals("auth=Bearer customer-key", open.body())
        val closed = send(port, "POST", "/plugins/Acme_Closed/echo", "Authorization" to "Bearer customer-key")
        assertEquals(200, closed.statusCode(), closed.body())
        assertEquals("auth=none", closed.body(), "default off: stripped exactly as before")
    }

    @Test fun `the daemon's own token is refused on an opted-in route and still stripped behind the token`() {
        val port = serve()
        val refused = send(port, "POST", "/plugins/Acme_Open/echo", "Authorization" to "Bearer $token")
        assertEquals(403, refused.statusCode())
        assertFalse(token in refused.body(), "a refusal never echoes the token")
        // A route behind the daemon's token (not public): the token admits it and the handler never sees it, opt-in or not.
        val inside = send(port, "GET", "/plugins/Acme_Open/inside", "Authorization" to "Bearer $token")
        assertEquals(200, inside.statusCode(), inside.body())
        assertEquals("auth=none", inside.body())
        assertEquals(401, send(port, "GET", "/plugins/Acme_Open/inside", "Authorization" to "Bearer customer-key").statusCode())
    }

    @Test fun `a listed origin is admitted with CORS headers and an unlisted one stays refused`() {
        val port = serve()
        val ok = send(port, "POST", "/plugins/Acme_Open/echo", "Origin" to allowed)
        assertEquals(200, ok.statusCode(), ok.body())
        assertEquals(allowed, ok.headers().firstValue("Access-Control-Allow-Origin").orElse(null))
        assertEquals(403, send(port, "POST", "/plugins/Acme_Open/echo", "Origin" to "http://evil.example").statusCode())
        assertEquals(403, send(port, "POST", "/plugins/Acme_Closed/echo", "Origin" to allowed).statusCode(), "default off: Origin refused")
        // The daemon's own routes and token-protected plugin routes keep refusing any Origin.
        assertEquals(403, send(port, "GET", "/plugins/Acme_Open/inside", "Origin" to allowed, "Authorization" to "Bearer $token").statusCode())
    }

    @Test fun `a preflight is answered for exactly a listed origin`() {
        val port = serve()
        val pre = send(port, "OPTIONS", "/plugins/Acme_Open/echo", "Origin" to allowed,
            "Access-Control-Request-Method" to "POST", "Access-Control-Request-Headers" to "authorization, content-type")
        assertEquals(204, pre.statusCode(), pre.body())
        assertEquals(allowed, pre.headers().firstValue("Access-Control-Allow-Origin").orElse(null))
        assertEquals("POST", pre.headers().firstValue("Access-Control-Allow-Methods").orElse(null))
        assertEquals("authorization, content-type", pre.headers().firstValue("Access-Control-Allow-Headers").orElse(null))
        val evil = send(port, "OPTIONS", "/plugins/Acme_Open/echo", "Origin" to "http://evil.example", "Access-Control-Request-Method" to "POST")
        assertEquals(403, evil.statusCode())
        assertTrue(evil.headers().firstValue("Access-Control-Allow-Origin").isEmpty)
        assertEquals(403, send(port, "OPTIONS", "/plugins/Acme_Closed/echo", "Origin" to allowed, "Access-Control-Request-Method" to "POST").statusCode())
        assertEquals(403, send(port, "OPTIONS", "/plugins/Acme_Open/inside", "Origin" to allowed, "Access-Control-Request-Method" to "GET").statusCode(),
            "a route behind the token never answers a preflight")
    }

    @Test fun `the policy alone keeps the defaults and allowNonLoopbackHost skips only the Host check`() {
        val tokenless = AccessPolicy.of("127.0.0.1", null, emptyList()) { emptySet() }
        val path = "/plugins/Acme_Open/echo"
        // No openings: exactly as before (a public route is checked like a bundle file).
        assertEquals(HttpStatusCode.Forbidden, tokenless.check(path, "api.example.com", null, null, publicRoute = true)?.status)
        assertEquals(HttpStatusCode.Forbidden, tokenless.check(path, "127.0.0.1:1", allowed, null, publicRoute = true)?.status)
        // Openings are ignored on a route that is not public.
        assertEquals(HttpStatusCode.Forbidden, tokenless.check(path, "127.0.0.1:1", allowed, null, openings = RouteOpenings(setOf(allowed)))?.status)
        val anyHost = RouteOpenings(anyHost = true)
        assertNull(tokenless.check(path, "api.example.com", null, null, publicRoute = true, openings = anyHost))
        assertEquals(HttpStatusCode.Forbidden, tokenless.check(path, "api.example.com", "http://evil.example", null, publicRoute = true, openings = anyHost)?.status,
            "any Host does not open any Origin")
        assertNull(tokenless.check(path, "127.0.0.1:1", "HTTP://LOCALHOST:5173/", null, publicRoute = true, openings = RouteOpenings(setOf(allowed))))
    }

    @Test fun `origins are normalised and a wildcard or path is not an origin`() {
        assertEquals("http://localhost:5173", AccessPolicy.normalizeOrigin("HTTP://LocalHost:5173/"))
        assertEquals("https://app.example.com", AccessPolicy.normalizeOrigin("https://app.example.com:443"))
        for (bad in listOf("*", "https://*.example.com", "null", "file://", "https://a.example.com/path", "ftp://a.example.com", "https://u@a.example.com", "")) {
            assertNull(AccessPolicy.normalizeOrigin(bad), bad)
        }
        val h = DaemonPluginHost.empty().also { hosts += it }
        val status = h.startInProcess(manifest("Acme_Bad", DaemonHttpDeclaration(allowedOrigins = listOf("*"))), Tier.OFFICIAL, ModuleSetting(), Echo()).orEmpty()
        assertTrue(status.startsWith("refused") && "allowedOrigins" in status, status)
    }
}
