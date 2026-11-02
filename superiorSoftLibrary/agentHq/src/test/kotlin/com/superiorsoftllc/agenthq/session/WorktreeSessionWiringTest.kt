package com.superiorsoftllc.agenthq.session

import com.superiorsoftllc.agenthq.harness.AgentProvider
import com.superiorsoftllc.agenthq.harness.RunRequest
import com.superiorsoftllc.agenthq.runner.ProcessListener
import com.superiorsoftllc.agenthq.runner.ProcessRunner
import com.superiorsoftllc.agenthq.runner.ProcessSpec
import com.superiorsoftllc.agenthq.runner.RunningProcess
import com.superiorsoftllc.agenthq.worktree.WorktreeException
import com.superiorsoftllc.agenthq.worktree.WorktreeProvisioner
import com.superiorsoftllc.agenthq.worktree.WorktreeRecord
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Phase 4 spawn wiring, provider-neutral and git-free: a session handed a repo runs in the
 * worktree the provisioner returns, and a provisioning failure FAILS the session instead of silently
 * falling back to the shared checkout.
 *
 * Prerequisite: the real git behaviour of the provisioner is proven in [WorktreeManagerTest].
 */
class WorktreeSessionWiringTest {

    private class FakeProvisioner(private val failing: Boolean = false) : WorktreeProvisioner {
        var calls = 0
        override fun create(sessionId: String, repo: File, branchHint: String?, baseRef: String?): WorktreeRecord {
            calls++
            if (failing) throw WorktreeException("provisioning boom")
            val path = File(repo, ".worktrees/agenthq-x-$sessionId")
            return WorktreeRecord(sessionId, repo.absolutePath, path.absolutePath, "agenthq/x-$sessionId", "base-sha", 0L)
        }
    }

    private class StubProvider : AgentProvider {
        override val id = "stub"
        var specs = 0
        override fun spec(request: RunRequest): ProcessSpec {
            specs++
            return ProcessSpec(listOf("stub"))
        }
        override fun sessionIdFromLine(line: String) = if (line.contains("session_id")) "prov-1" else null
    }

    private class NoopProcess : RunningProcess {
        override val isAlive: Boolean = false
        override val pid: Long? = null
        override fun writeInput(text: String) = Unit
        override fun closeInput() = Unit
        override fun killTree() = Unit
        override fun awaitExit(): Int = 0
    }

    private class NoopRunner : ProcessRunner {
        override val id = "noop"
        override fun start(spec: ProcessSpec, listener: ProcessListener): RunningProcess {
            listener.onStdout("{\"session_id\":\"prov-1\"}")
            return NoopProcess()
        }
    }

    private fun tempStore(): JsonSessionStore {
        val file = File.createTempFile("agenthq-wt-sessions", ".json")
        file.delete()
        return JsonSessionStore(file)
    }

    @Test
    fun startWithRepo_runsInTheWorktree_andRecordsItsIdentity() {
        val provisioner = FakeProvisioner()
        val manager = SessionManager(StubProvider(), NoopRunner(), tempStore(), worktrees = provisioner)
        val repo = File(System.getProperty("java.io.tmpdir"), "agenthq-fake-repo")

        val session = manager.startNew("do the task", repo = repo, branchHint = "do the task")

        assertEquals(1, provisioner.calls)
        assertEquals(File(repo, ".worktrees/agenthq-x-${session.id}").absolutePath, session.workingDir)
        assertEquals("agenthq/x-${session.id}", session.worktreeBranch)
        assertEquals(repo.absolutePath, session.repoRoot)
        assertEquals("base-sha", session.worktreeBaseRef)
        assertNotNull(session.worktreePath)
    }

    @Test
    fun startWithoutRepo_keepsTheSharedCheckout() {
        val provisioner = FakeProvisioner()
        val dir = File(System.getProperty("java.io.tmpdir"), "agenthq-shared")
        val manager = SessionManager(StubProvider(), NoopRunner(), tempStore(), worktrees = provisioner)

        val session = manager.startNew("task", workingDir = dir)

        assertEquals(0, provisioner.calls, "no repo -> no worktree")
        assertEquals(dir.absolutePath, session.workingDir)
        assertEquals(null, session.worktreePath)
    }

    @Test
    fun provisioningFailure_failsTheSession_andNeverRunsTheProvider() {
        val provider = StubProvider()
        val manager = SessionManager(
            provider, NoopRunner(), tempStore(), worktrees = FakeProvisioner(failing = true),
        )

        val session = manager.startNew("task", repo = File(System.getProperty("java.io.tmpdir"), "whatever"))

        assertEquals(SessionState.FAILED, session.state)
        assertTrue(session.lastError!!.contains("provisioning boom"))
        assertEquals(null, session.worktreePath)
        assertEquals(0, provider.specs, "the provider must not run when isolation could not be established")
    }
}
