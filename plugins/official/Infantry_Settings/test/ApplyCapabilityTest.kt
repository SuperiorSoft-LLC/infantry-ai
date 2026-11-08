// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official

import com.superiorsoftllc.infantry.ui.shell.PluginLifecycle
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** UI-PLUGIN-BRIDGE-1: the `plugins.apply` capability runs the shell's lifecycle (the one the Plugins tab uses) and reports it. */
class ApplyCapabilityTest {
    @Test
    fun `the capability calls the shared lifecycle apply once and answers its line and warnings`() {
        var calls = 0
        val lifecycle = PluginLifecycle(mapOf("Acme_X" to "did not unload cleanly")) { calls++; "Applied: Acme_X" }
        val out = applyPluginsLive(JsonObject(mapOf("id" to JsonPrimitive("Acme_X"), "action" to JsonPrimitive("enable"))), lifecycle)
        assertEquals(1, calls)
        assertTrue(out["applied"]!!.jsonPrimitive.boolean)
        assertEquals("Applied: Acme_X", out["message"]!!.jsonPrimitive.content)
        assertEquals("Acme_X", out["id"]!!.jsonPrimitive.content)
        assertEquals("enable", out["action"]!!.jsonPrimitive.content)
        assertEquals(1, (out["warnings"] as JsonArray).size)
    }

    @Test
    fun `a window with no lifecycle fails the call with the reason`() {
        val e = assertFailsWith<IllegalStateException> { applyPluginsLive(JsonObject(emptyMap()), null) }
        assertTrue(e.message!!.contains("no plugin lifecycle"))
    }

    @Test
    fun `the capability id is the one module json declares`() {
        val json = java.io.File("module.json").readText()
        assertTrue(json.contains("\"id\": \"$APPLY_CAPABILITY\""))
    }
}
