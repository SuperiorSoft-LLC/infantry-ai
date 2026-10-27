package com.superiorsoftllc.agenthq.ui.block

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize

/**
 * BLOCKS-SHELL-1: the smallest block shape, in the BLOCKS-1 style (a block type, an id, children, and a Render on the
 * class itself; context flows down through CompositionLocals). BLOCKS-1 P1 (registry, JSON decode) has not landed;
 * this is only what the shell's root needs, and P1 is expected to grow it.
 *
 * A block draws itself and decides its own size; it only tells its container which [edge] it takes.
 */
abstract class Block(
    val blockType: String,
    val id: String,
    val edge: Edge,
    val children: List<Block> = emptyList(),
) {
    @Composable
    abstract fun Render(dock: DockScope)
}

/**
 * Where a block sits in a [DockHost]. The host knows edges, never what a block is.
 *  [TOP] / [BOTTOM]: stacked above / below the middle region, in order.
 *  [START] / [FILL] / [END]: the middle region's row; [FILL] takes the width the others leave.
 *  [FILL_OVERLAY]: stacked over the middle region. [OVERLAY]: stacked over the whole dock.
 *  [WINDOW]: outside the dock (dialogs), drawn by [DockWindow].
 */
enum class Edge { TOP, BOTTOM, START, FILL, END, FILL_OVERLAY, OVERLAY, WINDOW }

/**
 * What the container hands a block: its own [modifier] for the edge, and in an overlay layer the layer's [box] (null
 * elsewhere), for blocks that align themselves in it.
 */
class DockScope internal constructor(val modifier: Modifier, val box: BoxScope?) {
    /** Aligns in the overlay layer's Box; outside an overlay layer it is the modifier unchanged. */
    fun Modifier.aligned(alignment: Alignment): Modifier = box?.run { this@aligned.align(alignment) } ?: this
}

/**
 * BLOCKS-SHELL-2: renders a static block's [children] in list order, where the parent calls it. A child is handed the
 * plain scope (no edge placement of its own) and keeps its own modifiers; it knows nothing of its siblings.
 */
@Composable
fun List<Block>.Render() {
    val plain = DockScope(Modifier, null)
    for (b in this) key(b.id) { b.Render(plain) }
}

/** The middle region's maximum size, for blocks that shape themselves by it (e.g. a compact window). */
val LocalDockSize = staticCompositionLocalOf { DpSize.Unspecified }

/**
 * BLOCKS-SHELL-1: the neutral root container. It renders [blocks] in array order, grouped by edge; [modifier] goes on
 * the docked column (the overlay layer sits outside it, as the window's popups always did).
 */
@Composable
fun DockHost(blocks: List<Block>, modifier: Modifier = Modifier) {
    val plain = DockScope(Modifier, null)
    Box(Modifier.fillMaxSize()) {
        Column(modifier) {
            blocks.on(Edge.TOP) { it.Render(plain) }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                CompositionLocalProvider(LocalDockSize provides DpSize(maxWidth, maxHeight)) {
                    Box(Modifier.fillMaxSize()) {
                        Row(Modifier.fillMaxSize()) {
                            blocks.on(Edge.START) { it.Render(plain) }
                            blocks.on(Edge.FILL) { it.Render(DockScope(Modifier.weight(1f), null)) }
                            blocks.on(Edge.END) { it.Render(plain) }
                        }
                        val over = DockScope(Modifier, this)
                        blocks.on(Edge.FILL_OVERLAY) { it.Render(over) }
                    }
                }
            }
            blocks.on(Edge.BOTTOM) { it.Render(plain) }
        }
        val over = DockScope(Modifier, this)
        blocks.on(Edge.OVERLAY) { it.Render(over) }
    }
}

/** BLOCKS-SHELL-1: the [Edge.WINDOW] blocks, in array order, wherever the host places its window layer. */
@Composable
fun DockWindow(blocks: List<Block>) {
    val plain = DockScope(Modifier, null)
    blocks.on(Edge.WINDOW) { it.Render(plain) }
}

@Composable
private inline fun List<Block>.on(edge: Edge, render: @Composable (Block) -> Unit) {
    for (b in this) if (b.edge == edge) key(b.id) { render(b) }
}
