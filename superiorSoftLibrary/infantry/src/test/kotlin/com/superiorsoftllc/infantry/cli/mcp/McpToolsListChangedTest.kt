// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.cli.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** PK-3 (plan CAL-PLAN-2 B4): tools.listChanged is advertised and notifications/tools/list_changed follows a changed tool list. */
class McpToolsListChangedTest {
    private fun tool(name: String) = JsonObject(mapOf("name" to JsonPrimitive(name)))

    private class Rig(var list: JsonArray?) {
        val out = StringBuilder()
        val server = McpServer(
            BufferedReader(StringReader("")), out, StringBuilder(),
            McpTools({ error("not used") }),
            listTools = { list },
        )
    }

    @Test
    fun initializeAdvertisesListChanged() {
        val rig = Rig(JsonArray(emptyList()))
        val line = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}"""
        val result = rig.server.handleLine(line)!!["result"]!!.jsonObject
        assertEquals("true", result["capabilities"]!!.jsonObject["tools"]!!.jsonObject["listChanged"]!!.jsonPrimitive.content)
    }

    @Test
    fun notifiesOnlyWhenTheListChanges() {
        val rig = Rig(JsonArray(listOf(tool("a"))))
        assertFalse(rig.server.checkToolsChanged(), "the first look is only the baseline")
        assertFalse(rig.server.checkToolsChanged(), "nothing changed, nothing sent")
        assertEquals("", rig.out.toString())

        rig.list = JsonArray(listOf(tool("a"), tool("calendar_add"))) // a plugin was enabled
        assertTrue(rig.server.checkToolsChanged())
        val sent = Json.parseToJsonElement(rig.out.toString().trim()).jsonObject
        assertEquals("notifications/tools/list_changed", sent["method"]!!.jsonPrimitive.content)
        assertFalse("id" in sent, "a notification carries no id")

        assertFalse(rig.server.checkToolsChanged(), "one notice per change")
    }

    @Test
    fun unreachableDaemonSendsNothingAndKeepsTheBaseline() {
        val rig = Rig(JsonArray(listOf(tool("a"))))
        rig.server.checkToolsChanged()
        rig.list = null
        assertFalse(rig.server.checkToolsChanged())
        rig.list = JsonArray(listOf(tool("a")))
        assertFalse(rig.server.checkToolsChanged(), "back with the same list is not a change")
        assertEquals("", rig.out.toString())
    }
}
