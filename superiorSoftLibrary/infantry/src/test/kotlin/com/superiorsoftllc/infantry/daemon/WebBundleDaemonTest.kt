// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.cli.freshToken
import com.superiorsoftllc.infantry.protocol.Envelope
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.net.http.WebSocketHandshakeException
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.CompletionException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * WEB-1 (contract 1.122.0) over real sockets: the daemon serves the browser bundle at `/` without the token, serves
 * nothing but the bundle's own files (no traversal), and admits a same-origin browser on `/ws` with the subprotocol
 * bearer, answering the plain `infantry` subprotocol. The decisions themselves are unit-tested in [WebAccessPolicyTest].
 */
class WebBundleDaemonTest {

    private val fixtures = mutableListOf<DaemonFixture>()
    private val temp = Files.createTempDirectory("infantry-web-test").toFile()

    @AfterTest
    fun stopAll() {
        fixtures.asReversed().forEach {
            runCatching { it.stop() }
            runCatching { it.deleteState() }
        }
        fixtures.clear()
        temp.deleteRecursively()
    }

    /** A bundle folder, with a secret file NEXT to it (in its parent) that must never be served. */
    private fun bundle(): File {
        File(temp, "secret.txt").writeText("TOP-SECRET")
        val web = File(temp, "web").apply { mkdirs() }
        File(web, "index.html").writeText("<!DOCTYPE html><title>Infantry</title>")
        File(web, "infantryWeb.mjs").writeText("export {}")
        File(web, "sub").mkdirs()
        File(web, "sub/app.wasm").writeBytes(byteArrayOf(0, 'a'.code.toByte(), 's'.code.toByte(), 'm'.code.toByte()))
        return web
    }

    private fun launch(token: String?, web: File?): DaemonFixture =
        DaemonFixture.launch(token = token, webBundle = WebBundle.load(web)).also { fixtures += it }

    private class Raw(val status: Int, val headers: Map<String, String>, val body: String)

    /** One raw HTTP/1.1 GET, so the path goes out exactly as written (no client-side normalisation). */
    private fun rawGet(port: Int, path: String, vararg headers: Pair<String, String>): Raw =
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5_000
            val all = (if (headers.any { it.first.equals("Host", true) }) headers.toList() else listOf("Host" to "127.0.0.1:$port") + headers)
            val request = buildString {
                append("GET ").append(path).append(" HTTP/1.1\r\n")
                for ((n, v) in all) append(n).append(": ").append(v).append("\r\n")
                append("Connection: close\r\n\r\n")
            }
            socket.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1))
            val status = reader.readLine()!!.split(' ')[1].toInt()
            val hs = mutableMapOf<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                hs[line.substringBefore(':').trim().lowercase()] = line.substringAfter(':').trim()
            }
            Raw(status, hs, reader.readText())
        }

    private fun subprotocols(token: String) =
        arrayOf("infantry.bearer." + Base64.getUrlEncoder().withoutPadding().encodeToString(token.toByteArray()))

    /** A browser-like handshake: an Origin, and the token only as a subprotocol. Returns the status and the chosen subprotocol. */
    private fun browserHandshake(port: Int, origin: String, protocols: Array<String>): Pair<Int, String?> {
        val builder = HttpClient.newHttpClient().newWebSocketBuilder().header("Origin", origin)
        if (protocols.isNotEmpty()) builder.subprotocols("infantry", *protocols)
        return try {
            val socket = builder.buildAsync(URI.create("ws://127.0.0.1:$port/ws"), WsTestClient()).join()
            val chosen = socket.subprotocol
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "probe").join()
            101 to chosen
        } catch (e: CompletionException) {
            val refused = e.cause as? WebSocketHandshakeException ?: throw e
            refused.response.statusCode() to null
        }
    }

    @Test
    fun theBundleIsServedAtTheRootWithoutTheTokenWithTheRightContentTypes() {
        val fixture = launch(freshToken(), bundle())
        val index = rawGet(fixture.port, "/")
        assertEquals(200, index.status)
        assertTrue(index.body.contains("<title>Infantry</title>"))
        assertTrue(index.headers["content-type"]!!.startsWith("text/html"), index.headers.toString())
        assertEquals(200, rawGet(fixture.port, "/index.html").status)
        assertTrue(rawGet(fixture.port, "/infantryWeb.mjs").headers["content-type"]!!.startsWith("text/javascript"))
        assertEquals("application/wasm", rawGet(fixture.port, "/sub/app.wasm").headers["content-type"])
        // Everything else still asks for the token.
        assertEquals(401, rawGet(fixture.port, "/attention").status)
    }

    @Test
    fun nothingButTheBundleFilesIsEverServedNoPathTraversal() {
        val token = freshToken()
        val fixture = launch(token, bundle())
        val paths = listOf(
            "/../secret.txt", "/%2e%2e/secret.txt", "/%2E%2E%2Fsecret.txt", "/sub/../../secret.txt", "/sub/..%2f..%2fsecret.txt",
            "/sub/%2e%2e/%2e%2e/secret.txt", "/..%5csecret.txt", "/sub\\..\\..\\secret.txt", "//secret.txt", "/secret.txt",
            "/web/index.html", "/sub/", "/sub", "/index.html/", "/./index.html/../../secret.txt", "/index.html%00.txt",
        )
        for (path in paths) {
            for (auth in listOf(null, "Bearer $token")) {
                val answer = runCatching { rawGet(fixture.port, path, *listOfNotNull(auth?.let { "Authorization" to it }).toTypedArray()) }.getOrNull()
                    ?: continue // the server dropped a malformed request line: nothing was served
                assertFalse(answer.body.contains("TOP-SECRET"), "$path (auth=${auth != null}) served the secret")
                assertNotEquals(200, answer.status, "$path (auth=${auth != null}) is not a bundle file")
            }
        }
    }

    @Test
    fun withoutABundleTheRootAnswersAPlainNotFoundNote() {
        val fixture = launch(freshToken(), web = null)
        val root = rawGet(fixture.port, "/")
        assertEquals(404, root.status)
        assertTrue(root.body.contains("no browser client installed"), root.body)
    }

    @Test
    fun aSameOriginBrowserIsAdmittedWithTheSubprotocolBearerAndTheBearerIsNeverEchoed() {
        val token = freshToken()
        val fixture = launch(token, bundle())
        val origin = "http://127.0.0.1:${fixture.port}"
        val (status, chosen) = browserHandshake(fixture.port, origin, subprotocols(token))
        assertEquals(101, status)
        assertEquals("infantry", chosen, "the daemon answers the plain subprotocol, never the bearer one")

        assertEquals(401, browserHandshake(fixture.port, origin, subprotocols(freshToken())).first, "a wrong token")
        assertEquals(401, browserHandshake(fixture.port, origin, emptyArray()).first, "no token")
        assertEquals(403, browserHandshake(fixture.port, "http://evil.example", subprotocols(token)).first, "cross-site")
        assertEquals(403, browserHandshake(fixture.port, "http://127.0.0.1:1", subprotocols(token)).first, "another port is another origin")
    }

    @Test
    fun aTokenlessLoopbackDaemonRefusesTheBrowserButStillServesItsPage() {
        val fixture = launch(token = null, web = bundle())
        assertEquals(200, rawGet(fixture.port, "/").status)
        assertEquals(403, rawGet(fixture.port, "/", "Host" to "evil.example").status, "DNS rebinding")
        assertEquals(403, browserHandshake(fixture.port, "http://127.0.0.1:${fixture.port}", subprotocols(freshToken())).first)
        // The CLI path (no Origin) is unchanged.
        assertTrue(fixture.client.await(Envelope.READY).data.isNotEmpty())
    }
}
