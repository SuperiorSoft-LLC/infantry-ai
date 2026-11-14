// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.infantry.daemon.InfantryDaemon
import com.superiorsoftllc.infantry.mailbox.JsonMailStore
import com.superiorsoftllc.infantry.persistence.SqlInfantryStore
import com.superiorsoftllc.infantry.provider.StubProvider
import com.superiorsoftllc.infantry.runner.StubScript
import com.superiorsoftllc.infantry.session.JsonSessionStore
import com.superiorsoftllc.infantry.settings.SettingsService
import com.superiorsoftllc.infantry.stories.JsonStoryStore
import com.superiorsoftllc.infantry.ui.model.LifecycleSetting
import com.superiorsoftllc.infantry.ui.net.Controller
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

/**
 * BUG-M2-11e: the ONE session-lifecycle setting is in the Configuration modal, shows the daemon's value, and a change
 * made there reaches the daemon (stored `session.keepAliveMinutes`, which every launch decision reads). REAL window,
 * REAL in-process daemon; the stored value is read back from the daemon's own store, not from the window.
 */
@OptIn(ExperimentalTestApi::class)
class KeepAliveSettingUiTest {
    @Test
    fun `the modal shows the daemon's keep-alive and a change there is stored by the daemon`() {
        val dir = Files.createTempDirectory("infantry-11e-ui").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val stub = StubProvider(StubScript(name = "m2-11e", stdoutLines = listOf("""{"type":"step_finish"}"""), exitCode = 0), delayMs = 0)
        val daemon = InfantryDaemon.build(
            memory = FixedMemory,
            port = 0, host = "127.0.0.1", provider = stub, runner = stub.transport()!!,
            sessionStore = JsonSessionStore(File(dir, "sessions.json")),
            mailStore = JsonMailStore(File(dir, "mailbox.json")),
            store = SqlInfantryStore.open(File(dir, "infantry.db").absolutePath),
            mailBaseUrl = "http://127.0.0.1:1", token = null,
            storyStore = JsonStoryStore(File(dir, "stories.json")),
            additionalProviders = emptyList(),
            modelCatalogs = com.superiorsoftllc.infantry.provider.HarnessModelCatalogs.EMPTY,
        )
        val stored = SettingsService(daemon.store)
        try {
            daemon.server.start(wait = false)
            val port = runBlocking {
                repeat(250) {
                    daemon.server.engine.resolvedConnectors().firstOrNull()?.port?.takeIf { it > 0 }?.let { return@runBlocking it }
                    delay(20)
                }
                error("no port")
            }
            val controller = Controller(scope)
            runDesktopComposeUiTest(1700, 1000) {
                setContent { App(controller, "ws://127.0.0.1:$port/ws", null) }
                dismissSetupWizard(controller)
                // The window asks for the setting after `ready`; the daemon's default is 30.
                waitUntil(timeoutMillis = 20_000) { controller.state.value.keepAliveMinutes == 30 }

                onNodeWithContentDescription("Configuration").performClick()
                onNodeWithTag(CONFIG_TAB_TAG_PREFIX + ConfigTab.GENERAL.name).performClick()
                onNodeWithText(LifecycleSetting.TITLE).assertExists()
                onNodeWithTag(KEEP_ALIVE_FIELD_TAG).assertTextContains(LifecycleSetting.label(30))

                // Off: the daemon stores 0 and answers; the field shows the daemon's answer.
                onNodeWithTag(KEEP_ALIVE_FIELD_TAG).performScrollTo().performClick()
                onNodeWithTag(KEEP_ALIVE_OPTION_TAG_PREFIX + 0).performClick()
                waitUntil(timeoutMillis = 10_000) { controller.state.value.keepAliveMinutes == 0 }
                assertEquals(0, stored.keepAliveMinutes(), "the change must be stored by the daemon")
                onNodeWithTag(KEEP_ALIVE_FIELD_TAG).assertTextContains("Off (start a process per reply)")

                // Back on with another window: the daemon stores that value too.
                onNodeWithTag(KEEP_ALIVE_FIELD_TAG).performScrollTo().performClick()
                onNodeWithTag(KEEP_ALIVE_OPTION_TAG_PREFIX + 120).performClick()
                waitUntil(timeoutMillis = 10_000) { controller.state.value.keepAliveMinutes == 120 }
                assertEquals(120, stored.keepAliveMinutes())
                onNodeWithTag(KEEP_ALIVE_FIELD_TAG).assertTextContains(LifecycleSetting.label(120))
            }
        } finally {
            scope.cancel()
            daemon.server.stop(0, 200)
            daemon.close()
            dir.deleteRecursively()
        }
    }
}
