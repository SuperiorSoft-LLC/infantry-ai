// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.runners

import com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerLink
import com.superiorsoftllc.infantry.settings.LimitDef
import com.superiorsoftllc.infantry.settings.LimitRegistry
import com.superiorsoftllc.infantry.settings.RunnerLinkSettings
import java.io.File

/**
 * RUNNER-POOL-5: the daemon's share of the runner link, run every `limits.runners.linkCheckSeconds` by [tick]:
 *
 * * Runner side: once this daemon is paired (by `infantry runner pair` on its console), it serves the link
 *   ([RunnerLinkListener], wss on `limits.runners.linkPort`); after a detach from either side it stops serving it.
 * * Orchestrator side: rekeys every paired runner whose credential is `limits.runners.credentialRotateDays` old.
 *
 * A daemon that was never paired opens nothing: no listener, no link keystore ([RunnerLinkStore.exists]).
 */
class RunnerLinkService(
    private val stateDir: File,
    private val limit: (LimitDef) -> Long,
    private val settings: () -> RunnerLinkSettings.Values,
    private val runner: RunnerLink,
    private val host: String = ANY_HOST,
    private val log: (String) -> Unit = { System.err.println(it) },
) : AutoCloseable {
    private var listener: RunnerLinkListener? = null

    @Synchronized
    fun tick() {
        if (!RunnerLinkStore.exists(stateDir)) return
        val store = RunnerLinkStore.open(stateDir)
        val paired = store.orchestrator() != null
        if (paired && listener == null) {
            runCatching { serve(store) }.onFailure { log("[infantry] runner link: could not serve the link: ${it.message}") }
        } else if (!paired && listener != null) {
            close()
            log("[infantry] runner link: detached; the link listener is stopped")
        }
        if (store.runners().isNotEmpty()) {
            peers(store).rotateDue(limit(LimitRegistry.RUNNERS_CREDENTIAL_ROTATE_DAYS)).forEach { log("[infantry] $it") }
        }
    }

    private fun serve(store: RunnerLinkStore) {
        val dir = RunnerLinkStore.dir(stateDir)
        val audit = RunnerAudit(File(dir, RunnerAudit.FILE))
        val server = RunnerLinkServer(store, audit, codes = null, settings = settings, runner = { runner })
        val plain = settings().allowPlainTailnet
        val tls = if (plain) null else RunnerLinkTls.serverContext(RunnerLinkTls.ensure(dir, store.tlsPassword()), store.tlsPassword())
        val l = RunnerLinkListener(server, tls, host, limit(LimitRegistry.RUNNERS_LINK_PORT).toInt(), (limit(LimitRegistry.RUNNERS_LINK_CALL_SECONDS) * MS_PER_SECOND).toInt())
        try {
            val port = l.start()
            listener = l
            log("[infantry] runner link: serving ${if (plain) "PLAIN ws (runners.allowPlainTailnet)" else "wss"} on $host:$port for orchestrator ${store.orchestrator()?.name}")
        } catch (e: Exception) {
            l.close()
            throw e
        }
    }

    /** The orchestrator-side operations over the real client, with the daemon's settings. */
    fun peers(store: RunnerLinkStore): RunnerPeers = RunnerPeers(store, clientCall(limit, settings))

    @Synchronized
    override fun close() {
        listener?.close()
        listener = null
    }

    companion object {
        const val ANY_HOST = "0.0.0.0"
        private const val MS_PER_SECOND = 1000L

        /** [LinkCall] over [RunnerLinkClient] with the link call timeout and the plain-ws switch read at each call. */
        fun clientCall(limit: (LimitDef) -> Long, settings: () -> RunnerLinkSettings.Values) = LinkCall { url, fp, id, cred, type, data ->
            RunnerLinkClient.call(url, fp, settings().allowPlainTailnet, id, cred, type, data, limit(LimitRegistry.RUNNERS_LINK_CALL_SECONDS))
        }
    }
}
