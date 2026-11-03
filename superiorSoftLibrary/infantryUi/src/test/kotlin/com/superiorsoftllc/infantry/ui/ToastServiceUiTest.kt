// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.plugin.api.ShellOverrideDeclaration
import com.superiorsoftllc.infantry.plugin.api.Tier
import com.superiorsoftllc.infantry.plugin.host.AcceptedShellOverride
import com.superiorsoftllc.infantry.plugins.core.CoreModules
import com.superiorsoftllc.infantry.ui.block.layout.Block
import com.superiorsoftllc.infantry.ui.block.layout.BlockOverride
import com.superiorsoftllc.infantry.ui.block.layout.BlockScope
import com.superiorsoftllc.infantry.ui.block.layout.OverrideModes
import com.superiorsoftllc.infantry.ui.model.ToastEvent
import com.superiorsoftllc.infantry.ui.model.ToastKind
import com.superiorsoftllc.infantry.ui.net.Controller
import com.superiorsoftllc.infantry.ui.plugin.InfantryModule
import com.superiorsoftllc.infantry.ui.plugin.TabFactory
import com.superiorsoftllc.infantry.ui.protocol.Envelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * TOAST-1: blocks toast through `LocalToast` (nearest provider wins), the toast node `infantry.shell.toast` can be
 * replaced or wrapped by a module, and a toast of a module's own drawn as a child of it can be called from anywhere.
 * All in the real window.
 */
@OptIn(ExperimentalTestApi::class)
class ToastServiceUiTest {
    private class Ov(val node: String, val name: String, val mode: String, val override: BlockOverride)

    private fun factory(vararg ovs: Ov): TabFactory {
        val decls = ovs.map { ShellOverrideDeclaration(it.node, it.name, it.mode) }
        val module = InfantryModule(
            ModuleManifest("Acme_Toast", "1.0.0", author = "Acme", license = "NOASSERTION", shell = decls),
            tier = Tier.COMMUNITY, shell = ovs.associate { it.name to it.override },
        )
        val f = TabFactory(CoreModules.all + com.superiorsoftllc.infantry.plugins.official.header.InfantryHeader + com.superiorsoftllc.infantry.plugins.official.InfantryAgents + com.superiorsoftllc.infantry.plugins.official.attention.InfantryAttention + module, shellOverrides = decls.map { AcceptedShellOverride("Acme_Toast", it.nodeId, it.name, it.mode) })
        assertEquals(emptyList(), f.problems)
        return f
    }

    private fun withWindow(tabs: TabFactory, body: ComposeUiTest.(Controller) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val link = ChangesFakeLink(scope)
            val controller = Controller(scope, link)
            runDesktopComposeUiTest(1400, 860) {
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

    /** A button that toasts [message] through whatever `LocalToast` it sits under. */
    @Composable
    private fun Poster(tag: String, message: String) {
        val toast = LocalToast.current
        Text(tag, Modifier.testTag(tag).clickable { toast.post(message, ToastKind.SUCCESS) })
    }

    /** Wraps title-bar leaf [node] with [Poster] drawn beside it (and, with [service], both under that service). */
    private fun poster(node: String, name: String, tag: String, message: String, service: ToastService? = null) =
        Ov(node, name, OverrideModes.WRAP, BlockOverride { scope, inner ->
            val draw: @Composable () -> Unit = { Row { inner!!.Render(scope); Poster(tag, message) } }
            if (service == null) draw() else CompositionLocalProvider(LocalToast provides service) { draw() }
        })

    /** A module's own toast system: a list it draws itself. */
    private class Recording(val tag: String) : Block("acmeToast", "acme.toast"), ToastService {
        val posted = mutableStateListOf<ToastEvent>()
        override fun post(toast: ToastEvent) { posted += toast }
        override fun dismiss(id: String) { posted.removeAll { it.id == id } }

        @Composable
        override fun Render(scope: BlockScope) = Box(scope.modifier.testTag(tag)) {
            Column { for (t in posted) Text(t.title, Modifier.testTag("$tag-item")) }
        }
    }

    private fun ComposeUiTest.coreToastShows(text: String) =
        waitUntil(timeoutMillis = 3_000) { onAllNodes(hasTestTag(TOAST_TAG)).fetchSemanticsNodes().isNotEmpty() && onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    @Test fun `a block deep in the tree toasts through the root service and the core host shows it`() =
        withWindow(factory(poster("infantry.shell.header.lead.brand", "deep", "deep-post", "hello from deep"))) { controller ->
            onNodeWithTag("deep-post").performClick()
            coreToastShows("hello from deep")
            assertTrue(controller.state.value.toasts.any { it.title == "hello from deep" }, "it went through the controller's state")
        }

    @Test fun `a wrapped subtree routes only its own descendants to its own service`() {
        val mine = Recording("acme-sink")
        withWindow(factory(
            poster("infantry.shell.header.lead.brand", "inner", "inner-post", "to acme", service = mine),
            poster("infantry.shell.header.attention", "outer", "outer-post", "to core"),
        )) { controller ->
            onNodeWithTag("inner-post").performClick()
            waitForIdle()
            assertEquals(listOf("to acme"), mine.posted.map { it.title })
            assertTrue(controller.state.value.toasts.isEmpty(), "the core service saw nothing")
            onNodeWithTag(TOAST_TAG).assertDoesNotExist()

            onNodeWithTag("outer-post").performClick()
            coreToastShows("to core")
            assertEquals(listOf("to acme"), mine.posted.map { it.title }, "a sibling subtree is not routed to it")
        }
    }

    @Test fun `replacing the toast node draws the module's toast in its place, and a wrap keeps the core host`() {
        val mine = Recording("acme-replace")
        withWindow(factory(Ov("infantry.shell.toast", "replace", OverrideModes.REPLACE, BlockOverride { scope, inner ->
            assertEquals(null, inner)
            CompositionLocalProvider(LocalToast provides mine) { mine.Render(scope) }
        }))) { controller ->
            onNodeWithTag("acme-replace").assertExists()
            controller.postToast("into the core queue")
            waitForIdle()
            onNodeWithTag(TOAST_TAG).assertDoesNotExist()
        }
        withWindow(factory(Ov("infantry.shell.toast", "wrap", OverrideModes.WRAP, BlockOverride { scope, inner ->
            Box(Modifier.testTag("acme-frame")) { inner!!.Render(scope) }
        }))) { controller ->
            onNodeWithTag("acme-frame").assertExists()
            controller.postToast("still the core host")
            coreToastShows("still the core host")
        }
    }

    @Test fun `a toast drawn as a direct child of the toast node is called from elsewhere in the window`() {
        val mine = Recording("acme-child")
        withWindow(factory(
            // The toast node accepts children: a wrap hands it one more and draws it.
            Ov("infantry.shell.toast", "child", OverrideModes.WRAP, BlockOverride { scope, inner ->
                // RUNTIME-17: the toast node is the shell's own now; a community wrap gets it at the shell's tier.
                inner!!.withChildren(inner.children + mine).Render(scope)
            }),
            Ov("infantry.shell.header.attention", "caller", OverrideModes.WRAP, BlockOverride { scope, inner ->
                Row { inner!!.Render(scope); Text("call", Modifier.testTag("call-child").clickable { mine.post("from the title bar") }) }
            }),
        )) { controller ->
            onNodeWithTag("acme-child").assertExists()
            onNodeWithTag("call-child").performClick()
            waitForIdle()
            assertEquals(1, onAllNodesWithTag("acme-child-item").fetchSemanticsNodes().size)
            controller.postToast("core still works")
            coreToastShows("core still works")
        }
    }
}
