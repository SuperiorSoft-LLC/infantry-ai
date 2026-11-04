// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets

internal actual fun createPlatformHttpClient(): HttpClient = HttpClient(CIO) {
    // CHAT-SENDING-1: ping like the desktop client. Without it a phone's dead link (sleep, a tailnet path change) was never
    // noticed: a send went into the dead socket and only surfaced once TCP gave up, which read as a message delayed or lost.
    install(WebSockets) { pingIntervalMillis = 20_000 }
}

/** WEB-1: a JVM/Android WebSocket sends the bearer as an Authorization header. */
internal actual val tokenAsSubprotocol: Boolean = false
