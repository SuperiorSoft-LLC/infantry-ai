// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.updater

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class ResumeWorkersTest {
    @Test fun saveThenTakeReturnsIdsOnce() {
        val dir = Files.createTempDirectory("resume").toFile()
        ResumeWorkers.save(dir, listOf("s1", "s2"))
        assertEquals(listOf("s1", "s2"), ResumeWorkers.take(dir))
        assertEquals(emptyList(), ResumeWorkers.take(dir))
    }

    @Test fun emptySaveClearsStaleList() {
        val dir = Files.createTempDirectory("resume").toFile()
        ResumeWorkers.save(dir, listOf("old"))
        ResumeWorkers.save(dir, emptyList())
        assertEquals(emptyList(), ResumeWorkers.take(dir))
    }
}
