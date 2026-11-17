// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.triggers

import com.superiorsoftllc.infantry.ui.*

import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.protocol.Role
import kotlin.test.Test
import com.superiorsoftllc.infantry.ui.model.AgentView
import com.superiorsoftllc.infantry.ui.protocol.SessionRecord
import com.superiorsoftllc.infantry.ui.protocol.SessionState
import kotlin.test.assertEquals

/** S4: moved from :infantryUiCore's BoardTriggersModelTest with [triggerLanes] (Infantry_Triggers). */
class TriggerLanesTest {
    @Test fun `the trigger target lanes include the built-in roles even with no profiles, without duplicates`() {
        assertEquals(listOf("role:pm", "role:qa", "role:version-control", "role:assistant"), triggerLanes(AppState()))
        val withCatalog = AppState(roles = listOf(Role("pm", "PM", "#000"), Role("backend", "Backend", "#111")))
        assertEquals(listOf("role:pm", "role:qa", "role:version-control", "role:assistant", "role:backend"), triggerLanes(withCatalog))
    }

    private fun agent(id: String, role: String?, state: SessionState, at: Long) =
        id to AgentView(SessionRecord(id = id, provider = "p", workingDir = "w", spawnedAt = at, lastHeartbeat = at, state = state), role = role)

    @Test fun `live sessions are offered as session targets, newest first, and ended ones are not`() {
        val state = AppState(agents = mapOf(
            agent("aaaaaaaa-1", "fullstack", SessionState.IDLE, 1),
            agent("bbbbbbbb-2", "fullstack", SessionState.BUSY, 2),
            agent("cccccccc-3", "qa", SessionState.EXITED, 3),
        ))
        assertEquals(listOf("session:bbbbbbbb-2" to "fullstack bbbbbbbb", "session:aaaaaaaa-1" to "fullstack aaaaaaaa"), triggerSessionTargets(state))
        val draft = TriggerDraft(name = "n", action = "prompt", agentId = "session:bbbbbbbb-2", prompt = "go")
        assertEquals(null, draft.problem())
        assertEquals("session:bbbbbbbb-2", draft.toSave(null)["action"]!!.let { (it as kotlinx.serialization.json.JsonObject)["agentId"]!!.let { v -> (v as kotlinx.serialization.json.JsonPrimitive).content } })
    }
}
