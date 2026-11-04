// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.acme.helloview

import com.superiorsoftllc.infantry.daemon.api.DaemonApi
import com.superiorsoftllc.infantry.daemon.api.DaemonPlugin
import com.superiorsoftllc.infantry.daemon.api.DaemonTool
import com.superiorsoftllc.infantry.daemon.api.ToolCall
import com.superiorsoftllc.infantry.daemon.api.ToolResult
import com.superiorsoftllc.infantry.daemon.api.ViewReply
import com.superiorsoftllc.infantry.daemon.api.ViewRequest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * DV-6: the reference declarative-view plugin. Views `main` (tab) and `detail` (reached only by `open`) are answered here;
 * `about` is the static file layout/about.json (no provider). The tools change state, then call `api.views.changed("main")`.
 */
class HelloViewDaemon : DaemonPlugin {
    private class Item(val id: String, val title: String, var done: Boolean)

    private val lock = Any()
    private val items = mutableListOf(Item("1", "Say hello", false), Item("2", "Read the docs", false), Item("3", "Ship a plugin", false))
    private var nextId = 4

    override fun start(api: DaemonApi) {
        api.tools.register(Mark(api))
        api.tools.register(Remove(api))
        api.views.provide("main") { main() }
        api.views.provide("detail") { detail(it) }
    }

    private fun main(): ViewReply {
        val snapshot = synchronized(lock) { items.map { Item(it.id, it.title, it.done) } }
        val rows = snapshot.map {
            listItem(
                it.id, it.title, subtitle = "item ${it.id}",
                badgeText = if (it.done) "done" else "open", badgeTone = if (it.done) "good" else "warn",
                action = openAction("detail", buildJsonObject { put("id", it.id) }),
            )
        }
        val open = snapshot.count { !it.done }
        return ViewReply.Page(page(section("root", "Hello", "$open open of ${snapshot.size}", listOf(
            list("items", rows, empty = "Nothing to do."),
            button("add", "Add an item", toolAction("acme_helloview_mark"), style = "primary"),
        ))))
    }

    private fun detail(req: ViewRequest): ViewReply {
        val id = (req.params["id"] as? JsonPrimitive)?.contentOrNull ?: return ViewReply.Refused("detail needs the param id")
        val item = synchronized(lock) { items.firstOrNull { it.id == id }?.let { Item(it.id, it.title, it.done) } }
            ?: return ViewReply.Refused("no item $id")
        val idArg = buildJsonObject { put("id", id) }
        return ViewReply.Page(page(section("root", item.title, null, listOf(
            stat("state", "State", if (item.done) "done" else "open", tone = if (item.done) "good" else "warn", hint = "item $id"),
            badge("badge", if (item.done) "finished" else "pending", if (item.done) "good" else "muted"),
            button("mark", "Mark done", toolAction("acme_helloview_mark", idArg), style = "primary", enabled = !item.done),
            button("remove", "Remove", toolAction("acme_helloview_remove", idArg), style = "danger", confirm = "Remove this item for good?"),
        ))))
    }

    private fun schema(idDesc: String) = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject { put("id", buildJsonObject { put("type", "string"); put("description", idDesc) }) })
        put("required", buildJsonArray {})
        put("additionalProperties", false)
    }

    private fun idOf(call: ToolCall) = (call.arguments["id"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private inner class Mark(private val api: DaemonApi) : DaemonTool {
        override val name = "acme_helloview_mark"
        override val description = "Marks an item as done, or adds a new item when no id is given."
        override val inputSchema: JsonObject = schema("item id; omit to add an item")
        override fun call(call: ToolCall): ToolResult {
            val id = idOf(call)
            val msg = synchronized(lock) {
                if (id == null) {
                    val n = nextId++
                    items.add(Item(n.toString(), "New item $n", false))
                    "Added item $n."
                } else {
                    val found = items.firstOrNull { it.id == id } ?: return ToolResult("No item $id.", isError = true)
                    found.done = true
                    "Marked item $id done."
                }
            }
            api.views.changed("main")
            return ToolResult(msg)
        }
    }

    private inner class Remove(private val api: DaemonApi) : DaemonTool {
        override val name = "acme_helloview_remove"
        override val description = "Removes an item for good."
        override val inputSchema: JsonObject = schema("item id")
        override fun call(call: ToolCall): ToolResult {
            val id = idOf(call) ?: return ToolResult("id is required.", isError = true)
            val gone = synchronized(lock) { items.removeIf { it.id == id } }
            if (!gone) return ToolResult("No item $id.", isError = true)
            api.views.changed("main")
            return ToolResult("Removed item $id.")
        }
    }
}
