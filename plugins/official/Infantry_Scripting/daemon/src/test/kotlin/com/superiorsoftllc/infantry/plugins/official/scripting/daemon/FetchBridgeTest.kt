// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.scripting.daemon

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.net.URI
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** SCRIPT-FETCH-1: the http.fetch bridge against a local server (port 0). */
class FetchBridgeTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/json") { ex -> reply(ex, """{"a":1}""") }
        createContext("/big") { ex -> reply(ex, "x".repeat(5000)) }
        createContext("/slow") { ex -> Thread.sleep(3000); reply(ex, "late") }
        createContext("/echo") { ex ->
            val auth = ex.requestHeaders.getFirst("Authorization").orEmpty()
            val body = ex.requestBody.readBytes().decodeToString()
            reply(ex, "auth=$auth method=${ex.requestMethod} body=$body q=${ex.requestURI.rawQuery.orEmpty()}")
        }
        start()
    }
    private val base = "http://127.0.0.1:${server.address.port}"
    private val settings = HashMap<String, String>().apply { put("scripting.fetch.enabled", "true"); put("scripting.fetch.allow", "127.0.0.1") }
    private val logs = ArrayList<String>()
    private val bridge = FetchBridge({ settings[it] }, { n, _ -> if (n == "tok") mapOf("value" to "s3cr3t-token") else null }, { logs += it })

    private fun reply(ex: com.sun.net.httpserver.HttpExchange, text: String) {
        val b = text.toByteArray(); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) }
    }
    private fun args(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject
    private fun run(json: String) = bridge.fetch(args(json), "p1").jsonObject

    @AfterTest fun stop() = server.stop(0)

    @Test fun offByDefault() {
        settings.remove("scripting.fetch.enabled")
        assertTrue(assertFailsWith<IllegalStateException> { run("""{"url":"$base/json"}""") }.message!!.contains("off"))
    }

    @Test fun emptyAllowListRefusesEverything() {
        settings.remove("scripting.fetch.allow")
        assertTrue(assertFailsWith<IllegalStateException> { run("""{"url":"$base/json"}""") }.message!!.contains("allow-list"))
    }

    @Test fun hostNotListedIsRefused() {
        settings["scripting.fetch.allow"] = "example.com"
        assertFailsWith<IllegalStateException> { run("""{"url":"$base/json"}""") }
    }

    @Test fun allowMatching() {
        val u = URI("https://api.example.com/v1/x")
        assertTrue(FetchBridge.allowed(listOf("*.example.com"), u))
        assertFalse(FetchBridge.allowed(listOf("example.com"), u))
        assertTrue(FetchBridge.allowed(listOf("https://api.example.com/v1/"), u))
        assertFalse(FetchBridge.allowed(listOf("https://api.example.com/v2/"), u))
        assertFalse(FetchBridge.allowed(listOf("*.example.com"), URI("https://evilexample.com/")))
        assertFalse(FetchBridge.allowed(emptyList(), u))
    }

    @Test fun parsesJson() {
        val r = run("""{"url":"$base/json"}""")
        assertEquals(200, r["status"]!!.jsonPrimitive.content.toInt())
        assertEquals("1", r["json"]!!.jsonObject["a"]!!.jsonPrimitive.content)
    }

    @Test fun capsBody() {
        settings["scripting.fetch.maxBytes"] = "2048"
        val r = run("""{"url":"$base/big"}""")
        assertEquals(2048, r["body"]!!.jsonPrimitive.content.length)
        assertEquals("true", r["truncated"]!!.jsonPrimitive.content)
    }

    @Test fun timeoutFails() {
        assertFailsWith<Exception> { run("""{"url":"$base/slow","timeoutSec":1}""") }
    }

    @Test fun secretHeaderIsSentButNeverReturnedOrLogged() {
        val r = run("""{"url":"$base/echo","method":"POST","body":"hi","secretHeaders":{"Authorization":"tok"}}""")
        val body = r["body"]!!.jsonPrimitive.content
        assertEquals("auth=*** method=POST body=hi q=", body)
        assertTrue(logs.none { it.contains("s3cr3t") })
    }

    @Test fun unknownSecretFails() {
        assertFailsWith<IllegalArgumentException> { run("""{"url":"$base/echo","secretHeaders":{"X":"nope"}}""") }
    }

    @Test fun everyMethodIsSent() {
        for (m in listOf("PUT", "PATCH", "DELETE")) {
            val b = run("""{"url":"$base/echo","method":"$m","body":"x"}""")["body"]!!.jsonPrimitive.content
            assertEquals("auth= method=$m body=x q=", b)
        }
        assertEquals("", run("""{"url":"$base/echo","method":"HEAD"}""")["body"]!!.jsonPrimitive.content)
        assertEquals(200, run("""{"url":"$base/echo","method":"head"}""")["status"]!!.jsonPrimitive.content.toInt())
        assertTrue(assertFailsWith<IllegalArgumentException> { run("""{"url":"$base/echo","method":"TRACE"}""") }.message!!.contains("PATCH"))
    }

    @Test fun queryIsEncodedAndAppended() {
        val b = run("""{"url":"$base/echo?a=1","query":{"q":"a b&c","t":["x","y"],"n":2}}""")["body"]!!.jsonPrimitive.content
        assertTrue(b.endsWith("q=a=1&q=a+b%26c&t=x&t=y&n=2"), b)
    }
}
