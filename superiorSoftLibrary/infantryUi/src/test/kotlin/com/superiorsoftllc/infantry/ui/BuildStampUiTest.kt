package com.superiorsoftllc.infantry.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.infantry.ui.model.UiFlags
import com.superiorsoftllc.infantry.ui.net.Controller
import com.superiorsoftllc.infantry.ui.shell.LocalPlatformHost
import com.superiorsoftllc.infantry.ui.shell.PlatformHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * BUILD-STAMP-1: the owner can see which build they run. The footer (bottom right, every screen) names the app's build and turns
 * into a plain warning when the connected daemon is another build; Configuration > About lists both with their date and commit.
 */
@OptIn(ExperimentalTestApi::class)
class BuildStampUiTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val noDaemon = "ws://127.0.0.1:1/ws"
    private val appBuild = "1.0.1+20261023.1432.e85b223"

    @AfterTest fun tearDown() = scope.cancel()

    private class Host(override val appBuild: String?) : PlatformHost {
        override val defaultDaemonUrl = "ws://127.0.0.1:1/ws"
        override fun setting(key: String): String? = null
        override fun readTextFile(path: String): String? = null
    }

    @Test fun `About shows both builds with date and commit, and no warning when they match`() = runDesktopComposeUiTest(900, 900) {
        setContent {
            ConfigDialog(UiFlags(), onFlags = {}, onDismiss = {}, about = AboutEntry(appBuild, "1.0.1+20261023.1500.e85b223", daemonConnected = true))
        }
        onNodeWithTag(CONFIG_TAB_TAG_PREFIX + ConfigTab.ABOUT.name).performClick()
        onNodeWithTag(ABOUT_APP_BUILD_TAG).assertTextEquals("App: $appBuild")
        onNodeWithTag(ABOUT_DAEMON_BUILD_TAG).assertTextEquals("Daemon: 1.0.1+20261023.1500.e85b223")
        onNodeWithTag(ABOUT_BUILD_MISMATCH_TAG).assertDoesNotExist()
    }

    @Test fun `About says plainly when the daemon is another build`() = runDesktopComposeUiTest(900, 900) {
        setContent {
            ConfigDialog(UiFlags(), onFlags = {}, onDismiss = {}, about = AboutEntry(appBuild, "1.0.0+20261001.0800.0123abc.dirty", daemonConnected = true))
        }
        onNodeWithTag(CONFIG_TAB_TAG_PREFIX + ConfigTab.ABOUT.name).performClick()
        onNodeWithTag(ABOUT_BUILD_MISMATCH_TAG).assertTextContains("Different builds", substring = true)
        onNodeWithTag(ABOUT_BUILD_MISMATCH_TAG).assertTextContains("1.0.0+20261001.0800.0123abc.dirty", substring = true)
    }

    @Test fun `About with an unstamped app and no daemon says so`() = runDesktopComposeUiTest(900, 900) {
        setContent { ConfigDialog(UiFlags(), onFlags = {}, onDismiss = {}, about = AboutEntry(null, null, daemonConnected = false)) }
        onNodeWithTag(CONFIG_TAB_TAG_PREFIX + ConfigTab.ABOUT.name).performClick()
        onNodeWithTag(ABOUT_APP_BUILD_TAG).assertTextEquals("App: not stamped (a development run)")
        onNodeWithTag(ABOUT_DAEMON_BUILD_TAG).assertTextEquals("Daemon: not connected")
    }

    @Test fun `the footer names the app build, even with no daemon, and opens About`() = runDesktopComposeUiTest(1400, 900) {
        setContent { CompositionLocalProvider(LocalPlatformHost provides Host(appBuild)) { App(Controller(scope), noDaemon, null) } }
        onNodeWithTag(FOOTER_BUILD_TAG).assertTextEquals("build $appBuild")
        onNodeWithTag(FOOTER_BUILD_TAG).performClick()
        waitForIdle()
        onNodeWithTag(ABOUT_APP_BUILD_TAG).assertTextEquals("App: $appBuild")
    }

    // App always dials its URL on start (a dead port here), so the live-daemon cases render the footer piece the status bar uses.
    @Test fun `the footer warns when the connected daemon is another build`() = runDesktopComposeUiTest(900, 200) {
        setContent { FooterBuild(appBuild, "1.0.0+20261001.0800.0123abc") }
        onNodeWithTag(FOOTER_BUILD_TAG).assertTextEquals("app $appBuild ≠ daemon 1.0.0+20261001.0800.0123abc")
    }

    @Test fun `the footer stays quiet for the same build or an older daemon that sends no build`() = runDesktopComposeUiTest(900, 200) {
        var daemon by androidx.compose.runtime.mutableStateOf<String?>("1.0.1+20261023.1600.e85b223")
        setContent { FooterBuild(appBuild, daemon) }
        onNodeWithTag(FOOTER_BUILD_TAG).assertTextEquals("build $appBuild")
        daemon = null
        waitForIdle()
        onNodeWithTag(FOOTER_BUILD_TAG).assertTextEquals("build $appBuild")
    }
}
