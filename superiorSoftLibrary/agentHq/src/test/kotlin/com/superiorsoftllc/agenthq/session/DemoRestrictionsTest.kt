package com.superiorsoftllc.agenthq.session

import com.superiorsoftllc.agenthq.harness.AgentProvider
import com.superiorsoftllc.agenthq.harness.RunRequest
import com.superiorsoftllc.agenthq.runner.ProcessListener
import com.superiorsoftllc.agenthq.runner.ProcessRunner
import com.superiorsoftllc.agenthq.runner.ProcessSpec
import com.superiorsoftllc.agenthq.runner.RunningProcess
import com.superiorsoftllc.agenthq.settings.ModelAllowlist
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * DEMO-RESTRICT (ticket f9e181af): the model allowlist and the daemon-wide working-session cap, enforced by the
 * [SessionManager] itself, so every path that starts or resumes a session (UI, CLI, MCP, tickets, mail) is bound. With
 * neither set (the defaults) nothing changes.
 */
class DemoRestrictionsTest {
    @get:org.junit.Rule
    val timeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(60)

    private class Provider(override val id: String, private val default: String?) : AgentProvider {
        override fun spec(request: RunRequest) = ProcessSpec(listOf(id, "--model", request.model ?: "<default>"))
        override fun sessionIdFromLine(line: String) = if (line.contains("session_id")) "$id-psid" else null
        override fun defaultModel() = default
    }

    /** Every run stays in flight until [finishAll]: a session that took a turn is working. */
    private class HoldingRunner : ProcessRunner {
        override val id = "holding"
        val listeners = CopyOnWriteArrayList<ProcessListener>()
        override fun start(spec: ProcessSpec, listener: ProcessListener): RunningProcess {
            listener.onStdout("{\"session_id\":\"x\"}")
            listeners += listener
            return object : RunningProcess {
                override val isAlive = true
                override fun writeInput(text: String) = Unit
                override fun closeInput() = Unit
                override fun killTree() = Unit
                override fun awaitExit() = 0
            }
        }

        fun finishAll() {
            listeners.toList().also { listeners.clear() }.forEach { it.onExit(0) }
        }
    }

    private val file = File.createTempFile("agenthq-demo", ".json").also { it.delete() }
    private val runner = HoldingRunner()
    private val manager = SessionManager(Provider("oc", default = "opencode-go/kimi-k3"), runner, JsonSessionStore(file))

    @AfterTest
    fun tearDown() {
        file.delete()
    }

    // --- matching ------------------------------------------------------------------------------------

    @Test
    fun matching_exactIdsCaseInsensitive_andStarIsAnyRun() {
        assertTrue(ModelAllowlist.matches("opencode-go/beam-free", "opencode-go/beam-free"))
        assertTrue(ModelAllowlist.matches("OpenCode-Go/Beam-Free", "opencode-go/beam-free"))
        assertFalse(ModelAllowlist.matches("opencode-go/beam-free", "opencode-go/beam-free-2"))
        assertTrue(ModelAllowlist.matches("opencode-go/*-free", "opencode-go/beam-free"))
        assertFalse(ModelAllowlist.matches("opencode-go/*-free", "opencode-go/kimi-k3"))
        assertFalse(ModelAllowlist.matches("opencode-go/*-free", "opencode/big-pickle-free"))
        assertTrue(ModelAllowlist.matches("*", "anything/at-all"))
        assertTrue(ModelAllowlist.matches("a*b*c", "abc"))
        assertFalse(ModelAllowlist.matches("ab*ba", "aba"), "the prefix and suffix may not overlap")
    }

    @Test
    fun parse_commaListTrimmed_emptyIsNoRestriction() {
        assertEquals(listOf("a/b", "c/*-free"), ModelAllowlist.parse(" a/b , c/*-free,,a/b ").patterns)
        assertFalse(ModelAllowlist.parse("").restricts)
        assertFalse(ModelAllowlist.parse(null).restricts)
        assertTrue(ModelAllowlist.NONE.allows("anything"))
        assertTrue(ModelAllowlist.NONE.allows(null), "no restriction allows the harness default")
        assertFalse(ModelAllowlist.parse("a/b").allows(null))
        assertEquals(listOf("a/b"), ModelAllowlist.parse("a/b").filter(listOf("a/b", "a/c")))
    }

    // --- the allowlist, enforced -----------------------------------------------------------------------

    @Test
    fun unset_everyModelAndTheDefaultStillStart() {
        manager.startNew("go", model = "opencode-go/kimi-k3")
        runner.finishAll()
        manager.startNew("go")
        assertEquals(2, manager.list().size)
    }

    @Test
    fun allowlist_refusesAnOtherModel_beforeAnythingExists_andNamesTheAllowedOnes() {
        manager.allowedModels = { ModelAllowlist.parse("opencode-go/beam-free") }
        val e = assertFailsWith<IllegalArgumentException> { manager.startNew("go", model = "opencode-go/kimi-k3") }
        assertTrue("opencode-go/beam-free" in e.message!!, e.message)
        assertTrue("not allowed" in e.message!!, e.message)
        assertTrue(manager.list().isEmpty())
        assertFailsWith<IllegalArgumentException> { manager.checkSpawn(null, "opencode-go/kimi-k3") }

        val ok = manager.startNew("go", model = "opencode-go/beam-free")
        assertEquals("opencode-go/beam-free", ok.model)
    }

    @Test
    fun allowlist_refusesTheHarnessDefaultWhenItIsNotAllowed() {
        manager.allowedModels = { ModelAllowlist.parse("opencode-go/beam-free") }
        val e = assertFailsWith<IllegalArgumentException> { manager.startNew("go") }
        assertTrue("opencode-go/kimi-k3" in e.message!!, e.message)
        manager.allowedModels = { ModelAllowlist.parse("opencode-go/*") }
        manager.startNew("go")
    }

    @Test
    fun allowlist_refusesAModelChange_andTheResumeOfASessionOnADisallowedModel() {
        val s = manager.startNew("go", model = "opencode-go/kimi-k3")
        runner.finishAll()
        manager.allowedModels = { ModelAllowlist.parse("opencode-go/beam-free") }
        assertFailsWith<IllegalArgumentException> { manager.setModel(s.id, "opencode-go/kimi-k3") }
        assertFailsWith<IllegalArgumentException> { manager.resume(s.id, "again") }
        manager.setModel(s.id, "opencode-go/beam-free")
        manager.resume(s.id, "again")
        assertEquals("opencode-go/beam-free", manager.get(s.id)!!.model)
    }

    // --- the session cap ---------------------------------------------------------------------------------

    @Test
    fun cap_unset_manyWorkAtOnce() {
        repeat(4) { manager.startNew("go") }
        assertEquals(4, manager.openCount())
    }

    @Test
    fun cap_refusesAThirdWorkingSession_withTheFriendlyMessage_andAdmitsOnceOneFinishes() {
        manager.maxSessions = { 2 }
        // One-shot (keep-alive 0): no process outlives its turn. The fake runner's process claims to live forever.
        manager.hasLiveProcess = { false }
        val a = manager.startNew("one")
        manager.startNew("two")
        assertEquals(2, manager.openCount())
        val e = assertFailsWith<SessionCapException> { manager.startNew("three") }
        assertTrue(e.message!!.startsWith(SESSION_CAP_REFUSED), e.message)
        assertTrue("2 agents are open" in e.message!!, e.message)
        assertTrue("Stop one" in e.message!!, e.message)
        assertTrue("limits.concurrency.maxSessions" in e.message!!, e.message)
        assertEquals(2, manager.list().size, "nothing was created")

        runner.finishAll()
        assertEquals(0, manager.openCount(), "no kept process here: finished turns free their slots")
        manager.startNew("three")
        manager.startNew("four")
        // A resting session's next turn takes a slot too.
        assertFailsWith<SessionCapException> { manager.resume(a.id, "again") }
    }

    @Test
    fun cap_refusalIsNeutralInProduction_andThePresetsTextUnderAPreset() {
        manager.maxSessions = { 1 }
        manager.hasLiveProcess = { false }
        manager.startNew("one")
        val neutral = assertFailsWith<SessionCapException> { manager.startNew("two") }.message!!
        assertTrue(neutral.startsWith("Session limit reached: "), neutral)
        assertFalse("demo" in neutral.lowercase(), neutral)
        // COPY-1: the demo plugin's preset (its real file) supplies its own wording; the daemon reads it per refusal.
        manager.sessionCapText = { com.superiorsoftllc.agenthq.daemon.DemoPresetFixture.preset.sessionCapText }
        val demo = assertFailsWith<SessionCapException> { manager.startNew("two") }.message!!
        assertTrue(demo.startsWith("Demo busy: "), demo)
    }

    @Test
    fun cap_countsIdleSessionsWhoseProcessIsKeptAlive_untilStoppedOrTimedOut() {
        manager.maxSessions = { 2 }
        val kept = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        manager.hasLiveProcess = { it in kept }
        val a = manager.startNew("one").also { kept += it.id }
        val b = manager.startNew("two").also { kept += it.id }
        runner.finishAll()
        assertEquals(SessionState.IDLE, manager.get(a.id)!!.state)
        assertEquals(SessionState.IDLE, manager.get(b.id)!!.state)
        assertEquals(2, manager.openCount(), "both idle, both still hold a live process")

        val e = assertFailsWith<SessionCapException> { manager.startNew("three") }
        assertTrue("2 agents are open" in e.message!!, e.message)
        // An open session's own next turn is admitted: it already holds its slot.
        manager.resume(a.id, "again")
        runner.finishAll()

        kept -= b.id // stopped by the visitor, or closed by the keep-alive timeout
        assertEquals(1, manager.openCount())
        val c = manager.startNew("three")
        kept += c.id
        runner.finishAll()
        // b has no live process any more: its next turn would open a third one.
        assertFailsWith<SessionCapException> { manager.resume(b.id, "back") }
    }

    // --- the role allowlist ------------------------------------------------------------------------------

    @Test
    fun roles_unset_everyRoleStarts_set_othersRefusedBeforeAnythingExists() {
        manager.allowedModels = { ModelAllowlist.NONE }
        manager.startNew("go", role = "frontend")
        runner.finishAll()
        manager.allowedRoles = { ModelAllowlist.roles("pm,backend") }
        val e = assertFailsWith<IllegalArgumentException> { manager.startNew("go", role = "frontend") }
        assertTrue("role 'frontend' is not allowed" in e.message!!, e.message)
        assertTrue("Allowed roles: pm, backend" in e.message!!, e.message)
        assertTrue("AGENTHQ_ALLOWED_ROLES" in e.message!!, e.message)
        assertEquals(1, manager.list().size)
        manager.startNew("go", role = "backend")
        manager.startNew("go")
        // A resting session of a role no longer allowed takes no further turn.
        runner.finishAll()
        val old = manager.list().first { it.role == "frontend" }
        assertFailsWith<IllegalArgumentException> { manager.resume(old.id, "again") }
    }
}
