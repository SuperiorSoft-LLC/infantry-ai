package com.superiorsoftllc.agenthq.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.agenthq.plugins.core.CoreModules
import com.superiorsoftllc.agenthq.ui.net.Controller
import com.superiorsoftllc.agenthq.ui.plugin.TabFactory
import com.superiorsoftllc.agenthq.ui.shell.AgentHqShell
import com.superiorsoftllc.agenthq.ui.shell.DropCheck
import com.superiorsoftllc.agenthq.ui.shell.DropPreview
import com.superiorsoftllc.agenthq.ui.shell.LocalPlatformHost
import com.superiorsoftllc.agenthq.ui.shell.LocalSoftRestart
import com.superiorsoftllc.agenthq.ui.shell.NodeAsk
import com.superiorsoftllc.agenthq.ui.shell.PlatformHost
import com.superiorsoftllc.agenthq.ui.shell.PluginDropIn
import com.superiorsoftllc.agenthq.ui.shell.blocks.DROP_BOX_CHOOSE_TAG
import com.superiorsoftllc.agenthq.ui.shell.blocks.DROP_BOX_CHECKING_TAG
import com.superiorsoftllc.agenthq.ui.shell.blocks.DROP_BOX_ERROR_TAG
import com.superiorsoftllc.agenthq.ui.shell.blocks.DROP_BOX_LOCKED_TAG
import com.superiorsoftllc.agenthq.ui.shell.blocks.DROP_BOX_TAG
import com.superiorsoftllc.agenthq.ui.shell.blocks.DROP_CONSENT_CANCEL_TAG
import com.superiorsoftllc.agenthq.ui.shell.blocks.DROP_CONSENT_INSTALL_TAG
import com.superiorsoftllc.agenthq.ui.shell.blocks.DROP_CONSENT_NODE_TAG_PREFIX
import com.superiorsoftllc.agenthq.ui.shell.blocks.DROP_CONSENT_LIST_TAG
import com.superiorsoftllc.agenthq.ui.shell.blocks.DROP_CONSENT_SIGNER_TAG
import com.superiorsoftllc.agenthq.ui.shell.blocks.DROP_CONSENT_TIER_TAG
import com.superiorsoftllc.agenthq.ui.shell.blocks.DROP_CONSENT_TAG
import com.superiorsoftllc.agenthq.ui.shell.blocks.DROP_CONSENT_TICK_TAG_PREFIX
import com.superiorsoftllc.agenthq.ui.shell.blocks.SAFE_MODE_BANNER_TAG
import com.superiorsoftllc.agenthq.ui.shell.blocks.SAFE_MODE_REMOVE_TAG_PREFIX
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * S6 (drop-in path): the empty shell's drop box, the consent dialog, the soft restart, safe mode and the demo lock, in
 * the real window (App) with a fake [PluginDropIn]. The real install, grants and safe-mode loading are DesktopDropInTest's.
 */
@OptIn(ExperimentalTestApi::class)
class DropInUiTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val noDaemon = "ws://127.0.0.1:1/ws"

    @AfterTest fun tearDown() = scope.cancel()

    private val community = DropPreview(
        path = "C:/drop/Acme_Panel.ihq", name = "Acme_Panel", version = "1.2.0", author = "Acme", signer = "CN=acme", core = false,
        asks = listOf(
            NodeAsk("infantry.shell.left", NodeAsk.Kind.OVERRIDE, locked = false),
            NodeAsk("infantry.shell.frame", NodeAsk.Kind.INJECT, locked = false),
            NodeAsk("infantry.shell.connect", NodeAsk.Kind.OVERRIDE, locked = true),
        ),
    )

    private class FakeDropIn(
        override val demoLock: Boolean = false,
        override val safeMode: Boolean = false,
        var check: DropCheck = DropCheck.Refused("nothing"),
        val onDisk: MutableList<String> = mutableListOf(),
    ) : PluginDropIn {
        val installs = mutableListOf<Pair<DropPreview, Set<String>>>()
        val removed = mutableListOf<String>()
        override val canPick = true
        var gate: java.util.concurrent.CountDownLatch? = null
        var inspectThread: Thread? = null
        override fun inspect(path: String): DropCheck { inspectThread = Thread.currentThread(); gate?.await(); return check }
        override fun install(preview: DropPreview, granted: Set<String>): String? { installs += preview to granted; return null }
        override fun installed(): List<String> = onDisk.toList()
        override fun remove(name: String): String? { removed += name; onDisk -= name; return null }
        override fun pickPackage(): String = "C:/drop/picked.ihq"
        override fun droppedPaths(event: DragAndDropEvent): List<String> = emptyList()
    }

    private class Host(override val dropIn: PluginDropIn?, private val first: TabFactory = TabFactory(emptyList()), private val next: TabFactory = first) : PlatformHost {
        var reloads = 0
        override val defaultDaemonUrl = "ws://127.0.0.1:1/ws"
        override fun setting(key: String): String? = null
        override fun readTextFile(path: String): String? = null
        override val tabFactory: TabFactory get() = first
        override fun reloadTabs(): TabFactory { reloads++; return next }
    }

    private fun androidx.compose.ui.test.ComposeUiTest.window(host: Host, tabs: TabFactory, restart: () -> Unit = {}) = setContent {
        CompositionLocalProvider(LocalPlatformHost provides host, LocalSoftRestart provides restart) { App(Controller(scope), noDaemon, null, tabs = tabs) }
    }

    @Test fun `the empty shell draws the drop box, the app UI does not`() = runDesktopComposeUiTest(1200, 800) {
        val host = Host(FakeDropIn())
        var full by mutableStateOf(false)
        setContent {
            CompositionLocalProvider(LocalPlatformHost provides host) {
                key(full) { App(Controller(scope), noDaemon, null, tabs = if (full) CoreModules.factory else TabFactory(emptyList())) }
            }
        }
        onNodeWithTag(DROP_BOX_TAG).assertExists()
        full = true
        waitForIdle()
        onNodeWithTag(DROP_BOX_TAG).assertDoesNotExist()
    }

    @Test fun `no drop box where the host cannot load modules (web, Android)`() = runDesktopComposeUiTest(1200, 800) {
        window(Host(dropIn = null), TabFactory(emptyList()))
        onNodeWithTag(DROP_BOX_TAG).assertDoesNotExist()
    }

    @Test fun `a refused file says why and opens no dialog`() = runDesktopComposeUiTest(1200, 800) {
        val drop = FakeDropIn(check = DropCheck.Refused("picked.ihq was refused: the package is not signed"))
        window(Host(drop), TabFactory(emptyList()))
        onNodeWithTag(DROP_BOX_CHOOSE_TAG).performClick()
        waitUntil { onAllNodesWithTag(DROP_BOX_CHECKING_TAG).fetchSemanticsNodes().isEmpty() } // S7: the check runs off the UI thread
        onNodeWithTag(DROP_BOX_ERROR_TAG).assertExists()
        onNodeWithTag(DROP_CONSENT_TAG).assertDoesNotExist()
    }

    @Test fun `Cancel in the consent dialog installs nothing`() = runDesktopComposeUiTest(1200, 800) {
        val drop = FakeDropIn(check = DropCheck.Ready(community))
        var restarts = 0
        window(Host(drop), TabFactory(emptyList())) { restarts++ }
        onNodeWithTag(DROP_BOX_CHOOSE_TAG).performClick()
        waitUntil { onAllNodesWithTag(DROP_BOX_CHECKING_TAG).fetchSemanticsNodes().isEmpty() } // S7: the check runs off the UI thread
        onNodeWithTag(DROP_CONSENT_TAG).assertExists()
        onNodeWithTag(DROP_CONSENT_CANCEL_TAG).performClick()
        waitForIdle()
        onNodeWithTag(DROP_CONSENT_TAG).assertDoesNotExist()
        assertEquals(emptyList(), drop.installs)
        assertEquals(0, restarts)
    }

    @Test fun `Install grants the ticked nodes only, never a locked one, then soft-restarts`() = runDesktopComposeUiTest(1200, 800) {
        val drop = FakeDropIn(check = DropCheck.Ready(community))
        var restarts = 0
        window(Host(drop), TabFactory(emptyList())) { restarts++ }
        onNodeWithTag(DROP_BOX_CHOOSE_TAG).performClick()
        waitUntil { onAllNodesWithTag(DROP_BOX_CHECKING_TAG).fetchSemanticsNodes().isEmpty() } // S7: the check runs off the UI thread
        // Every node it asks for is listed; the locked one has no box to tick.
        listOf("infantry.shell.left", "infantry.shell.frame", "infantry.shell.connect").forEach { onNodeWithTag(DROP_CONSENT_NODE_TAG_PREFIX + it, useUnmergedTree = true).assertExists() }
        // Untick the injection into the frame: only the left panel's override is granted.
        onNodeWithTag(DROP_CONSENT_TICK_TAG_PREFIX + "infantry.shell.connect", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag(DROP_CONSENT_TICK_TAG_PREFIX + "infantry.shell.frame", useUnmergedTree = true).performClick()
        onNodeWithTag(DROP_CONSENT_INSTALL_TAG).performClick()
        waitUntil { onAllNodesWithTag(DROP_CONSENT_TAG).fetchSemanticsNodes().isEmpty() }
        waitForIdle()
        assertEquals(listOf(community to setOf("infantry.shell.left")), drop.installs)
        assertEquals(1, restarts)
        onNodeWithTag(DROP_CONSENT_TAG).assertDoesNotExist()
    }

    @Test fun `the soft restart composes the window again with the new module set, in the same process`() = runDesktopComposeUiTest(1200, 800) {
        val app = DropPreview("C:/drop/App_UI.ihq", "Infantry_AppUi", "1.0.0", "Infantry AI", "CN=infantry", core = true,
            asks = listOf(NodeAsk("infantry.shell.center", NodeAsk.Kind.FILL, locked = false)))
        val drop = FakeDropIn(check = DropCheck.Ready(app))
        val host = Host(drop, first = TabFactory(emptyList()), next = CoreModules.factory)
        setContent { AgentHqShell(host, Controller(scope)) }
        onNodeWithTag(DROP_BOX_TAG).assertExists()
        onNodeWithTag(DROP_BOX_CHOOSE_TAG).performClick()
        waitUntil { onAllNodesWithTag(DROP_BOX_CHECKING_TAG).fetchSemanticsNodes().isEmpty() } // S7: the check runs off the UI thread
        onNodeWithTag(DROP_CONSENT_INSTALL_TAG).performClick()
        waitUntil { onAllNodesWithTag(DROP_CONSENT_TAG).fetchSemanticsNodes().isEmpty() }
        waitForIdle()
        assertEquals(1, host.reloads)
        assertEquals(1, drop.installs.size)
        onNodeWithTag(DROP_BOX_TAG).assertDoesNotExist()
    }

    @Test fun `under the demo lock the drop box is disabled and says why`() = runDesktopComposeUiTest(1200, 800) {
        val drop = FakeDropIn(demoLock = true, check = DropCheck.Ready(community))
        window(Host(drop), TabFactory(emptyList()))
        onNodeWithTag(DROP_BOX_LOCKED_TAG).assertExists()
        onNodeWithTag(DROP_BOX_CHOOSE_TAG).assertIsNotEnabled()
        onNodeWithTag(DROP_CONSENT_TAG).assertDoesNotExist()
    }

    @Test fun `safe mode shows its banner and removes an installed plugin`() = runDesktopComposeUiTest(1200, 800) {
        val drop = FakeDropIn(safeMode = true, onDisk = mutableListOf("Acme_Bad"))
        window(Host(drop), TabFactory(emptyList()))
        onNodeWithTag(SAFE_MODE_BANNER_TAG).assertExists()
        onNodeWithTag(SAFE_MODE_REMOVE_TAG_PREFIX + "Acme_Bad").performClick()
        waitForIdle()
        assertEquals(listOf("Acme_Bad"), drop.removed)
        assertTrue(onAllNodesWithTag(SAFE_MODE_REMOVE_TAG_PREFIX + "Acme_Bad").fetchSemanticsNodes().isEmpty())
    }

    @Test fun `outside safe mode there is no banner`() = runDesktopComposeUiTest(1200, 800) {
        window(Host(FakeDropIn()), CoreModules.factory)
        onNodeWithTag(SAFE_MODE_BANNER_TAG).assertDoesNotExist()
    }

    @Test fun `the file is checked off the UI thread, with a Checking state until it is done`() = runDesktopComposeUiTest(1200, 800) {
        val drop = FakeDropIn(check = DropCheck.Ready(community)).also { it.gate = java.util.concurrent.CountDownLatch(1) }
        window(Host(drop), TabFactory(emptyList()))
        onNodeWithTag(DROP_BOX_CHOOSE_TAG).performClick()
        waitUntil { onAllNodesWithTag(DROP_BOX_CHECKING_TAG).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(DROP_BOX_CHOOSE_TAG).assertIsNotEnabled()
        onNodeWithTag(DROP_CONSENT_TAG).assertDoesNotExist()
        drop.gate!!.countDown()
        waitUntil { onAllNodesWithTag(DROP_CONSENT_TAG).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(drop.inspectThread !== Thread.currentThread(), "inspect ran on a background thread")
    }

    @Test fun `the tier sentence names the signer, not the Infantry key`() = runDesktopComposeUiTest(1200, 800) {
        val acme = community.copy(core = true, signer = "CN=Acme Ltd, O=Acme, C=US", asks = emptyList())
        window(Host(FakeDropIn(check = DropCheck.Ready(acme))), TabFactory(emptyList()))
        onNodeWithTag(DROP_BOX_CHOOSE_TAG).performClick()
        waitUntil { onAllNodesWithTag(DROP_CONSENT_TAG).fetchSemanticsNodes().isNotEmpty() }
        val tier = onNodeWithTag(DROP_CONSENT_TIER_TAG, useUnmergedTree = true).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString { it.text }
        assertTrue("Signed by Acme Ltd," in tier, tier)
        assertTrue("Infantry key" !in tier, tier)
    }

    @Test fun `a long list of asked nodes shows a scroll thumb at the right edge`() = runDesktopComposeUiTest(1200, 800) {
        val many = community.copy(asks = (1..30).map { NodeAsk("infantry.shell.node$it", NodeAsk.Kind.OVERRIDE, locked = false) })
        window(Host(FakeDropIn(check = DropCheck.Ready(many))), TabFactory(emptyList()))
        onNodeWithTag(DROP_BOX_CHOOSE_TAG).performClick()
        waitUntil { onAllNodesWithTag(DROP_CONSENT_LIST_TAG).fetchSemanticsNodes().isNotEmpty() }
        waitForIdle()
        val image = onNodeWithTag(DROP_CONSENT_LIST_TAG, useUnmergedTree = true).captureToImage().toPixelMap()
        val thumb = com.superiorsoftllc.agenthq.ui.Palette.Muted.toArgb()
        val edge = (image.width - 4 until image.width).any { x -> (0 until image.height).any { y -> image[x, y].toArgb() == thumb } }
        assertTrue(edge, "a thumb of the muted colour is drawn along the right edge")
    }
}
