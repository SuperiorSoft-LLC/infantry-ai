// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.changes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import com.superiorsoftllc.infantry.plugins.official.changes.DiffModalBody
import com.superiorsoftllc.infantry.plugins.official.changes.DiffModalFrame
import com.superiorsoftllc.infantry.plugins.official.changes.DiffModalHeader
import com.superiorsoftllc.infantry.plugins.official.changes.OpenDiff
import com.superiorsoftllc.infantry.ui.block.layout.Block
import com.superiorsoftllc.infantry.ui.block.layout.BlockScope
import com.superiorsoftllc.infantry.ui.block.layout.Place
import com.superiorsoftllc.infantry.ui.block.layout.RenderPlaced
import com.superiorsoftllc.infantry.ui.block.layout.Size
import com.superiorsoftllc.infantry.ui.model.DiffBody
import com.superiorsoftllc.infantry.ui.model.diffBody
import com.superiorsoftllc.infantry.plugins.official.changes.openDiff
import com.superiorsoftllc.infantry.ui.shell.LocalShellContext

private const val DIFF = "infantry.shell.diff"

/** What the diff modal's children draw: the open file's diff, its parsed body, close / prev / next. */
private class DiffHost(val open: OpenDiff, val body: DiffBody)

/** The open diff; null outside [DiffBlock] (a child moved elsewhere draws nothing). */
private val LocalDiffHost = compositionLocalOf<DiffHost?> { null }

/**
 * DIFF-MODAL: the changed file's diff over the window, while one is open.
 * BLOCKS-LAYOUT L11: a container of child blocks `infantry.shell.diff.<name>`, top to bottom, drawn straight into the
 * modal's panel column. The frame (scrim, scrim tap = close, Esc, focus) stays the modal's own.
 */
class DiffBlock(children: List<Block>, place: Place = Place()) : Block("diffModal", DIFF, place, children) {
    @Composable
    override fun Render(scope: BlockScope) {
        val d = LocalShellContext.current?.diff ?: return
        val state = d.state ?: return
        val controller = d.controller ?: return
        val target = d.target ?: return
        val open = openDiff(state, controller, target, onTarget = d::show) ?: return
        val host = DiffHost(open, diffBody(open.patch))
        DiffModalFrame(open.onClose) {
            CompositionLocalProvider(LocalDiffHost provides host) { RenderPlaced(children) }
        }
    }

    override fun withChildren(children: List<Block>) = DiffBlock(children, place)
}

/** The title row (path, status, +/− counts, ‹ › ✕) and the divider under it; its placement goes on the row. */
object DiffHeader : Block("diffHeader", "$DIFF.header") {
    @Composable
    override fun Render(scope: BlockScope) {
        val host = LocalDiffHost.current ?: return
        val open = host.open
        DiffModalHeader(open.path, open.status, host.body, open.onClose, open.onPrev, open.onNext, scope.modifier)
    }
}

/** Weight 1: the diff view (gutters, add/remove bands, sideways scroll) or its notice; its Box wears the placement. */
object DiffView : Block("diffView", "$DIFF.body", Place(weight = 1f, size = Size.FillWidth)) {
    @Composable
    override fun Render(scope: BlockScope) {
        val host = LocalDiffHost.current ?: return
        DiffModalBody(host.body, scope.modifier)
    }
}

val ShellDiff = DiffBlock(listOf(DiffHeader, DiffView))
