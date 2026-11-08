// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.mascot

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.superiorsoftllc.infantry.ui.AvatarMotion
import com.superiorsoftllc.infantry.ui.AvatarRequest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * MASCOT-3: a chat of 200 rows (never live) plus 20 sidebar agents (live sites) per `mascot.motion` mode.
 * Prints `MASCOT-PERF mode=.. firstMs=.. frameMs=..` (first composition, mean per animation frame over 120 frames).
 */
@OptIn(ExperimentalComposeUiApi::class, androidx.compose.ui.test.ExperimentalTestApi::class)
class MascotPerfTest {
    private fun run(mode: String): Pair<Long, Double> {
        var first = 0L
        var perFrame = 0.0
        runComposeUiTest {
            mainClock.autoAdvance = false
            val states = listOf("BUSY", "IDLE", "WAITING_INPUT", "EXITED")
            val t0 = System.nanoTime()
            setContent {
                Column {
                    repeat(200) { i ->
                        DrawMascot(AvatarRequest(role = "dev$i", sizeDp = 20, state = states[i % 4], motion = AvatarMotion.STATIC), mode, null, null, Modifier.size(20.dp))
                    }
                    repeat(20) { i ->
                        DrawMascot(AvatarRequest(role = "agent$i", sizeDp = 28, state = states[i % 4], motion = AvatarMotion.LIVE), mode, null, null, Modifier.size(28.dp))
                    }
                }
            }
            mainClock.advanceTimeByFrame()
            first = (System.nanoTime() - t0) / 1_000_000
            val t1 = System.nanoTime()
            repeat(120) { mainClock.advanceTimeByFrame() }
            perFrame = (System.nanoTime() - t1) / 1_000_000.0 / 120
        }
        return first to perFrame
    }

    @Test
    fun `measure static focus all`() {
        run("static") // warm up the JIT
        listOf("static", "focus", "all").forEach { m ->
            val (f, p) = run(m)
            println("MASCOT-PERF mode=$m firstMs=$f frameMs=${"%.2f".format(p)}")
            assertTrue(p < 500, "frame mean $p ms in mode $m")
        }
    }
}
