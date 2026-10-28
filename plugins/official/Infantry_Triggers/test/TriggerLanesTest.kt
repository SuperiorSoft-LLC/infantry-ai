package com.superiorsoftllc.agenthq.plugins.official.triggers

import com.superiorsoftllc.agenthq.ui.*

import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.protocol.Role
import kotlin.test.Test
import kotlin.test.assertEquals

/** S4: moved from :agenthqUiCore's BoardTriggersModelTest with [triggerLanes] (Infantry_Triggers). */
class TriggerLanesTest {
    @Test fun `the trigger target lanes include the built-in roles even with no profiles, without duplicates`() {
        assertEquals(listOf("role:pm", "role:qa", "role:version-control", "role:assistant"), triggerLanes(AppState()))
        val withCatalog = AppState(roles = listOf(Role("pm", "PM", "#000"), Role("backend", "Backend", "#111")))
        assertEquals(listOf("role:pm", "role:qa", "role:version-control", "role:assistant", "role:backend"), triggerLanes(withCatalog))
    }
}
