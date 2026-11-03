// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.protocol.AgentProfile
import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.protocol.SkillView
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P10-K12 (contract 1.56.0, docs/INFANTRY-PHASE10-KNOWLEDGE.md K-D9) over the real daemon and a real WebSocket:
 * `AgentProfile.skillIds` (absent keeps, `[]` clears, unknown or repeated refused, `agent_swap` keeps), `skill_get` by
 * name and by id, and the `attachedOnly` round trip (absent keeps on an update).
 */
class AgentSkillsWireTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun withDaemon(block: (DaemonFixture) -> Unit) {
        val fixture = DaemonFixture.launch()
        try {
            fixture.client.await(Envelope.READY)
            block(fixture)
        } finally {
            fixture.stop()
            fixture.deleteState()
        }
    }

    private fun DaemonFixture.saveSkill(data: String): SkillView {
        val before = client.of(Envelope.SKILL_ITEM).size
        send(Envelope(Envelope.SKILL_SAVE, data))
        return json.decodeFromString(SkillView.serializer(), client.await(Envelope.SKILL_ITEM, before).data)
    }

    private fun DaemonFixture.saveAgent(data: String): AgentProfile {
        val before = client.of(Envelope.AGENT).size
        send(Envelope(Envelope.AGENT_SAVE, data))
        return json.decodeFromString(AgentProfile.serializer(), client.await(Envelope.AGENT, before).data)
    }

    private fun DaemonFixture.listed(id: String): AgentProfile {
        val before = client.of(Envelope.AGENTS).size
        send(Envelope(Envelope.AGENT_LIST))
        return json.decodeFromString(ListSerializer(AgentProfile.serializer()), client.await(Envelope.AGENTS, before).data).single { it.id == id }
    }

    private fun DaemonFixture.error(data: Envelope): String {
        val before = client.of(Envelope.ERROR).size
        send(data)
        return client.await(Envelope.ERROR, before).data
    }

    private fun DaemonFixture.get(key: String): SkillView {
        val before = client.of(Envelope.SKILL_ITEM).size
        send(Envelope(Envelope.SKILL_GET, key))
        return json.decodeFromString(SkillView.serializer(), client.await(Envelope.SKILL_ITEM, before).data)
    }

    @Test
    fun anAgentSaveWithoutTheFieldKeepsTheListAndEmptyClearsIt() = withDaemon { f ->
        val a = f.saveSkill("""{"name":"Alpha","body":"a"}""")
        val b = f.saveSkill("""{"name":"Beta","body":"b"}""")

        val created = f.saveAgent("""{"id":"a-skills","name":"B","role":"backend","projectId":"p1"}""")
        assertEquals(emptyList(), created.skillIds, "a new agent attaches nothing")
        assertEquals("[]", Json.parseToJsonElement(f.client.of(Envelope.AGENT).last().data).jsonObject["skillIds"].toString())

        assertEquals(listOf(b.id, a.id), f.saveAgent("""{"id":"a-skills","name":"B","role":"backend","skillIds":["${b.id}","${a.id}"]}""").skillIds, "attach order kept")

        // A pre-1.56.0 client re-saves the whole profile without the field: the list survives.
        val kept = f.saveAgent("""{"id":"a-skills","name":"Renamed","role":"backend"}""")
        assertEquals("Renamed", kept.name)
        assertEquals(listOf(b.id, a.id), kept.skillIds)
        assertEquals(listOf(b.id, a.id), f.saveAgent("""{"id":"a-skills","name":"Renamed","role":"backend","skillIds":null}""").skillIds, "null keeps too")
        assertEquals(listOf(b.id, a.id), f.daemon.store.agents.get("a-skills")!!.skillIds, "stored, not just echoed")

        assertEquals(emptyList(), f.saveAgent("""{"id":"a-skills","name":"Renamed","role":"backend","skillIds":[]}""").skillIds)
        assertEquals(emptyList(), f.listed("a-skills").skillIds)
    }

    @Test
    fun agentSwapKeepsTheSkillList() = withDaemon { f ->
        val a = f.saveSkill("""{"name":"Alpha","body":"a"}""")
        f.saveAgent("""{"id":"a-swap","name":"B","role":"backend","projectId":"p1","skillIds":["${a.id}"]}""")

        val before = f.client.of(Envelope.AGENT_SWAPPED).size
        f.send(Envelope(Envelope.AGENT_SWAP, """{"agentId":"a-swap","provider":"stub","model":null}"""))
        f.client.await(Envelope.AGENT_SWAPPED, before)

        val after = f.listed("a-swap")
        assertEquals("stub", after.provider)
        assertEquals(listOf(a.id), after.skillIds, "a swap changes the executor only")
    }

    @Test
    fun unknownOrRepeatedSkillIdsAreRefusedAndNothingIsWritten() = withDaemon { f ->
        val a = f.saveSkill("""{"name":"Alpha","body":"a"}""")
        f.saveAgent("""{"id":"a-bad","name":"B","role":"backend","projectId":"p1","skillIds":["${a.id}"]}""")

        val unknown = f.error(Envelope(Envelope.AGENT_SAVE, """{"id":"a-bad","name":"B2","role":"backend","skillIds":["nope"]}"""))
        assertTrue(unknown.startsWith("agent_save refused") && "unknown skill 'nope'" in unknown, unknown)
        val twice = f.error(Envelope(Envelope.AGENT_SAVE, """{"id":"a-bad","name":"B3","role":"backend","skillIds":["${a.id}","${a.id}"]}"""))
        assertTrue(twice.startsWith("agent_save refused") && "listed twice" in twice, twice)

        val stored = f.daemon.store.agents.get("a-bad")!!
        assertEquals("B", stored.name, "a refused save writes nothing")
        assertEquals(listOf(a.id), stored.skillIds)
    }

    @Test
    fun skillGetTakesAnIdOrAnExactName() = withDaemon { f ->
        val review = f.saveSkill("""{"name":"Code Review","description":"Use when reviewing","body":"look hard"}""")

        assertEquals(review, f.get(review.id), "by id")
        assertEquals(review, f.get("Code Review"), "by name")
        assertEquals(review, f.get("  Code Review "), "trimmed")

        val refused = f.error(Envelope(Envelope.SKILL_GET, "code review"))
        assertTrue(refused.startsWith("skill_get refused: unknown skill"), "names match exactly: $refused")
    }

    @Test
    fun attachedOnlyRoundTripsAndAnUpdateWithoutItKeepsIt() = withDaemon { f ->
        val plain = f.saveSkill("""{"name":"Plain","body":"p"}""")
        assertEquals(false, plain.attachedOnly, "a new skill is visible by default")
        assertEquals("false", Json.parseToJsonElement(f.client.of(Envelope.SKILL_ITEM).last().data).jsonObject["attachedOnly"].toString())

        val hidden = f.saveSkill("""{"name":"Hidden","body":"h","attachedOnly":true}""")
        assertEquals(true, hidden.attachedOnly)
        assertEquals(true, f.get(hidden.id).attachedOnly, "stored")
        assertEquals(true, f.daemon.store.skills.get(hidden.id)!!.attachedOnly)

        // A pre-1.56.0 client re-saves the skill without the field: it stays hidden.
        val resaved = f.saveSkill("""{"name":"Hidden","body":"h2"}""")
        assertEquals(hidden.id, resaved.id)
        assertEquals("h2", resaved.body)
        assertEquals(true, resaved.attachedOnly)

        assertEquals(false, f.saveSkill("""{"name":"Hidden","body":"h3","attachedOnly":false}""").attachedOnly)

        // The wire list still carries every skill, flag included (hiding is the reader's job).
        f.saveSkill("""{"name":"Hidden","body":"h4","attachedOnly":true}""")
        val before = f.client.of(Envelope.SKILLS).size
        f.send(Envelope(Envelope.SKILL_LIST, ""))
        val listed = Json.parseToJsonElement(f.client.await(Envelope.SKILLS, before).data)
        val flags = (listed as kotlinx.serialization.json.JsonArray).associate {
            it.jsonObject["name"]!!.jsonPrimitive.content to it.jsonObject["attachedOnly"]!!.jsonPrimitive.boolean
        }
        assertEquals(mapOf("Hidden" to true, "Plain" to false), flags)
    }
}
