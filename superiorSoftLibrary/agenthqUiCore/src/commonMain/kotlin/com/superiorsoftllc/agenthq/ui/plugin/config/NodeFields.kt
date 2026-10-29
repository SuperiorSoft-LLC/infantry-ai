package com.superiorsoftllc.agenthq.ui.plugin.config

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.agenthq.plugin.api.ConfigFieldTypes
import com.superiorsoftllc.agenthq.plugin.api.ConfigSetBy
import com.superiorsoftllc.agenthq.ui.Checkbox
import com.superiorsoftllc.agenthq.ui.Chip
import com.superiorsoftllc.agenthq.ui.FilterChip
import com.superiorsoftllc.agenthq.ui.Palette
import com.superiorsoftllc.agenthq.ui.SectionLabel
import com.superiorsoftllc.agenthq.ui.TextButton
import com.superiorsoftllc.agenthq.ui.handCursor
import com.superiorsoftllc.agenthq.ui.plugin.TabFactory
import com.superiorsoftllc.agenthq.ui.shell.parseConfigColor
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** One field row; the suffix is `<node>/<key>`. */
const val NODE_FIELD_TAG_PREFIX = "node-field-"
/** A select, multiSelect or implementation option; the suffix is `<node>/<key>/<option>`. */
const val NODE_FIELD_OPTION_TAG_PREFIX = "node-field-option-"
/** Where the value comes from (set here, a parent, the default); the suffix is `<node>/<key>`. */
const val NODE_FIELD_ORIGIN_TAG_PREFIX = "node-field-origin-"
const val NODE_FIELD_INPUT_TAG_PREFIX = "node-field-input-"
const val NODE_FIELD_SAVE_TAG_PREFIX = "node-field-save-"
const val NODE_FIELD_RESET_TAG_PREFIX = "node-field-reset-"
const val NODE_FIELD_CHECK_TAG_PREFIX = "node-field-check-"
/** One config group's tab above a node's fields; the suffix is `<node>/<group>`. */
const val NODE_GROUP_TAB_TAG_PREFIX = "node-group-tab-"
/** Why a field cannot be changed (locked node, not exposed, unknown type); the suffix is `<node>/<key>`. */
const val NODE_FIELD_NOTE_TAG_PREFIX = "node-field-note-"

/**
 * P1 step 2 (docs/BLOCKS-LAYOUT-DESIGN.md 6s A, D): the editors of [fields] (declared on [node]), one row each: label,
 * editor, where the value comes from, and Reset when this node holds its own. Writes go through [TabFactory.config];
 * with [editable] false (a locked node, or the stored copy not loaded) every editor is read-only. An implementation
 * field offers the keys registered now and only when it is `exposed`; an unknown type is shown, never edited.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NodeFields(factory: TabFactory, node: String, fields: List<DeclaredField>, editable: Boolean, locked: Boolean) {
    val scope = rememberCoroutineScope()
    val config = factory.config
    val resolved = factory.resolvedConfig
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (f in fields) {
            val tag = "$node/${f.key}"
            val value = resolved.json(node, f.key)
            val own = config.own(node, f.key)
            fun write(v: JsonElement?) { scope.launch { config.set(node, f.key, v) } }
            FlowRow(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Palette.Raised).padding(horizontal = 10.dp, vertical = 6.dp)
                    .testTag(NODE_FIELD_TAG_PREFIX + tag),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                SectionLabel(f.field.label, topGap = false)
                val note = when {
                    locked -> "Locked node"
                    f.field.type !in ConfigFieldTypes.BUILT_IN -> "Unknown type ${f.field.type}: read-only"
                    f.field.type == ConfigFieldTypes.IMPLEMENTATION && !f.field.exposed -> "Not exposed"
                    else -> null
                }
                val can = editable && note == null
                when (f.field.type) {
                    ConfigFieldTypes.CHECKBOX, ConfigFieldTypes.TOGGLE -> Checkbox(
                        (value as? JsonPrimitive)?.booleanOrNull ?: false, if (can) { on -> write(JsonPrimitive(on)) } else null, enabled = can,
                        modifier = Modifier.testTag(NODE_FIELD_CHECK_TAG_PREFIX + tag).semantics { contentDescription = f.field.label },
                    )
                    ConfigFieldTypes.TEXT, ConfigFieldTypes.NUMBER, ConfigFieldTypes.COLOR -> TextEditor(f, tag, value, can, ::write)
                    ConfigFieldTypes.SELECT, ConfigFieldTypes.RADIO -> for (o in f.field.options) Option(tag, o, f.field.optionLabels[o] ?: o, (value as? JsonPrimitive)?.content == o, can) { write(JsonPrimitive(o)) }
                    ConfigFieldTypes.MULTI_SELECT -> {
                        val picked = (value as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
                        for (o in f.field.options) Option(tag, o, o, o in picked, can) {
                            write(JsonArray((if (o in picked) picked - o else f.field.options.filter { it in picked || it == o }).map(::JsonPrimitive)))
                        }
                    }
                    ConfigFieldTypes.IMPLEMENTATION -> {
                        val registry = f.field.registry.orEmpty()
                        val current = (value as? JsonPrimitive)?.content
                        val keys = factory.implementations.keys(registry)
                        if (can) {
                            for (k in keys) Option(tag, k, k + (factory.implementations.moduleOf(registry, k)?.takeIf { it != com.superiorsoftllc.agenthq.ui.plugin.CORE_SHELL_MODULE }?.let { " ($it)" } ?: ""), k == current, true) { write(JsonPrimitive(k)) }
                        } else {
                            Chip(current ?: "none")
                        }
                        // 6s D: a stored key whose module is gone falls back to the default; say so.
                        val stored = (own?.value as? JsonPrimitive)?.content
                        if (stored != null && stored !in keys) Chip("$stored is not registered: using ${current ?: "nothing"}", Palette.Danger)
                    }
                    else -> Chip(value?.toString() ?: "unset")
                }
                if (note != null) Chip(note, textModifier = Modifier.testTag(NODE_FIELD_NOTE_TAG_PREFIX + tag))
                val origin = resolved.origin(node, f.key)
                Chip(
                    when (origin) { null -> "Default"; node -> if (own?.setBy == ConfigSetBy.USER) "Set here" else "Default (stored)"; else -> "From $origin" },
                    textModifier = Modifier.testTag(NODE_FIELD_ORIGIN_TAG_PREFIX + tag),
                )
                if (can && own?.setBy == ConfigSetBy.USER) {
                    TextButton({ write(null) }, Modifier.testTag(NODE_FIELD_RESET_TAG_PREFIX + tag).semantics { contentDescription = "Reset ${f.field.label}" }) { Text("Reset") }
                }
            }
        }
    }
}

/**
 * P1 step 2 (6s F): [fields] split by config group, one tab per group, in the merged tree's group order (core's first,
 * then each name a module adds); the picked group's fields show below through [NodeFields]. A single group still shows
 * its tab, so the view reads the same however many groups a node has.
 */
@Composable
fun NodeFieldGroups(factory: TabFactory, node: String, fields: List<DeclaredField>, editable: Boolean, locked: Boolean) {
    val order = factory.configTree.groups
    val groups = fields.map { it.group }.distinct().sortedBy { g -> order.indexOf(g).let { if (it < 0) Int.MAX_VALUE else it } }
    if (groups.isEmpty()) return
    var picked by remember(node) { mutableStateOf(groups.first()) }
    val group = picked.takeIf { it in groups } ?: groups.first()
    androidx.compose.material3.PrimaryScrollableTabRow(
        selectedTabIndex = groups.indexOf(group),
        edgePadding = 0.dp,
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        contentColor = Palette.Accent,
        modifier = Modifier.fillMaxWidth(),
    ) {
        for (g in groups) {
            val on = g == group
            androidx.compose.material3.Tab(
                selected = on, onClick = { picked = g },
                modifier = Modifier.handCursor().testTag("$NODE_GROUP_TAB_TAG_PREFIX$node/$g"),
                text = { Text(g, fontSize = 12.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, color = if (on) Palette.Accent else Palette.Muted) },
            )
        }
    }
    NodeFields(factory, node, fields.filter { it.group == group }, editable, locked)
}

@Composable
internal fun Option(tag: String, option: String, label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected, onClick = onClick, enabled = enabled,
        label = { Text(label, fontSize = 11.sp) },
        modifier = Modifier.testTag("$NODE_FIELD_OPTION_TAG_PREFIX$tag/$option"),
    )
}

/** A text or number field: typed into a draft, stored on Save; a number outside min..max (or not a number) is refused. */
@Composable
internal fun TextEditor(f: DeclaredField, tag: String, value: JsonElement?, enabled: Boolean, write: (JsonElement?) -> Unit) {
    val shown = (value as? JsonPrimitive)?.content.orEmpty()
    var draft by remember(tag, shown) { mutableStateOf(shown) }
    val number = f.field.type == ConfigFieldTypes.NUMBER
    val parsed = if (number) draft.trim().toDoubleOrNull() else null
    val min = f.field.min
    val max = f.field.max
    val problem = when {
        f.field.type == ConfigFieldTypes.COLOR -> if (parseConfigColor(draft.trim()) == null) "Use #RRGGBB or #AARRGGBB" else null
        !number -> null
        parsed == null -> "Not a number"
        min != null && parsed < min -> "At least $min"
        max != null && parsed > max -> "At most $max"
        else -> null
    }
    OutlinedTextField(
        value = draft, onValueChange = { draft = it }, singleLine = true, enabled = enabled, isError = problem != null,
        modifier = Modifier.widthIn(min = 160.dp, max = 320.dp).testTag(NODE_FIELD_INPUT_TAG_PREFIX + tag).semantics { contentDescription = f.field.label },
    )
    if (problem != null) Chip(problem, Palette.Danger)
    TextButton(
        {
            write(
                if (!number) JsonPrimitive(draft)
                else parsed?.let { if (it % 1.0 == 0.0 && '.' !in draft) JsonPrimitive(it.toLong()) else JsonPrimitive(it) },
            )
        },
        Modifier.testTag(NODE_FIELD_SAVE_TAG_PREFIX + tag), enabled = enabled && draft != shown && problem == null,
    ) { Text("Save") }
}
