package com.superiorsoftllc.agenthq.plugin.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * P1 step 2 (docs/BLOCKS-LAYOUT-DESIGN.md section 6s A): the config fields a module declares for node [node] (an
 * `infantry.shell.*` id it owns or is granted), shown in the configuration view under tab [group].
 */
@Serializable
data class ConfigDeclaration(val node: String, val group: String = "General", val fields: List<ConfigFieldDeclaration> = emptyList())

/**
 * One field of a node's schema. [type] is a key of the field-type registry (`text`, `number`, `checkbox`, `select`,
 * `multiSelect`, `implementation`; a module may register more). [default] is the declaring module's value, the last
 * step of resolution. `number` reads [min], [max], [step]; `select` and `multiSelect` read [options]; `implementation`
 * reads [registry] (whose keys are the options), [exposed] (a user may swap it) and [autoSelectNew] (a newly
 * registered key becomes the selection while the user never picked one).
 */
@Serializable
data class ConfigFieldDeclaration(
    val key: String,
    val type: String,
    val label: String = key,
    val default: JsonElement? = null,
    val options: List<String> = emptyList(),
    val min: Double? = null,
    val max: Double? = null,
    val step: Double? = null,
    val registry: String? = null,
    val exposed: Boolean = false,
    val autoSelectNew: Boolean = false,
)

/** The built-in field types (6s A). */
object ConfigFieldTypes {
    const val TEXT = "text"
    const val NUMBER = "number"
    const val CHECKBOX = "checkbox"
    const val SELECT = "select"
    const val MULTI_SELECT = "multiSelect"
    const val IMPLEMENTATION = "implementation"
    val BUILT_IN = setOf(TEXT, NUMBER, CHECKBOX, SELECT, MULTI_SELECT, IMPLEMENTATION)
}

/** Who wrote a stored value: the merged JSON tree on first load, or the user from the configuration view. */
object ConfigSetBy {
    const val SEED = "seed"
    const val USER = "user"
}

/**
 * The wire form of one `node_config` row (6s B): `GET /node-config` answers a list of them, `POST /node-config/seed`
 * takes one (only missing keys are inserted), `PUT /node-config` writes one ([value] null removes the row, so the key
 * resolves from the parent again).
 */
@Serializable
data class NodeConfigEntry(
    val nodeId: String,
    val key: String,
    val value: JsonElement? = null,
    val setBy: String = ConfigSetBy.USER,
    val updatedAt: Long = 0,
)
