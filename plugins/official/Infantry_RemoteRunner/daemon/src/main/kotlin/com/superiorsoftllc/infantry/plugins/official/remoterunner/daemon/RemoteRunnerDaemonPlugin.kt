// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.remoterunner.daemon

import com.superiorsoftllc.infantry.daemon.api.DaemonApi
import com.superiorsoftllc.infantry.daemon.api.DaemonPlugin
import com.superiorsoftllc.infantry.daemon.api.runners.LinkAnswer
import com.superiorsoftllc.infantry.daemon.api.runners.LinkHandler
import com.superiorsoftllc.infantry.daemon.api.runners.LinkServing
import com.superiorsoftllc.infantry.daemon.api.runners.LinkTlsMaterial
import com.superiorsoftllc.infantry.daemon.api.runners.RunnerLinkTransport

/**
 * RUNNER-POOL-6a (docs/RUNNER-POOL.md 2.2 and 5): Infantry_RemoteRunner's daemon half. It offers the runner link transport
 * ([WssLinkTransport]: wss with a pinned self-signed certificate) to core with `hooks.runnerLinkTransport`. RUNNER-POOL-6 adds
 * the remote runner binding (`hooks.runnerBinding`) here.
 */
class RemoteRunnerDaemonPlugin : DaemonPlugin {
    override fun start(api: DaemonApi) {
        api.hooks.runnerLinkTransport(TRANSPORT_ID, WssLinkTransport)
    }

    companion object {
        const val TRANSPORT_ID = "wss"
    }
}

/** The RUNNER-POOL-5 transport, unchanged: [LinkClient] (JDK WebSocket client, TLS pin), [LinkListener] (ktor), [LinkTls]. */
object WssLinkTransport : RunnerLinkTransport {
    override fun call(
        url: String,
        fingerprint: String?,
        allowPlain: Boolean,
        orchestratorId: String?,
        credential: String?,
        type: String,
        data: String,
        timeoutSeconds: Long,
    ): LinkAnswer = LinkClient.call(url, fingerprint, allowPlain, orchestratorId, credential, type, data, timeoutSeconds)

    override fun serve(handler: LinkHandler, host: String, port: Int, tls: LinkTlsMaterial?, callTimeoutMs: Int): LinkServing {
        val context = tls?.let { LinkTls.serverContext(LinkTls.ensure(it.dir, it.password), it.password) }
        val listener = LinkListener(handler, context, host, port, callTimeoutMs)
        val bound = try {
            listener.start()
        } catch (e: Exception) {
            listener.close()
            throw e
        }
        return object : LinkServing {
            override val port: Int = bound
            override fun close() = listener.close()
        }
    }

    override fun fingerprint(tls: LinkTlsMaterial): String = LinkTls.fingerprint(LinkTls.certificate(LinkTls.ensure(tls.dir, tls.password)))
}
