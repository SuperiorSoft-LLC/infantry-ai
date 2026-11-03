// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.demo.lock

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.plugin.api.ShellOverrideDeclaration
import com.superiorsoftllc.infantry.ui.block.layout.BlockOverride
import com.superiorsoftllc.infantry.ui.plugin.InfantryModule
import com.superiorsoftllc.infantry.ui.plugin.ModuleSource
import com.superiorsoftllc.infantry.ui.plugin.UiPlugin
import com.superiorsoftllc.infantry.ui.plugin.UiRegistrar
import com.superiorsoftllc.infantry.ui.shell.LocalShellContext
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** The daemon's turn-limit refusal code (`demo_limit <message>`), declared a notice by the demo preset (notices: demo_limit). */
const val TURN_LIMIT_CODE = "demo_limit"

/**
 * What the demo lock derived from the daemon's ready fields: whether it is locked, and the notice to show (null = none).
 * POLICY-1: the write controls are no longer dimmed here; core's gates follow the daemon's policy (the demo preset,
 * plugins/demo/Demo_Lock/policies/demo.json), in every build. This module keeps the notice for Demo_Banner.
 */
data class DemoLockState(val locked: Boolean, val notice: String?) {
    companion object {
        val Off = DemoLockState(false, null)

        /**
         * demo=lock (the demo preset's `ready`) locks; notice= is the pinned notice (base64url UTF-8; demoNotice= from a
         * daemon before POLICY-1). Under the lock the turn-limit notice, once the daemon sends it, replaces the pinned one.
         * Without demo=lock: [Off], whatever else the daemon said.
         */
        fun of(readyFields: Map<String, String>, notices: Map<String, String>): DemoLockState {
            if (readyFields["demo"] != "lock") return Off
            val pinned = (readyFields["notice"] ?: readyFields["demoNotice"])?.let(::decodeNotice)
            return DemoLockState(true, notices[TURN_LIMIT_CODE] ?: pinned)
        }
    }
}

/** The notice field is base64url (padding optional) UTF-8; null when it does not decode or is blank. */
@OptIn(ExperimentalEncodingApi::class)
fun decodeNotice(encoded: String): String? = runCatching {
    Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).decode(encoded).decodeToString()
}.getOrNull()?.takeIf { it.isNotBlank() }

/** The demo lock state for every block under the frame (Demo_Banner reads the notice); [DemoLockState.Off] outside it. */
val LocalDemoLock = compositionLocalOf { DemoLockState.Off }

/** Wraps `infantry.shell.frame` (the whole docked app): provides the notice, then draws the frame. */
val demoLockWrap = BlockOverride { scope, inner ->
    val link = LocalShellContext.current?.link
    val fields = link?.readyFields.orEmpty()
    val notices = link?.notices.orEmpty()
    val state = remember(fields, notices) { DemoLockState.of(fields, notices) }
    DemoLockProvider(state) { inner?.Render(scope) }
}

@Composable
private fun DemoLockProvider(state: DemoLockState, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalDemoLock provides state, content = content)
}

/** DEMO-MERGE-1: a demo-only module; its single override is declared in module.json and here (same name). */
val DemoLockModule = InfantryModule(
    ModuleManifest(
        name = "Demo_Lock", version = "1.1.0", author = "Infantry AI",
        description = "Demo build only: the demo notice around the whole app; ships the demo policy preset (policies/demo.json).",
        shell = listOf(ShellOverrideDeclaration("infantry.shell.frame", "lock", "wrap")),
    ),
    shell = mapOf("lock" to demoLockWrap),
)

class DemoLockPlugin : UiPlugin, ModuleSource {
    override fun register(registrar: UiRegistrar) = registrar.shell("lock", demoLockWrap)

    override fun modules(): List<InfantryModule> = listOf(DemoLockModule)
}
