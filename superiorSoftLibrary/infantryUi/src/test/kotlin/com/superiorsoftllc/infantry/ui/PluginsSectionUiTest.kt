package com.superiorsoftllc.infantry.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.superiorsoftllc.infantry.ui.voice.plugin.PLUGINS_EMPTY_TAG
import com.superiorsoftllc.infantry.ui.voice.plugin.PLUGIN_ENABLED_TAG_PREFIX
import com.superiorsoftllc.infantry.ui.voice.plugin.PLUGIN_INSTALL_TAG_PREFIX
import com.superiorsoftllc.infantry.ui.voice.plugin.PLUGIN_REMOVE_TAG_PREFIX
import com.superiorsoftllc.infantry.ui.voice.plugin.PLUGIN_STATUS_TAG_PREFIX
import com.superiorsoftllc.infantry.ui.voice.plugin.PluginsSection
import com.superiorsoftllc.infantry.ui.voice.plugin.VoicePluginOutcome
import com.superiorsoftllc.infantry.ui.voice.plugin.VoicePluginRow
import com.superiorsoftllc.infantry.ui.voice.plugin.VoicePlugins
import kotlin.test.Test
import kotlin.test.assertEquals

/** VOICE-PLUGIN-1: Configuration > Plugins drives install, enable and remove through [VoicePlugins]; nothing else downloads. */
@OptIn(ExperimentalTestApi::class)
class PluginsSectionUiTest {
    private class FakePlugins(var failInstall: Boolean = false) : VoicePlugins {
        val calls = java.util.Collections.synchronizedList(ArrayList<String>())
        @Volatile var installed = false
        @Volatile var enabled = false
        override fun rows() = listOf(
            VoicePluginRow("piper-kathleen", "Natural voice", "Offline voice", "CC0", 85_581_931,
                if (installed) "installed" else "missing", installedVersion = if (installed) "1.0.0" else null, enabled = enabled),
        )
        override fun install(id: String, onProgress: (String) -> Unit): VoicePluginOutcome {
            calls += "install $id"
            if (failInstall) return VoicePluginOutcome("failed", "sha256 mismatch")
            installed = true
            return VoicePluginOutcome("installed")
        }
        override fun remove(id: String): VoicePluginOutcome { calls += "remove $id"; installed = false; enabled = false; return VoicePluginOutcome("removed") }
        override fun setEnabled(id: String, enabled: Boolean) { calls += "enable $id $enabled"; this.enabled = enabled }
    }

    private val id = "piper-kathleen"

    @Test fun installEnableAndRemove() = runComposeUiTest {
        val fake = FakePlugins()
        setContent { PluginsSection(fake) }
        waitUntil(timeoutMillis = 5_000) { runCatching { onNodeWithTag(PLUGIN_INSTALL_TAG_PREFIX + id).assertExists() }.isSuccess }
        onNodeWithTag(PLUGIN_INSTALL_TAG_PREFIX + id).assertTextContains("Install (86 MB download)")
        onNodeWithTag(PLUGIN_STATUS_TAG_PREFIX + id).assertTextContains("Not installed")
        assertEquals(emptyList(), fake.calls.toList(), "showing the list downloads nothing")

        onNodeWithTag(PLUGIN_INSTALL_TAG_PREFIX + id).performClick()
        waitUntil(timeoutMillis = 5_000) { runCatching { onNodeWithTag(PLUGIN_ENABLED_TAG_PREFIX + id).assertExists() }.isSuccess }
        onNodeWithTag(PLUGIN_ENABLED_TAG_PREFIX + id).assertIsOff()

        onNodeWithTag(PLUGIN_ENABLED_TAG_PREFIX + id).performClick()
        waitUntil(timeoutMillis = 5_000) { runCatching { onNodeWithTag(PLUGIN_ENABLED_TAG_PREFIX + id).assertIsOn() }.isSuccess }
        onNodeWithTag(PLUGIN_STATUS_TAG_PREFIX + id).assertTextContains("in use", substring = true)

        onNodeWithTag(PLUGIN_REMOVE_TAG_PREFIX + id).performClick()
        waitUntil(timeoutMillis = 5_000) { runCatching { onNodeWithTag(PLUGIN_INSTALL_TAG_PREFIX + id).assertExists() }.isSuccess }
        assertEquals(listOf("install $id", "enable $id true", "remove $id"), fake.calls.toList())
    }

    @Test fun aFailedInstallSaysWhyAndStaysNotInstalled() = runComposeUiTest {
        val fake = FakePlugins(failInstall = true)
        setContent { PluginsSection(fake) }
        waitUntil(timeoutMillis = 5_000) { runCatching { onNodeWithTag(PLUGIN_INSTALL_TAG_PREFIX + id).assertExists() }.isSuccess }
        onNodeWithTag(PLUGIN_INSTALL_TAG_PREFIX + id).performClick()
        waitUntil(timeoutMillis = 5_000) { runCatching { onNodeWithText("Install failed: sha256 mismatch").assertExists() }.isSuccess }
        onNodeWithTag(PLUGIN_STATUS_TAG_PREFIX + id).assertTextContains("Not installed")
    }

    @Test fun nothingOfferedSaysSo() = runComposeUiTest {
        val none = object : VoicePlugins {
            override fun rows() = emptyList<VoicePluginRow>()
            override fun install(id: String, onProgress: (String) -> Unit) = VoicePluginOutcome("failed")
            override fun remove(id: String) = VoicePluginOutcome("failed")
            override fun setEnabled(id: String, enabled: Boolean) {}
        }
        setContent { PluginsSection(none) }
        waitUntil(timeoutMillis = 5_000) { runCatching { onNodeWithTag(PLUGINS_EMPTY_TAG).assertExists() }.isSuccess }
    }
}
