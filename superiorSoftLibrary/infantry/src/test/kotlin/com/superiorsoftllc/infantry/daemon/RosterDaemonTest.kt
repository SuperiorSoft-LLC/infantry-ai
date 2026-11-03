// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.protocol.Envelope
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AGENT-ROSTER over the REAL daemon and a real WebSocket: `agent_roster` names a project and is
 * answered by `roster`, the JSON list of exactly that project's roster rows -- project A's agents,
 * never project B's -- and a blank projectId is refused as an `error` frame naming the problem rather
 * than crashing the daemon or answering rows.
 *
 * Project membership is the project's in-session agents (BUG-ROLE-1, owner's rule): the fixture opens New Agent
 * sessions the way the modal does -- two agents in project A (one of them opened twice, so an agent with two
 * sessions still gets ONE row) and one in project B. A profile with no session in A is never listed, even when a
 * ticket in A names it.
 */
class RosterDaemonTest {

    private fun decodeRows(answer: Envelope): List<RosterRow> =
        JSON.decodeFromString(ListSerializer(RosterRow.serializer()), answer.data)

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

    @Test
    fun agentRosterListsOnlyTheGivenProjectsAgents() = withDaemon { fixture ->
        fixture.client.saveProfile("""{"id":"a-one","name":"A One","role":"backend"}""")
        fixture.client.saveProfile("""{"id":"a-two","name":"A Two","role":"qa"}""")
        fixture.client.saveProfile("""{"id":"b-one","name":"B One","role":"backend"}""")
        fixture.client.saveProfile("""{"id":"a-idle","name":"A Idle","role":"backend"}""")
        // a-idle is named by a ticket in A but has no session: the profile store is never a roster source.
        fixture.client.saveTicket("""{"title":"A idle work","projectId":"A","agentId":"a-idle"}""")
        for ((agent, project) in listOf("a-one" to "A", "a-one" to "A", "a-two" to "A", "b-one" to "B")) {
            val before = fixture.daemon.manager.list().count { it.agentId == agent && it.projectId == project }
            fixture.send(Envelope(Envelope.NEW, """{"agentId":"$agent","projectId":"$project"}"""))
            fixture.await(what = "a new $agent session in $project") {
                fixture.daemon.manager.list().count { it.agentId == agent && it.projectId == project } > before
            }
        }

        fixture.send(Envelope(Envelope.AGENT_ROSTER, """{"projectId":"A"}"""))
        val answer = fixture.client.await(Envelope.ROSTER)
        val rows = decodeRows(answer)
        assertEquals(2, rows.size, "exactly the two agents of project A: ${answer.data}")
        assertEquals(setOf("a-one", "a-two"), rows.map { it.agentId }.toSet(), "A's roster names A's own agents: ${answer.data}")
        assertTrue(rows.none { it.agentId == "b-one" }, "project B's agent is never in A's roster: ${answer.data}")
        assertTrue(rows.none { it.agentId == "a-idle" }, "a profile with no session in A is not listed: ${answer.data}")
        assertTrue(rows.all { it.sessionId != null }, "every listed agent is in session: ${answer.data}")
    }

    @Test
    fun aBlankProjectIdIsRefusedWithAnErrorNotACrash() = withDaemon { fixture ->
        val empty = fixture.client.answerTo(Envelope.AGENT_ROSTER, """{"projectId":""}""")
        assertTrue(empty.data.startsWith("agent_roster refused:"), empty.data)
        assertTrue(empty.data.contains("project"), empty.data)

        val spaces = fixture.client.answerTo(Envelope.AGENT_ROSTER, """{"projectId":"   "}""")
        assertTrue(spaces.data.startsWith("agent_roster refused:"), spaces.data)
    }
}
