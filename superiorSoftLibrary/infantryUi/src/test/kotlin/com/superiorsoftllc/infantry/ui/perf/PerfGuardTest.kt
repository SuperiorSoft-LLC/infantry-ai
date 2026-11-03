// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.perf

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** PERF-1: the profile must never touch the owner's daemon (8787/8788) or `~/.infantry`. */
class PerfGuardTest {
    private val ownerHome = Files.createTempDirectory("perf-owner-home").toFile()
    private val ownerState = File(ownerHome, ".infantry").apply { mkdirs() }
    private val guard = PerfGuard(ownerHome)

    @Test
    fun `owner ports are refused, any other port passes`() {
        PerfGuard.OWNER_PORTS.forEach { port ->
            val refused = assertFailsWith<IllegalStateException> { guard.requirePort(port, "daemon") }
            assertTrue("$port" in refused.message.orEmpty(), refused.message)
        }
        guard.requirePort(51234, "daemon")
    }

    @Test
    fun `a path inside the owner's infantry dir or outside the temp root is refused`() {
        val root = Files.createTempDirectory("perf-root").toFile()
        guard.requireIsolated(File(root, "state"), root)
        assertFailsWith<IllegalStateException> { guard.requireIsolated(File(ownerState, "state"), root) }
        assertFailsWith<IllegalStateException> { guard.requireIsolated(ownerState, ownerState) }
        assertFailsWith<IllegalStateException> { guard.requireIsolated(File(ownerHome, "elsewhere"), root) }
    }

    @Test
    fun `the owner's daemon port file and listeners must be unchanged after the run`() {
        val portFile = File(ownerState, "daemon.port").apply { writeText("8787") }
        val before = guard.ownerFingerprint(listeners = mapOf(8787 to 4242L))
        assertEquals(emptyList(), guard.diff(before, guard.ownerFingerprint(listeners = mapOf(8787 to 4242L))))

        portFile.writeText("51234")
        val rewritten = guard.diff(before, guard.ownerFingerprint(listeners = mapOf(8787 to 4242L)))
        assertTrue(rewritten.any { "daemon.port" in it }, "$rewritten")

        portFile.writeText("8787")
        val restarted = guard.diff(before, guard.ownerFingerprint(listeners = mapOf(8787 to 9999L)))
        assertTrue(restarted.any { "8787" in it }, "$restarted")
    }

    @Test
    fun `a child JVM whose user home is not the temp home is refused`() {
        val root = Files.createTempDirectory("perf-root").toFile()
        val home = File(root, "home").apply { mkdirs() }
        guard.requireChildHome("daemon", home.path, home)
        assertFailsWith<IllegalStateException> { guard.requireChildHome("daemon", ownerHome.path, home) }
        assertFailsWith<IllegalStateException> { guard.requireChildHome("ui", null, home) }
    }
}
