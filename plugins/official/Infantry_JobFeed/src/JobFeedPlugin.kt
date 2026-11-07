// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.jobfeed

import com.superiorsoftllc.infantry.plugin.api.ConfigDeclaration
import com.superiorsoftllc.infantry.plugin.api.ConfigFieldDeclaration
import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.plugin.api.PanelDeclaration
import com.superiorsoftllc.infantry.ui.plugin.InfantryModule
import com.superiorsoftllc.infantry.ui.plugin.ModuleSource
import com.superiorsoftllc.infantry.ui.plugin.PanelProvider
import com.superiorsoftllc.infantry.ui.plugin.UiPlugin
import com.superiorsoftllc.infantry.ui.plugin.UiRegistrar
import kotlinx.serialization.json.JsonPrimitive

private fun jobFeedPanel() = PanelProvider(id = JobFeedKeys.SECTION_ID, panel = JobFeedKeys.SLOT, order = 0, testTag = "job-feed") { JobFeedBox(it) }

private fun f(key: String, type: String, label: String, default: JsonPrimitive, order: Int, min: Double? = null, max: Double? = null, step: Double? = null) =
    ConfigFieldDeclaration(key, type, label, default, min = min, max = max, step = step, order = order, scope = "device")

private fun d(key: String, type: String, label: String, default: JsonPrimitive, order: Int, min: Double? = null, max: Double? = null, step: Double? = null) =
    ConfigFieldDeclaration(key, type, label, default, min = min, max = max, step = step, order = order, scope = "daemon")

/** Infantry_JobFeed as a compiled-in module (web and Android, D9): the manifest mirrors module.json. */
val InfantryJobFeed = InfantryModule(
    ModuleManifest(
        name = "Infantry_JobFeed", version = "1.0.0", author = "Infantry AI",
        description = "Optional: a live box in the session view that shows the output of a watched command (gradle, tests, builds) while it runs. Off until you enable it; costs nothing while off or closed.",
        defaultEnabled = false,
        panels = listOf(PanelDeclaration(JobFeedKeys.SECTION_ID, JobFeedKeys.SLOT)),
        config = listOf(
            ConfigDeclaration(
                node = JobFeedKeys.NODE, group = "Job feed", tab = "Plugins", order = 931, title = "Job feed",
                applies = "Applies to: the box under the chat that shows a watched command's live output. Defaults suit a weak PC.",
                keywords = listOf("job", "feed", "output", "live", "log", "power", "low"),
                fields = listOf(
                    f(JobFeedKeys.UPDATES_PER_SECOND, "number", "Updates per second while the box is open", JsonPrimitive(JobFeedDefaults.UPDATES_PER_SECOND), 1, 1.0, 20.0, 1.0),
                    f(JobFeedKeys.MAX_LINES, "number", "Lines kept in the box", JsonPrimitive(JobFeedDefaults.MAX_LINES), 2, 10.0, 5000.0, 10.0),
                    f(JobFeedKeys.LOW_POWER, "checkbox", "Low power (1 update a second, 100 lines)", JsonPrimitive(false), 3),
                    f(JobFeedKeys.AUTO_COLLAPSE, "checkbox", "Stop reading while the window is not focused", JsonPrimitive(true), 4),
                    f(JobFeedKeys.OPEN_BY_DEFAULT, "checkbox", "Open the box when a job starts", JsonPrimitive(false), 5),
                    f(JobFeedKeys.FAILURE_MARKERS, "text", "Highlight lines containing (separate with |)", JsonPrimitive(JobFeedDefaults.FAILURE_MARKERS), 6),
                    f(JobFeedKeys.QUIET_SECONDS, "number", "Quiet notice after this many seconds without output (0 = off)", JsonPrimitive(JobFeedDefaults.QUIET_SECONDS), 7, 0.0, 86400.0, 30.0),
                ),
            ),
            ConfigDeclaration(
                node = "infantry.jobfeed.daemon", group = "Job feed (daemon)", tab = "Plugins", order = 930, title = "Job feed: daemon limits",
                applies = "Applies to: how the daemon answers the Job feed box. The box asks for new lines; the daemon never pushes.",
                keywords = listOf("job", "feed", "output", "live", "log", "gradle", "build"),
                fields = listOf(
                    d("jobfeed.minIntervalMs", "number", "Least milliseconds between two reads of one job (0 = no cap)", JsonPrimitive(200), 1, 0.0, 60000.0, 50.0),
                    d("jobfeed.maxLineChars", "number", "Cut a line after this many characters (0 = never)", JsonPrimitive(300), 2, 0.0, 100000.0, 50.0),
                    d("jobfeed.stripAnsi", "checkbox", "Strip terminal colour codes", JsonPrimitive(true), 3),
                    d("jobfeed.maxTracked", "number", "Jobs the rate cap remembers", JsonPrimitive(200), 4, 1.0, 100000.0, 10.0),
                ),
            ),
        ),
    ),
    panels = listOf(jobFeedPanel()),
)

/**
 * The entry of Infantry_JobFeed (module.json `entry`): one section in the chat's `chat.jobfeed` slot. Off by default
 * (`defaultEnabled: false`): until the owner enables it in Settings > Plugins nothing of it is loaded.
 */
class JobFeedPlugin : UiPlugin, ModuleSource {
    override fun register(registrar: UiRegistrar) {
        registrar.panel(jobFeedPanel())
    }

    override fun modules(): List<InfantryModule> = listOf(InfantryJobFeed)
}
