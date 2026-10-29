package com.superiorsoftllc.agenthq.daemon

import com.superiorsoftllc.agenthq.session.stateDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * POLICY-1 gap 9: a test JVM never resolves the owner's real state dir. `AgentHqDaemon.build`'s defaults (the owner token,
 * the policy selection, the access token) read [stateDir]; the build points every test task at its own throwaway folder,
 * and this fails the run when that is lost (a new Test task, an IDE run without it), before a fixture daemon can read or
 * write `~/.agenthq`.
 */
class TestStateIsolationTest {
    @Test fun `the state dir a test resolves is the build's throwaway one, never the owner's`() {
        val real = File(System.getProperty("user.home"), ".agenthq").canonicalFile
        val resolved = stateDir().canonicalFile
        assertNotEquals(real, resolved, "a test resolved the owner's real state dir")
        assertTrue(!resolved.path.startsWith(real.path + File.separator), "a test resolved a folder inside the owner's state dir: $resolved")
        val configured = System.getProperty("AGENTHQ_STATE_DIR")
        assertTrue(!configured.isNullOrBlank() && File(configured).canonicalFile == resolved, "the test task sets AGENTHQ_STATE_DIR (got '$configured')")
    }
}
