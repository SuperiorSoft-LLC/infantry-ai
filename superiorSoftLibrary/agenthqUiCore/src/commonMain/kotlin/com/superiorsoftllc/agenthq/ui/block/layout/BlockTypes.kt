package com.superiorsoftllc.agenthq.ui.block.layout

import com.superiorsoftllc.agenthq.plugin.api.BlockNeeds
import com.superiorsoftllc.agenthq.plugin.api.BlockTypeDeclaration
import com.superiorsoftllc.agenthq.plugin.api.ConfigFieldDeclaration
import com.superiorsoftllc.agenthq.plugin.api.ConfigFieldTypes
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * NS1 / PB1 (docs/BLOCKS-LAYOUT-DESIGN.md 6t.2): a block TYPE, as code registers it (`UiRegistrar.blockType`). The config
 * tree holds INSTANCES (id, type, children); [create] builds one. The factory never receives config: the block reads its
 * instance config through `ShellContext.config.values(id)`, as every block does (6s C).
 */
fun interface BlockType {
    fun create(id: String, children: List<Block>): Block
}

/** A [BlockType] with the module.json declaration it was bound to and the module that registered it. */
class RegisteredBlockType(val declaration: BlockTypeDeclaration, val module: String, val type: BlockType) {
    val name: String get() = declaration.type
}

/**
 * NS1: every registered block type, core's ([CoreBlockTypes]) first, then each module's in load order; [problems] has one
 * line per refused registration. Nothing here throws.
 */
class BlockTypes(val types: Map<String, RegisteredBlockType>, val problems: List<String> = emptyList()) {
    operator fun get(type: String): RegisteredBlockType? = types[type]

    /** A new instance [id] of [type], or null when [type] is not registered. */
    fun create(type: String, id: String, children: List<Block> = emptyList()): Block? = types[type]?.type?.create(id, children)

    /**
     * The registered type name of [block]: its own [Block.blockType] when that is registered, a core primitive's
     * `layout.<x>` alias, else null (a hard-coded Kotlin node that is not a type yet).
     */
    fun typeOf(block: Block): String? = when {
        block.blockType in types -> block.blockType
        CoreBlockTypes.PREFIX + block.blockType in types -> CoreBlockTypes.PREFIX + block.blockType
        else -> null
    }

    companion object {
        /** Core's types plus [modules]' registrations (module name, its declarations, its registered types), checked. */
        fun of(modules: List<Triple<String, List<BlockTypeDeclaration>, Map<String, BlockType>>>): BlockTypes {
            val out = LinkedHashMap(CoreBlockTypes.registered)
            val problems = mutableListOf<String>()
            for ((name, declared, registered) in modules) {
                for ((type, impl) in registered) {
                    val d = declared.firstOrNull { it.type == type }
                    val taken = out[type]
                    when {
                        d == null -> problems += "$name: block type $type is not declared in its module.json; refused"
                        taken != null -> problems += "$name: block type $type is already registered by ${taken.module}; refused"
                        d.needs !in BlockNeeds.ALL -> problems += "$name: block type $type has unknown needs '${d.needs}' (${BlockNeeds.ALL.joinToString()}); refused"
                        else -> out[type] = RegisteredBlockType(d, name, impl)
                    }
                }
                for (d in declared) if (d.type !in registered) problems += "$name: block type ${d.type} is declared but not registered; skipped"
            }
            return BlockTypes(out, problems)
        }
    }
}

/**
 * NS1: the section 2 layout primitives as core block types (`layout.group`, `layout.row`, `layout.column`,
 * `layout.stack`, `layout.spacer`), each with fields for its [Place] (weight, size, show). The shipped tree's primitives
 * keep their own Kotlin values; these only build new instances (PB2 onwards) and name the primitives in the page JSON.
 */
object CoreBlockTypes {
    const val MODULE = com.superiorsoftllc.agenthq.ui.plugin.CORE_SHELL_MODULE
    const val PREFIX = "layout."
    const val GROUP = "layout.group"
    const val ROW = "layout.row"
    const val COLUMN = "layout.column"
    const val STACK = "layout.stack"
    const val SPACER = "layout.spacer"

    private val placeFields = listOf(
        ConfigFieldDeclaration("weight", ConfigFieldTypes.NUMBER, "Weight", min = 0.0),
        ConfigFieldDeclaration("size", ConfigFieldTypes.SELECT, "Size", JsonPrimitive(Size.Wrap.name), Size.entries.map { it.name }),
        ConfigFieldDeclaration("show", ConfigFieldTypes.SELECT, "Show", JsonPrimitive(Show.Always.name), Show.entries.map { it.name }),
    )

    private fun primitive(type: String, title: String, takesChildren: Boolean, create: BlockType) =
        type to RegisteredBlockType(BlockTypeDeclaration(type, title, placeFields, takesChildren), MODULE, create)

    val registered: Map<String, RegisteredBlockType> = linkedMapOf(
        primitive(GROUP, "Group", true) { id, c -> GroupBlock(id, c) },
        primitive(ROW, "Row", true) { id, c -> RowBlock(id, children = c) },
        primitive(COLUMN, "Column", true) { id, c -> ColumnBlock(id, children = c) },
        primitive(STACK, "Stack", true) { id, c -> StackBlock(id, children = c) },
        primitive(SPACER, "Spacer", false) { id, _ -> SpacerBlock(id) },
    )
}

/**
 * NS1 (6t.5): the effective tree as "the page", one JSON: `{"schema": 1, "modules": [{"name", "version"}], "root":
 * {"blockType", "id", "config": {...}, "children": [...]}}`. Output only, never stored (6t.1 pick C). `config` is
 * omitted when empty, `children` when there are none.
 */
object PageJson {
    const val SCHEMA = 1

    fun render(
        root: Block,
        modules: List<Pair<String, String>>,
        typeOf: (Block) -> String,
        config: (Block) -> Map<String, JsonElement>,
    ): JsonObject = JsonObject(
        mapOf(
            "schema" to JsonPrimitive(SCHEMA),
            "modules" to JsonArray(modules.map { (n, v) -> JsonObject(mapOf("name" to JsonPrimitive(n), "version" to JsonPrimitive(v))) }),
            "root" to node(root, typeOf, config),
        ),
    )

    fun node(block: Block, typeOf: (Block) -> String, config: (Block) -> Map<String, JsonElement>): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        out["blockType"] = JsonPrimitive(typeOf(block))
        out["id"] = JsonPrimitive(block.id)
        val c = config(block)
        if (c.isNotEmpty()) out["config"] = JsonObject(c)
        if (block.children.isNotEmpty()) out["children"] = JsonArray(block.children.map { node(it, typeOf, config) })
        return JsonObject(out)
    }
}
