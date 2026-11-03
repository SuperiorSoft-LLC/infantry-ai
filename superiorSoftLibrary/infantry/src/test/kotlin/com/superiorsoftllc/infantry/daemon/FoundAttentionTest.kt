package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.daemon.api.attention.Attention
import com.superiorsoftllc.infantry.daemon.api.attention.AttentionEntry
import com.superiorsoftllc.infantry.daemon.api.attention.AttentionKinds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ATTN-1 / ATTN-2b: core only holds [FoundAttention]; the provider (store, service) is Infantry_Attention's. */
class FoundAttentionTest {
    private class Fake : Attention {
        val items = mutableListOf<AttentionEntry>()
        override fun raise(kind: String, triggerId: String?, ticketId: String?, podId: String?, sessionId: String?, outcome: String, detail: String): String? =
            "id-${items.size + 1}".also { items += AttentionEntry(it, 1L, kind, triggerId, ticketId, podId, sessionId, outcome, detail) }
        override fun resolve(id: String) = items.any { it.id == id }
        override fun list(project: String?) = items.toList()
    }

    @Test fun `absent provider drops silently`() {
        val svc = FoundAttention { null }
        assertNull(svc.raise(AttentionKinds.CEILING, outcome = "o", detail = "d")) // held until a provider appears
        assertFalse(svc.resolve("x")); assertTrue(svc.list().isEmpty())
    }

    @Test fun `found provider is looked up per call`() {
        var p: Attention? = null
        val svc = FoundAttention { p }
        assertNull(svc.raise(AttentionKinds.CEILING, outcome = "o", detail = "d")) // held until a provider appears
        p = Fake()
        val id = svc.raise(AttentionKinds.STALLED, ticketId = "t1", outcome = "silent", detail = "d")
        assertEquals("id-2", id)
        assertEquals(listOf("o", "silent"), svc.list().map { it.outcome }) // the held ceiling item arrived first
        assertTrue(svc.resolve("id-2")); assertFalse(svc.resolve("nope"))
    }
}
