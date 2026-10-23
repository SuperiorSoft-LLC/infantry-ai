package com.superiorsoftllc.agenthq.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.agenthq.ui.model.ConnState
import com.superiorsoftllc.agenthq.ui.model.ConnectionInfo
import com.superiorsoftllc.agenthq.ui.net.Controller
import com.superiorsoftllc.agenthq.ui.net.DaemonClient
import com.superiorsoftllc.agenthq.ui.shell.LocalPlatformHost
import com.superiorsoftllc.agenthq.ui.shell.PlatformHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * MOBILE-CONNCHIP-1: the title bar's connection chip calls [PlatformHost.onChangeConnection] when the host sets one
 * (Android routes it to its ConnectScreen) and opens the shared ConnectDialog when it does not (desktop). No daemon:
 * the controller dials a closed port and the chip is clicked whatever its state.
 */
@OptIn(ExperimentalTestApi::class)
class ConnChipRoutingUiTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val noDaemon = "ws://127.0.0.1:1/ws"

    @AfterTest fun tearDown() = scope.cancel()

    private class Host(override val onChangeConnection: (() -> Unit)?) : PlatformHost {
        override val defaultDaemonUrl = "ws://127.0.0.1:1/ws"
        override fun setting(key: String): String? = null
        override fun readTextFile(path: String): String? = null
    }

    @Test fun `with a hook set the chip calls it and opens no dialog`() {
        var calls = 0
        val host = Host { calls++ }
        runDesktopComposeUiTest(1400, 900) {
            setContent { CompositionLocalProvider(LocalPlatformHost provides host) { App(Controller(scope), noDaemon, null) } }
            onNodeWithTag(TITLE_BAR_CONNECTION_CHIP_TAG).performClick()
            waitForIdle()
            assertEquals(1, calls, "ConnChip must invoke PlatformHost.onChangeConnection when it is set")
            onNodeWithText("Connect to a daemon").assertDoesNotExist()
        }
    }

    @Test fun `without a hook the chip opens the ConnectDialog`() {
        runDesktopComposeUiTest(1400, 900) {
            setContent { CompositionLocalProvider(LocalPlatformHost provides Host(null)) { App(Controller(scope), noDaemon, null) } }
            onNodeWithText("Connect to a daemon").assertDoesNotExist()
            onNodeWithTag(TITLE_BAR_CONNECTION_CHIP_TAG).performClick()
            waitForIdle()
            onNodeWithText("Connect to a daemon").assertExists()
        }
    }

    /** A link whose daemon rejected the token (HTTP 401/403), as DaemonClient reports it mid-session. */
    private class RefusedLink(scope: CoroutineScope) : DaemonClient(scope) {
        override val info: StateFlow<ConnectionInfo> =
            MutableStateFlow(ConnectionInfo(state = ConnState.REFUSED, detail = "daemon refused the connection (HTTP 401)"))
    }

    // MOBILE-REFUSED-1: a token rejection on Android must go to the host's ConnectScreen (which saves), never the
    // in-memory ConnectDialog whose edits are lost on relaunch.
    @Test fun `a refused link calls the hook when set and opens no dialog`() {
        var calls = 0
        val host = Host { calls++ }
        runDesktopComposeUiTest(1400, 900) {
            setContent { CompositionLocalProvider(LocalPlatformHost provides host) { App(Controller(scope, RefusedLink(scope)), noDaemon, null) } }
            waitForIdle()
            assertEquals(1, calls, "REFUSED must invoke PlatformHost.onChangeConnection when it is set")
            onNodeWithText("Connect to a daemon").assertDoesNotExist()
        }
    }
}
