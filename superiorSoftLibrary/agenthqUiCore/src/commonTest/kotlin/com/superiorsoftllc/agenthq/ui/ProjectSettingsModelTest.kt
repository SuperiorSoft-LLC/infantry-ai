package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.ui.model.ConnectionInfo
import com.superiorsoftllc.agenthq.ui.model.ProjectSettingsRegistry
import com.superiorsoftllc.agenthq.ui.model.ProjectView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProjectSettingsModelTest {
    private val toggle = ProjectSettingsRegistry.toggles.single { it.id == ProjectSettingsRegistry.WATCHDOG_KEY }

    @Test
    fun the_watchdog_switch_is_off_unless_the_project_says_on() {
        assertFalse(toggle.isOn(ProjectView("p", "P")))
        assertFalse(toggle.isOn(ProjectView("p", "P", experiments = mapOf("other" to "on"))))
        assertTrue(toggle.isOn(ProjectView("p", "P", experiments = mapOf("agentWatchdog" to "on"))))
    }

    @Test
    fun the_switch_needs_a_daemon_that_stores_project_switches() {
        assertFalse(toggle.available(ConnectionInfo(contract = "1.125.0")))
        assertTrue(toggle.available(ConnectionInfo(contract = "1.127.0")))
    }

    @Test
    fun the_pipeline_section_lists_the_registered_switches_in_order() {
        val ids = ProjectSettingsRegistry.togglesFor(ProjectSettingsRegistry.PIPELINE, ConnectionInfo(contract = "1.127.0")).map { it.id }
        assertEquals(listOf("agentWatchdog", "autoRemoveDoneWorkers"), ids)
        assertEquals(listOf("general", "pipeline", "lanes"), ProjectSettingsRegistry.sections.map { it.id })
    }
}
