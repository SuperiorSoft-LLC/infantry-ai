package com.superiorsoftllc.infantry.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * DEMO-MERGE-1: whether the UI's write controls (create, edit, delete, spawn, stop, configure) may be enabled, and the
 * hover reason when not. Core reads it; core never decides it. The default allows everything, so a build with no module
 * providing one behaves exactly as before. A module that wraps the app (e.g. a `wrap` override on `infantry.shell.frame`)
 * provides one with [ProvideWritePolicy], and every block under it follows. Read-only views keep following the link.
 */
data class WritePolicy(val canWrite: Boolean = true, val reason: String? = null) {
    /** A write control's label: [normal], or the reason while writes are refused. */
    fun label(normal: String): String = if (!canWrite && reason != null) reason else normal

    companion object {
        val AllowAll = WritePolicy()
    }
}

/** The nearest provided [WritePolicy]; [WritePolicy.AllowAll] when no module provides one. */
val LocalWritePolicy = compositionLocalOf { WritePolicy.AllowAll }

/**
 * Where the policy a module provides is mirrored for code that runs outside composition but must follow it (the app's
 * keyboard shortcuts, which fire on the docked column). Provided by the app; null in a test that renders a block alone.
 */
internal val LocalWritePolicySink = staticCompositionLocalOf<MutableState<WritePolicy>?> { null }

/** Provide [policy] to [content] (nearest wins) and mirror it to the app's shortcut handler while it is composed. */
@Composable
fun ProvideWritePolicy(policy: WritePolicy, content: @Composable () -> Unit) {
    val sink = LocalWritePolicySink.current
    SideEffect { sink?.value = policy }
    DisposableEffect(sink) { onDispose { sink?.value = WritePolicy.AllowAll } }
    CompositionLocalProvider(LocalWritePolicy provides policy, content = content)
}
