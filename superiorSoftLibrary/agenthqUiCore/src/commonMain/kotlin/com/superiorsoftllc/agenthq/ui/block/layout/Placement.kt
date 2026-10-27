package com.superiorsoftllc.agenthq.ui.block.layout

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.isSpecified
import com.superiorsoftllc.agenthq.ui.model.PanelLayout

/** A group is transparent: a container places a group's children as its own (recursively). */
fun List<Block>.flattened(): List<Block> = flatMap { if (it is GroupBlock) it.children.flattened() else listOf(it) }

internal fun Modifier.sized(size: Size): Modifier = when (size) {
    Size.Wrap -> this
    Size.FillMax -> fillMaxSize()
    Size.FillWidth -> fillMaxWidth()
    Size.FillHeight -> fillMaxHeight()
}

/** `show` is the parent's call; compact means the nearest measured width is below the shell breakpoint. */
@Composable
private fun Place.visible(): Boolean {
    if (show == Show.Always) return true
    val width = LocalBlockSize.current.width
    val compact = width.isSpecified && width.value < PanelLayout.COMPACT_BELOW_DP
    return if (show == Show.Compact) compact else !compact
}

/** Order is the contract: weight, align, size. A parent passes only the parts its axis understands. */
internal fun placement(
    place: Place,
    weight: ((Float, Boolean) -> Modifier)?,
    align: ((Alignment) -> Modifier)?,
): Modifier {
    var m: Modifier = Modifier
    val w = place.weight
    if (w != null && weight != null) m = m.then(weight(w, place.weightFill))
    val a = place.align
    if (a != null && align != null) m = m.then(align(a))
    return m.sized(place.size)
}

@Composable
internal fun RowScope.RenderPlaced(children: List<Block>) {
    for (c in children.flattened()) key(c.id) {
        if (c.place.visible()) c.Render(BlockScope(placement(c.place, { w, f -> Modifier.weight(w, f) }, null), null, this))
    }
}

@Composable
internal fun ColumnScope.RenderPlaced(children: List<Block>) {
    for (c in children.flattened()) key(c.id) {
        if (c.place.visible()) c.Render(BlockScope(placement(c.place, { w, f -> Modifier.weight(w, f) }, null), null))
    }
}

@Composable
internal fun BoxScope.RenderPlaced(children: List<Block>) {
    for (c in children.flattened()) key(c.id) {
        if (c.place.visible()) c.Render(BlockScope(placement(c.place, null, { a -> Modifier.align(a) }), this))
    }
}

/** A container with no axis (a group at the root): children get only their `size`. */
@Composable
internal fun RenderPlain(children: List<Block>) {
    for (c in children.flattened()) key(c.id) {
        if (c.place.visible()) c.Render(BlockScope(placement(c.place, null, null), null))
    }
}
