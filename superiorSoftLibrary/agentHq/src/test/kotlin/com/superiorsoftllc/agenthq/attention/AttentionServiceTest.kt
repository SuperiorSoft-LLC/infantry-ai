package com.superiorsoftllc.agenthq.attention

import com.superiorsoftllc.agenthq.daemon.api.attention.AttentionKinds
import com.superiorsoftllc.agenthq.persistence.SqlAgentHqStore
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AttentionServiceTest {
    private fun store() = AttentionStore(
        SqlAgentHqStore.open(Files.createTempFile("agenthq-attn-", ".db").also { it.toFile().delete() }.toAbsolutePath().toString()).attention,
    )

    @Test fun `local provider raises lists and resolves with identical kinds and texts`() {
        val s = store()
        val svc = LocalAttention(s)
        val id = svc.raise(AttentionKinds.STALLED, ticketId = "t1", outcome = "silent", detail = "d")!!
        val e = svc.list().single()
        assertEquals(id, e.id); assertEquals("stalled", e.kind); assertEquals("silent", e.outcome); assertEquals("t1", e.ticketId)
        assertEquals(AttentionKind.STALLED, s.list().single().kind)
        assertTrue(svc.resolve(id)); assertFalse(svc.resolve("nope"))
        assertTrue(svc.list().single().ackedAt != null)
    }

    @Test fun `absent provider drops silently`() {
        val svc = FoundAttention { null }
        assertNull(svc.raise(AttentionKinds.CEILING, outcome = "o", detail = "d"))
        assertFalse(svc.resolve("x")); assertTrue(svc.list().isEmpty())
    }

    @Test fun `found provider is looked up per call`() {
        var p: com.superiorsoftllc.agenthq.daemon.api.attention.Attention? = null
        val svc = FoundAttention { p }
        assertNull(svc.raise(AttentionKinds.CEILING, outcome = "o", detail = "d"))
        p = LocalAttention(store())
        assertTrue(svc.raise(AttentionKinds.CEILING, outcome = "o", detail = "d") != null)
    }
}
