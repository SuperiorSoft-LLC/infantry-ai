package com.superiorsoftllc.agenthq.attention

import com.superiorsoftllc.agenthq.persistence.SqlAgentHqStore
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AttentionStoreTest {
    @Test
    fun attentionIsDurableOrderedAndAcknowledgedIdempotently() {
        val path = Files.createTempFile("agenthq-attention-", ".db").also { it.toFile().delete() }
            .toAbsolutePath().toString()
        val store = SqlAgentHqStore.open(path)
        var nextId = 0
        val attention = AttentionStore(
            repository = store.attention,
            clock = { 100L + nextId },
            idFactory = { "attention-${++nextId}" },
        )

        val first = attention.raise(
            kind = AttentionKind.TRIGGER_TRIPPED,
            triggerId = "trigger-1",
            outcome = "FAILED",
            detail = "trigger run failed",
        )
        val second = attention.raise(
            kind = AttentionKind.CEILING,
            podId = "pod-2",
            outcome = "REFUSED",
            detail = "human intervention required",
        )
        assertEquals(listOf(first, second), attention.list())
        assertEquals(2, attention.openCount())
        store.close()

        val reopened = SqlAgentHqStore.open(path)
        val afterRestart = AttentionStore(reopened.attention)
        assertEquals(listOf(first, second), afterRestart.list())
        val acked = assertNotNull(afterRestart.acknowledge(first.id, at = 500L))
        assertEquals(500L, acked.ackedAt)
        assertEquals(1, afterRestart.openCount())
        assertEquals(acked, afterRestart.acknowledge(first.id, at = 600L))
        assertEquals(500L, afterRestart.list().first().ackedAt)
        assertNull(afterRestart.acknowledge("missing", at = 700L))
        reopened.close()
    }
}
