// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.plugins.official.usage.*
import com.superiorsoftllc.infantry.ui.shell.CoreUsage

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.infantry.ui.net.Controller
import com.superiorsoftllc.infantry.ui.model.ThemeSettings
import com.superiorsoftllc.infantry.ui.net.DaemonClient
import com.superiorsoftllc.infantry.ui.protocol.Envelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

private class LimitsFakeDaemonClient(scope: CoroutineScope) : DaemonClient(scope) {
    val sent = CopyOnWriteArrayList<Envelope>()
    private val incoming = MutableSharedFlow<Envelope>(extraBufferCapacity = 64)
    override val frames: SharedFlow<Envelope> get() = incoming
    override suspend fun send(envelope: Envelope): Boolean { sent += envelope; return true }
    fun push(e: Envelope) {
        val deadline = System.currentTimeMillis() + 10_000
        while (incoming.subscriptionCount.value == 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        check(incoming.tryEmit(e)) { "frame buffer full" }
    }
    fun sentOf(type: String) = sent.filter { it.type == type }
}

/**
 * U2: the plan-limit panel above the per-run usage, drawn from a fake daemon's `limits` frame (contract 1.59.0).
 * One card per provider; each window shows its %, its reset, and how far ahead of or under an even pace it is.
 * A provider whose percent is unknown shows "—" and why, never a bar (no signal is never 0%).
 */
@OptIn(ExperimentalTestApi::class)
class UsageLimitsPanelUiTest {
    private val now = System.currentTimeMillis()
    private val sessionReset = now + 2 * 3_600_000L
    private val weekReset = now + 3 * 86_400_000L

    /** The daemon's wire, written by hand so the test pins the JSON shape and not this client's DTOs. */
    private val limitsJson = """
        [
          {"provider":"claude","status":"ok","reason":null,"observedAt":${now - 180_000},"windows":[
            {"provider":"claude","label":"Current session","windowMinutes":300,"percent":42.0,"resetsAt":$sessionReset,"observedAt":${now - 180_000},"source":"live","paceDelta":-12.3},
            {"provider":"claude","label":"Current week","windowMinutes":10080,"percent":81.0,"resetsAt":$weekReset,"observedAt":${now - 180_000},"source":"live","paceDelta":9.5}
          ]},
          {"provider":"opencode-go","status":"unknown","reason":"no_subscription","observedAt":${now - 180_000},"windows":[]},
          {"provider":"codex","status":"unknown","reason":"no_credentials","observedAt":${now - 180_000},"windows":[]}
        ]
    """.trimIndent()

    private fun resetText(at: Long) =
        "Resets " + DateTimeFormatter.ofPattern("MMM d h:mm a", Locale.ENGLISH).format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()))

    @Test fun `a limits frame renders a card per provider with percent, reset, pace and unknown reasons`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val link = LimitsFakeDaemonClient(scope)
            val controller = Controller(scope, link)
            runDesktopComposeUiTest(520, 900) {
                setContent {
                    val state by controller.state.collectAsState()
                    Box(Modifier.fillMaxSize().background(Palette.Panel).testTag("shot")) { UsagePane(state, null) }
                }
                link.push(Envelope(Envelope.READY, "contract=1.59.0 provider=stub instance=i1"))
                waitUntil(timeoutMillis = 10_000) { controller.state.value.connection.isLive }
                link.push(Envelope("limits", limitsJson))

                // One card per provider, under its display name.
                waitUntil(timeoutMillis = 10_000) { runCatching { onNodeWithText("Claude").assertExists() }.isSuccess }
                onNodeWithText("OpenCode Go").assertExists()
                onNodeWithText("Codex").assertExists()

                // Claude's windows: label, % used, reset date/time, pace.
                onNodeWithText("Current session").assertExists()
                onNodeWithText("42%").assertExists()
                onNodeWithText(resetText(sessionReset)).assertExists()
                onNodeWithText("12 pts under pace").assertExists()
                onNodeWithContentDescription("Current session: 42% used, under pace").assertExists()
                onNodeWithText("Current week").assertExists()
                onNodeWithText("81%").assertExists()
                onNodeWithText(resetText(weekReset)).assertExists()
                onNodeWithText("10 pts ahead of pace").assertExists()
                onNodeWithContentDescription("Current week: 81% used, ahead of pace").assertExists()

                // Unknown providers: "—" and the reason, no bar.
                onNodeWithText("no subscription").assertExists()
                onNodeWithText("not signed in").assertExists()

                // When the meters were read, and the per-run usage still below them.
                onNodeWithText("updated 3m ago").assertExists()
                onNodeWithText("Select an agent to see what its runs reported.").assertExists()

                val out = File("build/reports/u2/usage-limits-panel.png").apply { parentFile.mkdirs() }
                ImageIO.write(onNodeWithTag("shot").captureToImage().toAwtImage(), "png", out)
                println("U2 screenshot: ${out.absolutePath}")
            }
        } finally {
            scope.cancel()
        }
    }

    @Test fun `connecting asks for the limits frame and the refresh icon asks again`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val link = LimitsFakeDaemonClient(scope)
            val controller = Controller(scope, link)
            runDesktopComposeUiTest(520, 600) {
                setContent {
                    val state by controller.state.collectAsState()
                    UsagePane(state, null, CoreUsage(controller))
                }
                link.push(Envelope(Envelope.READY, "contract=1.59.0 provider=stub instance=i1"))
                waitUntil(timeoutMillis = 10_000) { link.sentOf("limits").isNotEmpty() }
                onNodeWithText("Waiting for the daemon's plan limits.").assertExists()
                link.push(Envelope("limits", limitsJson))
                waitUntil(timeoutMillis = 10_000) { controller.state.value.planLimits != null }

                onNodeWithTag("limits-refresh").performClick()
                waitUntil(timeoutMillis = 10_000) { link.sentOf("limits").size == 2 }
                assertEquals("", link.sentOf("limits").last().data, "a limits request carries no data")
            }
        } finally {
            scope.cancel()
        }
    }

    /** USAGE-CARD-1: a rate-limited read keeps the last good reading on the card, with its age and the retry time. */
    @Test fun `a stale card keeps the last reading with its age and retry time, dark and light`() {
        val read = now - 14 * 60_000L
        val retryAt = now + 9 * 60_000L
        val staleJson = """
            [
              {"provider":"claude","status":"ok","reason":"rate_limited","observedAt":$read,"stale":true,"retryAt":$retryAt,"windows":[
                {"provider":"claude","label":"Current session","windowMinutes":300,"percent":42.0,"resetsAt":$sessionReset,"observedAt":$read,"source":"live","paceDelta":-12.3},
                {"provider":"claude","label":"Current week","windowMinutes":10080,"percent":81.0,"resetsAt":$weekReset,"observedAt":$read,"source":"live","paceDelta":9.5}
              ]},
              {"provider":"opencode-go","status":"unknown","reason":"no_subscription","observedAt":$now,"windows":[]}
            ]
        """.trimIndent()
        val retryClock = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH).format(Instant.ofEpochMilli(retryAt).atZone(ZoneId.systemDefault()))
        for ((mode, dark) in listOf("dark" to true, "light" to false)) {
            Palette.theme = ThemeSettings(dark = dark)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val link = LimitsFakeDaemonClient(scope)
                val controller = Controller(scope, link)
                runDesktopComposeUiTest(520, 520) {
                    setContent {
                        val state by controller.state.collectAsState()
                        Box(Modifier.fillMaxSize().background(Palette.Panel).testTag("shot")) { UsagePane(state, null) }
                    }
                    link.push(Envelope(Envelope.READY, "contract=1.59.0 provider=stub instance=i1"))
                    waitUntil(timeoutMillis = 10_000) { controller.state.value.connection.isLive }
                    link.push(Envelope("limits", staleJson))
                    waitUntil(timeoutMillis = 10_000) { runCatching { onNodeWithTag("limits-stale-claude").assertExists() }.isSuccess }
                    // The last good reading stays: its percents, never "—".
                    onNodeWithText("42%").assertExists()
                    onNodeWithText("81%").assertExists()
                    onNodeWithText("updated 14 min ago · rate limited, retrying at $retryClock").assertExists()
                    // Only one Claude card.
                    assertEquals(1, onAllNodesWithText("Claude").fetchSemanticsNodes().size)

                    val out = File("build/reports/usage-card-1/usage-card-stale-$mode.png").apply { parentFile.mkdirs() }
                    ImageIO.write(onNodeWithTag("shot").captureToImage().toAwtImage(), "png", out)
                    println("USAGE-CARD-1 screenshot: ${out.absolutePath}")
                }
            } finally {
                scope.cancel()
                Palette.theme = ThemeSettings()
            }
        }
    }
}
