// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.plugins.official.usage.*
import com.superiorsoftllc.infantry.ui.shell.CoreUsage

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.infantry.daemon.InfantryDaemon
import com.superiorsoftllc.infantry.mailbox.JsonMailStore
import com.superiorsoftllc.infantry.persistence.SqlInfantryStore
import com.superiorsoftllc.infantry.provider.StubProvider
import com.superiorsoftllc.infantry.runner.StubScript
import com.superiorsoftllc.infantry.session.JsonSessionStore
import com.superiorsoftllc.infantry.stories.JsonStoryStore
import com.superiorsoftllc.infantry.ui.net.Controller
import com.superiorsoftllc.infantry.usage.LimitService
import com.superiorsoftllc.infantry.usage.LimitSource
import com.superiorsoftllc.infantry.daemon.api.usage.LimitWindow
import com.superiorsoftllc.infantry.usage.ProviderLimits
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * USAGE-PACE follow-up against a REAL in-process daemon (temp data dir, ephemeral port, a fake plan-usage source): the
 * meter shows "projected N% at reset" from the daemon's pace judgement; the rule editor edits the pace keys and a save
 * keeps the mode (it used to send enabled + tiers only, which reset it); switching to bands drops the projection.
 */
@OptIn(ExperimentalTestApi::class)
class PacingRuleRealDaemonUiTest {
    /** A 5h window at 40%, half elapsed at every read: projected 80% at reset. */
    private val halfway = object : LimitSource {
        override val provider = "claude"
        override fun read(now: Long) = ProviderLimits(
            "claude", "ok",
            listOf(LimitWindow("claude", "5h limit", 300, 40.0, now + 150 * 60_000L, now, "live", -10.0)),
            null, now,
        )
    }

    @Test
    fun the_meter_shows_the_projection_and_the_editor_keeps_the_mode() {
        val dir = Files.createTempDirectory("infantry-pacing-ui").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val stub = StubProvider(StubScript(name = "idle", stdoutLines = emptyList(), exitCode = 0))
        val daemon = InfantryDaemon.build(
            memory = FixedMemory,
            port = 0, host = "127.0.0.1", provider = stub, runner = stub.transport()!!,
            sessionStore = JsonSessionStore(File(dir, "sessions.json")),
            mailStore = JsonMailStore(File(dir, "mailbox.json")),
            store = SqlInfantryStore.open(File(dir, "infantry.db").absolutePath),
            mailBaseUrl = "http://127.0.0.1:1", token = null,
            storyStore = JsonStoryStore(File(dir, "stories.json")),
            modelCatalogs = com.superiorsoftllc.infantry.provider.HarnessModelCatalogs.EMPTY,
            limits = LimitService(listOf(halfway), liveEnabled = { true }),
        )
        try {
            daemon.server.start(wait = false)
            val port = runBlocking {
                repeat(250) {
                    daemon.server.engine.resolvedConnectors().firstOrNull()?.port?.takeIf { it > 0 }?.let { return@runBlocking it }
                    delay(20)
                }
                error("no port")
            }
            assertTrue(port != 8797)
            val c = Controller(scope)
            fun rule() = c.state.value.usageReport?.rule
            runDesktopComposeUiTest(1000, 1400) {
                c.connect("ws://127.0.0.1:$port/ws", null)
                waitUntil(timeoutMillis = 20_000) { c.state.value.connection.isLive }
                runBlocking { c.refreshUsage(force = true) }
                waitUntil(timeoutMillis = 15_000) { c.state.value.usageReport?.meters?.any { it.projectedPercent != null } == true }
                assertEquals("pace", rule()?.mode)
                setContent {
                    val state by c.state.collectAsState()
                    InfantryTheme { UsagePane(state, null, CoreUsage(c)) }
                }

                // The projection sits next to the meter's percent; the rule line names the mode.
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("pacing-projected-claude").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("pacing-projected-claude").assertTextContains("projected 80", substring = true)
                onNodeWithTag("pacing-rule").assertTextContains("pace · up to 5 ticket workers", substring = true)

                // Edit the pace keys: the mode stays pace, the keys land in the daemon.
                onNodeWithTag("pacing-edit-rule").performClick()
                onNodeWithTag("pacing-rule-mode-pace").assertIsSelected()
                onNodeWithTag("pacing-rule-max").performTextReplacement("7")
                onNodeWithTag("pacing-rule-ok").performTextReplacement("90")
                onNodeWithTag("pacing-rule-hardstop").performTextReplacement("97")
                onNodeWithTag("pacing-save").performClick()
                waitUntil(timeoutMillis = 10_000) { rule()?.maxWorkers == 7 }
                assertEquals("pace", rule()?.mode)
                assertEquals(90.0, rule()?.okProjected)
                assertEquals(97.0, rule()?.hardStopPercent)
                waitUntil(timeoutMillis = 10_000) { c.state.value.usageReport?.meters?.single()?.allowance == 7 }

                // A tiers-only edit no longer resets the mode.
                onNodeWithTag("pacing-edit-rule").performClick()
                onNodeWithTag("pacing-rule-input").performTextReplacement("50:4, 1")
                onNodeWithTag("pacing-save").performClick()
                waitUntil(timeoutMillis = 10_000) { rule()?.tiers?.size == 2 }
                assertEquals("pace", rule()?.mode)
                assertEquals(7, rule()?.maxWorkers)

                // Bands: the daemon judges by percent (40% < 50% -> 4) and the projection goes away.
                onNodeWithTag("pacing-edit-rule").performClick()
                onNodeWithTag("pacing-rule-mode-bands").performClick()
                onNodeWithTag("pacing-rule-max").assertDoesNotExist()
                onNodeWithTag("pacing-save").performClick()
                waitUntil(timeoutMillis = 10_000) { rule()?.mode == "bands" }
                assertEquals(7, rule()?.maxWorkers)
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("pacing-projected-claude").fetchSemanticsNodes().isEmpty() }
                assertEquals(4, c.state.value.usageReport?.meters?.single()?.allowance)
                onNodeWithTag("pacing-rule").assertTextContains("<50% -> 4 · else -> 1 ticket workers", substring = true)
            }
        } finally {
            scope.cancel()
            daemon.server.stop(0, 200)
            daemon.close()
            dir.deleteRecursively()
        }
    }
}
