// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.remoterunner.daemon

import com.superiorsoftllc.infantry.daemon.api.runners.LinkHandler
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.origin
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

/**
 * RUNNER-POOL-5 (moved to Infantry_RemoteRunner by RUNNER-POOL-6a): the runner link listener. With [tls] (the default; `runners.allowPlainTailnet` off) it serves **wss**: a JDK
 * [SSLServerSocket] on [host]:[port] terminates TLS with the runner's self-signed certificate and pipes each connection to a
 * WebSocket endpoint bound to loopback only (the daemon's CIO engine has no TLS of its own). With [tls] null it serves plain ws on
 * [host]:[port] directly; only `runners.allowPlainTailnet` asks for that.
 *
 * Every text frame is a [LinkWire.Envelope] request handed to [server]; the connection's [LinkWire.CREDENTIAL_HEADER] / [LinkWire.ORCHESTRATOR_HEADER]
 * say who calls. Nothing else is served: no `/ws`, no routes, no bundle.
 */
class LinkListener(
    private val server: LinkHandler,
    private val tls: SSLContext?,
    private val host: String,
    private val port: Int,
    private val handshakeMs: Int,
) : AutoCloseable {
    private var engine: EmbeddedServer<*, *>? = null
    private var front: SSLServerSocket? = null
    /** TLS mode: the loopback port of each piped connection -> the real peer address, for the audit's `from`. */
    private val peers = ConcurrentHashMap<Int, String>()

    /** Starts and returns the port the link is reachable on (the front's in TLS mode). */
    @Synchronized
    fun start(): Int {
        check(engine == null) { "the runner link listener is already running" }
        val innerHost = if (tls != null) LOOPBACK else host
        val innerPort = if (tls != null) 0 else port
        val e = embeddedServer(CIO, port = innerPort, host = innerHost) {
            install(WebSockets)
            routing {
                webSocket(LinkWire.PATH) {
                    val credential = call.request.headers[LinkWire.CREDENTIAL_HEADER]
                    val orchestrator = call.request.headers[LinkWire.ORCHESTRATOR_HEADER]
                    val from = if (tls != null) peers[call.request.origin.remotePort] else call.request.origin.remoteAddress
                    for (frame in incoming) {
                        if (frame !is Frame.Text) continue
                        val request = runCatching { json.decodeFromString(LinkWire.Envelope.serializer(), frame.readText()) }.getOrNull()
                        val answer = if (request == null) {
                            server.malformed()
                        } else {
                            server.handle(request.type, request.data, orchestrator, credential, from)
                        }
                        send(Frame.Text(json.encodeToString(LinkWire.Envelope.serializer(), LinkWire.Envelope(answer.type, answer.data, requestId = request?.requestId))))
                    }
                }
            }
        }.start(wait = false)
        engine = e
        val bound = runBlocking { e.engine.resolvedConnectors().first().port }
        if (tls == null) return bound
        val socket = tls.serverSocketFactory.createServerSocket(port, BACKLOG, InetAddress.getByName(host)) as SSLServerSocket
        socket.enabledProtocols = socket.supportedProtocols.filter { it in PROTOCOLS }.toTypedArray()
        front = socket
        thread(name = "infantry-runner-link-accept", isDaemon = true) { accept(socket, bound) }
        return socket.localPort
    }

    private fun accept(socket: SSLServerSocket, innerPort: Int) {
        while (!socket.isClosed) {
            val client = runCatching { socket.accept() as SSLSocket }.getOrNull() ?: continue
            thread(name = "infantry-runner-link-conn", isDaemon = true) { pipe(client, innerPort) }
        }
    }

    private fun pipe(client: SSLSocket, innerPort: Int) {
        val inner = Socket()
        try {
            client.soTimeout = handshakeMs
            client.startHandshake()
            client.soTimeout = 0
            inner.connect(InetSocketAddress(LOOPBACK, innerPort), handshakeMs)
            val key = inner.localPort
            peers[key] = client.inetAddress.hostAddress
            val back = thread(name = "infantry-runner-link-pipe", isDaemon = true) { copy(inner.getInputStream(), client.getOutputStream()); runCatching { client.close() } }
            copy(client.getInputStream(), inner.getOutputStream())
            runCatching { inner.close() }
            back.join()
            peers.remove(key)
        } catch (_: Exception) {
            // A failed handshake (a scanner, a wrong client) closes quietly; there is nothing to answer.
        } finally {
            runCatching { inner.close() }
            runCatching { client.close() }
        }
    }

    private fun copy(input: InputStream, output: OutputStream) {
        runCatching {
            val buffer = ByteArray(PIPE_BUFFER)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                output.write(buffer, 0, n)
                output.flush()
            }
        }
    }

    @Synchronized
    override fun close() {
        runCatching { front?.close() }
        front = null
        runCatching { engine?.stop(0, 0) }
        engine = null
    }

    companion object {
        const val LOOPBACK = "127.0.0.1"
        private const val BACKLOG = 16
        private const val PIPE_BUFFER = 16 * 1024
        private val PROTOCOLS = setOf("TLSv1.3", "TLSv1.2")
        private val json = Json { ignoreUnknownKeys = true }
    }
}
