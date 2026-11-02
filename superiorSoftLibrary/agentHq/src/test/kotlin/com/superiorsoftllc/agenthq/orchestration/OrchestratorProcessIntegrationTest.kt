package com.superiorsoftllc.agenthq.orchestration

import com.superiorsoftllc.agenthq.persistence.RunEvent
import com.superiorsoftllc.agenthq.persistence.SqlAgentHqStore
import com.superiorsoftllc.agenthq.protocol.FinishedPayload.Outcome
import com.superiorsoftllc.agenthq.harness.AgentProvider
import com.superiorsoftllc.agenthq.harness.RunRequest
import com.superiorsoftllc.agenthq.runner.PipedProcessRunner
import com.superiorsoftllc.agenthq.runner.ProcessSpec
import com.superiorsoftllc.agenthq.session.JsonSessionStore
import com.superiorsoftllc.agenthq.session.SessionManager
import com.superiorsoftllc.agenthq.session.SessionState
import com.superiorsoftllc.agenthq.worktree.GitWorktreeManager
import com.superiorsoftllc.agenthq.worktree.SystemGitCli
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OrchestratorProcessIntegrationTest {

    private val toClose = mutableListOf<AutoCloseable>()

    @AfterTest
    fun closeAll() {
        toClose.asReversed().forEach { runCatching { it.close() } }
        toClose.clear()
    }

    private val javaExe: String = run {
        val home = File(System.getProperty("java.home"))
        listOf(File(home, "bin/java.exe"), File(home, "bin/java")).firstOrNull { it.isFile }?.absolutePath
            ?: error("no java executable under ${home.path}/bin")
    }

    private val classpath: String = System.getProperty("java.class.path").orEmpty()

    /** Same real child as SessionProcessIntegrationTest: FakeProviderCli on the test classpath. */
    private fun provider(): AgentProvider = object : AgentProvider {
        override val id = "fakejava"
        override fun spec(request: RunRequest): ProcessSpec = ProcessSpec(
            command = buildList {
                add(javaExe); add("-cp"); add(classpath)
                add("com.superiorsoftllc.agenthq.session.FakeProviderCliKt")
                request.resumeSessionId?.let { add("--session"); add(it) }
            },
            workingDir = request.workingDir,
        )
        override fun sessionIdFromLine(line: String): String? = SESSION_ID.find(line)?.groupValues?.get(1)
        private val SESSION_ID = Regex("\"session_id\"\\s*:\\s*\"([^\"]+)\"")
    }

    private val git = SystemGitCli(
        extraEnv = mapOf(
            "GIT_AUTHOR_NAME" to "AgentHQ Test",
            "GIT_AUTHOR_EMAIL" to "agenthq@test.local",
            "GIT_COMMITTER_NAME" to "AgentHQ Test",
            "GIT_COMMITTER_EMAIL" to "agenthq@test.local",
        ),
    )

    private fun newRepo(): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "agenthq-orch-repo-${UUID.randomUUID()}").apply { mkdirs() }
        assertTrue(git.run(dir, listOf("init", "-q")).ok, "git init must succeed")
        File(dir, "README.md").writeText("hello\n")
        assertTrue(git.run(dir, listOf("add", "-A")).ok)
        assertTrue(git.run(dir, listOf("commit", "-q", "-m", "init")).ok, "initial commit must succeed")
        return dir
    }

    private fun await(timeoutMs: Long, what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        error("not met within ${timeoutMs}ms: $what")
    }

    @Test
    fun `real child processes fan out across real worktrees and converge`() {
        val dir = Files.createTempDirectory("agenthq-orch-it-").toFile()
        val repo = newRepo()
        val store = SqlAgentHqStore.open(File(dir, "agenthq.db").absolutePath).also { toClose += it }
        val manager = SessionManager(
            provider(), PipedProcessRunner(), JsonSessionStore(File(dir, "sessions.json")),
            worktrees = GitWorktreeManager(git), defaultWorkingDir = dir,
        )
        val orch = Orchestrator(store, manager).also { toClose += it }
        manager.eventSink = orch::onSessionEvent

        val pod = orch.submit(
            PodSpec(
                goal = "real fan-out", maxConcurrency = 2, repo = repo,
                nodes = listOf(
                    NodeSpec("left", "left work"),
                    NodeSpec("right", "right work"),
                    NodeSpec("join", "join work", setOf("left", "right")),
                ),
            ),
        )
        val done = orch.awaitTerminal(pod.id, 120_000)
        assertEquals(PodState.CONVERGED.name, done.state, "nodes: ${store.nodes.byPod(pod.id)}")

        val runs = store.nodes.byPod(pod.id).map { n -> store.runs.get(store.nodes.attempts(n.id).single().runId)!! }
        assertEquals(3, runs.map { File(it.workingDir).canonicalPath }.toSet().size, "every run in its own checkout")
        val worktreesRoot = File(repo, ".worktrees").canonicalFile
        for (run in runs) {
            val wd = File(run.workingDir).canonicalFile
            assertEquals(worktreesRoot, wd.parentFile, "run ${run.id} must run inside a worktree of the repo")
            assertTrue(File(wd, ".git").exists(), "a linked worktree carries a .git file")
            assertEquals(0, run.exitCode)
            assertEquals(SessionState.IDLE, run.state)
            assertTrue(run.providerSessionId!!.startsWith("sess-"), "the real child's stdout was parsed")
            assertNotNull(manager.get(run.sessionId)!!.worktreeBranch)
        }
        assertEquals(3, runs.map { manager.get(it.sessionId)!!.worktreeBranch }.toSet().size, "one branch per attempt")
        assertEquals(3, orch.reviewQueue().count { it.podId == pod.id && it.worktreeBranch != null })
        assertTrue(orch.failures.isEmpty(), "orchestrator thread threw: ${orch.failures}")
    }

    @Test
    fun `a real process tree killed by cancelPod is KILLED_FORCED and is not retried`() {
        val dir = Files.createTempDirectory("agenthq-orch-kill-").toFile()
        val store = SqlAgentHqStore.open(File(dir, "agenthq.db").absolutePath).also { toClose += it }
        val manager = SessionManager(
            provider(), PipedProcessRunner(), JsonSessionStore(File(dir, "sessions.json")),
            killGraceMs = 500, defaultWorkingDir = dir,
        )
        val orch = Orchestrator(store, manager).also { toClose += it }
        manager.eventSink = orch::onSessionEvent

        val pod = orch.submit(PodSpec(goal = "kill real", nodes = listOf(NodeSpec("sleeper", "please SLEEP", maxAttempts = 3))))
        var pid: Long? = null
        await(30_000, "the real child is alive and streaming") {
            val run = orch.status(pod.id)!!.runs.values.flatten().singleOrNull()
            val session = run?.let { manager.get(it.sessionId) }
            pid = session?.childPid
            pid != null && session?.state == SessionState.BUSY
        }

        // Graceful first: the child already consumed stdin EOF and is sleeping, so the daemon MUST escalate.
        orch.cancelPod(pod.id, force = false)
        assertEquals(PodState.CANCELLED.name, orch.awaitTerminal(pod.id, 30_000).state)

        val node = store.nodes.byPod(pod.id).single()
        assertEquals(NodeState.CANCELLED.name, node.state)
        assertEquals(1, node.attempts)
        assertEquals("killed (forced)", node.detail)
        val run = store.runs.get(store.nodes.attempts(node.id).single().runId)!!
        assertEquals(Outcome.KILLED_FORCED, (store.events.forRun(run.id).last().event as RunEvent.Finished).payload.outcome)
        val deadPid = pid!!
        await(10_000, "child pid $deadPid is dead") { !ProcessHandle.of(deadPid).map { it.isAlive }.orElse(false) }
        Thread.sleep(1_000)
        assertEquals(1, store.runs.byPod(pod.id).size, "a killed run is never retried")
        assertTrue(orch.failures.isEmpty(), "orchestrator thread threw: ${orch.failures}")
    }
}
