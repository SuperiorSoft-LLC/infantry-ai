// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
import com.superiorsoftllc.infantry.ui.model.ConnState
import com.superiorsoftllc.infantry.ui.model.ConnectionInfo
import com.superiorsoftllc.infantry.ui.net.DaemonClient
import com.superiorsoftllc.infantry.ui.protocol.Envelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * A daemon that is not there: replays hand-written fixtures through the real [DaemonClient] seam so the whole UI
 * runs offline. It never opens a socket and never sees a token.
 */
class FakeDaemonClient(private val scope: CoroutineScope) : DaemonClient(scope) {
    private val _info = MutableStateFlow(ConnectionInfo())
    override val info: StateFlow<ConnectionInfo> = _info.asStateFlow()
    private val _frames = MutableSharedFlow<Envelope>(extraBufferCapacity = 4096)
    override val frames: SharedFlow<Envelope> = _frames
    private val _opened = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    override val opened: SharedFlow<Unit> = _opened
    private var seq = 0L

    override fun connect(url: String, token: String?) {
        _info.value = ConnectionInfo(state = ConnState.CONNECTING, url = "demo", detail = "demo data, no daemon")
        scope.launch {
            delay(150)
            _frames.emit(Envelope(Envelope.READY, "contract=1.0.0 provider=demo instance=demo since=0"))
            _frames.emit(Envelope(Envelope.SESSIONS, DemoFixtures.sessions))
            for ((id, lines) in DemoFixtures.history) for ((kind, text) in lines) emit(id, kind, text)
            _opened.emit(Unit)
        }
    }

    override fun disconnect() {
        _info.value = _info.value.copy(state = ConnState.IDLE, detail = "disconnected")
    }

    override suspend fun send(envelope: Envelope): Boolean {
        if (envelope.type == Envelope.PROMPT) {
            val id = envelope.sessionId ?: DemoFixtures.firstId
            scope.launch {
                emit(id, Envelope.STATUS, "{\"state\":\"BUSY\"}")
                for (word in DemoFixtures.reply.split(' ')) { delay(60); emit(id, Envelope.TEXT_DELTA, "$word ") }
                emit(id, Envelope.STATUS, "{\"state\":\"IDLE\"}")
            }
        }
        // The libraries are empty offline; an unanswered list request would leave the pane on "Loading...".
        if (envelope.type == Envelope.PROMPT_LIST) scope.launch { _frames.emit(Envelope(Envelope.PROMPTS, "[]")) }
        if (envelope.type == Envelope.SKILL_LIST) scope.launch { _frames.emit(Envelope(Envelope.SKILLS, "[]")) }
        return true
    }

    private suspend fun emit(id: String, type: String, data: String) {
        _frames.emit(Envelope(type, data, sessionId = id, sequence = ++seq))
    }
}
