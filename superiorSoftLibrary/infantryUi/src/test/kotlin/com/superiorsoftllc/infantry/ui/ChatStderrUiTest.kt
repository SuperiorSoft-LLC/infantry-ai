package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.plugins.official.chat.*
import com.superiorsoftllc.infantry.plugins.official.chatfeed.*

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.model.reduce
import com.superiorsoftllc.infantry.ui.protocol.Envelope
import com.superiorsoftllc.infantry.ui.protocol.SessionRecord
import com.superiorsoftllc.infantry.ui.protocol.SessionState
import kotlinx.serialization.json.Json
import kotlin.test.Test

/** UI-LOG-1: a stderr line and the process-exit line are shown in the chat, not dropped. */
@OptIn(ExperimentalTestApi::class)
class ChatStderrUiTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val rec = SessionRecord("a", "stub", "/w", spawnedAt = 1, lastHeartbeat = 1, state = SessionState.BUSY, providerSessionId = "p")

    /** Feed the frames through the real reducer, exactly as a live window would. */
    private fun agentWith(vararg frames: Envelope) = frames
        .fold(
            reduce(AppState(), Envelope(Envelope.READY, "provider=stub contract=1.11.0 instance=A since=0"), 0),
            { s, f -> reduce(s, f, 0) },
        )
        .agents.getValue("a")

    @Test
    fun stderrAndExitLinesAreShownInTheChat() {
        val agent = agentWith(
            Envelope(Envelope.SESSION, json.encodeToString(SessionRecord.serializer(), rec), rec.id),
            Envelope(Envelope.STDERR, "auth failed", rec.id),
            Envelope(Envelope.EXIT, "1", rec.id),
        )
        runDesktopComposeUiTest(900, 600) {
            setContent { AgentChat(agent) }
            onNodeWithText("auth failed").assertExists()
            onNodeWithText("process exited with code 1").assertExists()
        }
    }
}