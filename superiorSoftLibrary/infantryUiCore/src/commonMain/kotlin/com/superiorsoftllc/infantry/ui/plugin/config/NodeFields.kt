// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.plugin.config

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import com.superiorsoftllc.infantry.ui.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.semantics.Role
import com.superiorsoftllc.infantry.ui.DropdownMenuItem
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
import com.superiorsoftllc.infantry.plugin.api.ConfigFieldTypes
import com.superiorsoftllc.infantry.plugin.api.ConfigFieldValidator
import com.superiorsoftllc.infantry.plugin.api.ConfigSetBy
import com.superiorsoftllc.infantry.ui.Checkbox
import com.superiorsoftllc.infantry.ui.Chip
import com.superiorsoftllc.infantry.ui.FilterChip
import com.superiorsoftllc.infantry.ui.Palette
import com.superiorsoftllc.infantry.ui.SectionLabel
import com.superiorsoftllc.infantry.ui.TextButton
import com.superiorsoftllc.infantry.ui.handCursor
import com.superiorsoftllc.infantry.ui.plugin.TabFactory
import com.superiorsoftllc.infantry.ui.shell.parseConfigColor
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import androidx.compose.foundation.layout.Row
import com.superiorsoftllc.infantry.plugin.api.ConfigFieldDeclaration
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
            if (!ConfigFieldValidator.isVisible(f.field) { resolved.json(node, it) }) continue
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
                    f.field.type !in ConfigFieldTypes.BUILT_IN && factory.fieldTypes[f.field.type] == null -> "Unknown type ${f.field.type}: read-only"
                    f.field.type == ConfigFieldTypes.IMPLEMENTATION && !f.field.exposed -> "Not exposed"
                    else -> null
                }
                val can = editable && note == null
                when (f.field.type) {
                    ConfigFieldTypes.CHECKBOX, ConfigFieldTypes.TOGGLE -> Checkbox(
                        (value as? JsonPrimitive)?.booleanOrNull ?: false, if (can) { on -> write(JsonPrimitive(on)) } else null, enabled = can,
                        modifier = Modifier.testTag(NODE_FIELD_CHECK_TAG_PREFIX + tag).semantics { contentDescription = f.field.label },
                    )
                    ConfigFieldTypes.TEXT, ConfigFieldTypes.TEXTAREA, ConfigFieldTypes.NUMBER, ConfigFieldTypes.COLOR -> TextEditor(f, tag, value, can, ::write)
                    ConfigFieldTypes.SELECT, ConfigFieldTypes.RADIO -> if (f.field.type == ConfigFieldTypes.SELECT && f.field.display == "dropdown") SelectDropdown(f.field, tag, value, can, ::write) else for (o in f.field.options) Option(tag, o, f.field.optionLabels[o] ?: o, (value as? JsonPrimitive)?.content == o, can) { write(JsonPrimitive(o)) }
                    ConfigFieldTypes.MULTI_SELECT -> {
                        val picked = (value as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
                        for (o in f.field.options) Option(tag, o, o, o in picked, can) {
                            write(JsonArray((if (o in picked) picked - o else f.field.options.filter { it in picked || it == o }).map(::JsonPrimitive)))
                        }
                    }
                    ConfigFieldTypes.CLASSES -> ClassesEditor(f, tag, value, can, ::write)
                    ConfigFieldTypes.LIST -> f.field.objectItemFields()?.let { ObjectListEditor(f.field, it, tag, value, can, ::write) } ?: ListEditor(f, tag, value, can, ::write)
                    ConfigFieldTypes.GROUP -> GroupEditor(f.field.fields, tag, value, can, ::write)
                    ConfigFieldTypes.IMPLEMENTATION -> {
                        val registry = f.field.registry.orEmpty()
                        val current = (value as? JsonPrimitive)?.content
                        val keys = factory.implementations.keys(registry)
                        if (can) {
                            for (k in keys) Option(tag, k, k + (factory.implementations.moduleOf(registry, k)?.takeIf { it != com.superiorsoftllc.infantry.ui.plugin.CORE_SHELL_MODULE }?.let { " ($it)" } ?: ""), k == current, true) { write(JsonPrimitive(k)) }
                        } else {
                            Chip(current ?: "none")
                        }
                        // 6s D: a stored key whose module is gone falls back to the default; say so.
                        val stored = (own?.value as? JsonPrimitive)?.content
                        if (stored != null && stored !in keys) Chip("$stored is not registered: using ${current ?: "nothing"}", Palette.Danger)
                    }
                    else -> factory.fieldTypes[f.field.type]?.let { RegisteredEditor(factory, f.field, value, can, ::write) } ?: Chip(value?.toString() ?: "unset")
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

/** CONFIG-FIELDS-2: a `select` with `display: "dropdown"`: the current label on a button, the options in a menu (item tag `node-field-option-<node>/<key>/<option>`). */
@Composable
internal fun SelectDropdown(field: ConfigFieldDeclaration, tag: String, value: JsonElement?, enabled: Boolean, write: (JsonElement?) -> Unit) {
    var open by remember(tag) { mutableStateOf(false) }
    val current = (value as? JsonPrimitive)?.content
    Box {
        TextButton({ open = true }, Modifier.testTag(NODE_FIELD_INPUT_TAG_PREFIX + tag).semantics { contentDescription = field.label }, enabled = enabled) {
            Text((current?.let { field.optionLabels[it] ?: it } ?: field.placeholder ?: "Choose") + " ▾")
        }
        com.superiorsoftllc.infantry.ui.InfantryDropdownMenu(open, { open = false }) {
            for (o in field.options) DropdownMenuItem(
                { Text(field.optionLabels[o] ?: o, fontSize = 12.sp) },
                { open = false; write(JsonPrimitive(o)) },
                Modifier.testTag("$NODE_FIELD_OPTION_TAG_PREFIX$tag/$o"),
            )
        }
    }
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
    val area = f.field.type == ConfigFieldTypes.TEXTAREA
    val parsed = if (number) draft.trim().toDoubleOrNull() else null
    val min = f.field.min
    val max = f.field.max
    val problem = when {
        f.field.type == ConfigFieldTypes.COLOR && parseConfigColor(draft.trim()) == null -> "Use #RRGGBB or #AARRGGBB"
        !number -> ConfigFieldValidator.checkRules(f.field, JsonPrimitive(draft))
        draft.isBlank() -> if (f.field.required) "Required" else "Not a number"
        parsed == null -> "Not a number"
        min != null && parsed < min -> "At least $min"
        max != null && parsed > max -> "At most $max"
        else -> null
    }
    OutlinedTextField(
        value = draft, onValueChange = { draft = it }, singleLine = !area, minLines = if (area) 3 else 1, enabled = enabled, isError = problem != null,
        placeholder = f.field.placeholder?.let { { Text(it, fontSize = 12.sp) } },
        modifier = Modifier.widthIn(min = 160.dp, max = 320.dp).testTag(NODE_FIELD_INPUT_TAG_PREFIX + tag).semantics { contentDescription = f.field.label },
    )
    if (problem != null) Chip(problem, Palette.Danger)
    for (hex in f.field.palette) {
        val argb = parseConfigColor(hex) ?: continue
        Box(
            Modifier.size(20.dp).clip(RoundedCornerShape(4.dp)).background(androidx.compose.ui.graphics.Color(argb.toInt()))
                .border(1.dp, if (hex.equals(draft.trim(), ignoreCase = true)) Palette.Accent else Palette.Muted, RoundedCornerShape(4.dp))
                .clickable(enabled = enabled, role = Role.Button) { draft = hex; write(JsonPrimitive(hex)) }.handCursor(enabled)
                .testTag("$NODE_FIELD_OPTION_TAG_PREFIX$tag/$hex").semantics { contentDescription = "${f.field.label} $hex" },
        )
    }
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

/**
 * CONFIG-9: a `classes` field: one row per slot. A slot with a token allow-list offers exactly those tokens (multi-pick);
 * a slot with none is free text. A token outside the list is refused by [ConfigFieldValidator], so it never reaches a block.
 */
@Composable
internal fun ClassesEditor(f: DeclaredField, tag: String, value: JsonElement?, enabled: Boolean, write: (JsonElement?) -> Unit) {
    val current = (value as? JsonObject).orEmpty()
    fun tokens(slot: String) = (current[slot] as? JsonPrimitive)?.content.orEmpty().split(' ').filter { it.isNotEmpty() }
    fun put(slot: String, text: String) = write(JsonObject(current + (slot to JsonPrimitive(text))))
    for ((slot, decl) in f.field.slots) {
        val picked = tokens(slot)
        Text(slot, fontSize = 11.sp)
        if (decl.tokens.isNotEmpty()) {
            for (t in decl.tokens) Option("$tag/$slot", t, t, t in picked, enabled) {
                put(slot, decl.tokens.filter { (it in picked) != (it == t) }.joinToString(" "))
            }
        } else {
            var draft by remember(tag, slot, picked) { mutableStateOf(picked.joinToString(" ")) }
            OutlinedTextField(
                value = draft, onValueChange = { draft = it }, singleLine = true, enabled = enabled,
                modifier = Modifier.widthIn(min = 160.dp, max = 320.dp).testTag("$NODE_FIELD_INPUT_TAG_PREFIX$tag/$slot").semantics { contentDescription = "${f.field.label} $slot" },
            )
            TextButton({ put(slot, draft.trim()) }, Modifier.testTag("$NODE_FIELD_SAVE_TAG_PREFIX$tag/$slot"), enabled = enabled && draft.trim() != picked.joinToString(" ")) { Text("Save") }
        }
    }
}

/** CONFIG-9: a `list` field of text items: each row removable, one box adds. Items are checked against the field's `item` before they are written. */
@Composable
internal fun ListEditor(f: DeclaredField, tag: String, value: JsonElement?, enabled: Boolean, write: (JsonElement?) -> Unit) {
    val items = (value as? JsonArray).orEmpty()
    var draft by remember(tag, items) { mutableStateOf("") }
    val item = (f.field.item ?: ConfigFieldDeclaration(f.key, ConfigFieldTypes.TEXT)).copy(key = f.key)
    val problem = if (draft.isBlank()) null else FieldTypes.validateBuiltIn(item, JsonPrimitive(draft))
    items.forEachIndexed { i, it ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text((it as? JsonPrimitive)?.content ?: it.toString(), fontSize = 11.sp)
            TextButton({ write(JsonArray(items.filterIndexed { j, _ -> j != i })) }, Modifier.testTag("$NODE_FIELD_SAVE_TAG_PREFIX$tag/remove-$i"), enabled = enabled) { Text("Remove") }
        }
    }
    OutlinedTextField(
        value = draft, onValueChange = { draft = it }, singleLine = true, enabled = enabled, isError = problem != null,
        modifier = Modifier.widthIn(min = 160.dp, max = 320.dp).testTag(NODE_FIELD_INPUT_TAG_PREFIX + tag).semantics { contentDescription = f.field.label },
    )
    if (problem != null) Chip(problem, Palette.Danger)
    TextButton({ write(JsonArray(items + JsonPrimitive(draft))) }, Modifier.testTag(NODE_FIELD_SAVE_TAG_PREFIX + tag), enabled = enabled && draft.isNotBlank() && problem == null) { Text("Add") }
}

/**
 * CONFIG-5: the editor a module registered for a custom field type. A value its type's `validate` refuses is not
 * written; the reason shows under the editor.
 */
@Composable
internal fun RegisteredEditor(factory: TabFactory, field: com.superiorsoftllc.infantry.plugin.api.ConfigFieldDeclaration, value: JsonElement?, enabled: Boolean, write: (JsonElement?) -> Unit) {
    val type = factory.fieldTypes[field.type] ?: return
    var problem by remember(field.key, value) { mutableStateOf<String?>(null) }
    type.Editor(field, value, enabled) { next ->
        val why = factory.fieldTypes.validate(field, next)
        problem = why
        if (why == null) write(next)
    }
    problem?.let { Chip(it, Palette.Danger) }
}
