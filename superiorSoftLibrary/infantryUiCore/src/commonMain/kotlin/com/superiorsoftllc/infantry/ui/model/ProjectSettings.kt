// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.model

import com.superiorsoftllc.infantry.ui.net.CommandResult
import com.superiorsoftllc.infantry.ui.net.Controller

/** A section of the Project settings panel (a tab). Sections and their toggles are registered here, not hard-coded in the dialog. */
data class SettingsSection(val id: String, val label: String, val tag: String)

/**
 * A boolean project setting shown as a switch in section [sectionId]. [isOn] reads it off the project, [available] says
 * whether the connected daemon can store it, [write] sends the change (the daemon pushes the project back).
 */
data class SettingToggle(
    val id: String,
    val sectionId: String,
    val label: String,
    val tag: String,
    val isOn: (ProjectView) -> Boolean,
    val available: (ConnectionInfo) -> Boolean = { true },
    val write: suspend (Controller, String, Boolean) -> CommandResult,
)

object ProjectSettingsRegistry {
    const val GENERAL = "general"
    const val PIPELINE = "pipeline"
    const val LANES = "lanes"
    const val SKILLS = "skills"

    /** The `Project.experiments` key behind the watchdog switch; the daemon reads the same key. */
    const val WATCHDOG_KEY = "agentWatchdog"

    val sections: List<SettingsSection> = listOf(
        SettingsSection(GENERAL, "General", "project-config-tab-general"),
        SettingsSection(PIPELINE, "Pipeline", "project-config-tab-pipeline"),
        SettingsSection(LANES, "Lanes / Roles", "project-config-tab-lanes"),
        SettingsSection(SKILLS, "Skills", "project-config-tab-skills"),
    )

    val toggles: List<SettingToggle> = listOf(
        SettingToggle(
            id = WATCHDOG_KEY, sectionId = PIPELINE, label = "Agent watchdog (nudges for unanswered mail)",
            tag = "project-config-toggle-agent-watchdog",
            isOn = { it.experiments[WATCHDOG_KEY] == "on" },
            available = { contractAtLeast(it.contract, 1, 126) },
            write = { c, project, on -> c.setProjectSwitch(project, WATCHDOG_KEY, on) },
        ),
        SettingToggle(
            id = "autoRemoveDoneWorkers", sectionId = PIPELINE, label = "Remove a worker's session when its ticket is done (after its last turn)",
            tag = "project-config-auto-remove-done-workers",
            isOn = { it.autoRemoveDoneWorkers },
            available = { it.autoRemovesDoneWorkers },
            write = { c, project, on -> c.setAutoRemoveDoneWorkers(project, on) },
        ),
    )

    /** The toggles of [sectionId] that the connected daemon can store, in registration order. */
    fun togglesFor(sectionId: String, connection: ConnectionInfo): List<SettingToggle> =
        toggles.filter { it.sectionId == sectionId && it.available(connection) }
}
