// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.uidriver

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runDesktopComposeUiTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * UI-DRIVER-1: one round trip through the real API. A small screen is rendered offscreen, the server answers on a free
 * loopback port, and an HTTP client on another thread queries, clicks, types, waits, shoots, hits a missing node (which
 * fails with a shot of the moment) and quits, while the UI thread serves.
 */
@OptIn(ExperimentalTestApi::class)
class UiDriverTest {
    @Test
    fun `a client drives the offscreen UI over HTTP by semantics only`() {
        val out = Files.createTempDirectory("ui-driver-test").toFile()
        val results = mutableMapOf<String, Pair<Int, JsonObject>>()
        runDesktopComposeUiTest(400, 300) {
            setContent {
                var clicks by remember { mutableStateOf(0) }
                var text by remember { mutableStateOf("") }
                Column {
                    Button(onClick = { clicks++ }, modifier = Modifier.testTag("plugin-demo-button")) { Text("Press") }
                    Text("Clicked $clicks")
                    BasicTextField(text, { text = it }, Modifier.testTag("plugin-demo-field"))
                }
            }
            UiDriverServer(0).use { server ->
                val session = UiDriverSession(this, out)
                val http = HttpClient.newHttpClient()
                fun call(verb: String, body: String = "{}", token: String = server.token): Pair<Int, JsonObject> {
                    val req = HttpRequest.newBuilder(URI("http://127.0.0.1:${server.port}/ui/$verb"))
                        .header("Authorization", "Bearer $token").POST(HttpRequest.BodyPublishers.ofString(body)).build()
                    val res = http.send(req, HttpResponse.BodyHandlers.ofString())
                    return res.statusCode() to Json.parseToJsonElement(res.body()).jsonObject
                }
                val client = thread(name = "ui-driver-test-client") {
                    try {
                        results["badToken"] = call("health", token = "wrong")
                        results["query"] = call("query", """{"tagPrefix":"plugin-demo-"}""")
                        results["click"] = call("click", """{"tag":"plugin-demo-button"}""")
                        results["clicked"] = call("wait", """{"text":"Clicked 1","timeoutMs":5000}""")
                        results["type"] = call("type", """{"tag":"plugin-demo-field","input":"hello"}""")
                        results["typed"] = call("query", """{"tag":"plugin-demo-field","text":"hello"}""")
                        results["shot"] = call("shot", """{"name":"after"}""")
                        results["missing"] = call("click", """{"tag":"no-such-node"}""")
                        results["badName"] = call("shot", """{"name":"../escape"}""")
                    } finally {
                        call("quit")
                    }
                }
                session.serve(server, idleTimeoutMs = 60_000)
                client.join()
            }
        }
        fun status(k: String) = results.getValue(k).first
        fun body(k: String) = results.getValue(k).second
        assertEquals(401, status("badToken"))
        assertEquals(2, body("query")["count"]!!.jsonPrimitive.int, "both plugin-demo-* tags are found: ${body("query")}")
        assertEquals(200, status("click"), "${body("click")}")
        assertEquals(200, status("clicked"), "the click reached the button: ${body("clicked")}")
        assertEquals(200, status("type"), "${body("type")}")
        assertEquals(1, body("typed")["count"]!!.jsonPrimitive.int, "the typed text is in the field: ${body("typed")}")
        val shot = File(body("shot")["path"]!!.jsonPrimitive.content)
        assertTrue(shot.isFile && shot.length() > 0, "the shot is written: $shot")
        assertEquals(400, status("missing"))
        val failShot = File(body("missing")["failShot"]!!.jsonPrimitive.content)
        assertTrue(failShot.isFile && failShot.length() > 0, "a failed action writes a shot of the moment: $failShot")
        assertEquals(400, status("badName"), "a shot name never leaves the out dir")
        assertTrue(out.listFiles().orEmpty().all { it.parentFile == out }, "nothing is written outside the out dir")
        out.deleteRecursively()
    }
}
