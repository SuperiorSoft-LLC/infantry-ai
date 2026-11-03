package com.superiorsoftllc.infantry.ui.net

import com.superiorsoftllc.infantry.ui.protocol.Envelope
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * DISCONNECT-1 (a): the daemon is alive and one websocket to it drops, however it drops -- the client MUST go
 * RECONNECTING and come back to CONNECTED on its own. It must never end up with no retry loop and a stale state (the owner
 * then relaunches the app, which the state-dir lock correctly refuses: "state dir in use by another daemon").
 *
 * A fake daemon (no real daemon, no state dir, nothing of the owner's): connection #1 sends `ready` and then drops in the
 * way under test; every later connection sends `ready` and stays open. The client is a plain [DaemonClient].
 */
class DaemonClientDropTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val closers = mutableListOf<() -> Unit>()
    private val ready = Json.encodeToString(Envelope.serializer(), Envelope(Envelope.READY, ""))

    @AfterTest fun tearDown() {
        scope.cancel()
        closers.reversed().forEach { runCatching { it() } }
    }

    private enum class Drop { CLOSE_NORMAL, SESSION_CANCELLED, HANDLER_THROWS }

    /** Starts the fake daemon on an ephemeral port; returns its port and the count of websocket connections it has seen. */
    private fun fakeDaemon(drop: Drop, connections: AtomicInteger): Int {
        val server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            install(WebSockets)
            routing {
                webSocket("/ws") {
                    val n = connections.incrementAndGet()
                    send(Frame.Text(ready))
                    if (n == 1) {
                        when (drop) {
                            Drop.CLOSE_NORMAL -> close()
                            // The shape in the owner's daemon log: "ws handler error: JobCancellationException: LazyStandaloneCoroutine was cancelled".
                            Drop.SESSION_CANCELLED -> cancel("simulated: the session job was cancelled")
                            Drop.HANDLER_THROWS -> error("simulated handler failure")
                        }
                    } else {
                        incoming.consumeEach { }
                    }
                }
            }
        }
        server.start(wait = false)
        closers += { server.stop(0, 200) }
        return runBlocking {
            repeat(250) {
                server.engine.resolvedConnectors().firstOrNull()?.port?.takeIf { it > 0 }?.let { return@runBlocking it }
                kotlinx.coroutines.delay(20)
            }
            error("no port")
        }
    }

    private fun await(what: String, client: DaemonClient, connections: AtomicInteger, timeoutMs: Long = 20_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) return
            Thread.sleep(25)
        }
        error("not met: $what; conn=${client.info.value} connections=${connections.get()}")
    }

    private fun assertReconnects(drop: Drop) {
        val connections = AtomicInteger()
        val port = fakeDaemon(drop, connections)
        val client = DaemonClient(scope)
        closers += { client.close() }
        val opened = AtomicInteger()
        scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { client.opened.collect { opened.incrementAndGet() } }
        client.connect("ws://127.0.0.1:$port/ws", null)
        // `opened` fires on every `ready`: the first connect, then the reconnect after the drop.
        await("ready seen again after the drop", client, connections) { opened.get() >= 2 }
        assertEquals(2, connections.get(), "one retry, not a storm")
    }

    @Test fun `a normal close from a live daemon is reconnected`() = assertReconnects(Drop.CLOSE_NORMAL)

    @Test fun `a cancelled session on a live daemon is reconnected, not the end of the retry loop`() = assertReconnects(Drop.SESSION_CANCELLED)

    @Test fun `a handler failure on a live daemon is reconnected`() = assertReconnects(Drop.HANDLER_THROWS)
}
