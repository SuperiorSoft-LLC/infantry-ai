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
    /**
     * CONFIG-5: escape hatch for a section that is not a form: the id of the fill that draws the card (placed by
     * [tab]/[group]/[order]). A block card has no [fields].
     */
    val block: String? = null,
    /** CONFIG-5: a trust section: only core may declare it, and no module hides or edits it. */
    val locked: Boolean = false,
    /** CONFIG-8: set by the host, never trusted from a module.json: a daemon field from an unsigned (community) plugin is shown but cannot be edited. */
    val readOnly: Boolean = false,
    /** CONFIG-9: the platforms (`desktop`, `android`, `web`) this group exists on; null = all. The compile-time list drops it elsewhere. */
    val platforms: List<String>? = null,
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
    /** CONFIG-9: the platforms this field exists on; null = all. */
    val platforms: List<String>? = null,
    /** CONFIG-9: a `classes` field's slots, each with the allow-list of style tokens the block's author owns. */
    val slots: Map<String, ConfigClassSlot> = emptyMap(),
    /** CONFIG-9: a `list` field's item: a field (`{type:"text"}`); its [key] is ignored. Null = text. */
    val item: ConfigFieldDeclaration? = null,
)

/** CONFIG-9: one slot of a `classes` field. An empty [tokens] means free text (stored, ignored unless the block knows it). */
@Serializable
data class ConfigClassSlot(val tokens: List<String> = emptyList(), val default: String? = null)

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
    /** CONFIG-9: `{ "<slot>": "<style token string>" }`, tokens space-separated, each in the slot's allow-list. */
    const val CLASSES = "classes"
    /** CONFIG-9: a JSON array whose items are checked against the field's `item`. */
    const val LIST = "list"
    /** CONFIG-FIELDS-1: a string like [TEXT], drawn as a multi-line box. */
    const val TEXTAREA = "textarea"
    val BUILT_IN = setOf(TEXT, TEXTAREA, NUMBER, CHECKBOX, SELECT, MULTI_SELECT, IMPLEMENTATION, TOGGLE, RADIO, COLOR, CLASSES, LIST)
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

/** CONFIG-8 (docs/CONFIG-MANIFEST-DESIGN.md section 6): `config_schema`, the daemon's own `scope:"daemon"` declarations; [rev] moves on every schema or value change. */
@Serializable
data class ConfigSchemaFrame(val rev: Long, val declarations: List<ConfigDeclaration> = emptyList())

/** CONFIG-8: `config_values`, the stored value of every declared daemon key (a key never set is absent: the client resolves the default). */
@Serializable
data class ConfigValuesFrame(val rev: Long, val rows: List<NodeConfigEntry> = emptyList())

/** CONFIG-8: `config_set` (client to daemon). A null [value] resets the key to its default; [expectRev] non-null must equal the daemon's rev or the write is refused as stale. */
@Serializable
data class ConfigSetFrame(val node: String, val key: String, val value: JsonElement? = null, val expectRev: Long? = null)

/** CONFIG-8: `config_changed`, pushed to every connected client after a write; [by] is who wrote it. */
@Serializable
data class ConfigChangedFrame(val node: String, val key: String, val value: JsonElement? = null, val by: String = ConfigSetBy.USER, val rev: Long = 0)

/** CONFIG-8: the check a declared field applies to a written value; the daemon and a client share it. Throws [IllegalArgumentException]. */
object ConfigFieldValidator {
    private val COLOR = Regex("^#([0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})$")

    fun validate(field: ConfigFieldDeclaration, value: JsonElement): JsonElement {
        val name = field.key
        val prim = value as? kotlinx.serialization.json.JsonPrimitive
        fun text(): String {
            require(prim != null && prim.isString) { "setting '$name' must be a string" }
            return prim.content
        }
        return when (field.type) {
            ConfigFieldTypes.CHECKBOX, ConfigFieldTypes.TOGGLE -> {
                require(prim != null && !prim.isString && prim.content in setOf("true", "false")) { "setting '$name' must be true or false" }
                prim
            }
            ConfigFieldTypes.NUMBER -> {
                val n = prim?.takeIf { !it.isString }?.content?.toDoubleOrNull()
                require(n != null && n.isFinite()) { "setting '$name' must be a number" }
                require(field.min == null || n >= field.min) { "setting '$name' must be at least ${field.min}" }
                require(field.max == null || n <= field.max) { "setting '$name' must be at most ${field.max}" }
                prim
            }
            ConfigFieldTypes.SELECT, ConfigFieldTypes.RADIO -> {
                val t = text()
                require(field.options.isEmpty() || t in field.options) { "setting '$name' must be one of ${field.options.joinToString { "\"$it\"" }}" }
                prim!!
            }
            ConfigFieldTypes.MULTI_SELECT -> {
                val arr = value as? kotlinx.serialization.json.JsonArray
                require(arr != null && arr.all { it is kotlinx.serialization.json.JsonPrimitive && it.isString }) { "setting '$name' must be an array of strings" }
                require(field.options.isEmpty() || arr.all { (it as kotlinx.serialization.json.JsonPrimitive).content in field.options }) { "setting '$name' has a value outside its options" }
                arr
            }
            ConfigFieldTypes.COLOR -> {
                require(COLOR.matches(text())) { "setting '$name' must be #RRGGBB or #AARRGGBB" }
                prim!!
            }
            ConfigFieldTypes.TEXT, ConfigFieldTypes.TEXTAREA, ConfigFieldTypes.IMPLEMENTATION -> { text(); prim!! }
            ConfigFieldTypes.CLASSES -> {
                val obj = value as? kotlinx.serialization.json.JsonObject
                require(obj != null && obj.values.all { it is kotlinx.serialization.json.JsonPrimitive && it.isString }) { "setting '$name' must be an object of strings" }
                for ((slot, v) in obj) {
                    val declared = field.slots[slot]
                    require(field.slots.isEmpty() || declared != null) { "setting '$name' has no slot '$slot'" }
                    val bad = (v as kotlinx.serialization.json.JsonPrimitive).content.split(' ').filter { it.isNotEmpty() }
                        .firstOrNull { declared != null && declared.tokens.isNotEmpty() && it !in declared.tokens }
                    require(bad == null) { "setting '$name' slot '$slot' has no token '$bad'" }
                }
                obj
            }
            ConfigFieldTypes.LIST -> {
                val arr = value as? kotlinx.serialization.json.JsonArray
                require(arr != null) { "setting '$name' must be an array" }
                val item = field.item ?: ConfigFieldDeclaration(name, ConfigFieldTypes.TEXT)
                arr.forEach { validate(item.copy(key = name), it) }
                arr
            }
            else -> value
        }
    }
}
