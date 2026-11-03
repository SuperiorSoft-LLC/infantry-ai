// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.views

import com.superiorsoftllc.infantry.plugin.api.ViewInfo
import com.superiorsoftllc.infantry.ui.block.layout.Block
import com.superiorsoftllc.infantry.ui.block.layout.Show
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DV-3: a daemon page becomes blocks: known and unknown widgets, id prefix, props, the newer-level flag, the tab filter. */
class ViewBuilderTest {
    private fun page(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun ids(b: Block): List<String> = listOf(b.id) + b.children.flatMap(::ids)
    private fun find(b: Block, id: String): Block? = if (b.id == id) b else b.children.firstNotNullOfOrNull { find(it, id) }

    private val sample = page(
        """{"schema":1,"widgets":1,"root":{"blockType":"layout.column","id":"root","children":[
          {"blockType":"ui.section","id":"s","config":{"title":"Hello"},"children":[
            {"blockType":"ui.text","id":"t","config":{"text":"Hi","tone":"good"}},
            {"blockType":"ui.stat","id":"st","config":{"label":"Agents","value":"3"},"show":"Wide"}]},
          {"blockType":"ui.chart","id":"c","fallback":"A chart is here","children":[{"blockType":"ui.text","id":"hidden"}]},
          {"blockType":"ui.gauge","id":"g"}]}}""",
    )

    @Test fun `known and unknown widgets build, unknown ones become a fallback block without children`() {
        val built = ViewBuilder.build("acme.hello", "main", sample, ViewWidgets.types)
        val root = assertNotNull(built.root)
        val p = "view:acme.hello/main/"
        assertEquals(listOf("root", "s", "t", "st", "c", "g").map { p + it }, ids(root))
        val chart = assertNotNull(find(root, p + "c"))
        assertEquals(UNKNOWN_TYPE, chart.blockType)
        assertTrue(chart.children.isEmpty())
        assertNull(find(root, p + "hidden"))
        assertEquals("A chart is here", built.props.getValue(p + "c")["text"]?.let { (it as JsonPrimitive).content })
        assertEquals("ui.gauge needs a newer app", (built.props.getValue(p + "g")["text"] as JsonPrimitive).content)
        assertTrue(built.problems.isEmpty(), built.problems.toString())
    }

    @Test fun `every node id is prefixed with the module and view`() {
        val root = assertNotNull(ViewBuilder.build("m", "v", sample, ViewWidgets.types).root)
        assertTrue(ids(root).all { it.startsWith("view:m/v/") })
    }

    @Test fun `props reach the widget by prefixed id and show is honoured`() {
        val built = ViewBuilder.build("m", "v", sample, ViewWidgets.types)
        val t = built.props.getValue("view:m/v/t")
        assertEquals("good", (t["tone"] as JsonPrimitive).content)
        assertEquals("Hi", Props(t).string("text"))
        assertEquals("body", Props(t).oneOf("style", listOf("body", "title"), "body"))
        assertEquals("default", Props(t).oneOf("tone", listOf("default"), "default"), "an unknown enum value reads as the default")
        assertEquals(Show.Wide, find(assertNotNull(built.root), "view:m/v/st")!!.place.show)
        assertEquals(Show.Always, find(built.root!!, "view:m/v/t")!!.place.show)
    }

    @Test fun `a page written for a newer level sets the banner flag and is still built`() {
        val newer = page("""{"schema":1,"widgets":${WIDGETS_LEVEL + 1},"root":{"blockType":"ui.text","id":"a","config":{"text":"x"}}}""")
        val built = ViewBuilder.build("m", "v", newer, ViewWidgets.types)
        assertTrue(built.newerLevel)
        assertNotNull(built.root)
        assertFalse(ViewBuilder.build("m", "v", sample, ViewWidgets.types).newerLevel)
    }

    @Test fun `a broken page never throws`() {
        val built = ViewBuilder.build("m", "v", page("""{"root":{"blockType":"ui.text"}}"""), ViewWidgets.types)
        assertNull(built.root)
        assertTrue(built.problems.isNotEmpty())
        ViewBuilder.build("m", "v", JsonObject(emptyMap()), ViewWidgets.types)
    }

    @Test fun `the Views tab lists place tab views for this platform only`() {
        val all = listOf(
            ViewInfo("a", "main", "Main", "tab"),
            ViewInfo("a", "detail", "Detail", "none"),
            ViewInfo("a", "about", "About", "tab", listOf("android", "web")),
            ViewInfo("a", "desk", "Desk", "tab", listOf("desktop")),
        )
        assertEquals(listOf("main", "desk"), tabViews(all, "desktop").map { it.id })
        assertEquals(listOf("main", "about"), tabViews(all, "web").map { it.id })
        assertTrue(tabViews(emptyList(), "desktop").isEmpty())
    }
}
