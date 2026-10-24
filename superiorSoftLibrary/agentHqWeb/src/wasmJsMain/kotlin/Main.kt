import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeViewport
import com.superiorsoftllc.agenthq.ui.net.Controller
import com.superiorsoftllc.agenthq.ui.shell.AgentHqShell
import com.superiorsoftllc.agenthq.ui.shell.BrowserStorage
import com.superiorsoftllc.agenthq.ui.shell.WasmPlatformHost
import com.superiorsoftllc.agenthq.ui.shell.pageWebSocketUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.await
import kotlinx.coroutines.cancel
import kotlin.js.Promise

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport { WebRoot() }
}

/** What the page shows: the token screen, the app on the real daemon, or the offline demo. One owner: [WebRoot]. */
private sealed interface WebMode {
    /** The token screen; [error] says why the last token was not used. */
    data class Gate(val error: String? = null) : WebMode
    /** Asking the daemon whether [token] is accepted, before the app opens its WebSocket. */
    data class Checking(val token: String) : WebMode
    data class Live(val token: String) : WebMode
    data object Demo : WebMode
}

@Composable
private fun WebRoot() {
    var mode by remember { mutableStateOf<WebMode>(BrowserStorage.get(BrowserStorage.TOKEN_KEY)?.let { WebMode.Checking(it) } ?: WebMode.Gate()) }
    when (val m = mode) {
        is WebMode.Gate -> TokenGate(
            savedToken = BrowserStorage.get(BrowserStorage.TOKEN_KEY),
            error = m.error,
            onConnect = { token -> mode = WebMode.Checking(token) },
            onForget = { BrowserStorage.remove(BrowserStorage.TOKEN_KEY) },
            onDemo = { mode = WebMode.Demo },
        )
        is WebMode.Checking -> {
            // A browser is never told why a WebSocket handshake failed (a 401 looks like a network error), so the
            // token is checked over plain HTTP first: a refused one goes back to the token screen with the reason.
            LaunchedEffect(m.token) {
                mode = when (val status = TokenCheck.status(m.token)) {
                    200 -> { BrowserStorage.set(BrowserStorage.TOKEN_KEY, m.token); WebMode.Live(m.token) }
                    401 -> WebMode.Gate("The daemon refused that token. Check it and try again.")
                    0 -> WebMode.Gate("Could not reach the daemon at ${pageWebSocketUrl()}. Is it running?")
                    else -> WebMode.Gate("The daemon answered HTTP $status while checking the token.")
                }
            }
            CenteredNote("Checking the token...")
        }
        is WebMode.Live -> key(m.token) {
            val host = remember { WasmPlatformHost(token = m.token, onChangeConnection = { mode = WebMode.Gate() }) }
            AgentHqShell(host)
        }
        WebMode.Demo -> {
            val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
            DisposableEffect(scope) { onDispose { scope.cancel() } }
            val controller = remember { Controller(scope, FakeDaemonClient(scope)) }
            AgentHqShell(WasmPlatformHost(token = null, onChangeConnection = { mode = WebMode.Gate() }), controller)
        }
    }
}

@Composable
private fun CenteredNote(text: String) {
    Box(Modifier.fillMaxSize().background(Color(0xFF111111)), contentAlignment = Alignment.Center) {
        Text(text, color = Color(0xFFBBBBBB))
    }
}

/** The token check: an authenticated same-origin GET (no Origin header, so the daemon's ordinary bearer check answers). */
private object TokenCheck {
    /** 200 accepted, 401 refused, 0 unreachable, else the HTTP status. */
    suspend fun status(token: String): Int = fetchStatus(token).await<JsNumber>().toInt()
}

private fun fetchStatus(token: String): Promise<JsNumber> =
    js("fetch('/attention', { headers: { 'Authorization': 'Bearer ' + token }, cache: 'no-store' }).then(r => r.status).catch(() => 0)")

const val TOKEN_FIELD_TAG = "web-token-field"
const val CONNECT_TAG = "web-connect"

/** First load (or the connection chip): ask for the daemon's token; it is kept in this browser's localStorage. */
@Composable
private fun TokenGate(savedToken: String?, error: String?, onConnect: (String) -> Unit, onForget: () -> Unit, onDemo: () -> Unit) {
    var token by remember { mutableStateOf(savedToken.orEmpty()) }
    var hasSaved by remember { mutableStateOf(savedToken != null) }
    val submit = { token.trim().takeIf { it.isNotEmpty() }?.let(onConnect) }
    MaterialTheme(colorScheme = darkColorScheme()) {
        Box(Modifier.fillMaxSize().background(Color(0xFF111111)), contentAlignment = Alignment.Center) {
            Column(
                Modifier.widthIn(max = 460.dp).fillMaxWidth().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Infantry AI", style = MaterialTheme.typography.headlineMedium, color = Color.White)
                Text(
                    "Connect to the daemon at ${pageWebSocketUrl()}. Enter its access token: it is kept in this browser " +
                        "so you are not asked again.",
                    color = Color(0xFFBBBBBB),
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("Access token") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { submit() }),
                    modifier = Modifier.fillMaxWidth().testTag(TOKEN_FIELD_TAG).semantics { contentDescription = "Access token" },
                )
                if (error != null) {
                    Text(error, color = Color(0xFFFF8A80), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { submit() }, enabled = token.isNotBlank(), modifier = Modifier.testTag(CONNECT_TAG)) { Text("Connect") }
                    if (hasSaved) {
                        TextButton(onClick = { onForget(); hasSaved = false; token = "" }) { Text("Forget the saved token") }
                    }
                }
                Text(
                    "The token is the daemon's AGENTHQ_TOKEN (or the contents of AGENTHQ_TOKEN_FILE). A daemon with no " +
                        "token only accepts this machine's own apps: turn on remote access to use the browser.",
                    color = Color(0xFF888888),
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = onDemo) { Text("Try the offline demo (fake data, no daemon)") }
            }
        }
    }
}
