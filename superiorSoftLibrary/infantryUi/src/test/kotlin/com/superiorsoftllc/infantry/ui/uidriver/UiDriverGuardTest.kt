// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.uidriver

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** UI-DRIVER-1: the driver starts only on an explicit opt-in, headless, on a test port, in a temp state dir. */
class UiDriverGuardTest {
    private val tmp = Files.createTempDirectory("ui-driver-guard").toFile()
    private val state = File(tmp, "state").apply { mkdirs() }
    private val home = Files.createTempDirectory("ui-driver-home").toFile()

    private fun problems(optIn: String? = "1", headless: Boolean = true, port: Int = 18950, stateDir: File = state) =
        UiDriverGuard.problems(optIn, headless, port, stateDir, tmp, home)

    @Test
    fun `a test instance with every fence in place may start`() {
        assertEquals(emptyList(), problems())
    }

    @Test
    fun `no opt-in, no start`() {
        assertTrue(problems(optIn = null).single().contains(UiDriverGuard.OPT_IN_ENV))
        assertTrue(problems(optIn = "true").single().contains(UiDriverGuard.OPT_IN_ENV), "only the exact value 1 opts in")
    }

    @Test
    fun `a visible window is refused`() {
        assertTrue(problems(headless = false).single().contains("headless"))
    }

    @Test
    fun `the owner's ports and ports outside the test range are refused`() {
        for (p in UiDriverGuard.OWNER_PORTS) assertTrue(problems(port = p).single().contains("owner"), "port $p")
        for (p in listOf(0, 18899, 19000, 8080)) assertTrue(problems(port = p).single().contains("test range"), "port $p")
    }

    @Test
    fun `a state dir outside the temp dir or inside the owner's state is refused`() {
        val elsewhere = Files.createTempDirectory("other").toFile().parentFile.parentFile
        assertTrue(problems(stateDir = elsewhere).any { it.contains("not under the temp dir") })
        val ownerState = File(home, ".infantry/x").apply { mkdirs() }
        assertTrue(problems(stateDir = ownerState).any { it.contains("owner's state dir") })
    }
}
