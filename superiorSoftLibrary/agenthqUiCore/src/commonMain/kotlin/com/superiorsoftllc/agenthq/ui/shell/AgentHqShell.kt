package com.superiorsoftllc.agenthq.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import com.superiorsoftllc.agenthq.ui.App
import com.superiorsoftllc.agenthq.ui.LogsFolder
import com.superiorsoftllc.agenthq.ui.net.Controller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * The portable shell: resolves the launch connection from the platform host, owns the controller's
 * lifetime and renders [App]. A platform entry (desktop `Window`, Android `Activity`) only supplies a
 * container and a [PlatformHost]. Pass [controller] to drive the shell from outside (smoke/tests); the
 * caller then owns its scope.
 */
@Composable
fun AgentHqShell(host: PlatformHost, controller: Controller? = null) {
    val config = remember(host) { resolveShellConfig(host) }
    val owned = remember(controller) {
        if (controller == null) CoroutineScope(SupervisorJob() + Dispatchers.Default) else null
    }
    val active = remember(controller, owned) { controller ?: Controller(owned!!) }
    DisposableEffect(owned) { onDispose { owned?.cancel() } }
    CompositionLocalProvider(LocalPlatformHost provides host) {
        App(
            active, config.url, config.token,
            pickFile = if (host.canPickFiles) host::pickFile else null,
            logs = host.logsDir?.let { dir -> LogsFolder(dir) { host.openFolder(dir) } },
            updates = host.updates,
        )
    }
}
