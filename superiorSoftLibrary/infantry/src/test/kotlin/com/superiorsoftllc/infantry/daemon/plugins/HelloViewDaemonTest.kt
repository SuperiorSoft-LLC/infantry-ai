// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.plugins

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** DV-6: the Acme_HelloView example answers its views and pushes plugin_view_changed after its tool mutates state. */
class HelloViewDaemonTest {
    private val fx = HelloViewFixture()

    @AfterTest fun cleanup() = fx.close()

    private fun changedCount() = fx.got.count { it.type == DaemonPluginHost.PLUGIN_VIEW_CHANGED }

    @Test fun mainAndDetailAreServed() {
        val main = fx.open("main")
        assertEquals("ui.section", main["page"]!!.jsonObject["root"]!!.jsonObject["blockType"]!!.jsonPrimitive.content)
        val detail = fx.open("detail", """{"id":"1"}""")
        assertTrue(detail["page"] != null, "detail with an id answers a page: $detail")
    }

    @Test fun detailWithoutIdIsRefused() {
        val r = fx.open("detail")
        assertTrue(r["error"]!!.jsonPrimitive.content.contains("refused"), r.toString())
        assertTrue(fx.open("detail", """{"id":"nope"}""")["error"] != null)
    }

    @Test fun aboutIsServedFromTheLayoutFile() {
        val r = fx.open("about")
        assertEquals("about", r["page"]!!.jsonObject["root"]!!.jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test fun markPushesChangedForMain() {
        fx.open("main")
        val before = changedCount()
        val res = fx.host.callTool("acme_helloview_mark", buildJsonObject { put("id", "1") }, fx.owner) { _, _ -> error("no request expected") }
        assertFalse(res.isError, res.toString())
        val end = System.currentTimeMillis() + 5000
        while (changedCount() == before && System.currentTimeMillis() < end) Thread.sleep(10)
        assertTrue(changedCount() > before, "a plugin_view_changed arrives after the tool")
        val pushed = fx.got.last { it.type == DaemonPluginHost.PLUGIN_VIEW_CHANGED }
        assertEquals("main", Json.parseToJsonElement(pushed.data).jsonObject["view"]!!.jsonPrimitive.content)
        assertTrue(fx.open("detail", """{"id":"1"}""").toString().contains("finished"), "item 1 now shows as done")
    }
}
