// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.remoterunner.daemon

import kotlinx.serialization.Serializable

/**
 * RUNNER-POOL-6a: what this transport puts on the wire, unchanged from RUNNER-POOL-5 (fdbe64ba), so machines on either side of
 * the move still talk: the link lives at [PATH]; the pair credential and the caller's id travel in the connection's
 * [CREDENTIAL_HEADER] / [ORCHESTRATOR_HEADER], never in a frame; each text frame is one [LinkWire.Envelope] (the daemon's envelope
 * shape: `type`, `data`, `requestId`, other fields ignored).
 */
object LinkWire {
    const val PATH = "/runner-link"
    const val CREDENTIAL_HEADER = "X-Infantry-Runner-Credential"
    const val ORCHESTRATOR_HEADER = "X-Infantry-Orchestrator"

    @Serializable
    data class Envelope(val type: String, val data: String = "", val sessionId: String? = null, val requestId: String? = null)
}
