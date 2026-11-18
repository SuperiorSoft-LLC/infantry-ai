// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.plugins

import com.superiorsoftllc.infantry.plugin.api.NotificationSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginFailureNoticesTest {
    private val frames = mutableListOf<String>()
    private val hub = NotificationHub(settings = { NotificationSettings() }, broadcast = { t, d -> frames += "$t $d" })
    private val notices = PluginFailureNotices(hub)

    @Test fun missingGrantRaisesOneErrorNamingPluginAndPermission() {
        assertTrue(notices.raise("Calendar", PluginFailureKind.START, "failed: IllegalStateException: missing grant 'storage' in modules.json"))
        assertEquals(1, frames.size)
        val f = frames[0]
        assertTrue("Calendar couldn't start" in f && "'storage' permission" in f && "error" in f.lowercase() && "Details" in f, f)
    }

    @Test fun sameKindIsDedupedOtherKindsAndPluginsAreNot() {
        assertTrue(notices.raise("Calendar", PluginFailureKind.START, "a"))
        assertFalse(notices.raise("Calendar", PluginFailureKind.START, "a"))
        assertTrue(notices.raise("Calendar", PluginFailureKind.DISABLED, "b"))
        assertTrue(notices.raise("Other", PluginFailureKind.START, "a"))
        assertEquals(3, frames.size)
        notices.reset("Calendar")
        assertTrue(notices.raise("Calendar", PluginFailureKind.START, "a"))
    }

    @Test fun detailsActionPostsFullReason() {
        notices.raise("Calendar", PluginFailureKind.START, "failed: boom\nat Foo.bar")
        hub.action(com.superiorsoftllc.infantry.plugin.api.NotificationActionEvent("plugin-failure:Calendar:start", "details", "daemon"))
        assertEquals(2, frames.size)
        assertTrue("at Foo.bar" in frames[1])
    }
}
