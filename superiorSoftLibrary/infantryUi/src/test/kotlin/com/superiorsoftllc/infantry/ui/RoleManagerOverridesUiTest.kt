// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.plugins.official.roles.*
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.plugin.api.ShellOverrideDeclaration
import com.superiorsoftllc.infantry.plugin.api.Tier
import com.superiorsoftllc.infantry.plugin.host.AcceptedShellOverride
import com.superiorsoftllc.infantry.plugins.core.CoreModules
import com.superiorsoftllc.infantry.ui.block.layout.BlockOverride
import com.superiorsoftllc.infantry.ui.block.layout.BlockScope
import com.superiorsoftllc.infantry.ui.block.layout.OverrideModes
import com.superiorsoftllc.infantry.ui.model.ConnState
import com.superiorsoftllc.infantry.ui.model.ConnectionInfo
import com.superiorsoftllc.infantry.ui.net.Controller
import com.superiorsoftllc.infantry.ui.net.DaemonClient
import com.superiorsoftllc.infantry.ui.plugin.InfantryModule
import com.superiorsoftllc.infantry.ui.plugin.TabFactory
import com.superiorsoftllc.infantry.ui.protocol.Envelope
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

/** BLOCKS-LAYOUT L12: a role manager piece (`infantry.shell.roles.<name>`, `.form.<name>`) is replaced or wrapped by a module, in the real window. */
@OptIn(ExperimentalTestApi::class)
class RoleManagerOverridesUiTest {
    private fun factory(node: String, mode: String, override: BlockOverride): TabFactory {
        val decl = ShellOverrideDeclaration(node, "acme", mode)
        val module = InfantryModule(
            ModuleManifest("Acme_Roles", "1.0.0", author = "Acme", license = "NOASSERTION", shell = listOf(decl)),
            tier = Tier.COMMUNITY, shell = mapOf("acme" to override),
        )
        val f = TabFactory(CoreModules.all + InfantryRoles + module, shellOverrides = listOf(AcceptedShellOverride("Acme_Roles", decl.nodeId, decl.name, decl.mode)))
        assertEquals(emptyList(), f.problems)
        return f
    }

    /**
     * A fake link that never dials (frontend/ui-test-flake-causes): a real dial to ws://127.0.0.1:1 fails about 1 s in and
     * reports RECONNECTING over the pushed `ready`, which disables New role mid-test.
     */
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

    /** Configuration > Agents > Manage roles, in the real window over a fake daemon link. */
    private fun withOpenManager(tabs: TabFactory, body: ComposeUiTest.() -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val link = NoDialLink(scope)
            val controller = Controller(scope, link)
            runDesktopComposeUiTest(1400, 860) {
                setContent { App(controller, "ws://127.0.0.1:1/ws", null, tabs = tabs) }
                link.push(Envelope(Envelope.READY, "contract=1.92.0 provider=stub instance=i1"))
                waitUntil(timeoutMillis = 10_000) { controller.state.value.connection.isLive }
                dismissSetupWizard(controller)
                waitForIdle()
                fun shown(tag: String) = onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
                fun awaitTag(tag: String) = waitUntil(timeoutMillis = 10_000) { shown(tag) }
                // The cog only sets showConfig, so it is clicked until the dialog shows: in a second window of the same JVM
                // the first click can land before the footer takes input.
                waitUntil(timeoutMillis = 10_000) {
                    shown(CONFIG_TAB_TAG_PREFIX + ConfigTab.AGENTS.name) || run { onNodeWithContentDescription("Configuration").performClick(); false }
                }
                onNodeWithTag(CONFIG_TAB_TAG_PREFIX + ConfigTab.AGENTS.name).performClick()
                awaitTag(ROLE_MANAGER_OPEN_TAG)
                onNodeWithTag(ROLE_MANAGER_OPEN_TAG).performScrollTo().performClick()
                // Synchronize on the composed UI, not the controller (frontend/ui-test-flake-causes): New role is enabled once live.
                waitUntil(timeoutMillis = 10_000) { onAllNodes(hasTestTag(ROLE_MANAGER_NEW_TAG) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
                waitForIdle()
                body()
            }
        } finally {
            scope.cancel()
        }
    }

    private val inManager = hasAnyAncestor(hasTestTag(ROLE_MANAGER_TAG))

    @Test fun replacingTheTraitsDrawsTheModuleBlockInTheFormAndTheCorePiecesStay() {
        withOpenManager(factory("infantry.shell.roles.form.traits", OverrideModes.REPLACE, BlockOverride { scope, inner ->
            Text(if (inner == null) "acme traits" else "bad", scope.modifier.testTag("acme-traits"))
        })) {
            onNodeWithTag(ROLE_MANAGER_NEW_TAG).performClick()
            waitForIdle()
            onNode(hasTestTag("acme-traits") and inManager).assertExists()
            // The core traits are gone; the other pieces stay, and the frame's Back still returns to the list.
            onNodeWithText("Traits (up to", substring = true).assertDoesNotExist()
            onNode(hasTestTag(ROLE_EDITOR_NAME_TAG) and inManager).assertExists()
            onNodeWithText("Back").performScrollTo().performClick()
            waitForIdle()
            onNodeWithTag("acme-traits").assertDoesNotExist()
            onNode(hasTestTag(ROLE_MANAGER_NEW_TAG) and inManager).assertExists()
        }
    }

    @Test fun wrappingTheListDrawsTheCoreListInsideTheModuleFrame() {
        withOpenManager(factory("infantry.shell#infantry.shell.roles.list", OverrideModes.WRAP, BlockOverride { scope, inner ->
            Box(scope.modifier.testTag("acme-frame")) { inner!!.Render(scope) }
        })) {
            onNode(hasTestTag(ROLE_MANAGER_NEW_TAG) and hasAnyAncestor(hasTestTag("acme-frame"))).assertExists()
            onNode(hasTestTag("acme-frame") and inManager).assertExists()
            onNodeWithText("Changes apply to sessions", substring = true).assertExists()
            // The wrapped list still opens the form, which the core form block draws.
            onNodeWithTag(ROLE_MANAGER_NEW_TAG).performClick()
            waitForIdle()
            onNode(hasTestTag(ROLE_EDITOR_NAME_TAG) and inManager).assertExists()
        }
    }

    @Test fun aPieceDrawnOutsideTheManagerDrawsNothingAndDoesNotCrash() = runDesktopComposeUiTest(400, 300) {
        val pieces = listOf(RolesHint, RolesList, RoleFormTitleBlock, RoleFormIdentityBlock, RoleFormExecutorBlock, RoleFormTraitsBlock, RoleFormNotesBlock, RoleFormActionsBlock)
        setContent { for (b in pieces) b.Render(BlockScope(Modifier, null)) }
        waitForIdle()
        onNodeWithText("Changes apply to sessions", substring = true).assertDoesNotExist()
        onNodeWithTag(ROLE_MANAGER_NEW_TAG).assertDoesNotExist()
        onNodeWithTag(ROLE_EDITOR_NAME_TAG).assertDoesNotExist()
        onNodeWithTag(ROLE_MANAGER_SAVE_TAG).assertDoesNotExist()
    }
}
