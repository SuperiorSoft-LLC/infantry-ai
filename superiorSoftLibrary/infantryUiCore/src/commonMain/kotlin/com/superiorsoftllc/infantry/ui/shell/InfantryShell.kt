package com.superiorsoftllc.infantry.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import com.superiorsoftllc.infantry.ui.App
import com.superiorsoftllc.infantry.ui.LogsFolder
import com.superiorsoftllc.infantry.ui.SplashReady
import com.superiorsoftllc.infantry.ui.model.ConnState
import com.superiorsoftllc.infantry.ui.net.Controller
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
fun InfantryShell(host: PlatformHost, controller: Controller? = null) {
    val config = remember(host) { resolveShellConfig(host) }
    val owned = remember(controller) {
        if (controller == null) CoroutineScope(SupervisorJob() + Dispatchers.Default) else null
    }
    val active = remember(controller, owned) { controller ?: Controller(owned!!) }
    // SEC-FIX-1: the owner credential goes with the launch URL only, so a switch to another server never carries it.
    remember(active, config) { config.ownerToken?.let { active.client.ownerFor(config.url, it) } }
    DisposableEffect(owned) { onDispose { owned?.cancel() } }
    // S6: the module set, owned here: a drop-in install reloads it (the soft restart), and the window is composed again
    // with it (key), in the same process and with the same controller and connection.
    var tabs by remember(host) { mutableStateOf(host.tabFactory) }
    CompositionLocalProvider(LocalPlatformHost provides host, LocalSoftRestart provides { tabs = host.reloadTabs() }) {
        key(tabs) {
            App(
                active, config.url, config.token,
                pickFile = if (host.canPickFiles) host::pickFile else null,
                logs = host.logsDir?.let { dir -> LogsFolder(dir) { host.openFolder(dir) } },
                updates = host.updates,
                tabs = tabs,
            )
        }
    }
    ShellSplashReady(active)
}

/**
 * SPLASH-ALL-1: the shell is the first real screen once the first connect has an answer: live, or a refusal or
 * failure the window itself explains. Its own scope, so the state it reads does not recompose the shell.
 */
@Composable
private fun ShellSplashReady(controller: Controller) {
    val state by controller.state.collectAsState()
    SplashReady(state.connection.state != ConnState.IDLE && state.connection.state != ConnState.CONNECTING)
}
