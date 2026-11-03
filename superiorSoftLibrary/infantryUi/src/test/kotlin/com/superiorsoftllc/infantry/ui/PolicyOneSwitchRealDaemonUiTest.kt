// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.infantry.daemon.InfantryDaemon
import com.superiorsoftllc.infantry.mailbox.JsonMailStore
import com.superiorsoftllc.infantry.persistence.SqlInfantryStore
import com.superiorsoftllc.infantry.plugin.api.PolicyPreset
import com.superiorsoftllc.infantry.plugin.host.ModuleState
import com.superiorsoftllc.infantry.plugin.host.ModuleStatus
import com.superiorsoftllc.infantry.plugins.core.CoreModules
import com.superiorsoftllc.infantry.policy.DirectoryPolicyProvider
import com.superiorsoftllc.infantry.policy.PolicyRuntime
import com.superiorsoftllc.infantry.provider.StubProvider
import com.superiorsoftllc.infantry.runner.StubScript
import com.superiorsoftllc.infantry.session.JsonSessionStore
import com.superiorsoftllc.infantry.stories.JsonStoryStore
import com.superiorsoftllc.infantry.ui.model.ConnState
import com.superiorsoftllc.infantry.ui.net.Controller
import com.superiorsoftllc.infantry.ui.plugin.TabFactory
import com.superiorsoftllc.infantry.ui.protocol.Envelope
import com.superiorsoftllc.infantry.ui.shell.uiPrefsOverride
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * POLICY-1 (docs/POLICY.md section 5): the demo on and off with ONE switch. A real in-process daemon (temp state dir, port
 * 0, its own owner token, a plugins root holding only the demo plugin's preset file) and the real [App] over the real
 * WebSocket as the owner. It boots `production`: New agent is enabled and a ticket is saved. The owner flips ONLY the
 * policy setting to `demo` in Configuration > General > Policy: New agent dims, and the daemon refuses `ticket_save` and
 * `kill` with the preset's text. Flipped back to `production`: enabled again and a ticket is saved. No rebuild, no plugin
 * edited, no env var.
 */
@OptIn(ExperimentalTestApi::class)
class PolicyOneSwitchRealDaemonUiTest {
    private val waitMs = System.getenv("INFANTRY_TEST_WAIT_MS")?.toLongOrNull()?.takeIf { it > 0 } ?: 60_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val closers = mutableListOf<() -> Unit>()
    private val state: File = Files.createTempDirectory("infantry-ui-policy1").toFile()
    private var savedPrefs: MutableMap<String, String>? = null
    private val owner = "policy-one-switch-owner-token-0123456789abcdef"
    private val demoId: String

    init {
        // The demo plugin's preset, as shipped (the daemon reads the plugin's data; core names no preset).
        val shipped = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "plugins/demo/Demo_Lock/policies/demo.json") }.first { it.isFile }
        // DAEMON-PLUGINS-1: the preset's `seats` are claimed by the Demo_Lock daemon plugin, which this plugins root does not
        // load; with seats and no claimant the daemon fails closed with its own text. This test is the one-switch refusal,
        // so it ships the preset minus its seats (the seated lockdown is the Demo_Lock plugin's own test).
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val preset = json.decodeFromString(PolicyPreset.serializer(), shipped.readText()).copy(seats = null)
        File(state, "plugins/demo/Demo_Lock/policies/demo.json").apply { parentFile.mkdirs() }.writeText(json.encodeToString(PolicyPreset.serializer(), preset))
        demoId = preset.id
        savedPrefs = uiPrefsOverride
        uiPrefsOverride = mutableMapOf()
    }

    @AfterTest fun tearDown() {
        scope.cancel()
        closers.reversed().forEach { runCatching { it() } }
        uiPrefsOverride = savedPrefs
        state.deleteRecursively()
    }

    private fun boot(): Int {
        val provider = StubProvider(StubScript.default(), delayMs = 0)
        // No env: the selection is only the stored setting under this test's own state dir.
        val policies = PolicyRuntime(
            listOf(DirectoryPolicyProvider({ DirectoryPolicyProvider.defaultDirs(File(state, "plugins"), null) { null } })),
            { null }, File(state, PolicyRuntime.SELECTION_FILE), state,
        )
        assertEquals(PolicyPreset.PRODUCTION, policies.current.id)
        val daemon = InfantryDaemon.build(
            memory = FixedMemory,
            port = 0, host = "127.0.0.1", provider = provider, runner = provider.transport()!!,
            sessionStore = JsonSessionStore(File(state, "sessions.json")),
            mailStore = JsonMailStore(File(state, "mailbox.json")),
            store = SqlInfantryStore.open(File(state, "infantry.db").absolutePath),
            mailBaseUrl = "http://127.0.0.1:1", token = null, ownerToken = owner,
            storyStore = JsonStoryStore(File(state, "stories.json")),
            modelCatalogs = com.superiorsoftllc.infantry.provider.HarnessModelCatalogs.EMPTY,
            workingDir = state,
            policies = policies,
        )
        daemon.server.start(wait = false)
        closers += { runCatching { daemon.server.stop(0, 200); daemon.close() } }
        return runBlocking {
            repeat(250) {
                daemon.server.engine.resolvedConnectors().firstOrNull()?.port?.takeIf { it > 0 }?.let { return@runBlocking it }
                delay(20)
            }
            error("no port")
        }
    }

    private fun tabs(): TabFactory {
        val all = CoreModules.all + listOf(
            com.superiorsoftllc.infantry.plugins.official.header.InfantryHeader,
            com.superiorsoftllc.infantry.plugins.official.InfantryAgents,
            com.superiorsoftllc.infantry.plugins.official.chat.InfantryChat, com.superiorsoftllc.infantry.plugins.official.chatfeed.InfantryChatFeed,
        )
        return TabFactory(all, statuses = all.map { ModuleStatus(it.manifest.name, it.tier, ModuleState.LOADED, manifest = it.manifest) })
    }

    /** The refusal the daemon sent [c] for [frame] since [since] notices, if any. */
    private fun refusal(c: Controller, frame: String, since: Int): String? =
        c.state.value.notices.drop(since).map { it.text }.firstOrNull { it.contains("'$frame'") }

    @Test fun `one setting turns the demo on and off, the UI dims and the daemon refuses, then both come back`() {
        val port = boot()
        val url = "ws://127.0.0.1:$port/ws"
        val c = Controller(scope).also { it.client.ownerFor(url, owner) }
        val tabs = tabs()
        runDesktopComposeUiTest(1400, 900) {
            setContent { App(c, url, null, tabs = tabs) }
            waitUntil(timeoutMillis = waitMs) { c.state.value.connection.state == ConnState.CONNECTED && c.state.value.connection.policy.presets.isNotEmpty() }
            val wire = c.state.value.connection.policy
            assertEquals(PolicyPreset.PRODUCTION, wire.preset)
            assertTrue(wire.canSwitch, "the owner's connection may switch")
            assertTrue(wire.presets.any { it.id == demoId }, "the demo preset is offered: ${wire.presets}")

            // Production: the write control is enabled and the daemon answers a write.
            waitUntil(timeoutMillis = waitMs) { onAllNodesWithTag(SIDEBAR_NEW_AGENT_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(SIDEBAR_NEW_AGENT_TAG).assertIsEnabled()
            runBlocking { c.createTicket("before the switch", "", null, null) }
            waitUntil(timeoutMillis = waitMs) { c.state.value.tickets.any { it.title == "before the switch" } }

            // The ONE switch: Configuration > General > Policy, pick the demo preset.
            openConfig()
            onNodeWithTag(CONFIG_TAB_TAG_PREFIX + ConfigTab.GENERAL.name).performClick()
            waitUntil(timeoutMillis = waitMs) { onAllNodesWithTag(POLICY_CHOICE_TAG_PREFIX + demoId).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(POLICY_CHOICE_TAG_PREFIX + demoId).performScrollTo().performClick()
            waitUntil(timeoutMillis = waitMs) { c.state.value.connection.policy.preset == demoId }
            assertEquals(demoId, File(state, PolicyRuntime.SELECTION_FILE).readText().trim(), "the switch is the one stored setting")
            waitForIdle()

            // Demo: the same control dims, and the daemon refuses the writes with the preset's text.
            onNodeWithTag(SIDEBAR_NEW_AGENT_TAG).assertIsNotEnabled()
            assertFalse(c.state.value.connection.policy.allows(""), "undeclared writes are off")
            assertEquals("restricted", c.state.value.connection.readyFields["access"], "the preset's ready fields followed the switch")
            val mark = c.state.value.notices.size
            runBlocking {
                c.client.send(Envelope(Envelope.TICKET_SAVE, """{"title":"during the demo"}"""))
                c.client.send(Envelope(Envelope.KILL, "nobody"))
            }
            waitUntil(timeoutMillis = waitMs) { refusal(c, Envelope.TICKET_SAVE, mark) != null && refusal(c, Envelope.KILL, mark) != null }
            assertTrue(refusal(c, Envelope.TICKET_SAVE, mark)!!.contains("demo: 'ticket_save' is disabled in this demo"), refusal(c, Envelope.TICKET_SAVE, mark))
            assertFalse(c.state.value.tickets.any { it.title == "during the demo" })

            // And back: production again, from the same card.
            onNodeWithTag(POLICY_CHOICE_TAG_PREFIX + PolicyPreset.PRODUCTION).performScrollTo().performClick()
            waitUntil(timeoutMillis = waitMs) { c.state.value.connection.policy.preset == PolicyPreset.PRODUCTION }
            assertFalse(File(state, PolicyRuntime.SELECTION_FILE).exists())
            waitForIdle()
            onNodeWithTag(SIDEBAR_NEW_AGENT_TAG).assertIsEnabled()
            assertEquals(null, c.state.value.connection.readyFields["access"])
            runBlocking { c.createTicket("after the switch", "", null, null) }
            waitUntil(timeoutMillis = waitMs) { c.state.value.tickets.any { it.title == "after the switch" } }
        }
    }

    /** Opens the Configuration dialog once the footer cog is composed (it appears after the shell's first frames). */
    private fun androidx.compose.ui.test.ComposeUiTest.openConfig() {
        waitUntil(timeoutMillis = waitMs) {
            onAllNodesWithTag(CONFIG_DIALOG_TAG).fetchSemanticsNodes().isNotEmpty() ||
                run {
                    val cog = onAllNodesWithContentDescription("Configuration")
                    if (cog.fetchSemanticsNodes().isNotEmpty()) cog[0].performClick()
                    false
                }
        }
    }
}
