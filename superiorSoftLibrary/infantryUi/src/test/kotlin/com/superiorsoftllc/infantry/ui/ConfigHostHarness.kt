// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.infantry.ui.model.ConnState
import com.superiorsoftllc.infantry.ui.model.ConnectionInfo
import com.superiorsoftllc.infantry.ui.net.Controller
import com.superiorsoftllc.infantry.ui.net.DaemonClient
import com.superiorsoftllc.infantry.ui.plugin.TabFactory
import com.superiorsoftllc.infantry.ui.plugin.config.NodeConfigLoad
import com.superiorsoftllc.infantry.ui.protocol.Envelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** A fake link that never dials (frontend/ui-test-flake-causes); [push] delivers a daemon frame to the controller. */
internal class ConfigNoDialLink(scope: CoroutineScope) : DaemonClient(scope) {
    private val incoming = MutableSharedFlow<Envelope>(extraBufferCapacity = 64)
    override val frames: SharedFlow<Envelope> get() = incoming
    override val info: StateFlow<ConnectionInfo> = MutableStateFlow(ConnectionInfo(state = ConnState.CONNECTED))
    override fun connect(url: String, token: String?) {}
    override fun disconnect() {}
    /** Every frame the controller sent, in order (CONFIG-11: the test reads config_get / config_set off it). */
    val sent = java.util.concurrent.CopyOnWriteArrayList<Envelope>()
    override suspend fun send(envelope: Envelope): Boolean { sent += envelope; return true }
    fun push(e: Envelope) {
        val deadline = System.currentTimeMillis() + 10_000
        while (incoming.subscriptionCount.value == 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        check(incoming.tryEmit(e)) { "frame buffer full" }
    }
}

/**
 * QA-CONFIG-3: one host lifetime. A window over [tabs] and a fake daemon link that reports [contract] (then delivers
 * [frames]) with Configuration open and the config loaded; the window is torn down when [body] returns.
 */
@OptIn(ExperimentalTestApi::class)
internal fun withConfigDialog(tabs: TabFactory, contract: String = "1.142.0", frames: List<Envelope> = emptyList(), body: ComposeUiTest.(Controller) -> Unit) =
    withConfigDialogLink(tabs, contract, frames) { controller, _ -> body(controller) }

/** [withConfigDialog] that also hands [body] the fake link: it records what was sent and delivers further daemon frames. */
@OptIn(ExperimentalTestApi::class)
internal fun withConfigDialogLink(tabs: TabFactory, contract: String = "1.142.0", frames: List<Envelope> = emptyList(), body: ComposeUiTest.(Controller, ConfigNoDialLink) -> Unit) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    try {
        val link = ConfigNoDialLink(scope)
        val controller = Controller(scope, link)
        runDesktopComposeUiTest(1400, 860) {
            setContent { App(controller, "ws://127.0.0.1:1/ws", null, tabs = tabs) }
            link.push(Envelope(Envelope.READY, "contract=$contract provider=stub instance=i1"))
            waitUntil(timeoutMillis = 10_000) { controller.state.value.connection.isLive }
            frames.forEach(link::push)
            waitForIdle()
            waitUntil(timeoutMillis = 10_000) {
                onAllNodesWithTag(CONFIG_DIALOG_TAG).fetchSemanticsNodes().isNotEmpty() || run { onNodeWithContentDescription("Configuration").performClick(); false }
            }
            waitUntil(timeoutMillis = 10_000) { tabs.config.load == NodeConfigLoad.LOADED }
            waitUntil(timeoutMillis = 10_000) { tabs.config.snapshot != null }
            body(controller, link)
        }
    } finally {
        scope.cancel()
    }
}
