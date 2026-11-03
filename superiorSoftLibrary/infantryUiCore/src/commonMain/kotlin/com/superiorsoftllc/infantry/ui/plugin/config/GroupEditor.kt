// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.plugin.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.infantry.plugin.api.ConfigFieldDeclaration
import com.superiorsoftllc.infantry.plugin.api.ConfigFieldTypes
import com.superiorsoftllc.infantry.plugin.api.ConfigFieldValidator
import com.superiorsoftllc.infantry.ui.Checkbox
import com.superiorsoftllc.infantry.ui.Chip
import com.superiorsoftllc.infantry.ui.Palette
import com.superiorsoftllc.infantry.ui.TextButton
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** One group (or one object row of a list). The suffix is `<node>/<key>` of the group, then `/<child>` for each nested field. */
const val GROUP_TAG_PREFIX = "node-group-"
/** A list-of-objects row; the suffix is `<node>/<key>/<index>`. Its buttons: `node-field-save-<node>/<key>/add` and `.../remove-<index>`. */
const val GROUP_ROW_TAG_PREFIX = "node-group-row-"

/** CONFIG-FIELDS-4 (G3): the fields of each object of a `list` field, or null when it is a list of scalars. */
fun ConfigFieldDeclaration.objectItemFields(): List<ConfigFieldDeclaration>? = when {
    item?.type == ConfigFieldTypes.GROUP -> item!!.fields
    item == null && fields.isNotEmpty() -> fields
    else -> null
}

/** The object a new list row starts as: each nested field's default that is set. */
internal fun newRow(fields: List<ConfigFieldDeclaration>): JsonObject = JsonObject(fields.mapNotNull { f -> f.default?.let { f.key to it } }.toMap())

/**
 * CONFIG-FIELDS-4 (G3): a `group` field: its nested [ConfigFieldDeclaration.fields] as a titled sub-card, each edited by
 * [NestedEditor]. Any change writes the whole object back through [write]; a key the declaration does not name is kept.
 */
@Composable
internal fun GroupEditor(fields: List<ConfigFieldDeclaration>, tag: String, value: JsonElement?, enabled: Boolean, write: (JsonElement?) -> Unit) {
    val current = value as? JsonObject ?: JsonObject(emptyMap())
    Column(Modifier.fillMaxWidth().padding(start = 12.dp).testTag(GROUP_TAG_PREFIX + tag), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (child in fields) {
            if (!ConfigFieldValidator.isVisible(child) { current[it] }) continue
            val childTag = "$tag/${child.key}"
            Text(child.label, fontSize = 11.sp)
            NestedEditor(child, childTag, current[child.key], enabled) { v ->
                write(JsonObject(if (v == null) current - child.key else current + (child.key to v)))
            }
        }
    }
}

/** CONFIG-FIELDS-4 (G3): a `list` whose items are objects: one [GroupEditor] per row, Remove per row, Add appends a row of defaults. */
@Composable
internal fun ObjectListEditor(field: ConfigFieldDeclaration, itemFields: List<ConfigFieldDeclaration>, tag: String, value: JsonElement?, enabled: Boolean, write: (JsonElement?) -> Unit) {
    val items = (value as? JsonArray).orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEachIndexed { i, row ->
            Column(Modifier.testTag("$GROUP_ROW_TAG_PREFIX$tag/$i")) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${field.label} ${i + 1}", fontSize = 11.sp)
                    TextButton({ write(JsonArray(items.filterIndexed { j, _ -> j != i })) }, Modifier.testTag("$NODE_FIELD_SAVE_TAG_PREFIX$tag/remove-$i").semantics { contentDescription = "Remove ${field.label} ${i + 1}" }, enabled = enabled) { Text("Remove") }
                }
                GroupEditor(itemFields, "$tag/$i", row, enabled) { v -> write(JsonArray(items.mapIndexed { j, it -> if (j == i && v != null) v else it })) }
            }
        }
        TextButton({ write(JsonArray(items + newRow(itemFields))) }, Modifier.testTag("$NODE_FIELD_SAVE_TAG_PREFIX$tag/add").semantics { contentDescription = "Add ${field.label}" }, enabled = enabled) { Text("Add") }
    }
}

/** CONFIG-FIELDS-4: the editor of one nested field (the built-in scalar types, a nested group or object list); anything else is shown, not edited. */
@Composable
internal fun NestedEditor(field: ConfigFieldDeclaration, tag: String, value: JsonElement?, enabled: Boolean, write: (JsonElement?) -> Unit) {
    val declared = DeclaredField("", "", "", field)
    when (field.type) {
        ConfigFieldTypes.CHECKBOX, ConfigFieldTypes.TOGGLE -> Checkbox(
            (value as? JsonPrimitive)?.booleanOrNull ?: false, if (enabled) { on -> write(JsonPrimitive(on)) } else null, enabled = enabled,
            modifier = Modifier.testTag(NODE_FIELD_CHECK_TAG_PREFIX + tag).semantics { contentDescription = field.label },
        )
        ConfigFieldTypes.TEXT, ConfigFieldTypes.TEXTAREA, ConfigFieldTypes.NUMBER, ConfigFieldTypes.COLOR -> TextEditor(declared, tag, value, enabled, write)
        ConfigFieldTypes.SELECT, ConfigFieldTypes.RADIO ->
            if (field.type == ConfigFieldTypes.SELECT && field.display == "dropdown") SelectDropdown(field, tag, value, enabled, write)
            else for (o in field.options) Option(tag, o, field.optionLabels[o] ?: o, (value as? JsonPrimitive)?.content == o, enabled) { write(JsonPrimitive(o)) }
        ConfigFieldTypes.GROUP -> GroupEditor(field.fields, tag, value, enabled, write)
        ConfigFieldTypes.LIST -> field.objectItemFields()?.let { ObjectListEditor(field, it, tag, value, enabled, write) } ?: ListEditor(declared, tag, value, enabled, write)
        else -> Chip(value?.toString() ?: "unset")
    }
}
