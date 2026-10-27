package com.superiorsoftllc.agenthq.ui.block.layout

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize

/**
 * BLOCKS-LAYOUT L1 (docs/BLOCKS-LAYOUT-DESIGN.md section 1): the block contract. Lives beside the older `Block`/`DockHost`
 * (package `ui.block`) until L3 moves the shell onto it; nothing calls it yet.
 *
 * A block is a node: what it is ([blockType]), its address ([id]), how it asks its parent to place it ([place]), and its
 * [children]. The parent turns [place] into the [BlockScope.modifier] it hands the child; the child applies that exactly
 * once on its outermost node and knows nothing of its parent or siblings.
 */
abstract class Block(
    val blockType: String,
    val id: String,
    val place: Place = Place(),
    val children: List<Block> = emptyList(),
) {
    @Composable
    abstract fun Render(scope: BlockScope)

    /** The decomposition / override hook. The default refuses visibly; a block that accepts children overrides it. */
    open fun withChildren(children: List<Block>): Block = refuse("$blockType takes no children yet")
}

/** A refusal is loud: resolvers list it (Settings > Plugins, L5); nothing is dropped silently. */
class BlockRefusedException(message: String) : IllegalStateException(message)

fun refuse(message: String): Nothing = throw BlockRefusedException(message)

/** How a node asks its parent to place it. Fields a parent does not understand are ignored by that parent. */
data class Place(
    val weight: Float? = null,
    val weightFill: Boolean = true,
    val size: Size = Size.Wrap,
    val align: Alignment? = null,
    val show: Show = Show.Always,
)

enum class Size { Wrap, FillMax, FillWidth, FillHeight }

/** Pure show/hide, applied by the parent: [Wide] and [Compact] split at `PanelLayout.COMPACT_BELOW_DP`. */
enum class Show { Always, Wide, Compact }

/** What a parent hands a child: its placement [modifier], and in a stack the [box] it can align itself in. */
class BlockScope(val modifier: Modifier, internal val box: BoxScope?)

/** The nearest measured size (only `stack(measure)` provides it). Unspecified counts as wide. */
val LocalBlockSize = staticCompositionLocalOf { DpSize.Unspecified }
