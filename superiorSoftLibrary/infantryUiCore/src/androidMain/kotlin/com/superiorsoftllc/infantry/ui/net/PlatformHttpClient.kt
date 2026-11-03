package com.superiorsoftllc.infantry.ui.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets

internal actual fun createPlatformHttpClient(): HttpClient = HttpClient(CIO) {
    install(WebSockets)
}

/** WEB-1: a JVM/Android WebSocket sends the bearer as an Authorization header. */
internal actual val tokenAsSubprotocol: Boolean = false
