// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.views

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.superiorsoftllc.infantry.plugin.api.BlockTypeDeclaration
import com.superiorsoftllc.infantry.plugin.api.ConfigFieldDeclaration
import com.superiorsoftllc.infantry.plugin.api.ConfigFieldTypes
import com.superiorsoftllc.infantry.ui.block.layout.Block
import com.superiorsoftllc.infantry.ui.block.layout.BlockScope
import com.superiorsoftllc.infantry.ui.block.layout.BlockType
import com.superiorsoftllc.infantry.ui.block.layout.BlockTypes
import com.superiorsoftllc.infantry.ui.block.layout.Place
import com.superiorsoftllc.infantry.ui.block.layout.RenderPlaced
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * The node `config` of every block in the view being drawn, by (prefixed) node id. DV-3 design choice (DECL-UI-DESIGN
 * section 4 named it as the fallback): `ShellContext.config` is one per-window store the host cannot wrap for a subtree,
 * so the widgets read their fields from this local instead of `scope.config()`. Invisible to plugins.
 */
val LocalViewProps = compositionLocalOf<Map<String, JsonObject>> { emptyMap() }

/** One node's props: the node's inline config, else the default the widget passes. */
class Props(private val json: JsonObject) {
    fun string(key: String, default: String? = null): String? = (json[key] as? JsonPrimitive)?.contentOrNull ?: default
    fun int(key: String): Int? = (json[key] as? JsonPrimitive)?.intOrNull
    fun bool(key: String, default: Boolean): Boolean = (json[key] as? JsonPrimitive)?.booleanOrNull ?: default
    /** An enum value, or [default] when the key is unset or holds a value this client does not know (design 1.5). */
    fun oneOf(key: String, allowed: List<String>, default: String): String = string(key)?.takeIf { it in allowed } ?: default
}

@Composable
private fun BlockScope.props(): Props = Props(LocalViewProps.current[blockId] ?: JsonObject(emptyMap()))

/** The closed tone enum, mapped to the app theme; data never carries a colour. */
object Tones {
    val ALL = listOf("default", "muted", "good", "warn", "bad")
    private val GOOD = Color(0xFF2E9D5B)
    private val WARN = Color(0xFFD9962B)

    @Composable
    fun color(tone: String): Color = when (tone) {
        "muted" -> MaterialTheme.colorScheme.onSurfaceVariant
        "good" -> GOOD
        "warn" -> WARN
        "bad" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
}

private class SectionBlock(id: String, children: List<Block> = emptyList(), place: Place = Place()) : Block("ui.section", id, place, children) {
    @Composable
    override fun Render(scope: BlockScope) {
        val p = scope.props()
        val collapsible = p.bool("collapsible", false)
        var open by rememberSaveable(id) { mutableStateOf(true) }
        OutlinedCard(scope.modifier.testTag("view-section")) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val title = p.string("title")
                if (title != null || collapsible) {
                    Column(if (collapsible) Modifier.clickable { open = !open } else Modifier) {
                        Text((if (collapsible) (if (open) "▾ " else "▸ ") else "") + (title ?: ""), style = MaterialTheme.typography.titleSmall)
                        p.string("subtitle")?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Tones.color("muted")) }
                    }
                } else p.string("subtitle")?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Tones.color("muted")) }
                if (open) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { RenderPlaced(children) }
            }
        }
    }

    override fun withChildren(children: List<Block>) = SectionBlock(id, children, place)
}

private class TextBlock(id: String, place: Place = Place()) : Block("ui.text", id, place) {
    @Composable
    override fun Render(scope: BlockScope) {
        val p = scope.props()
        val style = when (p.oneOf("style", listOf("body", "title", "caption", "mono"), "body")) {
            "title" -> MaterialTheme.typography.titleMedium
            "caption" -> MaterialTheme.typography.bodySmall
            "mono" -> MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
            else -> MaterialTheme.typography.bodyMedium
        }
        val lines = p.int("maxLines")?.takeIf { it > 0 }
        Text(
            p.string("text", "")!!, scope.modifier.testTag("view-text"), color = Tones.color(p.oneOf("tone", Tones.ALL, "default")), style = style,
            maxLines = lines ?: Int.MAX_VALUE, overflow = if (lines != null) TextOverflow.Ellipsis else TextOverflow.Clip,
        )
    }
}

private class BadgeBlock(id: String, place: Place = Place()) : Block("ui.badge", id, place) {
    @Composable
    override fun Render(scope: BlockScope) {
        val p = scope.props()
        val c = Tones.color(p.oneOf("tone", Tones.ALL, "default"))
        Surface(scope.modifier.testTag("view-badge"), shape = RoundedCornerShape(50), color = c.copy(alpha = 0.15f), border = BorderStroke(1.dp, c.copy(alpha = 0.5f))) {
            Text(p.string("text", "")!!, Modifier.padding(horizontal = 8.dp, vertical = 2.dp), color = c, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

private class StatBlock(id: String, place: Place = Place()) : Block("ui.stat", id, place) {
    @Composable
    override fun Render(scope: BlockScope) {
        val p = scope.props()
        Column(scope.modifier.testTag("view-stat").padding(4.dp)) {
            Text(p.string("label", "")!!, style = MaterialTheme.typography.labelSmall, color = Tones.color("muted"))
            Text(p.string("value", "")!!, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold), color = Tones.color(p.oneOf("tone", Tones.ALL, "default")))
            p.string("hint")?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Tones.color("muted")) }
        }
    }
}

/** A node whose type this client does not know: one muted line (the node's `fallback`, else "<type> needs a newer app"). */
private class UnknownBlock(id: String, place: Place = Place()) : Block(UNKNOWN_TYPE, id, place) {
    @Composable
    override fun Render(scope: BlockScope) {
        Text(scope.props().string("text", "")!!, scope.modifier.testTag("view-unknown"), color = Tones.color("muted"), style = MaterialTheme.typography.bodySmall)
    }
}

/** The block types this plugin registers; the same declarations are in module.json `blockTypes`. */
object ViewWidgets {
    private fun tone() = ConfigFieldDeclaration("tone", ConfigFieldTypes.SELECT, "Tone", JsonPrimitive("default"), Tones.ALL)
    private fun text(key: String, label: String) = ConfigFieldDeclaration(key, ConfigFieldTypes.TEXT, label)

    val declarations: List<BlockTypeDeclaration> = listOf(
        BlockTypeDeclaration("ui.section", "Section", listOf(text("title", "Title"), text("subtitle", "Subtitle"), ConfigFieldDeclaration("collapsible", ConfigFieldTypes.CHECKBOX, "Collapsible", JsonPrimitive(false))), takesChildren = true),
        BlockTypeDeclaration("ui.text", "Text", listOf(text("text", "Text"), ConfigFieldDeclaration("style", ConfigFieldTypes.SELECT, "Style", JsonPrimitive("body"), listOf("body", "title", "caption", "mono")), tone(), ConfigFieldDeclaration("maxLines", ConfigFieldTypes.NUMBER, "Max lines", min = 1.0))),
        BlockTypeDeclaration("ui.badge", "Badge", listOf(text("text", "Text"), tone())),
        BlockTypeDeclaration("ui.stat", "Stat", listOf(text("label", "Label"), text("value", "Value"), tone(), text("hint", "Hint"))),
        BlockTypeDeclaration(UNKNOWN_TYPE, "Unknown widget", listOf(text("text", "Text"))),
    )

    val registered: Map<String, BlockType> = linkedMapOf(
        "ui.section" to BlockType { id, c -> SectionBlock(id, c) },
        "ui.text" to BlockType { id, _ -> TextBlock(id) },
        "ui.badge" to BlockType { id, _ -> BadgeBlock(id) },
        "ui.stat" to BlockType { id, _ -> StatBlock(id) },
        UNKNOWN_TYPE to BlockType { id, _ -> UnknownBlock(id) },
    )

    /** Core's layout primitives plus these widgets: everything a view page may use. */
    val types: BlockTypes by lazy { BlockTypes.of(listOf(Triple("Infantry_Views", declarations, registered))) }
}
