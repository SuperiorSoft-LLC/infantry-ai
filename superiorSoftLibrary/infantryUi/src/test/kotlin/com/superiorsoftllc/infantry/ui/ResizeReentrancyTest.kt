// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * UI-COROUTINE-1: the resize freeze. The edge handles ran `frame.bounds = ...; frame.validate()` inside their pointer
 * coroutine, which Compose can resume from inside FlushCoroutineDispatcher.flush (the synthetic move after a render);
 * the validate re-laid the window out, the Skia layer rendered synchronously, and a nested flush corrupted the
 * dispatcher while a busy session's spinner animated. Reproduced and verified in a real gradle-run window by the owner
 * (resize while a claude session was busy: frozen before, clean after). [FrameBoundsApplier] is the seam that keeps
 * the frame change out of the coroutine; this pins its contract.
 */
class ResizeReentrancyTest {
    @Test
    fun frameChangeNeverRunsInsideTheCallerAndMovesCoalesce() {
        val queue = ArrayDeque<Runnable>()
        val applied = mutableListOf<Rectangle>()
        val applier = FrameBoundsApplier(post = { queue.addLast(it) }) { applied += it }
        applier.request(Rectangle(0, 0, 100, 100))
        applier.request(Rectangle(0, 0, 120, 100))
        applier.request(Rectangle(0, 0, 140, 100))
        assertTrue(applied.isEmpty(), "the frame change must never run inside the pointer coroutine")
        assertEquals(1, queue.size, "moves between two event-queue turns coalesce into one post")
        queue.removeFirst().run()
        assertEquals(listOf(Rectangle(0, 0, 140, 100)), applied, "only the latest bounds are applied")
        applier.request(Rectangle(0, 0, 160, 100))
        assertEquals(1, queue.size, "after a drain the next move posts again")
    }
}
