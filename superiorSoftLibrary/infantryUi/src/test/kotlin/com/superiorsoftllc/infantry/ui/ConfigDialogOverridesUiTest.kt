package com.superiorsoftllc.infantry.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.plugin.api.ShellOverrideDeclaration
import com.superiorsoftllc.infantry.plugin.api.Tier
import com.superiorsoftllc.infantry.plugin.host.AcceptedShellOverride
import com.superiorsoftllc.infantry.plugins.core.CoreModules
import com.superiorsoftllc.infantry.ui.block.layout.Block
import com.superiorsoftllc.infantry.ui.block.layout.BlockInjection
import com.superiorsoftllc.infantry.ui.block.layout.BlockOverride
import com.superiorsoftllc.infantry.ui.block.layout.BlockResolver
import com.superiorsoftllc.infantry.ui.block.layout.NodeInjection
import com.superiorsoftllc.infantry.ui.block.layout.BlockScope
import com.superiorsoftllc.infantry.ui.block.layout.OverrideBlock
import com.superiorsoftllc.infantry.ui.block.layout.OverrideModes
import com.superiorsoftllc.infantry.ui.model.ConnState
import com.superiorsoftllc.infantry.ui.model.ConnectionInfo
import com.superiorsoftllc.infantry.ui.net.Controller
import com.superiorsoftllc.infantry.ui.net.DaemonClient
import com.superiorsoftllc.infantry.ui.plugin.InfantryModule
import com.superiorsoftllc.infantry.ui.plugin.TabFactory
import com.superiorsoftllc.infantry.ui.protocol.Envelope
import com.superiorsoftllc.infantry.ui.shell.blocks.CoreShell
import com.superiorsoftllc.infantry.ui.block.layout.PlaceholderBlock
import com.superiorsoftllc.infantry.ui.shell.blocks.ShellConfig
import com.superiorsoftllc.infantry.ui.shell.blocks.ShellConfigSettings
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
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * SHELL-EMPTY-DROPIN S3: a Configuration settings section (`infantry.shell.config.<entry>`) is replaced or wrapped by a
 * module, in the real window; a trust section is locked.
 */
@OptIn(ExperimentalTestApi::class)
class ConfigDialogOverridesUiTest {
    private fun factory(node: String, mode: String, override: BlockOverride): TabFactory {
        val decl = ShellOverrideDeclaration(node, "acme", mode)
        val module = InfantryModule(
            ModuleManifest("Acme_Config", "1.0.0", author = "Acme", license = "NOASSERTION", shell = listOf(decl)),
            tier = Tier.COMMUNITY, shell = mapOf("acme" to override),
        )
        return TabFactory(CoreModules.all + module, shellOverrides = listOf(AcceptedShellOverride("Acme_Config", decl.nodeId, decl.name, decl.mode)))
    }

    /** A fake link that never dials (frontend/ui-test-flake-causes): a real dial fails about 1 s in and reports RECONNECTING. */
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

    /** Configuration open on Appearance, in the real window over a fake daemon link. */
    private fun withOpenConfig(tabs: TabFactory, body: ComposeUiTest.() -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val link = NoDialLink(scope)
            val controller = Controller(scope, link)
            runDesktopComposeUiTest(1400, 860) {
                setContent { App(controller, "ws://127.0.0.1:1/ws", null, tabs = tabs) }
                link.push(Envelope(Envelope.READY, "contract=1.94.0 provider=stub instance=i1"))
                waitUntil(timeoutMillis = 10_000) { controller.state.value.connection.isLive }
                waitForIdle()
                fun shown(tag: String) = onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
                // The cog only sets showConfig, so it is clicked until the dialog shows (the first click can land early).
                waitUntil(timeoutMillis = 10_000) {
                    shown(CONFIG_TAB_TAG_PREFIX + ConfigTab.APPEARANCE.name) || run { onNodeWithContentDescription("Configuration").performClick(); false }
                }
                onNodeWithTag(CONFIG_TAB_TAG_PREFIX + ConfigTab.APPEARANCE.name).performClick()
                waitForIdle()
                body()
            }
        } finally {
            scope.cancel()
        }
    }

    private val inDialog = hasAnyAncestor(hasTestTag(CONFIG_DIALOG_TAG))

    @Test fun replacingASettingsSectionDrawsTheModuleBlockOnItsTabOnlyAndTheOtherSectionsStay() {
        val tabs = factory("infantry.shell.config.theme", OverrideModes.REPLACE, BlockOverride { scope, inner ->
            Text(if (inner == null) "acme theme" else "bad", scope.modifier.testTag("acme-theme"))
        })
        assertEquals(emptyList(), tabs.problems)
        val settings = tabs.shellRoot.children.last().children.first { it.id == "infantry.shell.config" }.children.first()
        assertIs<OverrideBlock>(settings.children.first())
        withOpenConfig(tabs) {
            onNode(hasTestTag("acme-theme") and inDialog).assertExists()
            // The core theme section is gone; the blur section next to it stays and still works.
            onNodeWithTag(CONFIG_SECTION_TAG_PREFIX + ConfigEntry.THEME.name).assertDoesNotExist()
            onNodeWithTag(THEME_DARK_SWITCH_TAG).assertDoesNotExist()
            onNode(hasTestTag(CONFIG_SECTION_TAG_PREFIX + ConfigEntry.BLUR.name) and inDialog).assertExists()
            // Another tab: the replacement keeps the theme's tab, so it is not drawn there.
            onNodeWithTag(CONFIG_TAB_TAG_PREFIX + ConfigTab.GENERAL.name).performClick()
            waitForIdle()
            onNodeWithTag("acme-theme").assertDoesNotExist()
            onNode(hasTestTag(CONFIG_SECTION_TAG_PREFIX + ConfigEntry.AGENT_SCOPE.name) and inDialog).assertExists()
        }
    }

    @Test fun wrappingASettingsSectionDrawsTheCoreSectionInsideTheModuleFrameAndItsSwitchStillChangesTheFlag() {
        withOpenConfig(factory("infantry.shell.config.blur", OverrideModes.WRAP, BlockOverride { scope, inner ->
            Column(scope.modifier.testTag("acme-frame")) { inner!!.Render(scope) }
        })) {
            onNode(hasTestTag(CONFIG_SECTION_TAG_PREFIX + ConfigEntry.BLUR.name) and hasAnyAncestor(hasTestTag("acme-frame"))).assertExists()
            fun inFrame(text: String) = onAllNodes(hasText(text) and hasAnyAncestor(hasTestTag("acme-frame"))).fetchSemanticsNodes().isNotEmpty()
            val before = if (inFrame("On")) "On" else "Off"
            val after = if (before == "On") "Off" else "On"
            // The switch, drawn by the wrapped section, changes the window's flag through the dialog's callback.
            onNode(hasTestTag(BLUR_SWITCH_TAG) and hasAnyAncestor(hasTestTag("acme-frame"))).performClick()
            waitUntil(timeoutMillis = 10_000) { inFrame(after) }
            // Off its tab the wrap is not drawn either.
            onNodeWithTag(CONFIG_TAB_TAG_PREFIX + ConfigTab.ABOUT.name).performClick()
            waitForIdle()
            onNodeWithTag("acme-frame").assertDoesNotExist()
        }
    }

    @Test fun aCommunityModuleCannotReplaceOrWrapATrustSectionOrTheDialog() {
        for (id in listOf("plugins", "modules", "registry", "secrets", "pair", "about", "updates", "serverUpdate", "logs")) {
            val node = "infantry.shell.config.$id"
            for (mode in listOf(OverrideModes.REPLACE, OverrideModes.WRAP)) {
                val f = factory(node, mode, BlockOverride { scope, _ -> Text("acme", scope.modifier) })
                assertEquals(listOf("Acme_Config: shell override ($mode) of $node refused: $node is locked"), f.problems)
                assertSame(f.baseRoot, f.shellRoot)
            }
        }
        val dialog = factory("infantry.shell.config", OverrideModes.REPLACE, BlockOverride { scope, _ -> Text("acme", scope.modifier) })
        assertEquals(listOf("Acme_Config: shell override (replace) of infantry.shell.config refused: infantry.shell.config is locked"), dialog.problems)
        // The settings group is open, but replacing it takes only the settings, never a trust section.
        val group = factory("infantry.shell.config.settings", OverrideModes.REPLACE, BlockOverride { scope, _ -> Text("acme", scope.modifier) })
        assertEquals(emptyList(), group.problems)
    }

    @Test fun aModuleAddsASectionByInjectingIntoTheSettingsNotIntoTheLockedDialog() {
        fun inject(target: String) = BlockResolver.resolve(
            CoreShell.root, emptyList(), CoreShell.locked,
            injections = listOf(NodeInjection("Acme_Config", "extra", target, injection = BlockInjection { scope -> Text("acme extra", scope.modifier) })),
        )
        val ok = inject("infantry.shell.config.settings")
        assertEquals(emptyList(), ok.problems)
        fun Block.find(id: String): Block? = if (this.id == id) this else children.firstNotNullOfOrNull { it.find(id) }
        assertEquals("Acme_Config.extra", ok.root.find("infantry.shell.config.settings")!!.children.last().id)
        assertEquals(
            listOf("Acme_Config: injection extra into infantry.shell.config (last) refused: infantry.shell.config is locked"),
            inject("infantry.shell.config").problems,
        )
    }

    @Test fun aLockedTrustSectionStillDrawsWhenAModuleTriesToReplaceIt() {
        withOpenConfig(factory("infantry.shell.config.about", OverrideModes.REPLACE, BlockOverride { scope, _ ->
            Text("acme about", scope.modifier.testTag("acme-about"))
        })) {
            onNodeWithTag(CONFIG_TAB_TAG_PREFIX + ConfigTab.ABOUT.name).performClick()
            waitForIdle()
            onNode(hasTestTag(CONFIG_SECTION_TAG_PREFIX + ConfigEntry.ABOUT.name) and inDialog).assertExists()
            onNodeWithTag("acme-about").assertDoesNotExist()
        }
    }

    @Test fun aSectionDrawnOutsideTheDialogDrawsNothingAndDoesNotCrash() = runDesktopComposeUiTest(400, 300) {
        fun Block.all(): List<Block> = children + children.flatMap { it.all() }
        // RUNTIME-26: the open settings are the shell's own (ShellConfig holds them); the trust sections are core's.
        val sections = ShellConfig.all().filter { it.children.isEmpty() && it !is PlaceholderBlock }
        assertEquals(21, sections.size) // 11 open settings (tabGroups included) + 10 trust sections (POLICY-1 added Policy); RUNTIME-19: 5 more are slots of their owners
        setContent { Column { for (b in sections) b.Render(BlockScope(Modifier, null)) } }
        waitForIdle()
        for (e in ConfigEntry.entries) onNodeWithTag(CONFIG_SECTION_TAG_PREFIX + e.name).assertDoesNotExist()
        onNodeWithTag(BLUR_SWITCH_TAG).assertDoesNotExist()
    }
}
