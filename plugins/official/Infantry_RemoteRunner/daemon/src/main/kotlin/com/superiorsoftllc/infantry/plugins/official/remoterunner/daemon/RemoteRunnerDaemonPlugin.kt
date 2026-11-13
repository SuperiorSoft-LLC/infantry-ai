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
 * the remote runner binding (`hooks.runnerBinding`, [RemoteRunnerLink]): every command to a paired runner is one frame.
 */
class RemoteRunnerDaemonPlugin : DaemonPlugin {
    override fun start(api: DaemonApi) {
        api.hooks.runnerLinkTransport(TRANSPORT_ID, WssLinkTransport)
        api.hooks.runnerBinding(BINDING_ID) { name, _ -> RemoteRunnerLink(name, api.runnerLinks) }
        // RUNNER-UPDATE-1: "Update runner" for a runner older than the app (recreate its local container on the matching image).
        val updater = RunnerUpdater(
            api.runnerLinks, ProcessDocker,
            settingsOf = {
                RunnerUpdater.Settings(
                image = api.config(KEY_IMAGE).orEmpty().trim(),
                registryImage = api.config(KEY_REGISTRY)?.trim()?.ifEmpty { null } ?: RunnerUpdater.DEFAULT_REGISTRY_IMAGE,
                waitSec = api.config(KEY_WAIT)?.trim()?.toLongOrNull()?.takeIf { it > 0 } ?: RunnerUpdater.DEFAULT_WAIT_SEC,
                dockerSec = api.config(KEY_DOCKER)?.trim()?.toLongOrNull()?.takeIf { it > 0 } ?: RunnerUpdater.DEFAULT_DOCKER_SEC,
                )
            },
            build = { System.getProperty(BUILD_PROPERTY) },
            version = { System.getProperty(VERSION_PROPERTY) },
        )
        val frame = RunnerUpdateFrame(api, updater)
        api.frames.handle(RunnerUpdateFrame.TYPE) { f, caller -> frame.handle(f, caller) }
    }

    companion object {
        const val TRANSPORT_ID = "wss"
        const val BINDING_ID = "remote"

        /** RUNNER-UPDATE-1: module.json `daemon.config` keys (node `infantry.runners.update`). */
        const val KEY_IMAGE = "runners.update.image"
        const val KEY_REGISTRY = "runners.update.registryImage"
        const val KEY_WAIT = "runners.update.waitSec"
        const val KEY_DOCKER = "runners.update.dockerSec"

        /** The app's build string and version the launcher sets (`-Dinfantry.build`, `-Dinfantry.version`; BUILD-STAMP-1). */
        const val BUILD_PROPERTY = "infantry.build"
        const val VERSION_PROPERTY = "infantry.version"
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
