package com.superiorsoftllc.agenthq.daemon

import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.protocol.RoleBinding
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ROLE-1c over the real daemon and a real WebSocket: a role's binding is set, read back, listed, and bad input is refused. */
class RoleBindingWireTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun withDaemon(block: (DaemonFixture) -> Unit) {
        val fixture = DaemonFixture.launch()
        try {
            fixture.client.await(Envelope.READY)
            block(fixture)
        } finally {
            fixture.stop()
            fixture.deleteState()
        }
    }

    @Test
    fun setBindingIsReadBackOverTheWireAndListed() = withDaemon { f ->
        f.send(Envelope(Envelope.ROLE_BINDING_SET, """{"roleId":"backend","provider":"stub","model":"m-1","effort":"high"}"""))
        val pushed = json.decodeFromString(RoleBinding.serializer(), f.client.await(Envelope.ROLE_BINDING).data)
        assertEquals(RoleBinding("backend", "stub", "m-1", "high", pushed.updatedAt), pushed)

        f.send(Envelope(Envelope.ROLE_BINDING_GET, "backend"))
        val got = json.decodeFromString(RoleBinding.serializer(), f.client.await(Envelope.ROLE_BINDING, 1).data)
        assertEquals("stub", got.provider)
        assertEquals("m-1", got.model)
        assertEquals("high", got.effort)

        f.send(Envelope(Envelope.ROLE_BINDING_LIST))
        val all = json.decodeFromString(ListSerializer(RoleBinding.serializer()), f.client.await(Envelope.ROLE_BINDINGS).data)
        assertEquals(listOf("backend"), all.map { it.roleId })

        // Switching the model later replaces it; a blank clears the pin.
        f.send(Envelope(Envelope.ROLE_BINDING_SET, """{"roleId":"backend","provider":"stub","model":"m-2"}"""))
        f.send(Envelope(Envelope.ROLE_BINDING_GET, "backend"))
        val switched = json.decodeFromString(RoleBinding.serializer(), f.client.await(Envelope.ROLE_BINDING, 3).data)
        assertEquals("m-2", switched.model)
        assertNull(switched.effort)
    }

    @Test
    fun aRoleWithNoBindingAnswersAnUnpinnedOne() = withDaemon { f ->
        f.send(Envelope(Envelope.ROLE_BINDING_GET, "backend"))
        val got = json.decodeFromString(RoleBinding.serializer(), f.client.await(Envelope.ROLE_BINDING).data)
        assertEquals(RoleBinding("backend"), got)
    }

    @Test
    fun unknownRoleProviderEffortAndKeysAreRefusedAndNothingIsWritten() = withDaemon { f ->
        listOf(
            """{"roleId":"nobody","provider":"stub"}""",
            """{"roleId":"backend","provider":"nope"}""",
            """{"roleId":"backend","effort":"turbo"}""",
            """{"roleId":"backend","provder":"stub"}""",
        ).forEach { bad ->
            f.send(Envelope(Envelope.ROLE_BINDING_SET, bad))
            assertTrue(f.client.await(Envelope.ERROR).data.startsWith("role_binding_set refused"), bad)
        }
        f.send(Envelope(Envelope.ROLE_BINDING_LIST))
        assertEquals("[]", f.client.await(Envelope.ROLE_BINDINGS).data)
    }
}
