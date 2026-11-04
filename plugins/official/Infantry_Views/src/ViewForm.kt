// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.views

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.superiorsoftllc.infantry.plugin.api.BlockTypeDeclaration
import com.superiorsoftllc.infantry.plugin.api.ConfigFieldDeclaration
import com.superiorsoftllc.infantry.plugin.api.ConfigFieldTypes
import com.superiorsoftllc.infantry.plugin.api.ConfigFieldValidator
import com.superiorsoftllc.infantry.ui.block.layout.Block
import com.superiorsoftllc.infantry.ui.block.layout.BlockScope
import com.superiorsoftllc.infantry.ui.block.layout.BlockType
import com.superiorsoftllc.infantry.ui.block.layout.Place
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** DV-5 (DECL-UI-DESIGN 1.3): the v1 field types a `ui.form` edits. Every other type is shown read-only. */
val FORM_EDITABLE_TYPES: Set<String> = setOf(
    ConfigFieldTypes.TEXT, ConfigFieldTypes.TEXTAREA, ConfigFieldTypes.NUMBER, ConfigFieldTypes.CHECKBOX,
    ConfigFieldTypes.TOGGLE, ConfigFieldTypes.SELECT, ConfigFieldTypes.RADIO, ConfigFieldTypes.MULTI_SELECT,
)

/** One parsed form field; [editable] false = drawn read-only (a type outside [FORM_EDITABLE_TYPES]). */
data class FormField(val decl: ConfigFieldDeclaration) {
    val key: String get() = decl.key
    val editable: Boolean get() = decl.type in FORM_EDITABLE_TYPES
}

data class FormSubmit(val label: String, val action: ViewAction)

/** The parsed `ui.form` node config. Pure, so a test can check it. */
data class FormModel(val fields: List<FormField>, val values: JsonObject, val submit: FormSubmit) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        fun parse(config: JsonObject): FormModel {
            val fields = (config["fields"] as? JsonArray).orEmpty().mapNotNull { e ->
                runCatching { json.decodeFromJsonElement(ConfigFieldDeclaration.serializer(), e) }.getOrNull()?.takeIf { it.key.isNotBlank() }?.let(::FormField)
            }.distinctBy { it.key }
            val s = config["submit"] as? JsonObject
            val label = (s?.get("label") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: "Submit"
            return FormModel(fields, (config["values"] as? JsonObject) ?: JsonObject(emptyMap()), FormSubmit(label, ViewAction.parse(s?.get("action"))))
        }
    }

    /** The value now shown for [key]: the user's local edit if any, else `values`, else the field's `default`. */
    fun current(key: String, edits: Map<String, JsonElement>): JsonElement? =
        edits[key] ?: values[key] ?: fields.firstOrNull { it.key == key }?.decl?.default

    fun visible(f: FormField, edits: Map<String, JsonElement>): Boolean = ConfigFieldValidator.isVisible(f.decl) { current(it, edits) }

    /** Field key to its one-line problem, for the visible editable fields only (a hidden field is not validated). */
    fun problems(edits: Map<String, JsonElement>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (f in fields) {
            if (!f.editable || !visible(f, edits)) continue
            val v = current(f.key, edits)
            val empty = v == null || (v is JsonPrimitive && v.isString && v.content.isEmpty()) || (v is JsonArray && v.isEmpty())
            val line = if (empty) ConfigFieldValidator.checkRules(f.decl, v)
            else runCatching { ConfigFieldValidator.validate(f.decl, coerce(f.decl, v!!)) }.exceptionOrNull()?.let { cleanMessage(it.message, f.key) }
            if (line != null) out[f.key] = line
        }
        return out
    }

    /** What is sent: every visible editable field that has a value, typed (numbers as numbers). Hidden fields are left out. */
    fun payload(edits: Map<String, JsonElement>): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        for (f in fields) {
            if (!f.editable || !visible(f, edits)) continue
            val v = current(f.key, edits) ?: continue
            if (v is JsonPrimitive && v.isString && v.content.isEmpty()) continue
            out[f.key] = coerce(f.decl, v)
        }
        return JsonObject(out)
    }

    private fun cleanMessage(m: String?, key: String): String =
        (m ?: "Invalid").removePrefix("setting '$key': ").removePrefix("setting '$key' ").replaceFirstChar { it.uppercase() }
}

/** A number field is typed as text while editing; turn it into a JSON number when it parses. */
fun coerce(field: ConfigFieldDeclaration, v: JsonElement): JsonElement {
    if (field.type != ConfigFieldTypes.NUMBER || v !is JsonPrimitive || !v.isString) return v
    val d = v.content.trim().toDoubleOrNull() ?: return v
    return if (d % 1.0 == 0.0 && '.' !in v.content) JsonPrimitive(d.toLong()) else JsonPrimitive(d)
}

/** The merge rule: the action's own `args`, with the form values on top (a form value wins over a same-named `args` key). */
fun mergeFormArgs(action: ViewAction.Tool, payload: JsonObject): ViewAction.Tool = action.copy(args = JsonObject(action.args + payload))

/** Submit: validate, then run the action with the merged args. Returns the problems when refused (nothing is sent). */
suspend fun submitForm(model: FormModel, edits: Map<String, JsonElement>, runner: ActionRunner): Map<String, String> {
    val problems = model.problems(edits)
    if (problems.isNotEmpty()) return problems
    when (val a = model.submit.action) {
        is ViewAction.Tool -> runner.run(mergeFormArgs(a, model.payload(edits)))
        else -> runner.run(a)
    }
    return emptyMap()
}

/**
 * The local edits of one form, keyed by field key. Held by the form block (remembered by block id, not read from the
 * page props), so a `plugin_view_changed` re-fetch, which rebuilds the page, leaves what the user typed alone.
 */
class FormEdits {
    var map by mutableStateOf<Map<String, JsonElement>>(emptyMap())
        private set
    fun set(key: String, v: JsonElement) { map = map + (key to v) }
}

private class FormBlock(id: String, place: Place = Place()) : Block("ui.form", id, place) {
    @Composable
    override fun Render(scope: BlockScope) {
        val model = FormModel.parse(LocalViewProps.current[scope.blockId] ?: JsonObject(emptyMap()))
        val edits = remember(scope.blockId) { FormEdits() }
        val runner = LocalViewActions.current.runner
        val coroutines = rememberCoroutineScope()
        var shown by remember(scope.blockId) { mutableStateOf<Map<String, String>>(emptyMap()) }
        val live = edits.map
        Column(scope.modifier.fillMaxWidth().testTag("view-form"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (model.fields.isEmpty()) Text("No fields", Modifier.testTag("view-form-empty"), color = Tones.color("muted"), style = MaterialTheme.typography.bodyMedium)
            for (f in model.fields) {
                if (!model.visible(f, live)) continue
                FormFieldRow(f, model.current(f.key, live), shown[f.key]) { v -> edits.set(f.key, v); shown = shown - f.key }
            }
            val problem = (model.submit.action as? ViewAction.Invalid)?.line
            Button(
                onClick = { if (runner != null) coroutines.launch { shown = submitForm(model, edits.map, runner) } },
                enabled = runner != null && problem == null, modifier = Modifier.testTag("view-form-submit"),
            ) { Text(model.submit.label) }
            problem?.let { Text(it, Modifier.testTag("view-form-problem"), color = Tones.color("bad"), style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun FormFieldRow(f: FormField, value: JsonElement?, problem: String?, onChange: (JsonElement) -> Unit) {
    val d = f.decl
    val prim = value as? JsonPrimitive
    Column(Modifier.testTag("view-form-field-${f.key}"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(d.label, style = MaterialTheme.typography.labelMedium)
        when {
            !f.editable -> Text(
                (value?.let { (it as? JsonPrimitive)?.contentOrNull ?: it.toString() } ?: "unset") + "  (${d.type}: read-only)",
                Modifier.testTag("view-form-readonly-${f.key}"), color = Tones.color("muted"), style = MaterialTheme.typography.bodyMedium,
            )
            d.type == ConfigFieldTypes.CHECKBOX || d.type == ConfigFieldTypes.TOGGLE -> Checkbox(
                prim?.booleanOrNull ?: false, { onChange(JsonPrimitive(it)) },
                Modifier.testTag("view-form-input-${f.key}").semantics { contentDescription = d.label },
            )
            d.type == ConfigFieldTypes.SELECT || d.type == ConfigFieldTypes.RADIO -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (o in d.options) FilterChip(
                    selected = prim?.contentOrNull == o, onClick = { onChange(JsonPrimitive(o)) }, label = { Text(d.optionLabels[o] ?: o, maxLines = 1) },
                    modifier = Modifier.testTag("view-form-option-${f.key}/$o"),
                )
            }
            d.type == ConfigFieldTypes.MULTI_SELECT -> {
                val picked = (value as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (o in d.options) FilterChip(
                        selected = o in picked, onClick = { onChange(JsonArray(d.options.filter { (it in picked) != (it == o) }.map(::JsonPrimitive))) },
                        label = { Text(d.optionLabels[o] ?: o, maxLines = 1) }, modifier = Modifier.testTag("view-form-option-${f.key}/$o"),
                    )
                }
            }
            else -> OutlinedTextField(
                value = prim?.contentOrNull.orEmpty(), onValueChange = { onChange(JsonPrimitive(it)) },
                singleLine = d.type != ConfigFieldTypes.TEXTAREA, minLines = if (d.type == ConfigFieldTypes.TEXTAREA) 3 else 1, isError = problem != null,
                placeholder = d.placeholder?.let { { Text(it) } }, modifier = Modifier.fillMaxWidth().testTag("view-form-input-${f.key}").semantics { contentDescription = d.label },
            )
        }
        d.description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Tones.color("muted")) }
        problem?.let { Text(it, Modifier.testTag("view-form-error-${f.key}"), color = Tones.color("bad"), style = MaterialTheme.typography.bodySmall) }
    }
}

/** DV-5 block type; merged into [ViewWidgets] next to [ViewControls]. Its `fields`, `values` and `submit` are read from the node config. */
object ViewForms {
    val declarations: List<BlockTypeDeclaration> = listOf(BlockTypeDeclaration("ui.form", "Form", emptyList()))
    val registered: Map<String, BlockType> = linkedMapOf("ui.form" to BlockType { id, _ -> FormBlock(id) })
}
