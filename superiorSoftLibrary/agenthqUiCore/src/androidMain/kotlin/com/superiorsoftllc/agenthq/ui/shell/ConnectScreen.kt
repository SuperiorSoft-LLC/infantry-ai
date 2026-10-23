package com.superiorsoftllc.agenthq.ui.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.window.Dialog
import com.superiorsoftllc.agenthq.ui.net.DaemonClient
import com.superiorsoftllc.agenthq.ui.pair.CameraQrScanner
import com.superiorsoftllc.agenthq.ui.pair.PairingHosts
import com.superiorsoftllc.agenthq.ui.pair.PairingLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withTimeoutOrNull

/**
 * MOBILE-CONNECT-1: one connection attempt with a throwaway client, decided by [ConnectOutcome.of]: `ready` from the
 * daemon, a 401/403, or the first failed attempt (unreachable). [timeoutMs] bounds a daemon that never answers.
 */
suspend fun probeDaemon(url: String, token: String?, timeoutMs: Long = 8_000): ConnectOutcome {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val client = DaemonClient(scope)
    return try {
        // DaemonClient.info never reports CONNECTED itself (the controller's reducer does, from the `ready` frame);
        // the client's own signal is `opened`, fired after `ready`. Subscribe before connecting: it does not replay.
        val verdict = scope.async(start = CoroutineStart.UNDISPATCHED) {
            merge(client.opened.map { ConnectOutcome.Connected }, client.info.mapNotNull { ConnectOutcome.of(it) }).first()
        }
        client.connect(url, token)
        withTimeoutOrNull(timeoutMs) { verdict.await() }
            ?: ConnectOutcome.Unreachable("Daemon unreachable: no answer from $url within ${formatTimeout(timeoutMs)}")
    } finally {
        client.close()
        scope.cancel()
    }
}

/** BUG-PROBE-MSG-1: whole seconds from 1s up (unchanged wording); a sub-second timeout in ms, never "0s". */
private fun formatTimeout(timeoutMs: Long): String = if (timeoutMs < 1_000) "${timeoutMs}ms" else "${timeoutMs / 1000}s"

private sealed interface Gate {
    /** [url]: the address just tried, kept in the field after a failure (null = the saved one). */
    data class Form(val error: String? = null, val url: String? = null) : Gate
    data class Probing(val url: String, val token: String?, val save: Boolean) : Gate
    /** MOBILE-CONNCHIP-1: the settings only; the host is built in the gate so its chip can reopen the form. */
    class Shell(val settings: LayeredSettings) : Gate
}

/**
 * MOBILE-CONNECT-1: the Android root. Intent extras go straight to the shell (tests, adb: unchanged); a saved
 * connection is probed first; nothing saved, a refused token or an unreachable daemon shows [ConnectForm]. The shell's
 * title-bar connection chip reopens the form (MOBILE-CONNCHIP-1, through [PlatformHost.onChangeConnection]).
 */
@Composable
fun AndroidConnectGate(
    store: ConnectionStore,
    extras: (String) -> String?,
    /** PAIR-1: an `agenthq://connect` link the OS opened the app with; the user is asked before it replaces the connection. */
    openedLink: String? = null,
    onOpenedLinkHandled: () -> Unit = {},
) {
    val initial = remember {
        when (val decision = launchDecision(extras, store)) {
            LaunchDecision.Setup -> Gate.Form()
            is LaunchDecision.Connect -> if (decision.fromExtras) {
                Gate.Shell(decision.settings)
            } else {
                Gate.Probing(decision.settings[ShellKeys.WS_URL].orEmpty(), decision.settings[ShellKeys.TOKEN], save = false)
            }
        }
    }
    var gate by remember { mutableStateOf<Gate>(initial) }
    var hadShell by remember { mutableStateOf(initial is Gate.Shell) }

    val pairLink = openedLink?.let { (PairingLink.parse(it) as? PairingLink.Parsed.Ok)?.link }
    val openedBad = openedLink != null && pairLink == null

    MaterialTheme(colorScheme = darkColorScheme()) {
        if (openedLink != null) {
            com.superiorsoftllc.agenthq.ui.AhqAlertDialog(
                onDismissRequest = onOpenedLinkHandled,
                title = { Text(if (pairLink != null) "Connect to this server?" else "Cannot use this link") },
                text = {
                    Text(
                        if (pairLink != null) "${pairLink.name ?: PairingHosts.labelOf(pairLink.url)} at ${PairingHosts.labelOf(pairLink.url)}. " +
                            "It replaces your saved connection and signs in with the token in the link."
                        else "This is not a valid Infantry AI pairing link.",
                    )
                },
                confirmButton = {
                    if (pairLink != null) {
                        TextButton(onClick = { onOpenedLinkHandled(); gate = Gate.Probing(pairLink.url, pairLink.token, save = true) }) { Text("Connect") }
                    } else {
                        TextButton(onClick = onOpenedLinkHandled) { Text("OK") }
                    }
                },
                dismissButton = if (pairLink != null) ({ TextButton(onClick = onOpenedLinkHandled) { Text("Cancel") } }) else null,
            )
        }
        when (val g = gate) {
            is Gate.Form -> {
                val saved = remember(g) { store.load() }
                ConnectForm(
                    savedUrl = g.url ?: saved?.url.orEmpty(),
                    hasSavedToken = saved?.token != null,
                    error = g.error,
                    busy = false,
                    onConnect = { url, typedToken ->
                        // An empty token field keeps the saved token (it is never shown back to the user).
                        val token = typedToken.trim().ifEmpty { saved?.token.orEmpty() }.ifEmpty { null }
                        gate = Gate.Probing(url.trim(), token, save = true)
                    },
                    onLink = { link -> gate = Gate.Probing(link.url, link.token, save = true) },
                    onForget = if (saved != null) ({ store.clear(); gate = Gate.Form() }) else null,
                    onCancel = if (hadShell && saved != null) ({ gate = Gate.Probing(saved.url, saved.token, save = false) }) else null,
                )
            }
            is Gate.Probing -> {
                ConnectForm(savedUrl = g.url, hasSavedToken = g.token != null, error = null, busy = true, onConnect = { _, _ -> })
                LaunchedEffect(g) {
                    gate = when (val outcome = probeDaemon(g.url, g.token)) {
                        ConnectOutcome.Connected -> {
                            if (g.save) store.save(g.url, g.token)
                            hadShell = true
                            Gate.Shell(LayeredSettings({ key ->
                                when (key) {
                                    ShellKeys.WS_URL -> g.url
                                    ShellKeys.TOKEN -> g.token
                                    else -> null
                                }
                            }))
                        }
                        is ConnectOutcome.BadToken -> Gate.Form(outcome.message, g.url)
                        is ConnectOutcome.Unreachable -> Gate.Form(outcome.message, g.url)
                    }
                }
            }
            is Gate.Shell -> key(g) {
                val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
                val speaker = remember(context) { com.superiorsoftllc.agenthq.ui.voice.AndroidSpeaker(context) }
                androidx.compose.runtime.DisposableEffect(speaker) { onDispose { speaker.shutdown() } }
                val host = remember(g) { AndroidPlatformHost(g.settings, onChangeConnection = { gate = Gate.Form() }, speaker = speaker, savedServers = ConnectionSavedServers(store), uiPrefs = PrefsKeyValueStore(context.getSharedPreferences("agenthq_ui", android.content.Context.MODE_PRIVATE))) }
                AgentHqShell(host)
            }
        }
    }
}

/** MOBILE-CONNECT-1: the URL + token form. The token is masked and never pre-filled. */
@Composable
fun ConnectForm(
    savedUrl: String,
    hasSavedToken: Boolean,
    error: String?,
    busy: Boolean,
    onConnect: (url: String, token: String) -> Unit,
    /** PAIR-1: a pairing link scanned or pasted here; null hides the scan and paste controls. */
    onLink: ((PairingLink) -> Unit)? = null,
    onForget: (() -> Unit)? = null,
    onCancel: (() -> Unit)? = null,
) {
    var url by remember(savedUrl) { mutableStateOf(savedUrl) }
    var token by remember { mutableStateOf("") }
    var urlError by remember { mutableStateOf<String?>(null) }
    fun submit() {
        urlError = DaemonUrlValidator.validate(url)
        if (urlError == null) onConnect(url.trim(), token)
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 480.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Connect to Infantry AI", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Enter your daemon's WebSocket URL and bearer token. Over Tailscale, use the machine's tailnet " +
                        "address, for example ws://100.64.0.1:8787/ws.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it; urlError = null },
                    label = { Text("Daemon URL (ws:// or wss://)") },
                    singleLine = true,
                    enabled = !busy,
                    isError = urlError != null,
                    supportingText = urlError?.let { msg -> { Text(msg) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text(if (hasSavedToken) "Bearer token (empty keeps the saved one)" else "Bearer token") },
                    singleLine = true,
                    enabled = !busy,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error != null) {
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
                if (onLink != null && !busy) PairingControls(onLink)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = ::submit, enabled = !busy) { Text("Connect") }
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("Connecting...", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (!busy && onCancel != null) TextButton(onClick = onCancel) { Text("Cancel") }
                }
                if (!busy && onForget != null) {
                    TextButton(onClick = onForget) { Text("Forget saved connection") }
                }
                Text(
                    "The token is encrypted with a key held in the Android Keystore and never shown again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** PAIR-1: connect from a QR scanned with the camera, or from a pasted link. */
@Composable
private fun PairingControls(onLink: (PairingLink) -> Unit) {
    var scanning by remember { mutableStateOf(false) }
    var paste by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    fun accept(text: String) {
        when (val parsed = PairingLink.parse(text)) {
            is PairingLink.Parsed.Ok -> { error = null; onLink(parsed.link) }
            is PairingLink.Parsed.Bad -> error = parsed.reason
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { scanning = true }, modifier = Modifier.fillMaxWidth()) { Text("Scan QR from another device") }
        OutlinedTextField(
            value = paste,
            onValueChange = { paste = it; error = null },
            label = { Text("Or paste an agenthq://connect link") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        if (paste.isNotBlank()) TextButton(onClick = { accept(paste) }) { Text("Connect with link") }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
    }
    if (scanning) {
        Dialog(onDismissRequest = { scanning = false }) {
            CameraQrScanner(onCode = { scanning = false; accept(it) }, onClose = { scanning = false })
        }
    }
}
