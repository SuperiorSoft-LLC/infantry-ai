// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.views

import com.superiorsoftllc.infantry.ui.block.layout.Block
import com.superiorsoftllc.infantry.ui.block.layout.BlockScope
import com.superiorsoftllc.infantry.ui.block.layout.BlockTypes
import com.superiorsoftllc.infantry.ui.block.layout.PageLayout
import com.superiorsoftllc.infantry.ui.block.layout.Place
import com.superiorsoftllc.infantry.ui.block.layout.Show
import com.superiorsoftllc.infantry.ui.block.layout.Size
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull

/** DECL-UI-DESIGN 1.5: the widget vocabulary level this client draws. A page written for a higher one gets a banner. */
const val WIDGETS_LEVEL = 1

/** The block type that stands in for a node whose `blockType` this client does not know (its children are not drawn). */
const val UNKNOWN_TYPE = "ui.unknown"

/** A page ready to draw: the block tree, each node's inline config (by prefixed id), the banner flag and any problems. */
class BuiltView(val root: Block?, val props: Map<String, JsonObject>, val newerLevel: Boolean, val problems: List<String>)

/** DV-3: turns a daemon page into blocks. Pure (no Compose state), so a test can check it. Never throws. */
object ViewBuilder {
    fun idPrefix(module: String, view: String) = "view:$module/$view/"

    fun build(module: String, view: String, page: JsonObject, types: BlockTypes): BuiltView {
        val prefix = idPrefix(module, view)
        val props = LinkedHashMap<String, JsonObject>()
        val places = HashMap<String, Place>()

        fun prepare(n: JsonObject): JsonObject {
            val type = (n["blockType"] as? JsonPrimitive)?.contentOrNull
            val rawId = (n["id"] as? JsonPrimitive)?.contentOrNull
            if (type == null || rawId == null) return n // PageLayout reports it as a problem
            val id = prefix + rawId
            val config = (n["config"] as? JsonObject) ?: JsonObject(emptyMap())
            val out = LinkedHashMap<String, JsonElement>()
            out["id"] = JsonPrimitive(id)
            if (type !in types.types) {
                val text = (n["fallback"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: "$type needs a newer app"
                out["blockType"] = JsonPrimitive(UNKNOWN_TYPE)
                props[id] = JsonObject(mapOf("text" to JsonPrimitive(text)))
                return JsonObject(out)
            }
            out["blockType"] = JsonPrimitive(type)
            props[id] = config
            places[id] = placeOf(n, config)
            (n["children"] as? JsonArray)?.let { kids -> out["children"] = JsonArray(kids.map { (it as? JsonObject)?.let(::prepare) ?: it }) }
            return JsonObject(out)
        }

        val rootNode = (page["root"] as? JsonObject) ?: page
        val built = runCatching { PageLayout.instantiate(JsonObject(mapOf("root" to prepare(rootNode))), types) }
            .getOrElse { return BuiltView(null, props, false, listOf("the view could not be built: ${it.message}")) }
        val level = (page["widgets"] as? JsonPrimitive)?.intOrNull ?: WIDGETS_LEVEL
        return BuiltView(built.root?.let { withPlaces(it, places) }, props, level > WIDGETS_LEVEL, built.problems)
    }

    /** `show` is a reserved node key; `weight`, `size` and `show` also come from `config`, as the layout primitives declare them. */
    private fun placeOf(node: JsonObject, config: JsonObject): Place {
        fun str(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
        val show = (str(node, "show") ?: str(config, "show"))?.let { s -> Show.entries.firstOrNull { it.name == s } } ?: Show.Always
        val size = str(config, "size")?.let { s -> Size.entries.firstOrNull { it.name == s } } ?: Size.Wrap
        val weight = (config["weight"] as? JsonPrimitive)?.floatOrNull?.takeIf { it > 0f }
        return Place(weight = weight, size = size, show = show)
    }

    private fun withPlaces(block: Block, places: Map<String, Place>): Block {
        val kids = block.children.map { withPlaces(it, places) }
        val inner = if (kids.isEmpty()) block else runCatching { block.withChildren(kids) }.getOrDefault(block)
        val place = places[block.id] ?: return inner
        return if (place == inner.place) inner else PlacedBlock(inner, place)
    }
}

/** A built block drawn with the place its node asked for (a type factory gets no config, so the host applies it). */
internal class PlacedBlock(val inner: Block, place: Place) : Block(inner.blockType, inner.id, place, inner.children) {
    @androidx.compose.runtime.Composable
    override fun Render(scope: BlockScope) = inner.Render(scope)

    override fun withChildren(children: List<Block>): Block = PlacedBlock(inner.withChildren(children), place)
}
