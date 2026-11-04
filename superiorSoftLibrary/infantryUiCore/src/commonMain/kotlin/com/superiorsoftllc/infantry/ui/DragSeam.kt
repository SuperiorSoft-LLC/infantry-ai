// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape

/** AGENT-TASKS-7: what one plugin offers for another to accept: a [kind] the receiver knows, an [id] in that kind, a [label] for display. */
data class DragPayload(val kind: String, val id: String, val label: String = "")

/**
 * AGENT-TASKS-7: the generic cross-plugin drag seam. A plugin that starts a drag calls [begin] / [move] / [end] with
 * root coordinates; any plugin registers a [dropTarget] for the kinds it accepts. Core names no payload kind.
 */
object DragSeam {
    var payload by mutableStateOf<DragPayload?>(null)
        private set
    private var pointer by mutableStateOf(Offset.Zero)
    internal val targets = mutableStateMapOf<Any, Target>()

    internal class Target(val bounds: Rect, val accepts: (DragPayload) -> Boolean, val onDrop: (DragPayload) -> Unit)

    fun begin(p: DragPayload, at: Offset) { payload = p; pointer = at }
    fun move(at: Offset) { pointer = at }

    /** The key of the target that accepts the drag and is under the pointer; null when none. */
    val hovered: Any? get() = hit(payload, pointer, targets.mapValues { it.value.bounds to it.value.accepts })

    /** Ends the drag; true when a registered target took it (its callback has run), so the source must not apply its own drop. */
    fun end(): Boolean {
        val p = payload ?: return false
        val key = hovered
        payload = null
        val target = key?.let { targets[it] } ?: return false
        target.onDrop(p)
        return true
    }

    fun cancel() { payload = null }

    /** Pure: the first key whose bounds contain [pointer] and which accepts [payload]. */
    internal fun hit(payload: DragPayload?, pointer: Offset, candidates: Map<Any, Pair<Rect, (DragPayload) -> Boolean>>): Any? {
        if (payload == null) return null
        return candidates.entries.firstOrNull { (_, v) -> v.first.contains(pointer) && v.second(payload) }?.key
    }
}

/** AGENT-TASKS-7: makes this element take drags of [kind] from [DragSeam]; the accent border shows while one is over it. */
@Composable
fun Modifier.dropTarget(kind: String, onDrop: (DragPayload) -> Unit): Modifier {
    val key = remember { Any() }
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val current = androidx.compose.runtime.rememberUpdatedState(onDrop)
    DisposableEffect(key, bounds) {
        DragSeam.targets[key] = DragSeam.Target(bounds, { it.kind == kind }, { current.value(it) })
        onDispose { DragSeam.targets.remove(key) }
    }
    val over = DragSeam.hovered == key
    return this.onGloballyPositioned { bounds = it.boundsInRoot() }
        .then(if (over) Modifier.border(2.dp, Palette.Accent, RoundedCornerShape(8.dp)) else Modifier)
}
