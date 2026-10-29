package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.ui.model.AllowedRoles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DEMO-RESTRICT (ticket f9e181af): the New agent dialog offers only the roles the daemon's allowlist names. */
class AllowedRolesTest {
    @Test
    fun settingsFrame_readsTheList_absentChangesNothing() {
        assertEquals(listOf("pm", "backend"), AllowedRoles.fromSettingsFrame("""{"limits.roles.allowed":["pm","backend"]}"""))
        assertEquals(emptyList(), AllowedRoles.fromSettingsFrame("""{"limits.roles.allowed":[]}"""))
        assertNull(AllowedRoles.fromSettingsFrame("""{"other":1}"""))
    }

    @Test
    fun matching_emptyIsAll_caseInsensitive_star() {
        assertTrue(AllowedRoles.allows(emptyList(), "frontend"))
        assertTrue(AllowedRoles.allows(listOf("pm", "backend"), "Backend"))
        assertFalse(AllowedRoles.allows(listOf("pm", "backend"), "frontend"))
        assertTrue(AllowedRoles.allows(listOf("back*"), "backend"))
    }

    @Test
    fun maxSessions_settingsFrame_absentIsNull_zeroIsOff() {
        assertEquals(2L, AllowedRoles.maxSessionsFromSettingsFrame("""{"limits.concurrency.maxSessions":2}"""))
        assertEquals(0L, AllowedRoles.maxSessionsFromSettingsFrame("""{"limits.concurrency.maxSessions":0}"""))
        assertNull(AllowedRoles.maxSessionsFromSettingsFrame("""{"other":1}"""))
    }
}
