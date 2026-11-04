// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.plugins.official.agents.*
import com.superiorsoftllc.infantry.plugins.official.chat.*

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.infantry.ui.model.AgentView
import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.model.ConnState
import com.superiorsoftllc.infantry.ui.model.ConnectionInfo
import com.superiorsoftllc.infantry.ui.model.LineKind
import com.superiorsoftllc.infantry.ui.model.ThemeSettings
import com.superiorsoftllc.infantry.ui.model.TranscriptEntry
import com.superiorsoftllc.infantry.ui.net.Controller
import com.superiorsoftllc.infantry.ui.net.DaemonClient
import com.superiorsoftllc.infantry.ui.protocol.Envelope
import com.superiorsoftllc.infantry.ui.protocol.Role
import com.superiorsoftllc.infantry.ui.protocol.SessionRecord
import com.superiorsoftllc.infantry.ui.protocol.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/** A daemon link with no network: lets the shots push READY and the session list so the composer reads live. */
private class ShotLink(scope: CoroutineScope) : DaemonClient(scope) {
    private val incoming = MutableSharedFlow<Envelope>(extraBufferCapacity = 16)
    override val frames: SharedFlow<Envelope> get() = incoming
    override suspend fun send(envelope: Envelope): Boolean = true
    suspend fun push(e: Envelope) {
        repeat(500) { if (incoming.subscriptionCount.value > 0) return@repeat; delay(10) }
        incoming.tryEmit(e)
    }
}

/**
 * CLI-PTY-5: screenshots of a not-running (dormant) session's "Resume session" button, opened (chat composer) and
 * listed (agent card menu), dark and light, desktop and phone width, under build/resume-session-shots.
 */
@OptIn(ExperimentalTestApi::class)
class ResumeSessionShots {
    private val record = SessionRecord(
        id = "be000001", provider = "claude", workingDir = "/w", spawnedAt = 1, lastHeartbeat = 1,
        state = SessionState.IDLE, providerSessionId = "p", dormant = true,
    )
    private val agent = AgentView(
        session = record, role = "backend",
        entries = listOf(TranscriptEntry(1, LineKind.TEXT, "Merged the session endpoint.", 1)),
    )
    private val dir = File("build/resume-session-shots").apply { mkdirs() }

    private fun opened(name: String, w: Int, dark: Boolean) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        Palette.theme = ThemeSettings(dark = dark)
        try {
            val link = ShotLink(scope)
            val controller = Controller(scope, link)
            runBlocking {
                link.push(Envelope(Envelope.READY, "contract=1.28.0 provider=claude instance=i1"))
                link.push(Envelope(Envelope.SESSIONS, Json.encodeToString(ListSerializer(SessionRecord.serializer()), listOf(record))))
                repeat(500) { if (controller.state.value.agents.isNotEmpty()) return@repeat; delay(10) }
            }
            runDesktopComposeUiTest(w, 260) {
                setContent {
                    InfantryTheme {
                        Box(Modifier.fillMaxWidth().background(Palette.Bg).padding(8.dp)) {
                            Column {
                                Text("Merged the session endpoint. All attach tests pass.", color = Palette.Text, fontSize = 13.sp, modifier = Modifier.padding(bottom = 12.dp))
                                PromptBox("", {}, {}, androidx.compose.ui.focus.FocusRequester(), false, controller.state.value.agents.getValue(record.id), controller, {}, onMinimize = {})
                            }
                        }
                    }
                }
                waitForIdle()
                onNodeWithTag(RESUME_SESSION_BUTTON_TAG).assertExists()
                ImageIO.write(onAllNodes(isRoot())[0].captureToImage().toAwtImage(), "png", File(dir, "$name.png"))
            }
        } finally {
            scope.cancel()
            Palette.theme = ThemeSettings()
        }
    }

    private fun listed(name: String, w: Int, dark: Boolean) {
        Palette.theme = ThemeSettings(dark = dark)
        val state = AppState(
            agents = mapOf(agent.id to agent), selectedId = agent.id,
            roles = listOf(Role("backend", "Backend Developer", "#3B82F6")),
            connection = ConnectionInfo(state = ConnState.CONNECTED),
        )
        try {
            runDesktopComposeUiTest(w, 320) {
                setContent { InfantryTheme { Box(Modifier.fillMaxSize().background(Palette.Bg)) { AgentsList(state, listOf(agent), onSelect = {}, onRemove = {}) } } }
                waitForIdle()
                onNodeWithTag("session-menu-${agent.id}").performClick()
                waitForIdle()
                onNodeWithTag(RESUME_SESSION_MENU_TAG_PREFIX + agent.id).assertExists()
                // The menu is a popup: its own root. Draw it over the window capture where it sits.
                val roots = onAllNodes(isRoot())
                val base = roots[0].captureToImage().toAwtImage()
                val g = base.createGraphics()
                for (i in 1 until roots.fetchSemanticsNodes().size) {
                    val at = roots[i].fetchSemanticsNode().boundsInWindow
                    g.drawImage(roots[i].captureToImage().toAwtImage(), at.left.toInt(), at.top.toInt(), null)
                }
                g.dispose()
                ImageIO.write(base, "png", File(dir, "$name.png"))
            }
        } finally {
            Palette.theme = ThemeSettings()
        }
    }

    @Test fun all() {
        for ((mode, dark) in listOf("dark" to true, "light" to false)) for ((size, w) in listOf("desktop" to 900, "phone" to 400)) {
            opened("opened-$mode-$size", w, dark)
            listed("listed-$mode-$size", if (w < 500) w else 420, dark)
        }
        assertTrue(dir.listFiles().orEmpty().count { it.name.endsWith(".png") } >= 8)
    }
}
