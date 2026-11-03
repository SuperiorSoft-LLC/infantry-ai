package com.superiorsoftllc.infantry.ui.block.layout

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize

/** BLOCKS-LAYOUT L1: `group`, a fragment. Draws no node; a container flattens it, at the root it renders in sequence. */
class GroupBlock(id: String, children: List<Block> = emptyList(), place: Place = Place()) :
    Block("group", id, place, children) {
    @Composable
    override fun Render(scope: BlockScope) = RenderPlain(children)

    override fun withChildren(children: List<Block>) = GroupBlock(id, children, place)
}

/** `stack`: `Box`; children align by `place.align`. With [measure] it provides [LocalBlockSize] to its children. */
class StackBlock(id: String, val measure: Boolean = false, children: List<Block> = emptyList(), place: Place = Place()) :
    Block("stack", id, place, children) {
    @Composable
    override fun Render(scope: BlockScope) {
        if (!measure) {
            Box(scope.modifier) { RenderPlaced(children) }
        } else {
            BoxWithConstraints(scope.modifier) {
                CompositionLocalProvider(LocalBlockSize provides DpSize(maxWidth, maxHeight)) {
                    Box(Modifier.fillMaxSize()) { RenderPlaced(children) }
                }
            }
        }
    }

    override fun withChildren(children: List<Block>) = StackBlock(id, measure, children, place)
}

/** `row`: `Row`; children are placed by `place.weight/weightFill`. */
class RowBlock(
    id: String,
    val verticalAlignment: Alignment.Vertical = Alignment.Top,
    children: List<Block> = emptyList(),
    place: Place = Place(),
) : Block("row", id, place, children) {
    @Composable
    override fun Render(scope: BlockScope) =
        Row(scope.modifier, verticalAlignment = verticalAlignment) { RenderPlaced(children) }

    override fun withChildren(children: List<Block>) = RowBlock(id, verticalAlignment, children, place)
}

/**
 * `column`: `Column`; children are placed by `place.weight/weightFill`. With [surface] (core only) it also wears the
 * host's [LocalHostSurface] modifier.
 */
class ColumnBlock(
    id: String,
    val horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    children: List<Block> = emptyList(),
    place: Place = Place(),
    internal val surface: Boolean = false,
) : Block("column", id, place, children) {
    @Composable
    override fun Render(scope: BlockScope) {
        val mod = if (surface) scope.modifier.then(LocalHostSurface.current) else scope.modifier
        Column(mod, horizontalAlignment = horizontalAlignment) { RenderPlaced(children) }
    }

    override fun withChildren(children: List<Block>) = ColumnBlock(id, horizontalAlignment, children, place, surface)

    override val wearsHostSurface: Boolean get() = surface
}

/** `spacer`: a leaf; with `place.weight` it is the flexible gap, [width]/[height] fix its size. */
class SpacerBlock(id: String, val width: Dp? = null, val height: Dp? = null, place: Place = Place()) :
    Block("spacer", id, place) {
    @Composable
    override fun Render(scope: BlockScope) {
        var m = scope.modifier
        width?.let { m = m.width(it) }
        height?.let { m = m.height(it) }
        Spacer(m)
    }
}
