package com.superiorsoftllc.agenthq.ui.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.plugins.websocket.WebSockets

internal actual fun createPlatformHttpClient(): HttpClient = HttpClient(Js) {
    install(WebSockets)
}
