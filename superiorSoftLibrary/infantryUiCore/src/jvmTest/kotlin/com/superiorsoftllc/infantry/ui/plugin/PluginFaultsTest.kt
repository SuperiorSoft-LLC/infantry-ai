// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.plugin

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** PLUGIN-CRASH-CONTAIN-1: a plugin's exception is attributed from its stack, marks it faulted, never rethrows; a reload clears it. */
class PluginFaultsTest {
    @AfterTest fun tearDown() = PluginFaults.reset()

    private fun thrownFrom(cls: String): Throwable =
        IllegalArgumentException("layouts are not part of the same hierarchy").also { it.stackTrace = arrayOf(StackTraceElement(cls, "run", "X.kt", 1)) }

    @Test fun pluginExceptionMarksPluginFaultedAndDoesNotThrow() {
        PluginFaults.claim("acme.readaloud", "com.acme.readaloud")
        val owner = PluginFaults.handle(thrownFrom("com.acme.readaloud.Tooltip"), generationOf = { 3 })
        assertEquals("acme.readaloud", owner)
        val fault = assertNotNull(PluginFaults.faultOf("acme.readaloud", 3))
        assertEquals(true, "same hierarchy" in fault.message)
        assertNull(PluginFaults.hostError.value)
        assertNull(PluginFaults.faultOf("acme.readaloud", 4), "a reload (new generation) draws the plugin again")
    }

    @Test fun nonPluginExceptionGoesToTheHostBannerNotAFault() {
        PluginFaults.claim("acme.readaloud", "com.acme.readaloud")
        assertNull(PluginFaults.handle(thrownFrom("androidx.compose.ui.Foo")))
        assertNotNull(PluginFaults.hostError.value)
        assertEquals(0, PluginFaults.all.size)
    }
}
