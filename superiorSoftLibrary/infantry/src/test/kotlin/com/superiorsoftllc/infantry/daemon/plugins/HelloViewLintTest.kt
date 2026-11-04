// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.plugins

import com.superiorsoftllc.infantry.daemon.TestDaemonPlugins
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * DV-6: every page Acme_HelloView produces, and its static layout, validates against
 * plugins/official/Infantry_Views/widgets.schema.json. A failure names the node id and the field.
 */
class HelloViewLintTest {
    private val fx = HelloViewFixture()

    @AfterTest fun cleanup() = fx.close()

    private val schema: JsonObject = Json.parseToJsonElement(
        File(TestDaemonPlugins.repo, "plugins/official/Infantry_Views/widgets.schema.json").readText(),
    ).jsonObject

    private fun strings(key: String) = schema[key]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()

    /** Problems as "node ID: field: what"; empty when the page is valid. */
    private fun lintPage(page: JsonElement): List<String> {
        val out = mutableListOf<String>()
        val p = page as? JsonObject ?: return listOf("page: not an object")
        if (p["schema"]?.jsonPrimitive?.content != "1") out += "page: schema: must be 1"
        if (p["widgets"]?.jsonPrimitive?.content != schema["widgets"]!!.jsonPrimitive.content) out += "page: widgets: must be ${schema["widgets"]}"
        val root = p["root"] as? JsonObject
        if (root == null) out += "page: root: missing" else lintNode(root, out)
        return out
    }

    private fun lintNode(n: JsonObject, out: MutableList<String>) {
        val id = n["id"]?.jsonPrimitive?.content ?: "?"
        val known = strings("nodeFields")
        for (k in n.keys) if (k !in known) out += "node $id: $k: not a node field"
        val type = n["blockType"]?.jsonPrimitive?.content
        val def = schema["types"]!!.jsonObject[type ?: ""]?.jsonObject
        if (def == null) { out += "node $id: blockType: unknown type $type"; return }
        n["show"]?.let { if (it.jsonPrimitive.content !in strings("show")) out += "node $id: show: bad value ${it.jsonPrimitive.content}" }
        val cfg = n["config"] as? JsonObject ?: JsonObject(emptyMap())
        fields(id, "", def["fields"]?.jsonObject ?: JsonObject(emptyMap()), cfg, out)
        val kids = n["children"] as? JsonArray
        if (kids != null && def["children"]?.jsonPrimitive?.booleanOrNull != true) out += "node $id: children: $type takes no children"
        kids?.forEach { lintNode(it.jsonObject, out) }
    }

    private fun fields(id: String, path: String, defs: JsonObject, value: JsonObject, out: MutableList<String>) {
        for (k in value.keys) if (k !in defs) out += "node $id: $path$k: not a field here"
        for ((name, d) in defs) {
            val def = d.jsonObject
            val v = value[name]
            if (v == null) {
                if (def["required"]?.jsonPrimitive?.booleanOrNull == true) out += "node $id: $path$name: required"
                continue
            }
            check(id, "$path$name", def, v, out)
        }
    }

    private fun check(id: String, f: String, def: JsonObject, v: JsonElement, out: MutableList<String>) {
        when (val t = def["type"]!!.jsonPrimitive.content) {
            "string" -> if (v !is JsonPrimitive || !v.isString) out += "node $id: $f: expected string"
            "number" -> if (v !is JsonPrimitive || v.isString || v.content.toDoubleOrNull() == null) out += "node $id: $f: expected number"
            "boolean" -> if (v !is JsonPrimitive || v.booleanOrNull == null) out += "node $id: $f: expected boolean"
            "enum" -> {
                val allowed = def["values"]?.jsonArray?.map { it.jsonPrimitive.content } ?: strings("tones").toList()
                if (v !is JsonPrimitive || v.content !in allowed) out += "node $id: $f: $v is not one of $allowed"
            }
            "array" -> {
                if (v !is JsonArray) { out += "node $id: $f: expected array"; return }
                val item = def["item"]?.jsonObject ?: return
                v.forEachIndexed { i, e ->
                    if (e !is JsonObject) out += "node $id: $f[$i]: expected object" else fields(id, "$f[$i].", item, e, out)
                }
            }
            "object" -> {
                if (v !is JsonObject) { out += "node $id: $f: expected object"; return }
                def["fields"]?.jsonObject?.let { fields(id, "$f.", it, v, out) }
            }
            "cells" -> if (v !is JsonObject && v !is JsonArray) out += "node $id: $f: expected cells"
            "action" -> {
                val a = v as? JsonObject
                val keys = a?.keys?.filter { it == "tool" || it == "open" }.orEmpty()
                if (a == null || keys.size != 1 || a.size != 1) out += "node $id: $f: an action is exactly one of tool/open"
                else if (keys[0] == "tool" && (a["tool"] as? JsonObject)?.get("name") == null) out += "node $id: $f.tool.name: required"
                else if (keys[0] == "open" && (a["open"] as? JsonObject)?.get("view") == null) out += "node $id: $f.open.view: required"
            }
            else -> out += "node $id: $f: schema has unknown field type $t"
        }
    }

    private fun pageOf(r: JsonObject) = r["page"] ?: error("no page in $r")

    @Test fun examplePagesValidate() {
        val pages = mutableMapOf("main" to pageOf(fx.open("main")), "about" to pageOf(fx.open("about")))
        for (id in listOf("1", "2", "3")) pages["detail $id"] = pageOf(fx.open("detail", """{"id":"$id"}"""))
        for ((name, p) in pages) assertEquals(emptyList(), lintPage(p), "view $name")
    }

    @Test fun layoutFileValidates() {
        val f = File(TestDaemonPlugins.repo, "plugins/examples/Acme_HelloView/layout/about.json")
        assertEquals(emptyList(), lintPage(Json.parseToJsonElement(f.readText())))
    }

    @Test fun failureNamesNodeAndField() {
        val bad = Json.parseToJsonElement(
            """{"schema":1,"widgets":1,"root":{"blockType":"ui.badge","id":"b1","config":{"tone":"loud"}}}""",
        )
        val errs = lintPage(bad)
        assertTrue(errs.any { it.startsWith("node b1: tone:") }, errs.toString())
        assertTrue(errs.any { it.startsWith("node b1: text:") }, errs.toString())
    }
}
