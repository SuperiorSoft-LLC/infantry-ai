package com.superiorsoftllc.agenthq.ui.net

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** WEB-1 (contract 1.122.0): the browser's bearer subprotocol is what the daemon's AccessPolicy decodes. */
class BearerSubprotocolTest {
    @Test
    fun theTokenTravelsAsBase64UrlWithoutPaddingAfterThePrefix() {
        for (token in listOf("correct-horse-battery-staple-0123456789", "a/b+c=d?e", "x")) {
            val protocol = bearerSubprotocol(token)
            assertTrue(protocol.startsWith("agenthq.bearer."), protocol)
            val encoded = protocol.removePrefix("agenthq.bearer.")
            // Every character must be an RFC 7230 token char, or the browser's WebSocket constructor throws.
            assertTrue(encoded.all { it.isLetterOrDigit() || it == '-' || it == '_' }, encoded)
            assertEquals(token, String(Base64.getUrlDecoder().decode(encoded)))
        }
    }

    @Test
    fun theJvmClientKeepsTheAuthorizationHeader() {
        assertEquals(false, tokenAsSubprotocol)
        assertEquals("agenthq", WEB_PROTOCOL)
    }
}
