package com.superiorsoftllc.agenthq.daemon

import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.protocol.EventVocabulary
import com.superiorsoftllc.agenthq.role.Role
import com.superiorsoftllc.agenthq.role.RoleIds
import com.superiorsoftllc.agenthq.role.RoleSource
import com.superiorsoftllc.agenthq.runner.StubScript
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Contract 1.12.0: the role catalog is on the wire. `role_list` answers `roles` (the catalog, canonical
 * ids first); `role_save` writes a custom role definition into the daemon's roles directory and pushes
 * the stored `role` to EVERY client. The client never reads role files itself: the catalog is the
 * daemon's, which is what lets a remote or Android client show the same roles.
 */
class RoleDaemonTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun roleListAnswersWithTheCatalogCanonicalIdsFirst() = withDaemon { fixture ->
        fixture.client.await(Envelope.READY)
        fixture.send(Envelope(Envelope.ROLE_LIST))
        val roles = decodeRoles(fixture.client.await(Envelope.ROLES).data)

        assertEquals(RoleIds.BUILT_IN, roles.take(RoleIds.BUILT_IN.size).map { it.id }, "canonical ids first, in contract order")
        assertTrue(roles.all { Regex("#[0-9a-f]{6}").matches(it.color) }, "every role paints: ${roles.map { it.color }}")
        assertTrue(roles.none { it.source == RoleSource.CUSTOM }, "no custom role before one is saved")
    }

    @Test
    fun roleSaveOverridesABuiltInIsPushedToEveryClientAndSurvivesARestart() {
        val state = java.nio.file.Files.createTempDirectory("agenthq-role-test").toFile()
        val first = DaemonFixture.launch(StubScript.default(), stateDir = state)
        try {
            first.client.await(Envelope.READY)
            val watcher = first.connectClient()
            first.send(
                Envelope(
                    Envelope.ROLE_SAVE,
                    """{"id":"backend","name":"Backend Ninja","color":"#0EA5E9","persona":"You are the backend ninja.\nShip it."}""",
                ),
            )
            val stored = json.decodeFromString(Role.serializer(), first.client.await(Envelope.ROLE).data)
            assertEquals(Role("backend", "Backend Ninja", "#0ea5e9", "You are the backend ninja.\nShip it.", RoleSource.CUSTOM), stored)
            assertEquals(stored, json.decodeFromString(Role.serializer(), watcher.await(Envelope.ROLE).data), "pushed to every client")
            assertTrue(File(state, "roles/backend.md").isFile, "a custom role is a file in the daemon's roles directory")

            first.send(Envelope(Envelope.ROLE_LIST))
            val listed = decodeRoles(first.client.await(Envelope.ROLES).data).single { it.id == "backend" }
            assertEquals(stored, listed, "the custom definition replaces the built-in one in the catalog")
        } finally {
            first.stop()
        }
        val second = DaemonFixture.launch(StubScript.default(), stateDir = state)
        try {
            second.client.await(Envelope.READY)
            second.send(Envelope(Envelope.ROLE_LIST))
            val reread = decodeRoles(second.client.await(Envelope.ROLES).data).single { it.id == "backend" }
            assertEquals("Backend Ninja", reread.name, "a restarted daemon reads the role back")
            assertEquals(RoleSource.CUSTOM, reread.source)
        } finally {
            second.stop()
            second.deleteState()
        }
    }

    @Test
    fun aNewRoleIdJoinsTheCatalogAfterTheCanonicalIds() = withDaemon { fixture ->
        fixture.client.await(Envelope.READY)
        fixture.send(Envelope(Envelope.ROLE_SAVE, """{"id":"data-wrangler","name":"Data Wrangler","persona":"Clean the data."}"""))
        val stored = json.decodeFromString(Role.serializer(), fixture.client.await(Envelope.ROLE).data)
        assertEquals("data-wrangler", stored.id)
        assertTrue(Regex("#[0-9a-f]{6}").matches(stored.color), "no colour given: the palette supplies one")

        fixture.send(Envelope(Envelope.ROLE_LIST))
        val ids = decodeRoles(fixture.client.await(Envelope.ROLES).data).map { it.id }
        assertEquals(RoleIds.BUILT_IN.size, ids.indexOf("data-wrangler"), "extra roles follow the canonical ids")
    }

    @Test
    fun anInvalidRoleIsRefusedWithTheReasonAndNothingIsWritten() = withDaemon { fixture ->
        fixture.client.await(Envelope.READY)
        val refusals = listOf(
            """{"id":"Bad_Id","name":"x"}""" to "role id",
            """{"id":"ok-id","name":"  "}""" to "name",
            """{"id":"ok-id","name":"Ok","color":"red"}""" to "colour",
            """{"id":"ok-id","name":"Ok","persona":"${"p".repeat(20_001)}"}""" to "persona",
            """{"id":"ok-id","name":"Ok","source":"BUILT_IN"}""" to "malformed",
        )
        for ((index, case) in refusals.withIndex()) {
            fixture.send(Envelope(Envelope.ROLE_SAVE, case.first))
            val error = fixture.client.await(Envelope.ERROR, occurrence = index)
            assertTrue(error.data.startsWith("role_save refused: "), error.data)
            assertTrue(error.data.contains(case.second, ignoreCase = true), "expected '${case.second}' in '${error.data}'")
        }
        assertTrue(fixture.client.of(Envelope.ROLE).isEmpty(), "a refusal stores nothing and pushes nothing")
        assertFalse(File(fixture.stateDir, "roles/ok-id.md").exists())
    }

    /**
     * ROLE-EDIT-1: editing a role from its card is a `role_save` of the SAME id with a new title and instructions. A
     * second client sees the new title under the unchanged id, and a session started for that role AFTER the save is
     * frozen with the edited instructions (a role-only spawn has no profile instructions, so the role's are the ones).
     */
    @Test
    fun anEditedRoleShowsItsNewTitleToASecondClientAndANewSessionStartsWithItsInstructions() = withDaemon { fixture ->
        fixture.client.await(Envelope.READY)
        val second = fixture.connectClient()
        second.await(Envelope.READY)
        fixture.send(Envelope(Envelope.ROLE_SAVE, """{"id":"backend","name":"Backend Renamed","persona":"EDITED-INSTRUCTIONS: ship small."}"""))
        fixture.client.await(Envelope.ROLE)

        val pushed = json.decodeFromString(Role.serializer(), second.await(Envelope.ROLE).data)
        assertEquals("backend" to "Backend Renamed", pushed.id to pushed.name, "the second client gets the new title under the same id")
        second.send(Envelope(Envelope.ROLE_LIST))
        val listed = decodeRoles(second.await(Envelope.ROLES).data)
        assertEquals("Backend Renamed", listed.single { it.id == "backend" }.name, "the second client's catalog carries the new title")
        assertEquals(1, listed.count { it.name == "Backend Renamed" }, "a rename adds no second role")

        fixture.send(Envelope(Envelope.NEW, """{"role":"backend"}"""))
        fixture.await(what = "the role-only session to be bound at `new`") { fixture.daemon.manager.list().isNotEmpty() }
        val session = fixture.daemon.manager.list().single()
        assertEquals("backend", session.role)
        assertEquals(
            "EDITED-INSTRUCTIONS: ship small.",
            fixture.daemon.manager.instance(session.id)?.instructions,
            "a session started after the save is frozen with the role's edited instructions",
        )
    }

    /**
     * ROLE-ICON-1 (contract 1.93.0): a role's icon is a key the daemon stores with the role. Client A saves it from the
     * Edit role dialog; client B is pushed the role carrying it, and B's `role_list` carries it too, so every window
     * paints the same icon. Read as raw JSON on purpose: what is asserted is the wire, not a client's decoding of it.
     */
    @Test
    fun aSavedIconIsReturnedToASecondClient() = withDaemon { fixture ->
        fixture.client.await(Envelope.READY)
        val second = fixture.connectClient()
        second.await(Envelope.READY)
        fixture.send(Envelope(Envelope.ROLE_SAVE, """{"id":"qa","name":"QA","icon":"shield"}"""))
        fixture.client.await(Envelope.ROLE)

        val pushed = Json.parseToJsonElement(second.await(Envelope.ROLE).data).jsonObject
        assertEquals("shield", pushed["icon"]?.jsonPrimitive?.content, "the second client is pushed the saved icon: $pushed")
        second.send(Envelope(Envelope.ROLE_LIST))
        val listed = Json.parseToJsonElement(second.await(Envelope.ROLES).data).jsonArray.map { it.jsonObject }
            .single { it["id"]?.jsonPrimitive?.content == "qa" }
        assertEquals("shield", listed["icon"]?.jsonPrimitive?.content, "the second client's catalog carries the saved icon")

        // A save that names no icon keeps the stored one (an older client editing the title must not wipe it) ...
        fixture.send(Envelope(Envelope.ROLE_SAVE, """{"id":"qa","name":"QA Renamed"}"""))
        assertEquals("shield", iconOf(fixture.client.await(Envelope.ROLE, occurrence = 1).data))
        // ... and an empty key clears it back to the role's default (the client paints its per-kind default).
        fixture.send(Envelope(Envelope.ROLE_SAVE, """{"id":"qa","name":"QA Renamed","icon":""}"""))
        assertEquals("", iconOf(fixture.client.await(Envelope.ROLE, occurrence = 2).data))
    }

    /** ROLE-ICON-1: the icon lives in the role's file, so a restarted daemon reads it back; a bad key is refused. */
    @Test
    fun aSavedIconSurvivesARestartAndAnInvalidKeyIsRefused() {
        val state = java.nio.file.Files.createTempDirectory("agenthq-role-icon-test").toFile()
        val first = DaemonFixture.launch(StubScript.default(), stateDir = state)
        try {
            first.client.await(Envelope.READY)
            first.send(Envelope(Envelope.ROLE_SAVE, """{"id":"backend","name":"Backend","icon":"Not An Icon!"}"""))
            val error = first.client.await(Envelope.ERROR)
            assertTrue(error.data.startsWith("role_save refused: ") && error.data.contains("icon"), error.data)
            first.send(Envelope(Envelope.ROLE_SAVE, """{"id":"backend","name":"Backend","icon":"storage"}"""))
            assertEquals("storage", iconOf(first.client.await(Envelope.ROLE).data))
        } finally {
            first.stop()
        }
        val second = DaemonFixture.launch(StubScript.default(), stateDir = state)
        try {
            second.client.await(Envelope.READY)
            second.send(Envelope(Envelope.ROLE_LIST))
            val reread = Json.parseToJsonElement(second.client.await(Envelope.ROLES).data).jsonArray
                .single { it.jsonObject["id"]?.jsonPrimitive?.content == "backend" }
            assertEquals("storage", iconOf(reread.toString()), "a restarted daemon reads the icon back from the role file")
        } finally {
            second.stop()
            second.deleteState()
        }
    }

    /** ROLE-ICON-1: a role frame's `icon`, read off the wire ("" when the key is present and empty, null when absent). */
    private fun iconOf(data: String): String? = Json.parseToJsonElement(data).jsonObject["icon"]?.jsonPrimitive?.content

    private fun decodeRoles(data: String): List<Role> = json.decodeFromString(ListSerializer(Role.serializer()), data)

    private fun withDaemon(block: (DaemonFixture) -> Unit) {
        val fixture = DaemonFixture.launch(StubScript.default())
        try {
            block(fixture)
        } finally {
            fixture.stop()
            fixture.deleteState()
        }
    }
}
