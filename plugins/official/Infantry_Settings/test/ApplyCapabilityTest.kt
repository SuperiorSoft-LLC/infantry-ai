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
        val out = applyPluginsLive(JsonObject(mapOf("id" to JsonPrimitive("Acme_X"), "action" to JsonPrimitive("install"))), lifecycle)
        assertEquals(1, calls)
        assertTrue(out["applied"]!!.jsonPrimitive.boolean)
        assertEquals("Applied: Acme_X", out["message"]!!.jsonPrimitive.content)
        assertEquals("Acme_X", out["id"]!!.jsonPrimitive.content)
        assertEquals("install", out["action"]!!.jsonPrimitive.content)
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

/** PLUGIN-APPLY-2: `plugins.apply` with enable / disable changes the state first (one call), and refuses when it cannot. */
class ApplyActionTest {
    private class Port(var enabled: Boolean, val ok: Boolean = true) {
        var set = 0
        val port: com.superiorsoftllc.infantry.plugin.api.PluginManagerPort = java.lang.reflect.Proxy.newProxyInstance(
            Port::class.java.classLoader, arrayOf(com.superiorsoftllc.infantry.plugin.api.PluginManagerPort::class.java),
        ) { _, m, a ->
            when (m.name) {
                "list" -> listOf(com.superiorsoftllc.infantry.plugin.api.PluginView("Acme_X", "1", "community", false, enabled, null, null, emptyList(), emptyList(), false, null))
                "setEnabled" -> { set++; if (ok) enabled = a[1] as Boolean; com.superiorsoftllc.infantry.plugin.api.ActionResult(ok, if (ok) "done" else "refused") }
                else -> error("unused ${m.name}")
            }
        } as com.superiorsoftllc.infantry.plugin.api.PluginManagerPort
    }

    private fun args(action: String) = JsonObject(mapOf("id" to JsonPrimitive("Acme_X"), "action" to JsonPrimitive(action)))

    @Test
    fun `enable changes the state then swaps, twice in a row`() {
        var swaps = 0
        val l = PluginLifecycle(emptyMap()) { swaps++; "ok" }
        val p = Port(false)
        applyPluginsLive(args("enable"), l, p.port); applyPluginsLive(args("disable"), l, p.port)
        assertEquals(2, swaps); assertEquals(2, p.set); assertEquals(false, p.enabled)
    }

    @Test
    fun `no manager or a refusal fails the call and swaps nothing`() {
        var swaps = 0
        val l = PluginLifecycle(emptyMap()) { swaps++; "ok" }
        assertTrue(assertFailsWith<IllegalStateException> { applyPluginsLive(args("enable"), l, null) }.message!!.contains("plugin enable Acme_X"))
        assertTrue(assertFailsWith<IllegalStateException> { applyPluginsLive(args("enable"), l, Port(false, ok = false).port) }.message!!.contains("refused"))
        assertEquals(0, swaps)
    }
}
