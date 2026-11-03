// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.views

import com.superiorsoftllc.infantry.plugin.api.ViewAccess
import com.superiorsoftllc.infantry.plugin.api.ViewOrigin
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** DV-4 (DECL-UI-DESIGN 1.2): the `action` object of a button, list item, table row. Exactly one of tool / open. */
sealed interface ViewAction {
    data class Tool(val tool: String, val args: JsonObject, val refresh: Boolean, val toast: Boolean) : ViewAction
    data class Open(val view: String, val params: JsonObject) : ViewAction
    /** Anything else: [line] is the one-line problem; the control is disabled. */
    data class Invalid(val line: String) : ViewAction

    companion object {
        fun parse(el: JsonElement?): ViewAction {
            val o = el as? JsonObject ?: return Invalid("action is missing or not an object")
            val hasTool = "tool" in o
            val hasOpen = "open" in o
            if (hasTool == hasOpen) return Invalid("action needs exactly one of tool or open")
            if (hasTool) {
                val tool = (o["tool"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return Invalid("action tool must be a name")
                val args = o["args"]?.let { it as? JsonObject ?: return Invalid("action args must be an object") } ?: JsonObject(emptyMap())
                return Tool(tool, args, (o["refresh"] as? JsonPrimitive)?.booleanOrNull ?: true, (o["toast"] as? JsonPrimitive)?.booleanOrNull ?: false)
            }
            val open = o["open"] as? JsonObject ?: return Invalid("action open must be an object")
            val view = (open["view"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return Invalid("action open needs a view")
            val params = open["params"]?.let { it as? JsonObject ?: return Invalid("action open params must be an object") } ?: JsonObject(emptyMap())
            return Open(view, params)
        }
    }
}

/** The action kind of a daemon tool (`plugin_tools` metadata); null = unknown. Provided by the host, default none. */
fun interface ToolKinds { fun kindOf(tool: String): String? }

const val KIND_DESTRUCTIVE = "destructive"

/** Whether the click must ask first: the view said so, or the tool is destructive (always). */
fun needsConfirm(action: ViewAction, confirm: String?, kinds: ToolKinds?): Boolean =
    !confirm.isNullOrBlank() || (action is ViewAction.Tool && kinds?.kindOf(action.tool) == KIND_DESTRUCTIVE)

/** The text of the confirm dialog. */
fun confirmText(action: ViewAction, confirm: String?): String =
    confirm?.takeIf { it.isNotBlank() } ?: ((action as? ViewAction.Tool)?.let { "Run ${it.tool}? This cannot be undone." } ?: "Are you sure?")

/** One view on a tab's back stack. */
data class ViewRef(val view: String, val params: JsonObject = JsonObject(emptyMap()))

/** Per-tab back stack: the root view first. [open] pushes, [back] pops (never the root). */
class ViewBackStack(root: ViewRef) {
    private val items = mutableListOf(root)
    val top: ViewRef get() = items.last()
    val canGoBack: Boolean get() = items.size > 1
    val depth: Int get() = items.size
    fun open(ref: ViewRef) { items += ref }
    fun back(): Boolean = if (canGoBack) { items.removeAt(items.lastIndex); true } else false
}

/** Runs actions for one view. Pure: the UI supplies [refresh] and [toast]; a test supplies fakes. Never throws. */
class ActionRunner(
    private val access: ViewAccess,
    private val origin: ViewOrigin,
    private val refresh: () -> Unit,
    private val toast: (String) -> Unit,
    private val open: (ViewRef) -> Unit,
) {
    suspend fun run(action: ViewAction) {
        when (action) {
            is ViewAction.Invalid -> toast(action.line)
            is ViewAction.Open -> open(ViewRef(action.view, action.params))
            is ViewAction.Tool -> {
                val r = runCatching { access.act(origin, action.tool, action.args) }.getOrElse { Result.failure(it) }
                if (action.refresh) refresh()
                if (r.isFailure) toast(r.exceptionOrNull()?.message ?: "failed")
                else if (action.toast) r.getOrNull()?.takeIf { it.isNotBlank() }?.let(toast)
            }
        }
    }
}

/** Parsed `ui.list` / `ui.table` data, pure so a test can check it. */
data class ListItem(val id: String, val title: String, val subtitle: String?, val badgeText: String?, val badgeTone: String, val action: ViewAction?)
data class TableColumn(val key: String, val label: String, val alignEnd: Boolean)
data class TableRow(val id: String, val cells: Map<String, String>, val action: ViewAction?)

object ViewData {
    private fun str(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.contentOrNull

    fun items(json: JsonObject): List<ListItem> = (json["items"] as? JsonArray).orEmpty().mapIndexedNotNull { i, e ->
        val o = e as? JsonObject ?: return@mapIndexedNotNull null
        val badge = o["badge"] as? JsonObject
        ListItem(
            str(o, "id") ?: i.toString(), str(o, "title") ?: "", str(o, "subtitle"), badge?.let { str(it, "text") },
            badge?.let { str(it, "tone") }?.takeIf { it in Tones.ALL } ?: "default", o["action"]?.let(ViewAction::parse),
        )
    }

    fun columns(json: JsonObject): List<TableColumn> = (json["columns"] as? JsonArray).orEmpty().mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        val key = str(o, "key") ?: return@mapNotNull null
        TableColumn(key, str(o, "label") ?: key, str(o, "align") == "end")
    }

    fun rows(json: JsonObject): List<TableRow> = (json["rows"] as? JsonArray).orEmpty().mapIndexedNotNull { i, e ->
        val o = e as? JsonObject ?: return@mapIndexedNotNull null
        val cells = (o["cells"] as? JsonObject).orEmpty().mapValues { (_, v) -> (v as? JsonPrimitive)?.contentOrNull ?: "" }
        TableRow(str(o, "id") ?: i.toString(), cells, o["action"]?.let(ViewAction::parse))
    }

    /** Compact width shows the first two columns. */
    fun visibleColumns(all: List<TableColumn>, compact: Boolean) = if (compact) all.take(2) else all
}
