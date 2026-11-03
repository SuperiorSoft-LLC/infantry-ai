package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.model.NoticeKind
import com.superiorsoftllc.infantry.ui.model.ToastEvent
import com.superiorsoftllc.infantry.ui.model.ToastKind
import com.superiorsoftllc.infantry.ui.model.noticesForSession
import com.superiorsoftllc.infantry.ui.model.reduce
import com.superiorsoftllc.infantry.ui.model.toToast
import com.superiorsoftllc.infantry.ui.model.visibleToasts
import com.superiorsoftllc.infantry.ui.model.withNotice
import com.superiorsoftllc.infantry.ui.model.withToast
import com.superiorsoftllc.infantry.ui.protocol.Envelope
import com.superiorsoftllc.infantry.ui.protocol.Ticket
import com.superiorsoftllc.infantry.ui.protocol.TicketStart
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The toast queue and the session scoping of the inline notice. */
class ToastModelTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private fun toast(id: String, at: Long) = ToastEvent(id, at, ToastKind.INFO, "t$id")

    @Test fun `at most three show, newest first, history and dismissed skipped`() {
        val q = (1L..5L).map { toast("n$it", it) }
        assertEquals(listOf("n5", "n4", "n3"), visibleToasts(q, 0, emptySet()).map { it.id })
        assertEquals(listOf("n5", "n4"), visibleToasts(q, 3, emptySet()).map { it.id })
        assertEquals(listOf("n4", "n3", "n2"), visibleToasts(q, 0, setOf("n5")).map { it.id })
    }

    @Test fun `posting an id twice queues it once`() {
        val s = AppState().withToast(toast("a", 1)).withToast(toast("a", 2))
        assertEquals(1, s.toasts.size)
    }

    @Test fun `a notice from another project becomes a toast titled by the project`() {
        val t = com.superiorsoftllc.infantry.ui.model.Notice(5, "boom", kind = NoticeKind.ERROR, projectId = "p2").toToast("There")
        assertEquals(ToastKind.ERROR, t.kind)
        assertEquals("There", t.title)
        assertEquals("boom", t.body)
    }

    private fun started(sessionId: String?) =
        Envelope(Envelope.TICKET, json.encodeToString(Ticket.serializer(), Ticket("t1", "Ship", status = "in_progress", sessionId = sessionId, start = TicketStart(true, "started", "cut from main 1a2b3c4d"))))

    @Test fun `a start toasts globally and its inline notice belongs to the ticket's session only`() {
        val s = reduce(AppState(), started("A"), 1)
        assertEquals(1, s.toasts.size)
        assertEquals("A", s.toasts.single().sessionId)
        assertEquals(1, s.noticesForSession("A").size)
        assertTrue(s.noticesForSession("B").isEmpty(), "session B must not show session A's notice")
        assertTrue(s.noticesForSession(null).isEmpty())
    }

    @Test fun `a start with no worker session yet is a toast only, never an inline line anywhere`() {
        val s = reduce(AppState(), started(null), 1)
        assertEquals(1, s.toasts.size)
        assertTrue(s.notices.isEmpty())
    }

    @Test fun `global notices still show in every session`() {
        val s = AppState().withNotice(1, "daemon said no", null).withNotice(2, "mine", "A")
        assertEquals(listOf("daemon said no", "mine"), s.noticesForSession("A").map { it.text })
        assertEquals(listOf("daemon said no"), s.noticesForSession("B").map { it.text })
    }
}
