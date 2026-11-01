package com.superiorsoftllc.agenthq.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.agenthq.plugins.official.board.*
import kotlin.test.Test

/** TICKET-COMMENTS-3: the comments panel against a fake api (no daemon). */
@OptIn(ExperimentalTestApi::class)
class TicketCommentsPanelUiTest {
    private class Fake : TicketCommentsApi {
        val list = mutableListOf(
            TicketComment("c1", "note", "open", "owner", "2026-10-31T10:00:00Z", "first **bold**"),
            TicketComment("c2", "note", "resolved", "agent-1", "2026-10-31T11:00:00Z", "second"),
        )
        override suspend fun comments(ticketId: String) = Result.success(list.toList())
        override suspend fun meta(ticketId: String) = Result.success(TicketCommentMeta("agent-1", listOf("agent-1"), list.count { it.open }))
        override suspend fun add(ticketId: String, body: String, resolved: Boolean): Result<Unit> {
            list += TicketComment("c${list.size + 1}", "note", if (resolved) "resolved" else "open", "owner", "2026-10-31T12:00:00Z", body)
            return Result.success(Unit)
        }
        override suspend fun resolve(commentId: String): Result<Unit> {
            val i = list.indexOfFirst { it.id == commentId }
            list[i] = list[i].copy(state = "resolved")
            return Result.success(Unit)
        }
        override suspend fun notifyCandidates() = Result.success(listOf(NotifyAgent("agent-1", "Alpha"), NotifyAgent("agent-2", "Beta")))
        override suspend fun saveNotify(ticketId: String, ids: List<String>) = Result.success(Unit)
    }

    @Test
    fun threadPostResolveAndNotifyPicker() = runDesktopComposeUiTest {
        setContent { TicketCommentsPanel("t1", Fake()) }
        waitForIdle()
        onNodeWithTag(COMMENT_TAG_PREFIX + "c1").assertExists()
        onNodeWithTag(COMMENT_TAG_PREFIX + "c2").assertExists()
        onNodeWithTag(COMMENT_STATE_TAG_PREFIX + "c1").assertTextContains("open")
        onNodeWithTag(COMMENT_STATE_TAG_PREFIX + "c2").assertTextContains("resolved")
        onNodeWithTag(COMMENT_CREATED_BY_TAG).assertTextContains("agent-1", substring = true)
        onNodeWithTag(COMMENT_NOTIFY_TAG_PREFIX + "agent-1").assertExists()
        onNodeWithTag(COMMENT_NOTIFY_TAG_PREFIX + "agent-2").assertExists()
        onNodeWithTag(COMMENT_NOTIFY_TAG_PREFIX + "agent-3").assertDoesNotExist()

        onNodeWithTag(COMMENT_INPUT_TAG).performTextInput("hello there")
        onNodeWithTag(COMMENT_SEND_TAG).assertIsEnabled().performClick()
        waitForIdle()
        onNodeWithTag(COMMENT_TAG_PREFIX + "c3").assertExists()

        onNodeWithTag(COMMENT_RESOLVE_TAG_PREFIX + "c1").performClick()
        waitForIdle()
        onNodeWithTag(COMMENT_STATE_TAG_PREFIX + "c1").assertTextContains("resolved")
        onNodeWithTag(COMMENT_RESOLVE_TAG_PREFIX + "c1").assertDoesNotExist()
    }
}
