package com.superiorsoftllc.agenthq.daemon

import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.protocol.RoleTraits
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * TRAITS-T2 (contract 1.49.0) over the real daemon and a real WebSocket, the RoleBindingWireTest style: a role's trait
 * list is set, pushed to every client, read back and listed; bad input is refused with nothing written; and
 * `trait_delete` is refused while a role still binds the trait.
 */
class RoleTraitsWireTest {
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

    private fun one(data: String) = json.decodeFromString(RoleTraits.serializer(), data)

    private fun saveTrait(f: DaemonFixture, id: String, n: Int) {
        f.send(Envelope(Envelope.TRAIT_SAVE, """{"id":"$id","name":"${id.uppercase()}","body":"rule $id"}"""))
        f.client.await(Envelope.TRAIT_ITEM, n)
    }

    @Test
    fun setIsPushedToEveryClientReadBackListedAndClearable() = withDaemon { f ->
        val other = f.connectClient()
        other.await(Envelope.READY)
        saveTrait(f, "report-back", 0)
        saveTrait(f, "no-force-push", 1)

        f.send(Envelope(Envelope.ROLE_TRAITS_SET, """{"roleId":"backend","traitIds":["report-back"," no-force-push "]}"""))
        val pushed = one(f.client.await(Envelope.ROLE_TRAITS).data)
        assertEquals(RoleTraits("backend", listOf("report-back", "no-force-push"), pushed.updatedAt), pushed)
        assertTrue(pushed.updatedAt > 0)
        assertEquals(pushed, one(other.await(Envelope.ROLE_TRAITS).data), "the push reaches every client")

        f.send(Envelope(Envelope.ROLE_TRAITS_GET, "backend"))
        assertEquals(pushed, one(f.client.await(Envelope.ROLE_TRAITS, 1).data))

        f.send(Envelope(Envelope.ROLE_TRAITS_LIST))
        val all = json.decodeFromString(ListSerializer(RoleTraits.serializer()), f.client.await(Envelope.ROLE_TRAITS_ALL).data)
        assertEquals(listOf(pushed), all)

        f.send(Envelope(Envelope.ROLE_TRAITS_SET, """{"roleId":"backend","traitIds":[]}"""))
        assertEquals(emptyList(), one(f.client.await(Envelope.ROLE_TRAITS, 2).data).traitIds, "[] clears the list")
    }

    @Test
    fun aRoleWithNoListAnswersAnEmptyOne() = withDaemon { f ->
        f.send(Envelope(Envelope.ROLE_TRAITS_GET, "backend"))
        assertEquals(RoleTraits("backend"), one(f.client.await(Envelope.ROLE_TRAITS).data))
    }

    @Test
    fun unknownRoleUnknownTraitTooManyDuplicatesAndBadKeysAreRefusedAndNothingIsWritten() = withDaemon { f ->
        saveTrait(f, "report-back", 0)
        val nine = (1..9).joinToString(",") { "\"report-back\"" }
        listOf(
            """{"roleId":"nobody","traitIds":["report-back"]}""",
            """{"roleId":"backend","traitIds":["nope"]}""",
            """{"roleId":"backend","traitIds":[$nine]}""",
            """{"roleId":"backend","traitIds":["report-back","report-back"]}""",
            """{"roleId":"backend","traitIdz":["report-back"]}""",
            """{"roleId":"backend"}""",
        ).forEachIndexed { i, bad ->
            f.send(Envelope(Envelope.ROLE_TRAITS_SET, bad))
            val error = f.client.await(Envelope.ERROR, i).data
            assertTrue(error.startsWith("role_traits_set refused"), "$bad -> $error")
        }
        f.send(Envelope(Envelope.ROLE_TRAITS_LIST))
        assertEquals("[]", f.client.await(Envelope.ROLE_TRAITS_ALL).data)
    }

    @Test
    fun traitDeleteIsRefusedWhileARoleBindsIt() = withDaemon { f ->
        saveTrait(f, "report-back", 0)
        f.send(Envelope(Envelope.ROLE_TRAITS_SET, """{"roleId":"backend","traitIds":["report-back"]}"""))
        f.client.await(Envelope.ROLE_TRAITS)

        f.send(Envelope(Envelope.TRAIT_DELETE, "report-back"))
        val refused = f.client.await(Envelope.ERROR).data
        assertTrue(refused.startsWith("trait_delete refused") && "role 'backend'" in refused, refused)
        f.send(Envelope(Envelope.TRAIT_GET, "report-back"))
        f.client.await(Envelope.TRAIT_ITEM, 1) // still there

        f.send(Envelope(Envelope.ROLE_TRAITS_SET, """{"roleId":"backend","traitIds":[]}"""))
        f.client.await(Envelope.ROLE_TRAITS, 1)
        f.send(Envelope(Envelope.TRAIT_DELETE, "report-back"))
        assertEquals("[]", f.client.await(Envelope.TRAITS).data, "unbound, the delete goes through")
    }
}
