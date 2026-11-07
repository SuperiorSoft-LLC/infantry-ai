// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.api.runners

import java.io.File

/**
 * RUNNER-POOL-6a (1.44.0, additive; docs/RUNNER-POOL.md 2.2 and 5): the runner link's TRANSPORT, offered by a plugin with
 * `api.hooks.runnerLinkTransport(id, transport)` (declare `runnerLinkTransport` in `daemon.hooks`). Core keeps the link's
 * meaning (frames, pairing, credentials, audit, allow-lists, rekey); the transport only carries one request frame to a runner
 * and its one answer back, and serves the runner's end. With no transport registered the daemon serves no link and the
 * `infantry runner` commands that need one say so.
 *
 * A frame is `(type, data)`; the caller's id and credential travel beside it (never in the frame body), and the answer is a
 * [LinkAnswer]. The transport authenticates the RUNNER (TLS pinned to [fingerprint]); core authenticates the ORCHESTRATOR (the
 * pair credential) in the handler.
 */
interface RunnerLinkTransport {
    /**
     * One orchestrator -> runner call: reach [url], check the runner's certificate against [fingerprint] (a plain URL only when
     * [allowPlain]), send `(type, data)` with [orchestratorId] and [credential], return the answer. Throws [LinkFailure] when no
     * answer arrives; [LinkFailure.presentedFingerprint] is set when the pin refused the certificate (then nothing was sent).
     */
    fun call(
        url: String,
        fingerprint: String?,
        allowPlain: Boolean,
        orchestratorId: String?,
        credential: String?,
        type: String,
        data: String,
        timeoutSeconds: Long,
    ): LinkAnswer

    /**
     * Serves the runner's end on [host]:[port] (0 = any free port) until closed: every request goes to [handler]. With [tls] the
     * link is encrypted with the runner's certificate (made under [LinkTlsMaterial.dir] on first use); null = plain (only
     * `runners.allowPlainTailnet` asks for that). [callTimeoutMs] bounds a connection's handshake.
     */
    fun serve(handler: LinkHandler, host: String, port: Int, tls: LinkTlsMaterial?, callTimeoutMs: Int): LinkServing

    /** The SHA-256 fingerprint of the runner certificate under [tls] (made on first use), in the form `runner pair` prints. */
    fun fingerprint(tls: LinkTlsMaterial): String
}

/** One link answer: core's reply or error frame [type] and its [data]. */
data class LinkAnswer(val type: String, val data: String)

/** A link call that got no answer. [presentedFingerprint] is set when the TLS pin refused the runner's certificate. */
class LinkFailure(message: String, val presentedFingerprint: String? = null) : Exception(message)

/**
 * Where the runner's certificate lives: core hands the transport the link folder and the store password it keeps in its own
 * link keystore; the transport makes, loads and uses the certificate there.
 */
data class LinkTlsMaterial(val dir: File, val password: String)

/** The runner's handler: one request in, one answer out. [from] is the peer address, for the audit. */
interface LinkHandler {
    fun handle(type: String, data: String, orchestratorId: String?, credential: String?, from: String?): LinkAnswer

    /** The answer to a frame the transport could not read as a request. */
    fun malformed(): LinkAnswer
}

/** A served link; [port] is the port it is reachable on. Closing stops it. */
interface LinkServing : AutoCloseable {
    val port: Int
}
