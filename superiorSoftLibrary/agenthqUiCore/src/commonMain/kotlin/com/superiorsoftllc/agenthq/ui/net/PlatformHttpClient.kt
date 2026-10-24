package com.superiorsoftllc.agenthq.ui.net

import io.ktor.client.HttpClient

/** Platform-specific engine selection; protocol, reconnect and replay policy stay in common code. */
internal expect fun createPlatformHttpClient(): HttpClient

/**
 * WEB-1 (contract 1.122.0): true where a WebSocket cannot set request headers (the browser). The bearer then travels as
 * the `agenthq.bearer.<base64url token>` subprotocol, offered together with the plain `agenthq` one.
 */
internal expect val tokenAsSubprotocol: Boolean
