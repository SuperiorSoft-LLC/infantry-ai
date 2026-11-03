package com.superiorsoftllc.infantry.ui.net

import com.superiorsoftllc.infantry.ui.model.ConnState
import com.superiorsoftllc.infantry.ui.model.ConnectionInfo
import com.superiorsoftllc.infantry.ui.protocol.Envelope
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
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

    /** SWITCH-1: wakes a sleeping backoff so "Reconnect now" retries at once. */
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var lastUrl: String? = null
    private var lastToken: String? = null

    /** SEC-FIX-1: the owner credential and the one URL it belongs to; sent to that daemon only, never to another server. */
    @kotlin.concurrent.Volatile private var owner: Pair<String, String>? = null

    /** SEC-FIX-1: present [token] as the owner when (and only when) this client connects to [url]. */
    fun ownerFor(url: String, token: String) { owner = url to token }

    open fun connect(url: String, token: String?) {
        disconnect()
        lastUrl = url
        lastToken = token
        _info.value = ConnectionInfo(state = ConnState.CONNECTING, url = url, detail = "opening $url")
        job = scope.launch { run(url, token?.trim()?.takeIf { it.isNotEmpty() }) }
    }

    /** SWITCH-1: retry now: skips a backoff wait, or starts over from REFUSED / FAILED / IDLE with the same target. */
    fun reconnectNow() {
        val url = lastUrl ?: return
        val state = _info.value.state
        if (state == ConnState.RECONNECTING && job?.isActive == true) wake.trySend(Unit) else if (!live.value) connect(url, lastToken)
    }

    open fun disconnect() {
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
                val ownerToken = owner?.takeIf { it.first == url }?.second
                val current = http.webSocketSession(urlString = url) {
                    if (token != null) {
                        if (tokenAsSubprotocol) {
                            // Two values, so the Js engine passes two protocols (one comma-joined string is invalid).
                            header(HttpHeaders.SecWebSocketProtocol, WEB_PROTOCOL)
                            header(HttpHeaders.SecWebSocketProtocol, bearerSubprotocol(token))
                        } else {
                            header(HttpHeaders.Authorization, "Bearer $token")
                        }
                    }
                    // SEC-FIX-1: the owner credential, to its own daemon only (saved commands, secrets and runs need it).
                    if (ownerToken != null) {
                        if (tokenAsSubprotocol) {
                            if (token == null) header(HttpHeaders.SecWebSocketProtocol, WEB_PROTOCOL)
                            header(HttpHeaders.SecWebSocketProtocol, ownerSubprotocol(ownerToken))
                        } else {
                            header(OWNER_HEADER, ownerToken)
                        }
                    }
                }
                socket.value = current
                // DISCONNECT-1: a session that ends by cancellation (the daemon's ws scope was cancelled, an abnormal close
                // surfacing from `incoming`) is a DROP to retry, not the end of this loop. Rethrowing it killed the loop with
                // the state frozen on CONNECTED, and only a relaunch of the app (refused by the state-dir lock) recovered.
                val reason = try {
                    receive(current)
                } catch (e: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    "daemon link cancelled (${e.message ?: e::class.simpleName})"
                }
                val wasReady = live.value
                live.value = false
                socket.value = null
                attempt = if (wasReady) 0 else attempt
                backoff(attempt + 1, reason)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // A session that had reached `ready` and then failed starts the backoff over; it is not a failing attempt.
                if (live.value) attempt = 1
                live.value = false
                socket.value = null
                when (val failure = ReconnectPolicy.classify(refusalStatus(e), generateSequence(e) { it.cause }.last())) {
                    is ReconnectPolicy.Failure.Terminal -> {
                        _info.value = _info.value.copy(state = ConnState.REFUSED, detail = failure.reason, retryAtMillis = null)
                        return
                    }
                    is ReconnectPolicy.Failure.Retry -> backoff(attempt, failure.reason)
                }
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
        val wait = ReconnectPolicy.delayMillis(attempt)
        _info.value = _info.value.copy(state = ConnState.RECONNECTING, attempt = attempt, detail = reason, retryAtMillis = kotlin.time.Clock.System.now().toEpochMilliseconds() + wait)
        wake.tryReceive()
        withTimeoutOrNull(wait) { wake.receive() }
    }

    private fun refusalStatus(error: Throwable): Int? =
        generateSequence(error) { it.cause }
            .mapNotNull { cause ->
                (cause as? ResponseException)?.response?.status?.value
                    ?: Regex("\\b(401|403)\\b").find(cause.message.orEmpty())?.value?.toIntOrNull()
            }
            .firstOrNull { it == 401 || it == 403 }
}
