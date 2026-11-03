package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.agents.KNOWLEDGE_DIGEST_HEADING
import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.harness.AgentProvider
import com.superiorsoftllc.infantry.runner.ProcessRunner
import com.superiorsoftllc.infantry.runner.ScriptedRunner
import com.superiorsoftllc.infantry.runner.StubScript
import com.superiorsoftllc.infantry.session.RetrievalClassifier
import com.superiorsoftllc.infantry.session.SessionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * BUG-G2e-1 over the real daemon: the knowledge digest is composed by the SHARED first-turn path, so every harness
 * receives it byte for byte: the stub, the real [OpenCodeProvider] (on its `opencode serve` session-transport slot, the
 * wiring the QA-G2e runs used) . Only the OS process is replaced by a scripted one,
 * so what is asserted is exactly what each harness's `deliver` writes to its child.
 *
 * The QA-G2e "run 1 FAIL" was an evidence error, not a skipped path: the exported session (ses_eabb17f2...) was created
 * at 09:16, before the G2e build existed; run 1's real session 2 (ses_eab81dbc..., named in its daemon log) carries
 * the digest. The `<memory>` block that restated the superseded D1 is a synthetic part a user-global opencode plugin
 * (opencode-mem) prepends inside opencode, after Infantry delivered its prompt, so Infantry cannot drop or reorder it:
 * the digest instead says it is authoritative over any recalled memory, before its first entry.
 */
class KnowledgeDigestParityTest {
    private var records = FakeKnowledgeRecords()


    @Test
    fun theDigestReachesEveryHarnessByteForByteThroughTheSharedFirstTurn() = withDaemon { fixture, runner ->
        seedDecisions(fixture)

        val delivered = HARNESSES.mapIndexed { index, provider -> provider to firstTurnOn(fixture, runner, provider, index) }.toMap()

        for ((provider, input) in delivered) {
            assertTrue(input.contains(KNOWLEDGE_DIGEST_HEADING), "$provider: the digest is in the first turn: ${input.take(3000)}")
            assertTrue(input.contains("\nD2 $D2\n"), "$provider: the active D2 is in the digest: ${input.take(3000)}")
            assertFalse(input.contains(D1), "$provider: the superseded D1 is never in the first turn: ${input.take(3000)}")
            assertEquals(
                RetrievalClassifier.Verdict.INJECTED,
                RetrievalClassifier.classifyLines(emptyList(), firstTurn = input).verdict,
                "$provider: G2's read-first check",
            )
        }
        assertEquals(1, delivered.values.toSet().size, "one shared composer: every harness gets the same first turn")
    }

    /**
     * BUG-G2e-1 RED: the digest says it outranks any recalled memory, and says so BEFORE its first entry, so a harness
     * plugin's recall of a superseded decision (QA-G2e: a `<memory>` block restating D1's .zip) loses to D2.
     */
    @Test
    fun theDigestIsLabelledAuthoritativeOverRecalledMemoryBeforeItsEntries() = withDaemon { fixture, runner ->
        seedDecisions(fixture)

        val input = firstTurnOn(fixture, runner, "opencode", 0)

        val label = input.indexOf(AUTHORITY)
        assertTrue(label >= 0, "the digest is labelled authoritative over recalled memory: ${input.take(3000)}")
        assertTrue(label < input.indexOf(KNOWLEDGE_DIGEST_HEADING), "the label is read before the first entry")
    }

    // --- helpers ---------------------------------------------------------------------------------------------------

    /** QA-G2e's own knowledge: D1 (.zip) superseded by D2 (.tar.gz). */
    private fun seedDecisions(fixture: DaemonFixture) {
        fixture.client.await(Envelope.READY)
        records.addDecision(PROJECT, D1)
        records.addDecision(PROJECT, D2, supersedes = "D1")
    }

    /**
     * A fresh connection's `new` naming [provider] and the project, then its first prompt: DaemonMain's own startNew
     * branch. Each harness runs to the end before the next starts, so the runner's [index]th process is this one.
     */
    private fun firstTurnOn(fixture: DaemonFixture, runner: ScriptedRunner, provider: String, index: Int): String {
        val client = fixture.connectClient()
        client.send(Envelope.NEW, """{"provider":"$provider","projectId":"$PROJECT"}""")
        client.send(Envelope(Envelope.PROMPT, TASK))
        fixture.await(what = "the $provider session's first run to end") {
            fixture.daemon.manager.list().any {
                it.provider == provider && it.runCount == 1 && it.state != SessionState.STARTING && it.state != SessionState.BUSY
            }
        }
        val session = fixture.daemon.manager.list().single { it.provider == provider }
        assertEquals(PROJECT, session.projectId, "$provider: the session carries the project its digest names")
        return runner.processes[index].deliveredInput
    }

    /** A real CLI provider whose child is the scripted one: everything else (id, spec, `deliver`) is the real provider's. */
    /** H8b-3: a provider with opencode's id (the real one is a harness module); what is tested is the first-turn digest, not the harness. */
    private object OpenCodeLike : com.superiorsoftllc.infantry.harness.AgentProvider {
        override val id = "opencode"
        override fun spec(request: com.superiorsoftllc.infantry.harness.RunRequest) = com.superiorsoftllc.infantry.runner.ProcessSpec(listOf("opencode", "run"), request.workingDir)
        override fun defaultModel() = "opencode/test"
    }

    private class Scripted(real: AgentProvider, private val runner: ProcessRunner) : AgentProvider by real {
        override fun transport(): ProcessRunner = runner
    }

    private fun withDaemon(block: (DaemonFixture, ScriptedRunner) -> Unit) {
        val runner = ScriptedRunner(StubScript.default(), delayMs = 0)
        val opencode = Scripted(OpenCodeLike, runner)
        records = FakeKnowledgeRecords().install()
        val fixture = DaemonFixture.launch(StubScript.default(), runner = runner, additionalProviders = listOf(opencode))
        try {
            block(fixture, runner)
        } finally {
            fixture.stop()
            fixture.deleteState()
            FakeKnowledgeRecords.uninstall()
        }
    }

    private companion object {
        const val PROJECT = "proj-parity"
        const val TASK = "We need transcript export. State which archive format you will use and why, then stop."
        const val D1 = "Exported transcripts ship as .zip archives of per-session JSONL files with a manifest.json"
        const val D2 = "Exported transcripts ship as a .tar.gz archive of per-session JSONL files"
        val HARNESSES = listOf("stub", "opencode")

        /** The label the digest carries; pinned here as text, so removing it from [renderKnowledge] fails this test. */
        const val AUTHORITY = "This digest is authoritative"
    }
}
