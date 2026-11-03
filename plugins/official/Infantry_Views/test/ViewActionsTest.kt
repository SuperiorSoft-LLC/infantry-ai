// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.views

import com.superiorsoftllc.infantry.plugin.api.ViewAccess
import com.superiorsoftllc.infantry.plugin.api.ViewInfo
import com.superiorsoftllc.infantry.plugin.api.ViewOrigin
import com.superiorsoftllc.infantry.plugin.api.ViewState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** DV-4: list/table data, action parsing, confirm rule, act + refresh + toast, back stack. */
class ViewActionsTest {
    private fun obj(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    private class FakeAccess(var reply: Result<String> = Result.success("ok")) : ViewAccess {
        val calls = mutableListOf<Triple<ViewOrigin, String, JsonObject>>()
        override suspend fun list(): List<ViewInfo> = emptyList()
        override fun open(module: String, view: String, params: JsonObject): Flow<ViewState> = emptyFlow()
        override suspend fun act(origin: ViewOrigin, tool: String, args: JsonObject): Result<String> { calls += Triple(origin, tool, args); return reply }
    }

    @Test fun `list items parse with badge, defaults and an empty list`() {
        val items = ViewData.items(obj("""{"items":[{"id":"a","title":"A","subtitle":"s","badge":{"text":"new","tone":"weird"},"action":{"open":{"view":"d"}}},{"title":"B"}]}"""))
        assertEquals(listOf("a", "1"), items.map { it.id })
        assertEquals("default", items[0].badgeTone, "an unknown tone falls back")
        assertEquals("new", items[0].badgeText)
        assertIs<ViewAction.Open>(items[0].action)
        assertEquals(null, items[1].action)
        assertTrue(ViewData.items(obj("""{"empty":"Nothing"}""")).isEmpty())
    }

    @Test fun `table columns, rows and compact width shows the first two columns`() {
        val json = obj("""{"columns":[{"key":"a","label":"A"},{"key":"b","label":"B","align":"end"},{"key":"c","label":"C"}],"rows":[{"id":"r","cells":{"a":"x","b":2,"c":"z"},"action":{"tool":"t"}}]}""")
        val cols = ViewData.columns(json)
        assertEquals(3, cols.size)
        assertTrue(cols[1].alignEnd)
        assertEquals(listOf("a", "b"), ViewData.visibleColumns(cols, compact = true).map { it.key })
        assertEquals(3, ViewData.visibleColumns(cols, compact = false).size)
        val row = ViewData.rows(json).single()
        assertEquals("2", row.cells["b"])
        assertIs<ViewAction.Tool>(row.action)
    }

    @Test fun `action parsing accepts tool and open, rejects the rest`() {
        val t = assertIs<ViewAction.Tool>(ViewAction.parse(obj("""{"tool":"acme_mark","args":{"id":"42"}}""")))
        assertTrue(t.refresh, "refresh defaults to true")
        assertFalse(t.toast)
        assertFalse(assertIs<ViewAction.Tool>(ViewAction.parse(obj("""{"tool":"x","refresh":false,"toast":true}"""))).refresh)
        val o = assertIs<ViewAction.Open>(ViewAction.parse(obj("""{"open":{"view":"detail","params":{"id":"42"}}}""")))
        assertEquals("detail", o.view)
        for (bad in listOf("""{}""", """{"tool":"a","open":{"view":"b"}}""", """{"tool":""}""", """{"open":{}}""", """{"tool":"a","args":3}""")) {
            assertIs<ViewAction.Invalid>(ViewAction.parse(obj(bad)), bad)
        }
        assertIs<ViewAction.Invalid>(ViewAction.parse(null))
    }

    @Test fun `confirm is forced for a destructive tool and honoured when set`() {
        val kinds = ToolKinds { if (it == "acme_wipe") KIND_DESTRUCTIVE else "write" }
        val wipe = ViewAction.Tool("acme_wipe", JsonObject(emptyMap()), true, false)
        val mark = ViewAction.Tool("acme_mark", JsonObject(emptyMap()), true, false)
        assertTrue(needsConfirm(wipe, null, kinds))
        assertTrue(needsConfirm(wipe, "", kinds), "an empty confirm text does not switch it off")
        assertFalse(needsConfirm(mark, null, kinds))
        assertTrue(needsConfirm(mark, "Sure?", kinds))
        assertFalse(needsConfirm(mark, null, null))
        assertEquals("Sure?", confirmText(mark, "Sure?"))
    }

    @Test fun `a tool action calls act with the origin, refreshes after, and toasts on error or toast true`() = runBlocking {
        val access = FakeAccess()
        val log = mutableListOf<String>()
        val runner = ActionRunner(access, ViewOrigin("acme", "main"), refresh = { log += "refresh" }, toast = { log += "toast:$it" }, open = { log += "open" })
        runner.run(ViewAction.Tool("acme_mark", obj("""{"id":"42"}"""), refresh = true, toast = false))
        assertEquals(listOf("refresh"), log)
        assertEquals(ViewOrigin("acme", "main"), access.calls.single().first)
        assertEquals("acme_mark", access.calls.single().second)
        log.clear()
        runner.run(ViewAction.Tool("t", JsonObject(emptyMap()), refresh = false, toast = true))
        assertEquals(listOf("toast:ok"), log)
        log.clear()
        access.reply = Result.failure(IllegalStateException("no way"))
        runner.run(ViewAction.Tool("t", JsonObject(emptyMap()), refresh = true, toast = false))
        assertEquals(listOf("refresh", "toast:no way"), log)
    }

    @Test fun `open and invalid actions never call the tool`() = runBlocking {
        val access = FakeAccess()
        val log = mutableListOf<String>()
        val runner = ActionRunner(access, ViewOrigin("a", "v"), refresh = { log += "refresh" }, toast = { log += "toast:$it" }, open = { log += "open:${it.view}" })
        runner.run(ViewAction.Open("detail", JsonObject(emptyMap())))
        runner.run(ViewAction.Invalid("bad"))
        assertEquals(listOf("open:detail", "toast:bad"), log)
        assertTrue(access.calls.isEmpty())
    }

    @Test fun `back stack pushes and pops and never pops the root`() {
        val s = ViewBackStack(ViewRef("main"))
        assertFalse(s.canGoBack)
        assertFalse(s.back())
        s.open(ViewRef("detail", obj("""{"id":"1"}""")))
        s.open(ViewRef("more"))
        assertEquals(3, s.depth)
        assertEquals("more", s.top.view)
        assertTrue(s.back())
        assertEquals("detail", s.top.view)
        assertTrue(s.back())
        assertEquals("main", s.top.view)
        assertFalse(s.back())
    }
}
