// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.plugins

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** PLUGIN-GUARD-1: a bad-argument error is that call's error; only a real fault disables the plugin. */
class PluginGuardCallerErrorTest {
    @Test
    fun `an IllegalArgumentException fails only that call and the plugin keeps answering`() {
        val told = mutableListOf<String>()
        val g = PluginGuard("Args", null, 10_000, {}, onDisabled = { told += it })
        val r = g.call("capability(http.download)", 2_000) { throw IllegalArgumentException("http.download runs in a project") }
        assertTrue(r.exceptionOrNull() is IllegalArgumentException)
        assertFalse(g.disabled)
        assertTrue(told.isEmpty())
        assertEquals(5, g.call("next", 2_000) { 5 }.getOrNull())
        g.shutdown()
    }

    @Test
    fun `an unexpected exception still disables the plugin`() {
        val g = PluginGuard("Crash", null, 10_000, {})
        g.call("boom", 2_000) { throw IllegalStateException("crash") }
        assertTrue(g.disabled)
        g.shutdown()
    }
}
