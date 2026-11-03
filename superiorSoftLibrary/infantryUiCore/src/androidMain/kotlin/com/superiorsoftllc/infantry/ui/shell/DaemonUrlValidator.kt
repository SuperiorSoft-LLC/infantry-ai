package com.superiorsoftllc.infantry.ui.shell

import com.superiorsoftllc.infantry.ui.model.ConnState
import com.superiorsoftllc.infantry.ui.model.ConnectionInfo
import java.net.URI

/** MOBILE-CONNECT-1: the daemon URL a user typed must be ws:// or wss:// with a host. */
object DaemonUrlValidator {
    /** Null when [raw] (trimmed) is usable, else a message for the user. */
    fun validate(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty()) return "Enter the daemon URL, for example ws://100.64.0.1:8787/ws"
        val uri = runCatching { URI(text) }.getOrNull() ?: return "That is not a valid URL"
        val scheme = uri.scheme?.lowercase()
        if (scheme != "ws" && scheme != "wss") return "The URL must start with ws:// or wss://"
        if (uri.host.isNullOrBlank()) return "The URL needs a host, for example ws://100.64.0.1:8787/ws"
        if (uri.port != -1 && uri.port !in 1..65535) return "The port must be between 1 and 65535"
        return null
    }
}

/** MOBILE-CONNECT-1: the verdict of a connection attempt, from the client's [ConnectionInfo]. */
sealed interface ConnectOutcome {
    data object Connected : ConnectOutcome
    data class BadToken(val message: String) : ConnectOutcome
    data class Unreachable(val message: String) : ConnectOutcome

    companion object {
        /** Null while the attempt is still undecided. */
        fun of(info: ConnectionInfo): ConnectOutcome? = when (info.state) {
            ConnState.CONNECTED -> Connected
            ConnState.REFUSED -> {
                val code = Regex("""\b(401|403)\b""").find(info.detail)?.value ?: "401"
                BadToken("Token rejected (HTTP $code): check the bearer token")
            }
            ConnState.RECONNECTING, ConnState.FAILED ->
                Unreachable("Daemon unreachable: ${info.detail.ifBlank { "no route to ${info.url}" }}")
            ConnState.IDLE, ConnState.CONNECTING -> null
        }
    }
}
