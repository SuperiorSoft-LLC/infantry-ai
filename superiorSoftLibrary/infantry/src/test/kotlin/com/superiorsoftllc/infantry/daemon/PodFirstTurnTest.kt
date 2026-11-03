package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.agents.KnowledgeCounts
import com.superiorsoftllc.infantry.agents.KnowledgeDigest
import com.superiorsoftllc.infantry.agents.renderKnowledge
import com.superiorsoftllc.infantry.agents.renderSkills
import com.superiorsoftllc.infantry.orchestration.NodeSpec
import com.superiorsoftllc.infantry.orchestration.PodStatus
import com.superiorsoftllc.infantry.orchestration.PodSubmitRequest
import com.superiorsoftllc.infantry.orchestration.RetryFeedback
import com.superiorsoftllc.infantry.persistence.SkillStore
import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.protocol.SkillSave
import com.superiorsoftllc.infantry.runner.ScriptedRunner
import com.superiorsoftllc.infantry.runner.StubScript
import com.superiorsoftllc.infantry.settings.SettingsService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * BUG-G2b over the real daemon: a pod node's FIRST attempt composes its first turn the way `prompt new` does
 * (knowledge, then skills, then the node's prompt) for the pod's OWN project, and the spawned session carries that
 * project. Before the fix, `Orchestrator.startAttempt` passed the raw node prompt and no project, so a pod session
 * never read knowledge (QA G2 run7: EXPLORED_FIRST) and `--project` was dropped (run6: landed in the repo's project).
 * Retries keep [RetryFeedback] over the ORIGINAL node prompt.
 */
class PodFirstTurnTest {
    private var records = FakeKnowledgeRecords()


    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun aPodSubmittedForAProjectGetsThatProjectsKnowledgeFirstAndCarriesTheProject() = withDaemon(StubScript.default()) { fixture, runner ->
        fixture.client.await(Envelope.READY)
        val repoProject = fixture.createProject("the repo's project") // the daemon's default project: must NOT be used
        records.addDecision(PROJECT, "use sqlite")

        submit(fixture, PodSubmitRequest(goal = NODE_PROMPT, nodes = listOf(NodeSpec("main", NODE_PROMPT)), projectId = PROJECT))
        val session = awaitRuns(fixture, runner, 1).let { fixture.daemon.manager.list().single() }

        val knowledge = renderKnowledge(PROJECT, KnowledgeCounts(notes = 0, activeDecisions = 1, openIssues = 0, digest = KnowledgeDigest(listOf("D1 use sqlite"), emptyList(), emptyList())))!!
        assertEquals("$knowledge\n\n$NODE_PROMPT", runner.processes.single().deliveredInput, "knowledge, then the node's prompt")
        assertEquals(PROJECT, session.projectId, "the pod's project, not the repo's ($repoProject)")
    }

    @Test
    fun aPodWithProjectSkillsAttachedRendersTheSkillsSectionAfterKnowledge() = withDaemon(StubScript.default()) { fixture, runner ->
        fixture.client.await(Envelope.READY)
        val skills = SkillStore(fixture.daemon.store.skills)
        val tdd = skills.save(SkillSave(name = "tdd", description = "Use when writing code.", body = "RED first, then GREEN."))
        skills.save(SkillSave(name = "review", description = "Use when reviewing a diff.", body = "Read it all."))
        SettingsService(fixture.daemon.store).set(SettingsService.SKILL_IDS, JsonArray(listOf(JsonPrimitive(tdd.id))))

        submit(fixture, PodSubmitRequest(goal = NODE_PROMPT, nodes = listOf(NodeSpec("main", NODE_PROMPT)), projectId = PROJECT))
        awaitRuns(fixture, runner, 1)

        val knowledge = renderKnowledge(PROJECT, KnowledgeCounts(0, 0, 0))!!
        val section = renderSkills(listOf(tdd.id), skills.list(null))!!
        assertEquals("$knowledge\n\n$section\n\n$NODE_PROMPT", runner.processes.single().deliveredInput)
    }

    @Test
    fun aPodNamingNoProjectFallsBackToTheDefaultProject() = withDaemon(StubScript.default()) { fixture, runner ->
        fixture.client.await(Envelope.READY)
        val repoProject = fixture.createProject("the repo's project")

        submit(fixture, PodSubmitRequest(goal = NODE_PROMPT, nodes = listOf(NodeSpec("main", NODE_PROMPT))))
        awaitRuns(fixture, runner, 1)

        assertEquals(repoProject, fixture.daemon.manager.list().single().projectId)
        val knowledge = renderKnowledge(repoProject, KnowledgeCounts(0, 0, 0))!!
        assertEquals("$knowledge\n\n$NODE_PROMPT", runner.processes.single().deliveredInput)
    }

    @Test
    fun aRetryKeepsRetryFeedbackOverTheOriginalPrompt() = withDaemon(StubScript.failing()) { fixture, runner ->
        fixture.client.await(Envelope.READY)
        records.addDecision(PROJECT, "use sqlite")

        val pod = submit(
            fixture,
            PodSubmitRequest(goal = NODE_PROMPT, nodes = listOf(NodeSpec("main", NODE_PROMPT, maxAttempts = 2)), projectId = PROJECT),
        )
        awaitRuns(fixture, runner, 2)
        // The retry is composed inside the first attempt's end, after its run row and Finished event are written.
        fixture.await(what = "both attempts recorded") { fixture.daemon.store.runs.byPod(pod.pod.id).size == 2 }
        val runs = fixture.daemon.store.runs.byPod(pod.pod.id)
        val prev = runs.single { it.endedAt != null && it.prompt == runner.processes[0].deliveredInput }
        val expected = RetryFeedback.compose(NODE_PROMPT, 2, 2, prev, fixture.daemon.store.events.forRun(prev.id))
        assertEquals(expected, runner.processes[1].deliveredInput, "a retry is RetryFeedback over the original prompt")
        assertFalse("PROJECT KNOWLEDGE" in runner.processes[1].deliveredInput)
    }

    // --- helpers ---------------------------------------------------------------------------------------------------

    private fun submit(fixture: DaemonFixture, request: PodSubmitRequest): PodStatus {
        fixture.send(Envelope.POD_SUBMIT, json.encodeToString(PodSubmitRequest.serializer(), request))
        return json.decodeFromString(PodStatus.serializer(), fixture.client.await(Envelope.POD).data)
    }

    private fun awaitRuns(fixture: DaemonFixture, runner: ScriptedRunner, count: Int) {
        fixture.await(what = "$count pod run(s) to be delivered") { runner.processes.size >= count }
    }

    private fun DaemonFixture.createProject(name: String): String {
        send(Envelope.PROJECT_SAVE, """{"name":"$name"}""")
        val frame = client.awaitWhere("project $name") { env ->
            env.type == Envelope.PROJECT && json.parseToJsonElement(env.data).jsonObject["name"]?.jsonPrimitive?.content == name
        }
        return json.parseToJsonElement(frame.data).jsonObject["id"]!!.jsonPrimitive.content
    }

    private fun withDaemon(script: StubScript, block: (DaemonFixture, ScriptedRunner) -> Unit) {
        val runner = ScriptedRunner(script, delayMs = 0)
        records = FakeKnowledgeRecords().install()
        val fixture = DaemonFixture.launch(script, runner = runner)
        try {
            block(fixture, runner)
        } finally {
            fixture.stop()
            fixture.deleteState()
            FakeKnowledgeRecords.uninstall()
        }
    }

    private companion object {
        const val PROJECT = "proj-pod"
        const val NODE_PROMPT = "add an endpoint"
    }
}
