package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.ui.model.USER_AVATAR_ID
import com.superiorsoftllc.infantry.ui.model.avatarFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/** AVATAR-1: each message shows its speaker's avatar, derived purely from the identity. */
class AvatarSpecTest {
    @Test
    fun avatarForAgentIsStablePerRole() {
        // Same identity -> same spec (deterministic, replay-safe).
        assertEquals(
            avatarFor("a1", "Scrum Master", "pm"),
            avatarFor("a1", "Scrum Master", "pm"),
        )
        // Initials come from the name.
        assertEquals("SM", avatarFor("a1", "Scrum Master", "pm").initials)
        // Different agents get visibly different colours.
        val sm = avatarFor("a1", "Scrum Master", "pm")
        val backend = avatarFor("a2", "Backend Dev", "backend")
        assertNotEquals(sm.colorArgb, backend.colorArgb)
        // No custom image yet (a later ticket): the field stays null.
        assertNull(sm.imageUrl)
        // The user's own messages get their own distinct avatar, outside the agent palette.
        val user = avatarFor(USER_AVATAR_ID, "You", null)
        assertNotEquals(user.colorArgb, sm.colorArgb)
        assertNotEquals(user.colorArgb, backend.colorArgb)
    }
}
