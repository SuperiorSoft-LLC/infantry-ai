package com.superiorsoftllc.agenthq.daemon

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class InstanceIdTest {
    @Test
    fun theIdSurvivesARestartOfTheSameDir_andADifferentDirGetsItsOwn() {
        val a = Files.createTempDirectory("agenthq-iid-a").toFile()
        val b = Files.createTempDirectory("agenthq-iid-b").toFile()
        val first = InstanceId.loadOrCreate(a)
        assertEquals(first, InstanceId.loadOrCreate(a))
        assertNotEquals(first, InstanceId.loadOrCreate(b))
    }
}
