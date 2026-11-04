// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.acme.helloview

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Copyable builders for the v1 widget vocabulary (docs/wiki/declarative-views.md). Each returns the node's JsonObject.

fun node(type: String, id: String, config: JsonObject = JsonObject(emptyMap()), children: List<JsonObject> = emptyList(), fallback: String? = null): JsonObject =
    buildJsonObject {
        put("blockType", type)
        put("id", id)
        put("config", config)
        if (children.isNotEmpty()) put("children", JsonArray(children))
        if (fallback != null) put("fallback", fallback)
    }

/** The page envelope: schema 1, vocabulary level 1. */
fun page(root: JsonObject): JsonObject = buildJsonObject { put("schema", 1); put("widgets", 1); put("root", root) }

private fun cfg(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
    for ((k, v) in pairs) when (v) {
        null -> {}
        is JsonElement -> put(k, v)
        is Boolean -> put(k, v)
        is Number -> put(k, v)
        else -> put(k, v.toString())
    }
}

fun section(id: String, title: String? = null, subtitle: String? = null, children: List<JsonObject>) =
    node("ui.section", id, cfg("title" to title, "subtitle" to subtitle), children)

fun text(id: String, text: String, style: String? = null, tone: String? = null) =
    node("ui.text", id, cfg("text" to text, "style" to style, "tone" to tone))

fun badge(id: String, text: String, tone: String = "default") = node("ui.badge", id, cfg("text" to text, "tone" to tone))

fun stat(id: String, label: String, value: String, tone: String? = null, hint: String? = null) =
    node("ui.stat", id, cfg("label" to label, "value" to value, "tone" to tone, "hint" to hint))

/** An action that calls one of this module's own tools. */
fun toolAction(tool: String, args: JsonObject = JsonObject(emptyMap()), refresh: Boolean = true, toast: Boolean = false): JsonObject =
    buildJsonObject {
        put("tool", buildJsonObject {
            put("name", tool); put("args", args); put("refresh", refresh)
            if (toast) put("toast", true)
        })
    }

/** An action that opens another view of this module. */
fun openAction(view: String, params: JsonObject = JsonObject(emptyMap())): JsonObject =
    buildJsonObject { put("open", buildJsonObject { put("view", view); put("params", params) }) }

fun listItem(id: String, title: String, subtitle: String? = null, badgeText: String? = null, badgeTone: String = "default", action: JsonObject? = null): JsonObject =
    buildJsonObject {
        put("id", id)
        put("title", title)
        if (subtitle != null) put("subtitle", subtitle)
        if (badgeText != null) put("badge", buildJsonObject { put("text", badgeText); put("tone", badgeTone) })
        if (action != null) put("action", action)
    }

fun list(id: String, items: List<JsonObject>, empty: String? = null) = node("ui.list", id, cfg("items" to JsonArray(items), "empty" to empty))

fun button(id: String, label: String, action: JsonObject, style: String? = null, confirm: String? = null, enabled: Boolean? = null) =
    node("ui.button", id, cfg("label" to label, "action" to action, "style" to style, "confirm" to confirm, "enabled" to enabled))
