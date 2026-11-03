package com.superiorsoftllc.infantry.ui.net

/** WEB-1: the plain subprotocol the daemon answers a browser with. */
internal const val WEB_PROTOCOL = "infantry"

/** WEB-1: [token] as the bearer subprotocol: `infantry.bearer.` + base64url, no padding (every character a valid token char). */
@OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
fun bearerSubprotocol(token: String): String =
    "infantry.bearer." + kotlin.io.encoding.Base64.UrlSafe.withPadding(kotlin.io.encoding.Base64.PaddingOption.ABSENT)
        .encode(token.encodeToByteArray())

/** SEC-FIX-1 (contract 1.136.0): the header that carries the owner credential (a JVM / Android socket). */
const val OWNER_HEADER = "X-Infantry-Owner"

/** SEC-FIX-1: [token] as the owner subprotocol a browser offers: `infantry.owner.` + base64url, no padding. */
@OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
fun ownerSubprotocol(token: String): String =
    "infantry.owner." + kotlin.io.encoding.Base64.UrlSafe.withPadding(kotlin.io.encoding.Base64.PaddingOption.ABSENT)
        .encode(token.encodeToByteArray())
