// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class InstanceIdTest {
    @Test
    fun theIdSurvivesARestartOfTheSameDir_andADifferentDirGetsItsOwn() {
        val a = Files.createTempDirectory("infantry-iid-a").toFile()
        val b = Files.createTempDirectory("infantry-iid-b").toFile()
        val first = InstanceId.loadOrCreate(a)
        assertEquals(first, InstanceId.loadOrCreate(a))
        assertNotEquals(first, InstanceId.loadOrCreate(b))
    }
}
