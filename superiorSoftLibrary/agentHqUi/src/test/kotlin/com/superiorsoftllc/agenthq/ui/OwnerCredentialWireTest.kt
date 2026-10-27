package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.daemon.OwnerCredential
import com.superiorsoftllc.agenthq.ui.net.OWNER_HEADER
import com.superiorsoftllc.agenthq.ui.net.ownerSubprotocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** SEC-FIX-1 (contract 1.136.0): what the client sends as the owner is what the daemon reads as the owner. */
class OwnerCredentialWireTest {
    private val token = "Ab-_0123456789abcdefghijklmnopqrstuvwxyzABCD"

    @Test fun `the client's owner header and subprotocol are the daemon's`() {
        assertEquals(OwnerCredential.HEADER, OWNER_HEADER)
        assertEquals(OwnerCredential.protocol(token), ownerSubprotocol(token))
        val credential = OwnerCredential(token)
        assertTrue(credential.presentedBy(null, "agenthq, agenthq.bearer.eHl6, " + ownerSubprotocol(token)))
        assertFalse(credential.presentedBy(null, "agenthq, " + ownerSubprotocol(token.reversed())))
    }
}
