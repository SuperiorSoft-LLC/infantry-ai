// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.processes.daemon

import com.superiorsoftllc.infantry.daemon.api.processes.PluginSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** STACK-NEUTRAL-2: the launch env is the plugin's; with the switch off it contributes nothing. */
class WatchLaunchEnvTest {
    private class Settings(val limits: Map<String, Long>) : PluginSettings {
        override fun limit(key: String) = limits[key]
        override fun stored(key: String): String? = null
    }

    @Test fun `switch off contributes nothing`() {
        assertTrue(WatchLaunchEnv.env(FakeOs(), Settings(mapOf("limits.watch.enabled" to 0L))).isEmpty())
    }

    @Test fun `switch on gives priority, caps and the profile templates (golden GRADLE_OPTS)`() {
        val env = WatchLaunchEnv.env(FakeOs(), Settings(mapOf("limits.watch.enabled" to 1L, "limits.build.maxWorkers" to 4L, "limits.build.maxParallelForks" to 2L)))
        assertEquals("belownormal", env["INFANTRY_BUILD_PRIORITY"])
        assertEquals("4", env["INFANTRY_BUILD_MAX_WORKERS"])
        assertEquals(
            "-Dorg.gradle.workers.max=4 -Dorg.gradle.project.kotlin.compiler.execution.strategy=in-process " +
                "-Dorg.gradle.project.infantry.testForks=2 -Dorg.gradle.project.infantry.uiTestForks=2",
            env["GRADLE_OPTS"],
        )
    }

    @Test fun `no profile means no stack env`() {
        val os = FakeOs().also { it.profiles = emptyList() }
        val env = WatchLaunchEnv.env(os, Settings(mapOf("limits.watch.enabled" to 1L, "limits.build.maxWorkers" to 4L)))
        assertTrue("GRADLE_OPTS" !in env)
    }
}
