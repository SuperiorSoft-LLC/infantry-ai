// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.plugins.official.roles.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.infantry.ui.net.Controller
import com.superiorsoftllc.infantry.ui.net.DaemonClient
import com.superiorsoftllc.infantry.ui.protocol.Envelope
import com.superiorsoftllc.infantry.ui.protocol.Role
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private class RolePluginFakeDaemon(scope: CoroutineScope) : DaemonClient(scope) {
    val sent = CopyOnWriteArrayList<Envelope>()
    private val incoming = MutableSharedFlow<Envelope>(extraBufferCapacity = 64)
    override val frames: SharedFlow<Envelope> get() = incoming
    override suspend fun send(envelope: Envelope): Boolean { sent += envelope; return true }
    fun push(e: Envelope) {
        val deadline = System.currentTimeMillis() + 10_000
        while (incoming.subscriptionCount.value == 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        check(incoming.tryEmit(e)) { "frame buffer full" }
    }
}

/**
 * ROLE-PLUGIN-1: the Roles pane shows a plugin's role with its plugin chip, "disabled" while its plugin is gone, the note on what
 * an edit / uninstall does, and the preferred cards / tools fields; a save sends only the preferences that changed.
 *
 * With `ROLE_SHOTS_DIR` (or `build/role-shots`) holding a folder of `role_list` answers captured from a live test daemon, `step*.json`), each step is also
 * drawn from that real payload and saved as `<step>.png` next to it (the ticket's evidence; the window opens tabs only by click).
 */
@OptIn(ExperimentalTestApi::class)
class RolePluginUiTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val hello = Role(
        "hello-agent", "Hello Agent", "#f59e0b", "Greets.", "CUSTOM", icon = "support-agent", description = "Demo plugin role",
        plugin = "Acme_HelloCard", cards = listOf("hello"),
    )

    private fun drawRoles(rolesJson: String, shot: File?, openEditor: Boolean, expectDisabled: Boolean? = null, check: (RolePluginFakeDaemon) -> Unit = {}) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val link = RolePluginFakeDaemon(scope)
            val controller = Controller(scope, link)
            runDesktopComposeUiTest(1280, 900) {
                setContent {
                    val state by controller.state.collectAsState()
                    RolesPane(
                        RolesDeps(
                            com.superiorsoftllc.infantry.ui.shell.CoreLink(state), com.superiorsoftllc.infantry.ui.shell.CoreSession(state),
                            com.superiorsoftllc.infantry.ui.shell.CoreRoles(state, controller), com.superiorsoftllc.infantry.ui.shell.CoreTraits(state, controller),
                            com.superiorsoftllc.infantry.ui.shell.CoreExecutor(state), com.superiorsoftllc.infantry.ui.shell.CoreNotes(state, controller),
                        ),
                        onSessionStarted = {}, onOpenAgent = {},
                    )
                }
                link.push(Envelope(Envelope.READY, "contract=1.153.0 provider=stub instance=i1"))
                waitUntil(timeoutMillis = 10_000) { controller.state.value.connection.isLive }
                dismissSetupWizard(controller)
                link.push(Envelope(Envelope.ROLES, rolesJson))
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("role-row-name:hello-agent", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("role-row-name:hello-agent", useUnmergedTree = true).performClick()
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Edit role").fetchSemanticsNodes().isNotEmpty() }
                if (openEditor) {
                    onNodeWithText("Edit role").performClick()
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag(ROLE_EDITOR_CARDS_TAG).fetchSemanticsNodes().isNotEmpty() }
                }
                waitForIdle()
                if (expectDisabled != null) {
                    // The list row carries one chip: "disabled" while the plugin is gone, else "plugin".
                    val disabled = onAllNodesWithTag(ROLE_DISABLED_CHIP_TAG_PREFIX + "hello-agent", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
                    val plugin = onAllNodesWithTag(ROLE_PLUGIN_CHIP_TAG_PREFIX + "hello-agent", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
                    assertEquals(expectDisabled, disabled, "disabled chip")
                    assertEquals(true, plugin, "the plugin chip (detail header) is always shown for a plugin role")
                    onNodeWithText("Disabled: the plugin", substring = true).let { n -> if (expectDisabled) n.assertExists() else n.assertDoesNotExist() }
                }
                shot?.let { ImageIO.write(onAllNodes(isRoot())[0].captureToImage().toAwtImage(), "png", it) }
                check(link)
                if (openEditor) {
                    onNodeWithTag(ROLE_EDITOR_TOOLS_TAG).performTextClearance()
                    onNodeWithTag(ROLE_EDITOR_TOOLS_TAG).performTextInput("infantry_ticket_get, news.headlines")
                    onNodeWithTag(ROLE_EDITOR_SAVE_TAG).performClick()
                    waitUntil(timeoutMillis = 10_000) { link.sent.any { it.type == Envelope.ROLE_SAVE } }
                }
            }
            check(link)
        } finally {
            scope.cancel()
        }
    }

    private fun roles(vararg r: Role) = json.encodeToString(ListSerializer(Role.serializer()), r.toList())

    @Test fun `a plugin role shows its plugin chip, and disabled while the plugin is gone`() {
        drawRoles(roles(hello), shot = null, openEditor = false, expectDisabled = false)
        drawRoles(roles(hello.copy(disabled = true, edited = true)), shot = null, openEditor = true, expectDisabled = true)
    }

    @Test fun `the editor saves only the preferences that changed`() {
        drawRoles(roles(hello), shot = null, openEditor = true) { link ->
            val save = link.sent.lastOrNull { it.type == Envelope.ROLE_SAVE } ?: return@drawRoles
            val body = Json.parseToJsonElement(save.data).jsonObject
            assertEquals(listOf("infantry_ticket_get", "news.headlines"), body["tools"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertNull(body["cards"], "unchanged cards are not sent (null = keep)")
        }
    }

    @Test fun `evidence shots from a live daemon's role_list answers`() {
        // ROLE_SHOTS_DIR, else build/role-shots (a Gradle daemon keeps the environment it started with).
        val dir = (System.getenv("ROLE_SHOTS_DIR")?.let(::File) ?: File("build/role-shots")).takeIf { it.isDirectory } ?: return
        dir.listFiles { f -> f.name.startsWith("step") && f.name.endsWith(".json") }.orEmpty().sortedBy { it.name }.forEach { f ->
            val payload = f.readText(Charsets.UTF_8).trim().removePrefix("﻿")
            val name = f.nameWithoutExtension
            drawRoles(payload, File(dir, "$name.png"), openEditor = false)
            drawRoles(payload, File(dir, "$name-editor.png"), openEditor = true)
        }
    }
}
