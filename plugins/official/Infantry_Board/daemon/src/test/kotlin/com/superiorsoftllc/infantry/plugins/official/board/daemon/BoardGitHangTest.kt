// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.board.daemon

import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** BOARD-HANG-1: a git that never answers is killed at its budget instead of blocking the board call. */
class BoardGitHangTest {
    private val win = System.getProperty("os.name").lowercase().contains("win")

    @Test
    fun `a hanging git returns null within the budget`() {
        val sleeper = if (win) listOf("ping", "-n", "30", "127.0.0.1") else listOf("sleep", "30")
        val t0 = System.nanoTime()
        assertNull(BoardTool.gitTip(sleeper, 500))
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue(ms < 5_000, "took $ms ms")
    }

    @Test
    fun `a hanging git that floods output still returns within the budget`() {
        val flood = if (win) listOf("ping", "-t", "127.0.0.1") else listOf("yes")
        val t0 = System.nanoTime()
        BoardTool.gitTip(flood, 500)
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 5_000)
    }
}
