// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.plugin

import com.superiorsoftllc.infantry.plugin.api.EndpointAuth
import com.superiorsoftllc.infantry.plugin.api.EndpointDeclaration
import com.superiorsoftllc.infantry.plugin.api.EndpointReply
import com.superiorsoftllc.infantry.plugin.api.EndpointSetting
import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.plugin.api.ModuleSetting
import com.superiorsoftllc.infantry.plugin.api.ModulesConfig
import com.superiorsoftllc.infantry.plugin.api.Tier
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** PLUGIN-ENDPOINTS-1 D2: a call reaches the owner-overridden backend with the plugin's headers minus Authorization. */
class JvmEndpointAccessTest {
    @Test
    fun `endpoint call strips Authorization and does not follow redirects`() = runBlocking {
        var seenAuth: String? = "unset"; var seenOther: String? = null
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/hi") { ex ->
            seenAuth = ex.requestHeaders.getFirst("Authorization"); seenOther = ex.requestHeaders.getFirst("X-Thing")
            val b = "hello".toByteArray(); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) }
        }
        server.createContext("/v1/moved") { ex -> ex.responseHeaders.add("Location", "/v1/hi"); ex.sendResponseHeaders(302, -1); ex.close() }
        server.start()
        try {
            val m = ModuleManifest("Acme_Hello", "1.0.0", services = listOf("endpoints"), endpoints = listOf(EndpointDeclaration("api", "https://api.example.com/v1/**")))
            val cfg = ModulesConfig(modules = mapOf("Acme_Hello" to ModuleSetting(endpoints = mapOf("api" to EndpointSetting("http://127.0.0.1:${server.address.port}/v1/**")))))
            val access = JvmEndpointAccess(m, Tier.CORE, cfg, false)
            val ok = access.call("api", "GET", "/v1/hi", null, mapOf("Authorization" to "Bearer secret", "X-Thing" to "1"))
            assertEquals("hello", (ok as EndpointReply.Ok).body)
            assertEquals(null, seenAuth); assertEquals("1", seenOther)
            assertEquals(302, (access.call("api", "GET", "/v1/moved") as EndpointReply.Ok).status)
        } finally { server.stop(0) }
    }

    /** PLUGIN-ENDPOINTS-2 D3: a bearer endpoint gets the `plugin.<module>` field from the host, not from the plugin's headers. */
    @Test
    fun `bearer endpoint carries the keystore field the host fetched`() = runBlocking {
        var seenAuth: String? = null
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/echo") { ex ->
            seenAuth = ex.requestHeaders.getFirst("Authorization")
            val b = "ok".toByteArray(); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) }
        }
        server.start()
        try {
            val m = ModuleManifest("Acme_Echo", "1.0.0", services = listOf("endpoints"),
                endpoints = listOf(EndpointDeclaration("echo", "http://127.0.0.1:${server.address.port}/**", auth = EndpointAuth("bearer", "apiKey"))))
            val asked = mutableListOf<String>()
            val access = JvmEndpointAccess(m, Tier.CORE, ModulesConfig(), false, secret = { mod, f -> asked += "$mod/$f"; Result.success("k-123") })
            assertTrue(access.call("echo", "GET", "/echo", null, mapOf("Authorization" to "Bearer forged")) is EndpointReply.Ok)
            assertEquals("Bearer k-123", seenAuth)
            assertEquals(listOf("Acme_Echo/apiKey"), asked)
        } finally { server.stop(0) }
    }
}
