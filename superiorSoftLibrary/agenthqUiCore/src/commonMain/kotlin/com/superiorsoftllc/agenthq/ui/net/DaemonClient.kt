package com.superiorsoftllc.agenthq.ui.net

import com.superiorsoftllc.agenthq.ui.model.ConnState
import com.superiorsoftllc.agenthq.ui.model.ConnectionInfo
import com.superiorsoftllc.agenthq.ui.protocol.Envelope
import io.ktor.client.HttpClient
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/**
 * One link to one daemon, local or remote -- the same `ws://host:port/ws` either way.
 *
 * CONNECTED is published only after `ready`; 401/403 are terminal until the human changes the
 * credential, and other losses reconnect with backoff. The transport engine is selected by the
 * jvmMain/androidMain actual, while framing, retry policy and state stay in common code.
 */
open class DaemonClient(private val scope: CoroutineScope) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val http: HttpClient = createPlatformHttpClient()
    private val _info = MutableStateFlow(ConnectionInfo())
    open val info: StateFlow<ConnectionInfo> = _info.asStateFlow()

    private val _frames = MutableSharedFlow<Envelope>(extraBufferCapacity = 4096)
    open val frames: SharedFlow<Envelope> = _frames.asSharedFlow()

    /** Fired after each `ready` (first connect and every reconnect) so the controller can resync. */
    private val _opened = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    open val opened: SharedFlow<Unit> = _opened.asSharedFlow()

    private val socket = MutableStateFlow<DefaultClientWebSocketSession?>(null)
    private val live = MutableStateFlow(false)
    private val sendMutex = Mutex()
    private var job: Job? = null

    fun connect(url: String, token: String?) {
        disconnect()
        _info.value = ConnectionInfo(state = ConnState.CONNECTING, url = url, detail = "opening $url")
        job = scope.launch { run(url, token?.trim()?.takeIf { it.isNotEmpty() }) }
    }

    fun disconnect() {
        job?.cancel()
        job = null
        live.value = false
        socket.value = null
        _info.value = _info.value.copy(state = ConnState.IDLE, detail = "disconnected")
    }

    fun close() {
        disconnect()
        http.close()
    }

    open suspend fun send(envelope: Envelope): Boolean = sendMutex.withLock {
        val current = socket.value ?: return@withLock false
        if (!live.value) return@withLock false
        try {
            current.send(Frame.Text(json.encodeToString(Envelope.serializer(), envelope)))
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            false
        }
    }

    private suspend fun run(url: String, token: String?) {
        var attempt = 0
        while (true) {
            attempt++
            try {
                val current = http.webSocketSession(urlString = url) {
                    if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
                }
                socket.value = current
                val reason = receive(current)
                val wasReady = live.value
                live.value = false
                socket.value = null
                attempt = if (wasReady) 0 else attempt
                backoff(attempt + 1, reason)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val code = refusalStatus(e)
                if (code == 401 || code == 403) {
                    live.value = false
                    socket.value = null
                    _info.value = _info.value.copy(
                        state = ConnState.REFUSED,
                        detail = "daemon refused the connection (HTTP $code): check the bearer token",
                    )
                    return
                }
                live.value = false
                socket.value = null
                val root = generateSequence(e) { it.cause }.last()
                backoff(attempt, "${root::class.simpleName}: ${root.message ?: "no route to daemon"}")
            }
        }
    }

    private suspend fun receive(session: DefaultClientWebSocketSession): String {
        val buffer = StringBuilder()
        for (frame in session.incoming) {
            when (frame) {
                is Frame.Text -> {
                    buffer.append(frame.readText())
                    if (frame.fin) {
                        val text = buffer.toString()
                        buffer.setLength(0)
                        runCatching { json.decodeFromString(Envelope.serializer(), text) }
                            .getOrNull()
                            ?.let { envelope ->
                                if (envelope.type == Envelope.READY) live.value = true
                                _frames.emit(envelope)
                                if (envelope.type == Envelope.READY) _opened.emit(Unit)
                            }
                    }
                }
                is Frame.Close -> break
                else -> Unit
            }
        }
        val close = session.closeReason.await()
        return close?.let {
            "daemon closed the connection (${it.code}${if (it.message.isNotBlank()) " ${it.message}" else ""})"
        } ?: "daemon closed the connection"
    }

    private suspend fun backoff(attempt: Int, reason: String) {
        _info.value = _info.value.copy(state = ConnState.RECONNECTING, attempt = attempt, detail = reason)
        delay(minOf(15_000L, 500L * (1L shl minOf(attempt, 5))))
    }

    private fun refusalStatus(error: Throwable): Int? =
        generateSequence(error) { it.cause }
            .mapNotNull { cause ->
                (cause as? ResponseException)?.response?.status?.value
                    ?: Regex("\\b(401|403)\\b").find(cause.message.orEmpty())?.value?.toIntOrNull()
            }
            .firstOrNull { it == 401 || it == 403 }
}
