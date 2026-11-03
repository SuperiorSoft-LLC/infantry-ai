// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.block.layout

import androidx.compose.runtime.Composable

/**
 * SHELL-EMPTY-DROPIN S1 (docs/BLOCKS-LAYOUT-DESIGN.md section 13): an open slot that a module may replace, wrap or inject
 * into. It keeps its [id] and [place], so its parent places whatever draws there exactly as before. It draws its
 * core-provided [default] (handed the same scope, so the default draws pixel for pixel what the node drew before it was
 * a placeholder) and nothing when it has none. Its [children] are the default's, so the default's nodes stay addressable
 * by id and an injection goes in among them; the default's own id and place are never read.
 */
class PlaceholderBlock(
    id: String,
    val default: Block? = null,
    place: Place = Place(),
    /** S6: what an empty slot (no [default]) draws until a module fills it; not a node (no id of its own in the tree). */
    val empty: Block? = null,
) : Block("placeholder", id, place, default?.children.orEmpty()) {
    @Composable
    override fun Render(scope: BlockScope) { (default ?: empty)?.Render(scope) }

    override fun withChildren(children: List<Block>) = PlaceholderBlock(id, default?.withChildren(children) ?: GroupBlock(id, children), place, empty)

    override val wearsHostSurface: Boolean get() = default?.wearsHostSurface == true
}

/** This block as an open slot: a [PlaceholderBlock] with its id and place, drawing this block when nothing replaces it. */
fun Block.asPlaceholder(): PlaceholderBlock = PlaceholderBlock(id, this, place)
