package com.superiorsoftllc.agenthq.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.agenthq.ui.model.AgentScope
import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.model.ConnState
import com.superiorsoftllc.agenthq.ui.model.ConnectionInfo
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * M2-6: the dark header is the window's title bar. With a [WindowChrome] (desktop, undecorated window) it carries
 * the logo + name, the project-picker slot, the connection status and our own minimize / maximize-restore / close;
 * a double-click on its empty area toggles maximize. Without one (Android) it shows no window controls.
 */
@OptIn(ExperimentalTestApi::class)
class TitleBarUiTest {
    /** A fake window: counts the actions and keeps a maximized flag the bar reads back. */
    private class FakeChrome : WindowChrome {
        var maximized by mutableStateOf(false)
        val calls = mutableListOf<String>()
        override val isMaximized: Boolean get() = maximized
        override fun minimize() { calls += "minimize" }
        override fun toggleMaximize() { calls += "toggleMaximize"; maximized = !maximized }
        override fun close() { calls += "close" }
    }

    private val state = AppState(connection = ConnectionInfo(state = ConnState.CONNECTED, url = "ws://127.0.0.1:8787/ws"))

    @Test
    fun theBarCarriesNameLogoProjectSlotStatusAndWindowControls() {
        runDesktopComposeUiTest(1200, 60) {
            setContent { TitleBar(state, AgentScope.GLOBAL, onConnect = {}, onAttention = {}, chrome = FakeChrome()) }
            onNodeWithText("AgentHQ").assertExists()
            onNodeWithTag(TITLE_BAR_LOGO_TAG).assertExists()
            onNodeWithTag(TITLE_BAR_PROJECT_SLOT_TAG).assertExists()
            onNodeWithText("connected", substring = true).assertExists()
            onNodeWithTag(TITLE_BAR_MINIMIZE_TAG).assertExists()
            onNodeWithTag(TITLE_BAR_MAXIMIZE_TAG).assertExists()
            onNodeWithTag(TITLE_BAR_CLOSE_TAG).assertExists()
        }
    }

    @Test
    fun theControlsCallTheWindowActions() {
        val chrome = FakeChrome()
        runDesktopComposeUiTest(1200, 60) {
            setContent { TitleBar(state, AgentScope.GLOBAL, onConnect = {}, onAttention = {}, chrome = chrome) }
            onNodeWithTag(TITLE_BAR_MINIMIZE_TAG).performClick()
            onNodeWithTag(TITLE_BAR_MAXIMIZE_TAG).performClick()
            onNodeWithTag(TITLE_BAR_CLOSE_TAG).performClick()
            waitForIdle()
            assertEquals(listOf("minimize", "toggleMaximize", "close"), chrome.calls)
        }
    }

    @Test
    fun theMaximizeButtonBecomesRestoreWhileMaximized() {
        val chrome = FakeChrome()
        runDesktopComposeUiTest(1200, 60) {
            setContent { TitleBar(state, AgentScope.GLOBAL, onConnect = {}, onAttention = {}, chrome = chrome) }
            onNodeWithContentDescription("Maximize").assertExists()
            onNodeWithTag(TITLE_BAR_MAXIMIZE_TAG).performClick()
            waitForIdle()
            onNodeWithContentDescription("Restore").assertExists()
            onNodeWithContentDescription("Maximize").assertDoesNotExist()
        }
    }

    @Test
    fun doubleClickingTheEmptyBarTogglesMaximize() {
        val chrome = FakeChrome()
        runDesktopComposeUiTest(1200, 60) {
            setContent { TitleBar(state, AgentScope.GLOBAL, onConnect = {}, onAttention = {}, chrome = chrome) }
            onNodeWithTag(TITLE_BAR_DRAG_TAG).performMouseInput { doubleClick() }
            waitForIdle()
            assertEquals(listOf("toggleMaximize"), chrome.calls)
        }
    }

    @Test
    fun withoutAWindowThereAreNoWindowControls() {
        runDesktopComposeUiTest(1200, 60) {
            setContent { TitleBar(state, AgentScope.GLOBAL, onConnect = {}, onAttention = {}, chrome = null) }
            onNodeWithText("AgentHQ").assertExists()
            onNodeWithTag(TITLE_BAR_MINIMIZE_TAG).assertDoesNotExist()
            onNodeWithTag(TITLE_BAR_MAXIMIZE_TAG).assertDoesNotExist()
            onNodeWithTag(TITLE_BAR_CLOSE_TAG).assertDoesNotExist()
        }
    }

    @Test
    fun screenshotOfTheTitleBar() {
        runDesktopComposeUiTest(1200, 37) {
            setContent {
                Column(Modifier.fillMaxSize().background(Palette.Bg).testTag("shot")) {
                    TitleBar(state, AgentScope.GLOBAL, onConnect = {}, onAttention = {}, chrome = FakeChrome())
                }
            }
            waitForIdle()
            val out = File("build/reports/m2-6/title-bar.png").apply { parentFile.mkdirs() }
            ImageIO.write(onNodeWithTag("shot").captureToImage().toAwtImage(), "png", out)
            println("M2-6 screenshot: ${out.absolutePath}")
        }
    }
}
