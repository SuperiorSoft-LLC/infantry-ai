// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.ui.model.RoleIcons
import com.superiorsoftllc.infantry.ui.protocol.RoleSave
import com.superiorsoftllc.infantry.ui.protocol.WireRequests
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** ROLE-ICON-1: the icon table, its vectors, and the `role_save` payload a 1.93.0 vs an older daemon receives. */
class AhqRoleIconsTest {
    @Test
    fun everyOfferedKeyHasAVectorAndEveryDefaultIsAnOfferedKey() {
        RoleIcons.KEYS.forEach { key -> assertNotNull(AhqRoleIcons.vector(key), "no vector for offered key '$key'") }
        assertEquals(RoleIcons.KEYS.size, RoleIcons.KEYS.toSet().size, "no key offered twice")
        listOf("pm", "version-control", "qa", "fullstack", "backend", "frontend", "devops", "security", "architect", "mobile",
            "marketing", "seo", "localization", "ideation", "automation", "assistant", "custom")
            .forEach { role -> assertTrue(RoleIcons.defaultFor(role) in RoleIcons.KEYS, "default for '$role' is not offered") }
    }

    @Test
    fun aStoredKeyWinsABlankOrUnknownKeyFallsBackToTheRoleKindsDefault() {
        assertEquals("shield", RoleIcons.resolve("qa", "shield"))
        assertEquals("bug-report", RoleIcons.resolve("qa", ""), "blank = the qa default")
        assertEquals("bug-report", RoleIcons.resolve("qa", "no-such-icon"), "a key this build lacks = the qa default")
        assertEquals("assignment", RoleIcons.resolve("pm", null))
        assertEquals("account-tree", RoleIcons.resolve("git", null), "a retired id keeps its successor's icon")
        assertEquals(RoleIcons.FALLBACK, RoleIcons.resolve("data-wrangler", null), "an unknown role paints the fallback")
        assertEquals(RoleIcons.FALLBACK, RoleIcons.resolve(null, null))
    }

    @Test
    fun roleSaveCarriesTheIconOnlyToADaemonThatKnowsItAndOnlyWhenChosen() {
        fun keys(withIcon: Boolean, icon: String?) =
            Json.parseToJsonElement(WireRequests.roleSave(RoleSave("qa", "QA", null, "", icon), withIcon).data).jsonObject

        assertEquals("shield", keys(withIcon = true, icon = "shield")["icon"]?.jsonPrimitive?.content)
        assertEquals("", keys(withIcon = true, icon = "")["icon"]?.jsonPrimitive?.content, "an explicit clear is sent")
        assertFalse("icon" in keys(withIcon = true, icon = null), "null = keep: the key is not sent at all")
        assertFalse("icon" in keys(withIcon = false, icon = "shield"), "a pre-1.93.0 daemon refuses the key, so it is never sent")
    }
}
