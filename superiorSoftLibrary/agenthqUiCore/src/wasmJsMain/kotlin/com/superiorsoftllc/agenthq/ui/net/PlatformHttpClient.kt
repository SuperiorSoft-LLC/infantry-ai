package com.superiorsoftllc.agenthq.ui.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.plugins.websocket.WebSockets

internal actual fun createPlatformHttpClient(): HttpClient = HttpClient(Js) {
    install(WebSockets)
}

/** WEB-1: a browser WebSocket cannot set Authorization; Ktor's Js engine turns these headers into `new WebSocket(url, protocols)`. */
internal actual val tokenAsSubprotocol: Boolean = true
