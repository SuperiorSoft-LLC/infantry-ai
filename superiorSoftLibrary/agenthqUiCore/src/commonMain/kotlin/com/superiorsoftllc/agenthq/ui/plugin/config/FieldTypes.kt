package com.superiorsoftllc.agenthq.ui.plugin.config

import androidx.compose.runtime.Composable
import com.superiorsoftllc.agenthq.plugin.api.ConfigFieldDeclaration
import com.superiorsoftllc.agenthq.plugin.api.ConfigFieldTypes
import com.superiorsoftllc.agenthq.ui.shell.parseConfigColor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * CONFIG-5 (docs/CONFIG-MANIFEST-DESIGN.md section 4, step 4): a config field type a module adds with
 * `UiRegistrar.fieldType` (declared under `fieldTypes` in module.json). [validate] is the type's one rule, shared by the
 * dialog (inline error, a failing value is not written); [Editor] draws the value.
 */
interface FieldType {
    /** Null when [value] is acceptable for [field], else one line saying why not. */
    fun validate(field: ConfigFieldDeclaration, value: JsonElement?): String? = null

    @Composable
    fun Editor(field: ConfigFieldDeclaration, value: JsonElement?, enabled: Boolean, onChange: (JsonElement?) -> Unit)
}

/** A registered [FieldType] and the module that registered it. */
class RegisteredFieldType(val id: String, val module: String, val type: FieldType)

/** Every registered field type (module.json `fieldTypes`), by id, with a line for each refusal. */
class FieldTypes(private val types: Map<String, RegisteredFieldType> = emptyMap(), val problems: List<String> = emptyList()) {
    val ids: Set<String> get() = types.keys
    operator fun get(id: String): FieldType? = types[id]?.type

    /** Null when [value] is fine for [field]: the registered type's rule, else the built-in one. */
    fun validate(field: ConfigFieldDeclaration, value: JsonElement?): String? {
        val custom = types[field.type]?.type
        return if (custom != null) custom.validate(field, value) else validateBuiltIn(field, value)
    }

    companion object {
        /** [modules]: module name, the ids its module.json declares, the types it registered. A built-in id, a repeat and an undeclared one are refused. */
        fun of(modules: List<Triple<String, List<String>, Map<String, FieldType>>>): FieldTypes {
            val out = LinkedHashMap<String, RegisteredFieldType>()
            val problems = mutableListOf<String>()
            for ((module, declared, registered) in modules) {
                for ((id, type) in registered) when {
                    id !in declared -> problems += "$module: field type $id is not declared in its module.json; refused"
                    id in ConfigFieldTypes.BUILT_IN -> problems += "$module: field type $id is built in; refused"
                    out[id] != null -> problems += "$module: field type $id is already registered by ${out.getValue(id).module}; refused"
                    else -> out[id] = RegisteredFieldType(id, module, type)
                }
                for (id in declared) if (id !in registered) problems += "$module: field type $id is declared but not registered; skipped"
            }
            return FieldTypes(out, problems)
        }

        /** The rule of each built-in type; an unknown type and an `implementation` (checked against its registry) pass. */
        fun validateBuiltIn(field: ConfigFieldDeclaration, value: JsonElement?): String? {
            if (value == null || value is JsonNull) return null
            return when (field.type) {
                ConfigFieldTypes.NUMBER -> {
                    val n = (value as? JsonPrimitive)?.doubleOrNull ?: return "Not a number"
                    val min = field.min
                    val max = field.max
                    when {
                        min != null && n < min -> "At least $min"
                        max != null && n > max -> "At most $max"
                        else -> null
                    }
                }
                ConfigFieldTypes.CHECKBOX, ConfigFieldTypes.TOGGLE -> if ((value as? JsonPrimitive)?.booleanOrNull == null) "Not on or off" else null
                ConfigFieldTypes.COLOR -> if (parseConfigColor((value as? JsonPrimitive)?.content) == null) "Use #RRGGBB or #AARRGGBB" else null
                ConfigFieldTypes.SELECT, ConfigFieldTypes.RADIO ->
                    if (value !is JsonPrimitive) "Not one of the options"
                    else if (field.options.isNotEmpty() && value.content !in field.options) "Not one of: ${field.options.joinToString()}"
                    else null
                ConfigFieldTypes.MULTI_SELECT -> {
                    val items = (value as? JsonArray)?.map { (it as? JsonPrimitive)?.content } ?: return "Not a list"
                    if (field.options.isNotEmpty() && items.any { it == null || it !in field.options }) "Not all among: ${field.options.joinToString()}" else null
                }
                ConfigFieldTypes.TEXT -> if (value is JsonPrimitive) null else "Not text"
                else -> null
            }
        }
    }
}
