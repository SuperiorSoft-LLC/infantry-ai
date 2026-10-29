package com.superiorsoftllc.agenthq.plugin.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * P1 step 2 (docs/BLOCKS-LAYOUT-DESIGN.md section 6s A): the config fields a module declares for node [node] (an
 * `infantry.shell.*` id it owns or is granted), shown in the configuration view under tab [group].
 */
@Serializable
data class ConfigDeclaration(
    val node: String,
    val group: String = "General",
    val fields: List<ConfigFieldDeclaration> = emptyList(),
    /** CONFIG-3: the Settings dialog tab the group is a card of; null keeps the old meaning of [group] (the Plugins view). */
    val tab: String? = null,
    /** CONFIG-3: sorts the groups of a tab (ties: load order). */
    val order: Int = 0,
    /** CONFIG-3: the card heading; null = [group]. */
    val title: String? = null,
    /** CONFIG-3: the "Applies to:" line of the card. */
    val applies: String? = null,
    /** CONFIG-3: extra words the dialog search matches. */
    val keywords: List<String> = emptyList(),
)

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
    /** CONFIG-3: the label of an option shown instead of its value (`{"RED": "Red"}`); an option without one shows as itself. */
    val optionLabels: Map<String, String> = emptyMap(),
    /** CONFIG-3: one line of help under the editor. */
    val description: String? = null,
    /** CONFIG-3: sorts the fields of a group (ties: declaration order). */
    val order: Int = 0,
    /** CONFIG-4: where the user's override lives: `device` (this host's ui prefs) or `daemon`; null = today's `node_config`. */
    val scope: String? = null,
)

/** CONFIG-4: the override scopes a field may declare. */
object ConfigScopes {
    const val DEVICE = "device"
    const val DAEMON = "daemon"
}

/** The built-in field types (6s A). */
object ConfigFieldTypes {
    const val TEXT = "text"
    const val NUMBER = "number"
    const val CHECKBOX = "checkbox"
    const val SELECT = "select"
    const val MULTI_SELECT = "multiSelect"
    const val IMPLEMENTATION = "implementation"
    /** CONFIG-3: an alias of [CHECKBOX] (a boolean, drawn as a switch). */
    const val TOGGLE = "toggle"
    /** CONFIG-3: a string out of [ConfigFieldDeclaration.options], drawn as a radio group. */
    const val RADIO = "radio"
    /** CONFIG-3: `"#RRGGBB"` or `"#AARRGGBB"`. */
    const val COLOR = "color"
    val BUILT_IN = setOf(TEXT, NUMBER, CHECKBOX, SELECT, MULTI_SELECT, IMPLEMENTATION, TOGGLE, RADIO, COLOR)
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

/**
 * CONFIG-3 (docs/CONFIG-MANIFEST-DESIGN.md section 2): one host's compiled unified manifest, as `GET/PUT /config-manifest`
 * carry it. [json] is the canonical JSON text of every loaded module's `config` (core first), [hash] its SHA-256 (hex),
 * [sources] who contributed. The daemon stores it per [host]; the dialog and the block injection read it.
 */
@Serializable
data class ConfigManifest(
    val host: String,
    val hash: String,
    val json: String,
    val sources: List<ConfigManifestSource> = emptyList(),
    val compiledAt: Long = 0,
)

/** One contributor to a [ConfigManifest]: a module, its version and the hash of its own `config`. */
@Serializable
data class ConfigManifestSource(val module: String, val version: String = "", val manifestHash: String = "")
