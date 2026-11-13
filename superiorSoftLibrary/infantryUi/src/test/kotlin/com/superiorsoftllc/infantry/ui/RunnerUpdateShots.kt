// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.superiorsoftllc.infantry.plugins.official.runners.RunnerUpdateStatus
import com.superiorsoftllc.infantry.plugins.official.runners.RunnersCard
import com.superiorsoftllc.infantry.plugins.official.runners.parseRunnerUpdateStatus
import com.superiorsoftllc.infantry.plugins.official.runners.parseRunnersList
import com.superiorsoftllc.infantry.ui.model.ThemeSettings
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * RUNNER-UPDATE-1: PNGs of the Runners card for an outdated runner: before the update, while it runs, after it. The rows are the
 * daemon's own answers when a live run saved them under build/runner-update-live (`list-before.json`, `update-frames.txt`,
 * `list-after.json`); else a built-in sample. Writes build/runner-update-shots; asserts nothing.
 */
@OptIn(ExperimentalTestApi::class)
class RunnerUpdateShots {
    private val live = File("build/runner-update-live")
    private fun liveText(name: String): String? = File(live, name).takeIf { it.isFile }?.readText()?.trim()?.ifEmpty { null }

    private val sampleBefore = """{"ok":true,"enabled":true,"runners":[{"name":"local","health":"up","workers":"0","cap":"1","paused":"false"},
        {"name":"testbox","health":"outdated","workers":"0","cap":"1","paused":"false","contract":"1.8.0","needs":"1.11.0","fingerprint":"cb5c23998a6bff1d",
         "outdated":"runner 'testbox' is older than the app (contract 1.8.0, the app needs 1.11.0 or newer): update it: Settings > Runners > Update runner"}]}"""

    private fun shoot(name: String, listText: String, updates: Map<String, RunnerUpdateStatus>) {
        Palette.theme = ThemeSettings(dark = true)
        runDesktopComposeUiTest(900, 300) {
            setContent {
                InfantryTheme {
                    Box(Modifier.fillMaxWidth().background(Palette.Bg).padding(8.dp)) {
                        Box(Modifier.fillMaxWidth().border(1.dp, Palette.Border).padding(12.dp)) {
                            RunnersCard(parseRunnersList(listText), null, {}, { _, _ -> }, {}, updates = updates)
                        }
                    }
                }
            }
            waitForIdle()
            val dir = File("build/runner-update-shots").apply { mkdirs() }
            ImageIO.write(onAllNodes(isRoot())[0].captureToImage().toAwtImage(), "png", File(dir, "$name.png"))
        }
        Palette.theme = ThemeSettings()
    }

    @Test
    fun shots() {
        val before = liveText("list-before.json") ?: sampleBefore
        val statuses = liveText("update-frames.txt")?.lines().orEmpty()
            .mapNotNull { l -> l.substringAfter("runner_update_status ", "").takeIf { it.startsWith("{") }?.let(::parseRunnerUpdateStatus) }
        val running = statuses.lastOrNull { it.running } ?: RunnerUpdateStatus("testbox", "running", "recreate", "recreating infantry-runner-test on infantry-runner:local-0a17c23d7")
        shoot("1-outdated", before, emptyMap())
        shoot("2-updating", before, mapOf(running.name to running))
        val final = statuses.lastOrNull { !it.running }
        val after = liveText("list-after.json")
        if (final != null && after != null) shoot("3-updated", after, mapOf(final.name to final))
    }
}
