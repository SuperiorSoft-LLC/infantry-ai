package com.superiorsoftllc.agenthq.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * TEST-SPEED-1: every parallel test fork reads its own tmpdir and state dir (TestForkAgent, wired in agentHqUi/build.gradle.kts from :agentHq).
 *
 * Only System.getProperty readers move: the JDK's createTempFile caches java.io.tmpdir before an agent's premain runs, so
 * random temp files still land in the shared parent (harmless: their names are unique). Fixed-name paths built from the
 * property are what could collide across forks, and those are covered.
 */
class UiTestForkIsolationTest {
    private val fork = "fork-${ProcessHandle.current().pid()}"

    @Test
    fun theTmpdirAndTheStateDirPropertiesAreThisForksOwn() {
        assertEquals(fork, File(System.getProperty("java.io.tmpdir")).name)
        assertEquals(fork, File(System.getProperty("AGENTHQ_STATE_DIR")).name)
    }
}
