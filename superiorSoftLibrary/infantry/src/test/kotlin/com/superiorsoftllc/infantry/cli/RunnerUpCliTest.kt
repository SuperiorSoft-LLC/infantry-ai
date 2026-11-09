// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.cli

import com.superiorsoftllc.infantry.settings.LimitRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** RUNNER-UP-1: `runner up` takes the pair flags, needs a name, and its start wait is a named limit. */
class RunnerUpCliTest {
    @Test fun upTakesTheSameFlagsAsPair() {
        assertNull(RunnerCli.usageError(RunnerCli.Args(listOf("up"), name = "t1", hosts = "127.0.0.1", allowProjects = "*", allowHarnesses = "echo")))
    }

    @Test fun upNeedsAName() {
        assertNotNull(RunnerCli.usageError(RunnerCli.Args(listOf("up"))))
    }

    @Test fun upRefusesPairCodeFlags() {
        assertNotNull(RunnerCli.usageError(RunnerCli.Args(listOf("up"), name = "t1", code = "x")))
    }

    @Test fun startWaitIsALimitWithAnEnvOverride() {
        assertEquals(60, RunnerUpLauncher.waitSeconds { null })
        assertEquals(5, RunnerUpLauncher.waitSeconds { if (it == LimitRegistry.RUNNERS_UP_START_SEC.env) "5" else null })
        assertEquals("INFANTRY_LIMIT_RUNNERS_UP_START_SEC", LimitRegistry.RUNNERS_UP_START_SEC.env)
    }
}
