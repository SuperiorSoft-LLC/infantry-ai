package com.superiorsoftllc.agenthq.ui.net

/** WEB-1: the plain subprotocol the daemon answers a browser with. */
internal const val WEB_PROTOCOL = "agenthq"

/** WEB-1: [token] as the bearer subprotocol: `agenthq.bearer.` + base64url, no padding (every character a valid token char). */
@OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
fun bearerSubprotocol(token: String): String =
    "agenthq.bearer." + kotlin.io.encoding.Base64.UrlSafe.withPadding(kotlin.io.encoding.Base64.PaddingOption.ABSENT)
        .encode(token.encodeToByteArray())
