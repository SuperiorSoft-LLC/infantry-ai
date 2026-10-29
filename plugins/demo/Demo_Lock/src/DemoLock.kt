package com.superiorsoftllc.agenthq.plugins.demo.lock

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import com.superiorsoftllc.agenthq.plugin.api.ModuleManifest
import com.superiorsoftllc.agenthq.plugin.api.ShellOverrideDeclaration
import com.superiorsoftllc.agenthq.ui.block.layout.BlockOverride
import com.superiorsoftllc.agenthq.ui.plugin.AgentHqModule
import com.superiorsoftllc.agenthq.ui.plugin.ModuleSource
import com.superiorsoftllc.agenthq.ui.plugin.UiPlugin
import com.superiorsoftllc.agenthq.ui.plugin.UiRegistrar
import com.superiorsoftllc.agenthq.ui.shell.LocalShellContext
import com.superiorsoftllc.agenthq.ui.shell.ProvideWritePolicy
import com.superiorsoftllc.agenthq.ui.shell.WritePolicy
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** The hover reason on every write control the demo lock dims. */
const val DISABLED_REASON = "Disabled in the demo"

/** The daemon's turn-limit refusal code (`demo_limit <message>`), declared a notice in `ready` (notices=demo_limit). */
const val TURN_LIMIT_CODE = "demo_limit"

/** What the demo lock derived from the daemon's `ready`: whether it is locked, and the notice to show (null = none). */
data class DemoLockState(val locked: Boolean, val notice: String?) {
    val policy: WritePolicy get() = if (locked) WritePolicy(canWrite = false, reason = DISABLED_REASON) else WritePolicy.AllowAll

    companion object {
        val Off = DemoLockState(false, null)

        /**
         * `ready` demo=lock locks; demoNotice= is the pinned notice (base64url UTF-8). Under the lock the turn-limit notice,
         * once the daemon sends it, replaces the pinned one. Without demo=lock: [Off], whatever else the daemon said.
         */
        fun of(readyFields: Map<String, String>, notices: Map<String, String>): DemoLockState {
            if (readyFields["demo"] != "lock") return Off
            return DemoLockState(true, notices[TURN_LIMIT_CODE] ?: readyFields["demoNotice"]?.let(::decodeNotice))
        }
    }
}

/** The `ready` demoNotice= value is base64url (padding optional) UTF-8; null when it does not decode or is blank. */
@OptIn(ExperimentalEncodingApi::class)
fun decodeNotice(encoded: String): String? = runCatching {
    Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).decode(encoded).decodeToString()
}.getOrNull()?.takeIf { it.isNotBlank() }

/** The demo lock state for every block under the frame (Demo_Banner reads the notice); [DemoLockState.Off] outside it. */
val LocalDemoLock = compositionLocalOf { DemoLockState.Off }

/** Wraps `infantry.shell.frame` (the whole docked app): provides the write policy and the notice, then draws the frame. */
val demoLockWrap = BlockOverride { scope, inner ->
    val link = LocalShellContext.current?.link
    val fields = link?.readyFields.orEmpty()
    val notices = link?.notices.orEmpty()
    val state = remember(fields, notices) { DemoLockState.of(fields, notices) }
    DemoLockProvider(state) { inner?.Render(scope) }
}

@Composable
private fun DemoLockProvider(state: DemoLockState, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalDemoLock provides state) { ProvideWritePolicy(state.policy, content) }
}

/** DEMO-MERGE-1: a demo-only module; its single override is declared in module.json and here (same name). */
val DemoLockModule = AgentHqModule(
    ModuleManifest(
        name = "Demo_Lock", version = "1.0.0", author = "Infantry AI",
        description = "Demo build only: the demo lock's write policy and notice, around the whole app.",
        shell = listOf(ShellOverrideDeclaration("infantry.shell.frame", "lock", "wrap")),
    ),
    shell = mapOf("lock" to demoLockWrap),
)

class DemoLockPlugin : UiPlugin, ModuleSource {
    override fun register(registrar: UiRegistrar) = registrar.shell("lock", demoLockWrap)

    override fun modules(): List<AgentHqModule> = listOf(DemoLockModule)
}
