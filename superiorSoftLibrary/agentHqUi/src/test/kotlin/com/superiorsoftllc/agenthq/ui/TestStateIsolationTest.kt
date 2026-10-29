package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.session.stateDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * POLICY-1 gap 9: a UI test JVM never resolves the owner's real state dir. The in-process daemons these tests boot take
 * `AgentHqDaemon.build`'s defaults (the owner token, the policy selection), which read [stateDir]; the build points every
 * UI test task at its own throwaway folder, and this fails the run when that is lost, before a daemon touches `~/.agenthq`.
 */
class TestStateIsolationTest {
    @Test fun `the state dir a UI test resolves is the build's throwaway one, never the owner's`() {
        val real = File(System.getProperty("user.home"), ".agenthq").canonicalFile
        val resolved = stateDir().canonicalFile
        assertNotEquals(real, resolved, "a UI test resolved the owner's real state dir")
        assertTrue(!resolved.path.startsWith(real.path + File.separator), "a UI test resolved a folder inside the owner's state dir: $resolved")
        val configured = System.getProperty("AGENTHQ_STATE_DIR")
        assertTrue(!configured.isNullOrBlank() && File(configured).canonicalFile == resolved, "the UI test task sets AGENTHQ_STATE_DIR (got '$configured')")
    }
}
