package com.superiorsoftllc.agenthq.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.agenthq.plugin.api.InjectDeclaration
import com.superiorsoftllc.agenthq.plugin.api.ModuleManifest
import com.superiorsoftllc.agenthq.plugin.api.ShellOverrideDeclaration
import com.superiorsoftllc.agenthq.plugin.api.Tier
import com.superiorsoftllc.agenthq.plugin.host.AcceptedShellOverride
import com.superiorsoftllc.agenthq.plugins.core.CoreModules
import com.superiorsoftllc.agenthq.ui.block.layout.BlockInjection
import com.superiorsoftllc.agenthq.ui.block.layout.BlockOverride
import com.superiorsoftllc.agenthq.ui.block.layout.OverrideModes
import com.superiorsoftllc.agenthq.ui.model.ConnState
import com.superiorsoftllc.agenthq.ui.model.ConnectionInfo
import com.superiorsoftllc.agenthq.ui.net.Controller
import com.superiorsoftllc.agenthq.ui.net.DaemonClient
import com.superiorsoftllc.agenthq.ui.plugin.AgentHqModule
import com.superiorsoftllc.agenthq.ui.plugin.NoPluginServices
import com.superiorsoftllc.agenthq.ui.plugin.TabFactory
import com.superiorsoftllc.agenthq.ui.plugin.UiPlugin
import com.superiorsoftllc.agenthq.ui.plugin.UiRegistrar
import com.superiorsoftllc.agenthq.ui.plugin.registerModule
import com.superiorsoftllc.agenthq.ui.protocol.Envelope
import com.superiorsoftllc.agenthq.ui.shell.LocalShellContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * S4d (docs/SHELL-LEAKS.md): the window's Shell (state, controller) is core tier. [coreShell] is null in a community
 * module's composition (an injection, a wrap) and set in a core module's; a community wrap's inner node, the app UI's,
 * still draws with it. Both kinds read the ShellContext.
 */
@OptIn(ExperimentalTestApi::class)
class CoreTierGateUiTest {
    /** What each probe saw: its name to (coreShell set, ShellContext set). */
    private val seen = linkedMapOf<String, Pair<Boolean, Boolean>>()

    private fun probe(name: String) = BlockInjection { scope ->
        seen[name] = (coreShell != null) to (LocalShellContext.current != null)
        Text(name, scope.modifier.testTag(name))
    }

    private fun module(name: String, tier: Tier, inject: String? = null, wrap: String? = null, order: Int = 0): AgentHqModule {
        val manifest = ModuleManifest(
            name, "1.0.0", author = "Acme", license = "NOASSERTION",
            shell = listOfNotNull(wrap?.let { ShellOverrideDeclaration(it, "frame", OverrideModes.WRAP) }),
            inject = listOfNotNull(inject?.let { InjectDeclaration(it, "probe", "last", order) }),
        )
        return registerModule(manifest, tier, NoPluginServices, object : UiPlugin {
            override fun register(registrar: UiRegistrar) {
                if (inject != null) registrar.inject("probe", probe("$name.probe"))
                if (wrap != null) registrar.shell("frame", BlockOverride { scope, inner ->
                    seen["$name.wrap"] = (coreShell != null) to (LocalShellContext.current != null)
                    Box(Modifier.testTag("$name.wrap")) { inner!!.Render(scope) }
                })
            }
        })
    }

    /** A fake link that never dials (frontend/ui-test-flake-causes). */
    private class NoDialLink(scope: CoroutineScope) : DaemonClient(scope) {
        private val incoming = MutableSharedFlow<Envelope>(extraBufferCapacity = 64)
        override val frames: SharedFlow<Envelope> get() = incoming
        override val info: StateFlow<ConnectionInfo> = MutableStateFlow(ConnectionInfo(state = ConnState.CONNECTED))
        override fun connect(url: String, token: String?) {}
        override fun disconnect() {}
        override suspend fun send(envelope: Envelope): Boolean = true
        fun push(e: Envelope) {
            val deadline = System.currentTimeMillis() + 10_000
            while (incoming.subscriptionCount.value == 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
            check(incoming.tryEmit(e)) { "frame buffer full" }
        }
    }

    private fun withWindow(tabs: TabFactory, body: ComposeUiTest.() -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val link = NoDialLink(scope)
            val controller = Controller(scope, link)
            runDesktopComposeUiTest(1400, 860) {
                setContent { App(controller, "ws://127.0.0.1:1/ws", null, tabs = tabs) }
                link.push(Envelope(Envelope.READY, "contract=1.115.0 provider=stub instance=i1"))
                waitUntil(timeoutMillis = 10_000) { controller.state.value.connection.isLive }
                waitForIdle()
                body()
            }
        } finally {
            scope.cancel()
        }
    }

    @Test fun `a community module's block sees no core shell, a core module's does, a wrapped app UI node still draws`() {
        val center = "infantry.shell.center"
        val acme = module("Acme_Probe", Tier.COMMUNITY, inject = "infantry.shell.header", wrap = center)
        val core = module("Infantry_Probe", Tier.CORE, inject = "infantry.shell.header", order = 1)
        val tabs = TabFactory(
            CoreModules.all + acme + core,
            shellOverrides = listOf(AcceptedShellOverride("Acme_Probe", center, "frame", OverrideModes.WRAP)),
            allowInject = { if (it == "Acme_Probe") listOf("infantry.shell.header") else emptyList() },
        )
        assertEquals(emptyList(), tabs.problems)
        withWindow(tabs) {
            onNodeWithTag("Acme_Probe.probe").assertExists()
            onNodeWithTag("Infantry_Probe.probe").assertExists()
            onNodeWithTag("Acme_Probe.wrap").assertExists()
            assertEquals(false to true, seen["Acme_Probe.probe"])
            assertEquals(false to true, seen["Acme_Probe.wrap"])
            assertEquals(true to true, seen["Infantry_Probe.probe"])
        }
    }
}
