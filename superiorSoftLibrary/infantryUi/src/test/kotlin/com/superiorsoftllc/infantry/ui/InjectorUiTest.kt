package com.superiorsoftllc.infantry.ui

import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.superiorsoftllc.infantry.plugin.api.InjectDeclaration
import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.plugin.api.ShellOverrideDeclaration
import com.superiorsoftllc.infantry.plugin.api.Tier
import com.superiorsoftllc.infantry.plugin.host.AcceptedShellOverride
import com.superiorsoftllc.infantry.plugins.core.CoreModules
import com.superiorsoftllc.infantry.ui.block.layout.Block
import com.superiorsoftllc.infantry.ui.block.layout.BlockInjection
import com.superiorsoftllc.infantry.ui.block.layout.BlockOverride
import com.superiorsoftllc.infantry.ui.block.layout.OverrideModes
import com.superiorsoftllc.infantry.ui.model.CompactScreen
import com.superiorsoftllc.infantry.ui.model.ConnState
import com.superiorsoftllc.infantry.ui.model.ConnectionInfo
import com.superiorsoftllc.infantry.ui.net.Controller
import com.superiorsoftllc.infantry.ui.net.DaemonClient
import com.superiorsoftllc.infantry.ui.plugin.InfantryModule
import com.superiorsoftllc.infantry.ui.plugin.TabFactory
import com.superiorsoftllc.infantry.ui.plugin.UiPlugin
import com.superiorsoftllc.infantry.ui.plugin.UiRegistrar
import com.superiorsoftllc.infantry.ui.plugin.NoPluginServices
import com.superiorsoftllc.infantry.ui.plugin.registerModule
import com.superiorsoftllc.infantry.ui.protocol.Envelope
import com.superiorsoftllc.infantry.ui.shell.blocks.CoreShell
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
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * BLOCKS-INJECTORS J2: module.json `inject` entries, bound by [TabFactory] and through the trust rule, draw among a
 * node's children in the real window; refusals are `problems` lines (Settings > Plugins and the `[plugins]` log).
 */
@OptIn(ExperimentalTestApi::class)
class InjectorUiTest {
    /** One `inject` entry and the button it draws (tag `<name>`, counting its clicks into [clicks]). */
    private class Inj(val into: String, val name: String, val at: String = "last", val order: Int = 0)

    private val clicks = mutableListOf<String>()

    private fun button(name: String) = BlockInjection { scope -> Text(name, scope.modifier.testTag(name).clickable { clicks += name }) }

    /**
     * The sample: a module that injects through its [UiPlugin] (`UiRegistrar.inject`) the way a jar module does. Its
     * module.json would read `"inject": [{"into": "<id>", "name": "<name>", "at": "<at>", "order": 0}]`.
     */
    private fun module(name: String, tier: Tier, vararg injs: Inj, shell: List<ShellOverrideDeclaration> = emptyList(), overrides: Map<String, BlockOverride> = emptyMap()): InfantryModule {
        val manifest = ModuleManifest(name, "1.0.0", author = "Acme", license = "NOASSERTION", shell = shell, inject = injs.map { InjectDeclaration(it.into, it.name, it.at, it.order) })
        val m = registerModule(manifest, tier, NoPluginServices, object : UiPlugin {
            override fun register(registrar: UiRegistrar) {
                injs.forEach { registrar.inject(it.name, button(it.name)) }
                overrides.forEach { (n, o) -> registrar.shell(n, o) }
            }
        })
        return m
    }

    private fun factory(vararg modules: InfantryModule, allowInject: Map<String, List<String>> = emptyMap(), shellOverrides: List<AcceptedShellOverride> = emptyList()) =
        TabFactory(CoreModules.all + com.superiorsoftllc.infantry.plugins.official.chat.InfantryChat + com.superiorsoftllc.infantry.plugins.official.chatfeed.InfantryChatFeed + com.superiorsoftllc.infantry.plugins.official.header.InfantryHeader + com.superiorsoftllc.infantry.plugins.official.InfantryAgents + com.superiorsoftllc.infantry.plugins.official.attention.InfantryAttention + modules, shellOverrides = shellOverrides, allowInject = { allowInject[it].orEmpty() })

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

    private fun withWindow(tabs: TabFactory, width: Int = 1400, body: ComposeUiTest.(Controller) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val link = NoDialLink(scope)
            val controller = Controller(scope, link)
            runDesktopComposeUiTest(width, 860) {
                setContent { App(controller, "ws://127.0.0.1:1/ws", null, tabs = tabs) }
                link.push(Envelope(Envelope.READY, "contract=1.115.0 provider=stub instance=i1"))
                waitUntil(timeoutMillis = 10_000) { controller.state.value.connection.isLive }
                waitForIdle()
                body(controller)
            }
        } finally {
            scope.cancel()
        }
    }

    private fun ComposeUiTest.left(tag: String) = onNodeWithTag(tag).getBoundsInRoot().left

    private fun Block.find(id: String): Block? = if (this.id == id) this else children.firstNotNullOfOrNull { it.find(id) }

    @Test fun `with no injection the window draws the core tree itself`() {
        factory().let { assertSame(it.baseRoot, it.shellRoot) }
        factory(module("Acme_Idle", Tier.COMMUNITY)).let { assertSame(it.baseRoot, it.shellRoot) }
    }

    @Test fun `a core module injects a button first and last into the header and both draw and click`() {
        val tabs = factory(module("Infantry_Sample", Tier.CORE, Inj("infantry.shell.header", "first", "first"), Inj("infantry.shell#infantry.shell.header", "last")))
        assertEquals(emptyList(), tabs.problems)
        assertNotSame(tabs.baseRoot, tabs.shellRoot)
        val header = tabs.shellRoot.find("infantry.shell.header")!!
        assertEquals(listOf("Infantry_Sample.first", "infantry.shell.header.lead"), header.children.take(2).map { it.id })
        assertEquals("Infantry_Sample.last", header.children.last().id)
        withWindow(tabs) {
            // first at the bar's left edge, before the left toggle; last at its right end, after the window controls.
            assertTrue(left("first") < 40.dp, "first at ${left("first")}")
            assertTrue(left("last") > 1200.dp, "last at ${left("last")}")
            onNodeWithTag("first").performClick()
            onNodeWithTag("last").performClick()
            waitForIdle()
            assertEquals(listOf("first", "last"), clicks)
        }
    }

    @Test fun `two modules on one anchor draw by order, then load order, then name`() {
        val anchor = "after:infantry.shell.header.attention"
        val tabs = factory(
            module("Infantry_A", Tier.CORE, Inj("infantry.shell.header", "a1", anchor, order = 1), Inj("infantry.shell.header", "a0", anchor)),
            module("Infantry_B", Tier.CORE, Inj("infantry.shell.header", "b0", anchor)),
        )
        // a0 and b0 tie on order 0: A loads first, so it goes first and the tie is reported.
        assertEquals(listOf("Infantry_B: injection b0 into infantry.shell.header ($anchor) has the same order 0 as Infantry_A.a0, which goes first"), tabs.problems)
        val ids = tabs.shellRoot.find("infantry.shell.header")!!.children.map { it.id }
        val at = ids.indexOf("infantry.shell.header.attention")
        assertEquals(listOf("Infantry_A.a0", "Infantry_B.b0", "Infantry_A.a1", "infantry.shell.header.toggleRight"), ids.subList(at + 1, at + 5))
        withWindow(tabs) {
            assertTrue(left("a0") < left("b0") && left("b0") < left("a1"), "a0 ${left("a0")} b0 ${left("b0")} a1 ${left("a1")}")
        }
    }

    @Test fun `a community module injects only into what modules_json allowInject lists`() {
        val acme = module("Acme_Pill", Tier.COMMUNITY, Inj("infantry.shell.header", "pill"))
        val refused = factory(acme)
        assertEquals(
            listOf("Acme_Pill: injection pill into infantry.shell.header refused: a community module cannot inject into infantry.shell.header unless modules.json allowInject lists it"),
            refused.problems,
        )
        assertSame(refused.baseRoot, refused.shellRoot)
        withWindow(refused) { onNodeWithTag("pill").assertDoesNotExist() }

        val granted = factory(acme, allowInject = mapOf("Acme_Pill" to listOf("infantry.shell.header")))
        assertEquals(emptyList(), granted.problems)
        withWindow(granted) { onNodeWithTag("pill").assertExists() }
    }

    @Test fun `an injection into a locked node is refused with a line and nothing draws`() {
        val tabs = factory(module("Infantry_Lock", Tier.CORE, Inj("infantry.shell.dock", "fake")))
        assertEquals(listOf("Infantry_Lock: injection fake into infantry.shell.dock (last) refused: infantry.shell.dock is locked"), tabs.problems)
        assertSame(tabs.baseRoot, tabs.shellRoot)
        withWindow(tabs) { onNodeWithTag("fake").assertDoesNotExist() }
    }

    /** SHELL-EMPTY-DROPIN S1: the placeholder containers are open slots in the real window. */
    @Test fun `a module replaces the frame placeholder and only its block draws there, the overlays stay`() {
        val frame = "infantry.shell.frame"
        // A marker in the footer, inside the frame: with no override the placeholder draws its default, the core frame.
        val marker = module("Infantry_Marker", Tier.CORE, Inj("infantry.shell.footer", "marker"))
        withWindow(factory(marker)) { onNodeWithTag("marker").assertExists() }
        val tabs = factory(
            marker,
            module("Infantry_Frame", Tier.CORE, shell = listOf(ShellOverrideDeclaration(frame, "frame")), overrides = mapOf("frame" to BlockOverride { scope, _ -> Text("frame", scope.modifier.testTag("frame")) })),
            shellOverrides = listOf(AcceptedShellOverride("Infantry_Frame", frame, "frame", OverrideModes.REPLACE)),
        )
        assertEquals(listOf("Infantry_Marker: injection marker into infantry.shell.footer not drawn: $frame is replaced by Infantry_Frame"), tabs.problems)
        // The window still starts (its focus target rides on the replacement's modifier) and draws the block, not the core frame.
        withWindow(tabs) {
            onNodeWithTag("frame").assertExists()
            onNodeWithTag("marker").assertDoesNotExist()
        }
    }

    @Test fun `a core module injects into the windowLayer placeholder and it draws`() {
        val tabs = factory(module("Infantry_Layer", Tier.CORE, Inj("infantry.shell.windowLayer", "layer")))
        assertEquals(emptyList(), tabs.problems)
        assertEquals("Infantry_Layer.layer", tabs.shellRoot.find("infantry.shell.windowLayer")!!.children.last().id)
        withWindow(tabs) { onNodeWithTag("layer").assertExists() }
    }

    @Test fun `an injection into the right panel body shows docked and in the compact drawer`() {
        val tabs = factory(module("Infantry_Side", Tier.CORE, Inj("infantry.shell.right.body", "side")))
        assertEquals(emptyList(), tabs.problems)
        withWindow(tabs) { onNodeWithTag("side").assertExists() }
        withWindow(tabs, width = 600) { controller ->
            controller.showCompactScreen(CompactScreen.RIGHT)
            waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag(DRAWER_TAG)).fetchSemanticsNodes().isNotEmpty() }
            onNode(hasTestTag("side") and hasAnyAncestor(hasTestTag(DRAWER_TAG))).assertExists()
        }
    }

    @Test fun `a replaced target draws none of its injections and says so, a replaced anchor keeps its neighbour`() {
        val replaceHeader = ShellOverrideDeclaration("infantry.shell.header", "bar", OverrideModes.REPLACE)
        val tabs = factory(
            module(
                "Infantry_Bar", Tier.CORE, Inj("infantry.shell.header", "gone"),
                shell = listOf(replaceHeader), overrides = mapOf("bar" to BlockOverride { scope, _ -> Text("bar", scope.modifier.testTag("bar")) }),
            ),
            shellOverrides = listOf(AcceptedShellOverride("Infantry_Bar", "infantry.shell.header", "bar", OverrideModes.REPLACE)),
        )
        assertEquals(listOf("Infantry_Bar: injection gone into infantry.shell.header not drawn: infantry.shell.header is replaced by Infantry_Bar"), tabs.problems)
        withWindow(tabs) {
            onNodeWithTag("bar").assertExists()
            onNodeWithTag("gone").assertDoesNotExist()
        }

        val replaceChip = ShellOverrideDeclaration("infantry.shell.header.attention", "chip", OverrideModes.REPLACE)
        val anchored = factory(
            module(
                "Infantry_Chip", Tier.CORE, Inj("infantry.shell.header", "next", "after:infantry.shell.header.attention"),
                shell = listOf(replaceChip), overrides = mapOf("chip" to BlockOverride { scope, _ -> Text("chip", scope.modifier.testTag("chip")) }),
            ),
            shellOverrides = listOf(AcceptedShellOverride("Infantry_Chip", "infantry.shell.header.attention", "chip", OverrideModes.REPLACE)),
        )
        assertEquals(emptyList(), anchored.problems)
        withWindow(anchored) { assertTrue(left("chip") < left("next")) }
    }

    @Test fun `a declared injection with no registration, a registration with no declaration and a bad at are refused`() {
        val manifest = ModuleManifest("Infantry_Odd", "1.0.0", inject = listOf(InjectDeclaration("infantry.shell.header", "missing"), InjectDeclaration("infantry.shell.header", "bad", at = "middle")))
        val m = InfantryModule(manifest, inject = mapOf("bad" to button("bad"), "stray" to button("stray")))
        assertEquals(
            listOf(
                "Infantry_Odd: injection stray is not declared in its module.json; refused",
                "Infantry_Odd: injection missing is declared but not registered; skipped",
                "Infantry_Odd: injection bad into infantry.shell.header has unknown at 'middle' (first, last, before:<id>, after:<id>); skipped",
            ),
            factory(m).problems,
        )
    }
}
