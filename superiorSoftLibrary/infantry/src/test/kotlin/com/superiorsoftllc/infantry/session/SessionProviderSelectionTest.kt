// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.session

import com.superiorsoftllc.infantry.harness.AgentProvider
import com.superiorsoftllc.infantry.harness.RunRequest
import com.superiorsoftllc.infantry.runner.ProcessListener
import com.superiorsoftllc.infantry.runner.ProcessRunner
import com.superiorsoftllc.infantry.runner.ProcessSpec
import com.superiorsoftllc.infantry.runner.RunningProcess
import com.superiorsoftllc.infantry.worktree.WorktreeProvisioner
import com.superiorsoftllc.infantry.worktree.WorktreeRecord
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract 1.3.0 (story 9.0): one [SessionManager], several providers.
 *
 * A spawn names its provider and model; the session records both; every later run and resume goes
 * back to them. A choice the manager cannot honour is refused BEFORE anything exists, and is never
 * quietly replaced by the default provider -- that substitution is the failure this story guards.
 *
 * Contract 1.4.0 (story 9.1) adds attribution: a session spawned from an agent profile records the
 * profile's id and role, keeps them across resumes and restarts, and a pre-1.4.0 record reads as "no
 * profile".
 */
class SessionProviderSelectionTest {

    /** Records every request it builds a spec for; its argv echoes the model so the runner sees it. */
    private class RecordingProvider(override val id: String, private val configuredModel: String? = null) : AgentProvider {
        val requests = CopyOnWriteArrayList<RunRequest>()
        override fun spec(request: RunRequest): ProcessSpec {
            requests += request
            return ProcessSpec(listOf(id, "--model", request.model ?: "<default>"))
        }
        override fun sessionIdFromLine(line: String) = if (line.contains("session_id")) "$id-psid" else null
        override fun defaultModel() = configuredModel
    }

    /** Ends every run at once after revealing a session id, so a session lands IDLE and resumable. */
    private class InstantRunner(override val id: String) : ProcessRunner {
        val specs = CopyOnWriteArrayList<ProcessSpec>()
        override fun start(spec: ProcessSpec, listener: ProcessListener): RunningProcess {
            specs += spec
            listener.onStdout("{\"session_id\":\"x\"}")
            listener.onExit(0)
            return Ended
        }
    }

    private object Ended : RunningProcess {
        override val isAlive = false
        override fun writeInput(text: String) = Unit
        override fun closeInput() = Unit
        override fun killTree() = Unit
        override fun awaitExit() = 0
    }

    private val opencode = RecordingProvider("oc-fake", configuredModel = "free-model")
    private val claude = RecordingProvider("cc-fake")
    private val defaultRunner = InstantRunner("default-runner")
    private val cliRunner = InstantRunner("cli-runner")

    private fun tempStore(): JsonSessionStore {
        val file = File.createTempFile("infantry-providers", ".json")
        file.delete()
        file.deleteOnExit()
        return JsonSessionStore(file)
    }

    private fun manager(
        store: SessionStore = tempStore(),
        additional: List<AgentProvider> = listOf(claude),
        worktrees: WorktreeProvisioner? = null,
    ) = SessionManager(
        opencode,
        defaultRunner,
        store,
        worktrees = worktrees,
        additionalProviders = additional,
        cliRunner = cliRunner,
    )

    // --- choosing --------------------------------------------------------------------------------

    @Test
    fun aSpawnThatNamesNoProviderRunsOnTheDefaultExactlyAsBefore() {
        val session = manager().startNew("hello")

        assertEquals("oc-fake", session.provider)
        assertNull(session.model, "no model chosen: the record says so instead of copying the default")
        assertNull(opencode.requests.single().model, "the run asks for the provider's configured default")
        assertEquals(1, defaultRunner.specs.size, "the default keeps the runner it was given")
        assertTrue(claude.requests.isEmpty() && cliRunner.specs.isEmpty(), "no other provider was touched")
        assertEquals(SessionState.IDLE, session.state)
    }

    @Test
    fun aNamedProviderRunsOnThatProviderAndTheCliRunner() {
        val session = manager().startNew("hello", providerId = "cc-fake", model = "sonnet")

        assertEquals("cc-fake", session.provider)
        assertEquals("sonnet", session.model)
        assertEquals("sonnet", claude.requests.single().model)
        assertEquals(listOf("cc-fake", "--model", "sonnet"), cliRunner.specs.single().command)
        assertEquals("cc-fake-psid", session.providerSessionId, "the session's own provider parses its stream")
        assertTrue(opencode.requests.isEmpty() && defaultRunner.specs.isEmpty(), "the default was not touched")
    }

    @Test
    fun anAdditionalProviderWithItsOwnTransportRunsOnIt() {
        val own = InstantRunner("own")
        val scripted = object : AgentProvider by RecordingProvider("scripted-fake") {
            override fun transport(): ProcessRunner = own
        }
        val manager = manager(additional = listOf(scripted))

        manager.startNew("hello", providerId = "scripted-fake")

        assertEquals(1, own.specs.size)
        assertTrue(cliRunner.specs.isEmpty() && defaultRunner.specs.isEmpty())
    }

    @Test
    fun providerIdsIgnoreCaseAndPaddingAndBlankMeansTheDefault() {
        val manager = manager()
        assertEquals("cc-fake", manager.startNew("a", providerId = "  CC-Fake ").provider)
        assertEquals("oc-fake", manager.startNew("b", providerId = " ").provider)
        assertEquals(listOf("oc-fake", "cc-fake"), manager.providers.map { it.id }, "the default comes first")
    }

    @Test
    fun aBlankModelMeansTheProvidersDefault() {
        val session = manager().startNew("hello", providerId = "cc-fake", model = "   ")
        assertNull(session.model)
        assertNull(claude.requests.single().model)
    }

    // --- refusing --------------------------------------------------------------------------------

    @Test
    fun anUnconfiguredProviderIsRefusedAndNothingIsCreated() {
        val manager = manager()

        val refusal = assertFailsWith<IllegalArgumentException> { manager.startNew("hello", providerId = "gemini") }

        assertTrue(refusal.message!!.contains("gemini"), refusal.message)
        assertTrue(refusal.message!!.contains("oc-fake, cc-fake"), "the refusal lists what IS configured: ${refusal.message}")
        assertTrue(manager.list().isEmpty(), "a refused spawn leaves no session behind")
        assertTrue(opencode.requests.isEmpty(), "and it never falls back to the default provider")
    }

    @Test
    fun aModelThatCouldChangeTheCommandLineIsRefused() {
        val manager = manager()
        val refused = listOf(
            "--dangerously-skip-permissions",
            "-m",
            "two words",
            "quote\"",
            "tab\there",
            "nul\u0000",
            "x".repeat(MAX_MODEL_LENGTH + 1),
        )
        for (bad in refused) {
            assertFailsWith<IllegalArgumentException>("'$bad' must be refused") {
                manager.startNew("hello", providerId = "cc-fake", model = bad)
            }
        }
        assertTrue(manager.list().isEmpty())
        assertTrue(claude.requests.isEmpty(), "no refused model reached a provider")
    }

    @Test
    fun realModelIdsAreAccepted() {
        val real = listOf(
            "opencode/space-bunny-free",
            "sonnet",
            "sonnet[1m]",
            "claude-opus-4-1-20250805",
            "openrouter/meta-llama/llama-3.1-8b:free",
            "ollama/qwen2.5-coder:7b",
        )
        for (model in real) assertNull(modelProblem(model), "'$model' is a real model id")
    }

    @Test
    fun checkSpawnRefusesWhatStartNewWouldRefuseAndCreatesNothing() {
        val manager = manager()
        manager.checkSpawn(null, null)
        manager.checkSpawn("cc-fake", "sonnet")
        assertFailsWith<IllegalArgumentException> { manager.checkSpawn("gemini", null) }
        assertFailsWith<IllegalArgumentException> { manager.checkSpawn(null, "-x") }
        assertTrue(manager.list().isEmpty())
        assertTrue(opencode.requests.isEmpty() && claude.requests.isEmpty())
    }

    @Test
    fun aProviderConfiguredTwiceIsRefusedAtConstruction() {
        assertFailsWith<IllegalArgumentException> {
            manager(additional = listOf(claude, RecordingProvider("cc-fake")))
        }
        assertFailsWith<IllegalArgumentException> {
            manager(additional = listOf(RecordingProvider("oc-fake")))
        }
    }

    @Test
    fun aWorktreeFailureStillRecordsTheChosenProviderAndModel() {
        val broken = object : WorktreeProvisioner {
            override fun create(sessionId: String, repo: File, branchHint: String?, baseRef: String?): WorktreeRecord =
                error("no git here")
        }
        val session = manager(worktrees = broken)
            .startNew("hello", repo = File("nowhere"), providerId = "cc-fake", model = "opus")

        assertEquals(SessionState.FAILED, session.state)
        assertEquals("cc-fake", session.provider)
        assertEquals("opus", session.model)
    }

    // --- resuming --------------------------------------------------------------------------------

    @Test
    fun aResumeGoesBackToTheSessionsOwnProviderAndModel() {
        val store = tempStore()
        val session = manager(store).startNew("turn one", providerId = "cc-fake", model = "opus")

        // A restarted daemon with the same configuration.
        val restarted = manager(store)
        restarted.loadAndReconcile()
        restarted.resume(session.id, "turn two")

        assertEquals(2, claude.requests.size)
        assertEquals("cc-fake-psid", claude.requests.last().resumeSessionId)
        assertEquals("opus", claude.requests.last().model, "the spawn's model is kept on resume")
        assertTrue(opencode.requests.isEmpty(), "never the default provider")
    }

    @Test
    fun aResumeOnAProviderTheDaemonNoLongerHasIsRefused() {
        val store = tempStore()
        val session = manager(store).startNew("turn one", providerId = "cc-fake")

        // Restarted with INFANTRY_PROVIDERS emptied: only the default is configured now.
        val restarted = manager(store, additional = emptyList())
        restarted.loadAndReconcile()
        val refusal = assertFailsWith<IllegalArgumentException> { restarted.resume(session.id, "turn two") }

        assertTrue(refusal.message!!.contains("cc-fake"), refusal.message)
        assertEquals(1, claude.requests.size, "the refused resume reached no provider")
        assertTrue(opencode.requests.isEmpty(), "and the claude session id never reached the default CLI")
        assertEquals(SessionState.IDLE, restarted.get(session.id)!!.state, "refused before anything changed")
    }

    @Test
    fun aRecordWrittenBefore130DecodesWithNoModel() {
        val legacy = """{"id":"s1","provider":"opencode","workingDir":"/tmp","spawnedAt":1,""" +
            """"lastHeartbeat":2,"state":"IDLE","providerSessionId":"p"}"""
        val record = Json { ignoreUnknownKeys = true }.decodeFromString(SessionRecord.serializer(), legacy)
        assertNull(record.model, "no model field = the provider's default, the pre-1.3.0 meaning")
    }

    // --- attributing (contract 1.4.0) ------------------------------------------------------------

    @Test
    fun aSessionRecordsTheProfileAndRoleItWasSpawnedFrom() {
        val manager = manager()
        val session = manager.startNew("hello", providerId = "cc-fake", agentId = "backend", role = "backend")

        assertEquals("backend", session.agentId)
        assertEquals("backend", session.role)
        assertEquals("cc-fake", session.provider, "attribution changes nothing about where the session runs")

        val plain = manager.startNew("no profile")
        assertNull(plain.agentId, "a spawn with no profile is attributed to none")
        assertNull(plain.role)
    }

    @Test
    fun aWorktreeFailureStillRecordsTheProfile() {
        val broken = object : WorktreeProvisioner {
            override fun create(sessionId: String, repo: File, branchHint: String?, baseRef: String?): WorktreeRecord =
                error("no git here")
        }
        val session = manager(worktrees = broken)
            .startNew("hello", repo = File("nowhere"), agentId = "qa", role = "qa")

        assertEquals(SessionState.FAILED, session.state)
        assertEquals("qa", session.agentId, "a UI must still be able to say whose spawn failed")
        assertEquals("qa", session.role)
    }

    @Test
    fun aResumeAndARestartKeepTheAttribution() {
        val store = tempStore()
        val session = manager(store).startNew("turn one", agentId = "qa", role = "qa")

        val restarted = manager(store)
        restarted.loadAndReconcile()
        assertEquals("qa", restarted.get(session.id)!!.agentId, "persisted with the session")

        val resumed = restarted.resume(session.id, "turn two")
        assertEquals("qa", resumed.agentId)
        assertEquals("qa", resumed.role)
        assertEquals("turn two", opencode.requests.last().prompt, "a resume carries only the client's prompt")
    }

    @Test
    fun aRecordWrittenBefore140DecodesWithNoProfile() {
        val legacy = """{"id":"s1","provider":"opencode","workingDir":"/tmp","spawnedAt":1,""" +
            """"lastHeartbeat":2,"state":"IDLE","model":"sonnet"}"""
        val record = Json { ignoreUnknownKeys = true }.decodeFromString(SessionRecord.serializer(), legacy)
        assertNull(record.agentId, "no agentId field = a session spawned without a profile")
        assertNull(record.role)
        assertEquals("sonnet", record.model)
    }
}
