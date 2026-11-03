package com.superiorsoftllc.infantry.ui.net

/**
 * SWITCH-1: the reconnect rules of [DaemonClient], pure so a test pins them. A dropped or failed link retries after an
 * exponential delay; a rejected credential (HTTP 401/403) is terminal: retrying the same token only hammers the server,
 * so the client stops and shows REFUSED until the human changes the token or presses Reconnect now.
 */
object ReconnectPolicy {
    const val MAX_DELAY_MILLIS = 15_000L

    /** Milliseconds to wait before attempt [attempt] (1 = first retry): 1s, 2s, 4s, 8s, 15s, 15s... */
    fun delayMillis(attempt: Int): Long = minOf(MAX_DELAY_MILLIS, 500L * (1L shl minOf(attempt.coerceAtLeast(0), 5)))

    sealed interface Failure {
        /** Worth retrying: the server may come back. */
        data class Retry(val reason: String) : Failure

        /** Retrying cannot help until the human acts. */
        data class Terminal(val reason: String) : Failure
    }

    /** Classifies a failed connection attempt: [status] is the HTTP status of the refusal, if any; [root] the root cause. */
    fun classify(status: Int?, root: Throwable): Failure = when (status) {
        401, 403 -> Failure.Terminal("daemon refused the connection (HTTP $status): bad or missing bearer token")
        else -> Failure.Retry(describe(root))
    }

    /** A reason a person can act on, from the root cause of a failed attempt. */
    fun describe(root: Throwable): String {
        val name = root::class.simpleName ?: "Error"
        val msg = root.message.orEmpty()
        return when {
            name.contains("ConnectException") || msg.contains("refused", ignoreCase = true) -> "connection refused: nothing is listening there (is the server running?)"
            name.contains("UnknownHost") -> "unknown host: $msg"
            name.contains("Timeout") || msg.contains("timed out", ignoreCase = true) -> "timed out reaching the server"
            else -> "$name: ${msg.ifBlank { "no route to daemon" }}"
        }
    }
}
