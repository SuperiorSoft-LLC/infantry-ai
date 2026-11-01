package com.superiorsoftllc.agenthq.ui.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets

internal actual fun createPlatformHttpClient(): HttpClient = HttpClient(CIO) {
    // DISCONNECT-1: a ping every 20s, so a dead or half-open link ends the session (and reconnects) instead of lingering.
    install(WebSockets) { pingIntervalMillis = 20_000 }
}

/** WEB-1: a JVM/Android WebSocket sends the bearer as an Authorization header. */
internal actual val tokenAsSubprotocol: Boolean = false
