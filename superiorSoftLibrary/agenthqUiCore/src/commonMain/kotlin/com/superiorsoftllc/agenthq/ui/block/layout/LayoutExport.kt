package com.superiorsoftllc.agenthq.ui.block.layout

import com.superiorsoftllc.agenthq.plugin.api.DaemonCapabilities
import com.superiorsoftllc.agenthq.plugin.api.ModuleManifest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * NS3 (docs/NORTH-STAR-DESIGN.md 1a-1c): exporting node N's effective subtree as a composition. Pure: the page JSON
 * (6t.5 format, a `"template": true` marker on each instance of a per-item type), the module packages it depends on
 * (every module that registers a type in it, core's layout primitives excepted), the union of their `daemon` needs and
 * wants, and the refusal list. Export is all-or-nothing: any refusal and [Result.page] is null.
 */
object LayoutExport {
    data class Result(
        val page: JsonObject?,
        /** Module name to version, in first-use order. */
        val dependencies: List<Pair<String, String>>,
        val daemon: DaemonCapabilities,
        val refusals: List<String>,
    ) {
        val ok: Boolean get() = refusals.isEmpty() && page != null
    }

    /** A config value that is a secret reference (`"secret:<field>"` or `{"secret": ...}`): secrets never travel. */
    fun isSecretRef(v: JsonElement): Boolean = when (v) {
        is JsonPrimitive -> v.isString && v.content.startsWith("secret:")
        is JsonObject -> "secret" in v
        is JsonArray -> v.any(::isSecretRef)
    }

    fun export(
        root: Block,
        types: BlockTypes,
        /** The manifest of each loaded module, by name (versions and `daemon`). */
        manifests: Map<String, ModuleManifest>,
        /** The resolved config values of an instance (its declared fields). */
        config: (Block) -> Map<String, JsonElement>,
        /** Whether a module is available as its own package (not inside another module's .ihq). */
        packaged: (module: String) -> Boolean,
        locked: Set<String> = emptySet(),
    ): Result {
        val refusals = mutableListOf<String>()
        val deps = LinkedHashMap<String, String>()
        fun walk(b: Block) {
            val type = types.typeOf(b)
            if (type == null) refusals += "not a type yet: ${b.id}" else {
                val module = types[type]!!.module
                if (module != CoreBlockTypes.MODULE && module !in deps) {
                    if (!packaged(module)) refusals += "$module is not a separate package; publish it as its own package"
                    deps[module] = manifests[module]?.version ?: "0.0.0"
                }
            }
            if (b.id in locked) refusals += "locked node ${b.id}: trust nodes never travel"
            config(b).forEach { (k, v) -> if (isSecretRef(v)) refusals += "${b.id}.$k is a secret reference: secrets never travel" }
            b.children.forEach(::walk)
        }
        walk(root)
        val needs = LinkedHashSet<String>()
        val wants = LinkedHashSet<String>()
        deps.keys.forEach { m -> manifests[m]?.daemon?.let { needs += it.needs; wants += it.wants } }
        val capabilities = DaemonCapabilities(needs.toList(), (wants - needs).toList())
        if (refusals.isNotEmpty()) return Result(null, deps.toList(), capabilities, refusals.distinct())
        val page = PageJson.render(root, deps.toList(), typeOf = { types.typeOf(it)!! }, config = config).let { p ->
            JsonObject(p + ("root" to markTemplates(p.getValue("root") as JsonObject, types)))
        }
        return Result(page, deps.toList(), capabilities, emptyList())
    }

    private fun markTemplates(node: JsonObject, types: BlockTypes): JsonObject {
        val out = LinkedHashMap(node)
        val type = node.getValue("blockType").jsonPrimitive.content
        if (types[type]?.declaration?.template == true) out["template"] = JsonPrimitive(true)
        (node["children"] as? JsonArray)?.let { kids -> out["children"] = JsonArray(kids.map { markTemplates(it as JsonObject, types) }) }
        return JsonObject(out)
    }
}

/**
 * NS3: the reverse of [PageJson]: a page (or its `root` node) built back into blocks through the type registry. A type that
 * is not registered is one line in [Built.problems] and that node is left out; nothing throws.
 */
object PageLayout {
    data class Built(val root: Block?, val problems: List<String>)

    fun instantiate(page: JsonObject, types: BlockTypes): Built {
        val problems = mutableListOf<String>()
        fun build(n: JsonObject): Block? {
            val type = (n["blockType"] as? JsonPrimitive)?.content
            val id = (n["id"] as? JsonPrimitive)?.content
            if (type == null || id == null) { problems += "a layout node has no blockType or id"; return null }
            val kids = (n["children"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::build) }
            val b = types.create(type, id, kids)
            if (b == null) problems += "$id: block type $type is not registered"
            return b
        }
        val rootNode = (page["root"] as? JsonObject) ?: page
        return Built(build(rootNode), problems)
    }

    /** Whether a page node carries the per-item template marker. */
    fun isTemplate(node: JsonObject): Boolean = (node["template"] as? JsonPrimitive)?.booleanOrNull == true
}
