package com.superiorsoftllc.agenthq.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.model.reduce
import com.superiorsoftllc.agenthq.ui.protocol.Envelope
import com.superiorsoftllc.agenthq.ui.protocol.SessionRecord
import com.superiorsoftllc.agenthq.ui.protocol.SessionState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * UX-WRITE-1: a write/edit tool call shows in the chat as a change box, in the turn where it happened.
 * The frames go through the real reducer, as a live window gets them: a `status` frame whose `write`
 * (contract 1.80.0) carries the file's path and content.
 */
@OptIn(ExperimentalTestApi::class)
class ChatFileWriteUiTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val rec = SessionRecord("a", "stub", "/w", spawnedAt = 1, lastHeartbeat = 1, state = SessionState.BUSY, providerSessionId = "p")

    private fun agentWith(vararg frames: Envelope) = frames
        .fold(
            reduce(AppState(), Envelope(Envelope.READY, "provider=stub contract=1.80.0 instance=A since=0"), 0),
            { s, f -> reduce(s, f, 0) },
        )
        .agents.getValue("a")

    private fun session() = Envelope(Envelope.SESSION, json.encodeToString(SessionRecord.serializer(), rec), rec.id)

    /** A `status` frame for a Write of [content] to [path], exactly as the 1.80.0 daemon encodes it. */
    private fun writeStatus(path: String, content: String) = Envelope(
        Envelope.STATUS,
        """{"state":"BUSY","tool":"Write","activity":"$path","write":{"path":"$path","content":${JsonPrimitive(content)}}}""",
        rec.id,
    )

    private fun lines(n: Int) = (1..n).joinToString("\n") { "line $it" }

    @Test
    fun aFortyLineWriteShowsItsPathAndATwelveLinePreviewThenViewAllShowsEverything() {
        val agent = agentWith(session(), writeStatus("src/Big.kt", lines(40)))
        runDesktopComposeUiTest(1000, 2400) {
            setContent { AgentChat(agent) }
            onNodeWithText("src/Big.kt").assertExists()
            onNodeWithText("+28 more lines").assertExists()
            // Exactly the first 12 lines are drawn; line 13 is not.
            (1..12).forEach { onNodeWithText("line $it").assertExists() }
            onNodeWithText("line 13").assertDoesNotExist()
            onNodeWithText("View all (40 lines)").performClick()
            (1..40).forEach { onNodeWithText("line $it").assertExists() }
            onNodeWithText("Collapse").assertExists()
        }
    }

    @Test
    fun aShortWriteShowsInFullWithNoControl() {
        val agent = agentWith(session(), writeStatus("notes.md", lines(5)))
        runDesktopComposeUiTest(1000, 1200) {
            setContent { AgentChat(agent) }
            onNodeWithText("notes.md").assertExists()
            (1..5).forEach { onNodeWithText("line $it").assertExists() }
            assertEquals(0, onAllNodesWithText("View all", substring = true).fetchSemanticsNodes().size)
            assertEquals(0, onAllNodesWithText("more lines", substring = true).fetchSemanticsNodes().size)
        }
    }

    @Test
    fun aNonWriteToolAddsNothingToTheChat() {
        val agent = agentWith(
            session(),
            Envelope(Envelope.STATUS, """{"state":"BUSY","tool":"Read","activity":"src/Big.kt"}""", rec.id),
        )
        runDesktopComposeUiTest(1000, 1200) {
            setContent { AgentChat(agent) }
            onNodeWithText("src/Big.kt").assertDoesNotExist()
        }
    }
}
