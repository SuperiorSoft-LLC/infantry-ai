package com.superiorsoftllc.agenthq.ui.block.layout

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize

/**
 * BLOCKS-LAYOUT L1 (docs/BLOCKS-LAYOUT-DESIGN.md section 1): the block contract. Since L3 the whole shell is a tree of
 * these blocks (`CoreShell.root`).
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

    /**
     * SHELL-EMPTY-DROPIN S1: this node wears [LocalHostSurface] (core only: `column(surface)`, or a placeholder or override
     * of one), so a replace of it hands the surface on in the scope's modifier instead of dropping the window's focus
     * target and key handler.
     */
    internal open val wearsHostSurface: Boolean get() = false
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

/**
 * What a parent hands a child: its placement [modifier], in a stack the [box] it can align itself in, and in a row the
 * [row] (L6): a block not yet decomposed that emits several items with their own weights into its parent row (both
 * public since S4c: the overlays that align in the dock and the title bar and right panel that emit into a row are app UI).
 */
class BlockScope(val modifier: Modifier, val box: BoxScope?, val row: RowScope? = null) {
    /** Aligns in the parent stack's Box; outside a stack it is the modifier unchanged. */
    fun Modifier.aligned(alignment: Alignment): Modifier = box?.run { this@aligned.align(alignment) } ?: this
}

/** The nearest measured size (only `stack(measure)` provides it). Unspecified counts as wide. */
val LocalBlockSize = staticCompositionLocalOf { DpSize.Unspecified }

/**
 * BLOCKS-LAYOUT L3: the host's window surface (fill, modal blur, background, focus target, key handler), worn by the one
 * `column(surface)` of the core tree. Core only: it sits on the docked column, not the outer stack, so the overlays
 * over it do not blur with it.
 */
internal val LocalHostSurface = compositionLocalOf<Modifier> { Modifier }
