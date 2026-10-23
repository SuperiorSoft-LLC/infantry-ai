package com.superiorsoftllc.agenthq.ui.shell

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier

/**
 * Android entry: the container for [AgentHqShell]. Intent extras (AGENTHQ_WS_URL / AGENTHQ_TOKEN / AGENTHQ_TOKEN_FILE)
 * win and open the shell directly (tests, adb). Otherwise MOBILE-CONNECT-1's [AndroidConnectGate] uses the saved
 * connection: URL in the "agenthq" prefs, token sealed by [KeystoreTokenCipher] in "agenthq_secure".
 */
class AgentHqActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = ConnectionStore(
            plain = PrefsKeyValueStore(getSharedPreferences("agenthq", Context.MODE_PRIVATE)),
            secure = PrefsKeyValueStore(getSharedPreferences("agenthq_secure", Context.MODE_PRIVATE)),
            cipher = KeystoreTokenCipher(),
        )
        // MOBILE-DRAWERS-1: edge-to-edge puts the window under the status/navigation bars and the keyboard; keep the
        // composer's send and switch buttons above them.
        openedLink.value = pairingLinkOf(intent)
        setContent {
            Box(Modifier.safeDrawingPadding()) {
                AndroidConnectGate(store, extras = { intent?.getStringExtra(it) }, openedLink = openedLink.value, onOpenedLinkHandled = { openedLink.value = null })
            }
        }
    }

    /** PAIR-1: the `agenthq://connect` link the app was opened with; consumed once the user answers. */
    private val openedLink = mutableStateOf<String?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pairingLinkOf(intent)?.let { openedLink.value = it }
    }

    private fun pairingLinkOf(intent: Intent?): String? =
        intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString?.takeIf { it.startsWith("agenthq:", ignoreCase = true) }
}
