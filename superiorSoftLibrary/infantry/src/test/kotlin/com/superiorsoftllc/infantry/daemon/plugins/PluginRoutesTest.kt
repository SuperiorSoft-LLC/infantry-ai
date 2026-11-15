// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.plugins

import com.superiorsoftllc.infantry.daemon.TestDaemonPlugins
import com.superiorsoftllc.infantry.daemon.api.HttpCall
import com.superiorsoftllc.infantry.plugin.host.ModuleResolver
import com.superiorsoftllc.infantry.plugin.host.PluginFolders
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * HTTP-SEAM-1: plugin-owned HTTP routes. The plugin is the repo's BUILT example (Acme_NewsAgent, which mounts `GET headlines`).
 * - the route answers once the plugin is loaded and is gone after it unloads;
 * - a route listed in `daemon.publicRoutes` that the plugin registered WITHOUT a declared check refuses the plugin at load.
 */
class PluginRoutesTest {
    private val news = "Acme_NewsAgent"
    private val dirs = mutableListOf<File>()
    private val hosts = mutableListOf<DaemonPluginHost>()

    @AfterTest fun cleanup() {
        hosts.forEach { runCatching { it.close() } }
        dirs.forEach { runCatching { it.deleteRecursively() } }
    }

    private fun host(publicRoute: Boolean): Pair<DaemonPluginHost, File> {
        val root = Files.createTempDirectory("routes-root").toFile().also { dirs += it }
        File(root, "modules.json").writeText(
            """{"folders":[{"path":"community","tier":"community"}],"modules":{"$news":{"enabled":true,"allow":["daemon.code","daemon.tool","daemon.route","daemon.env:ACME_NEWS_FEED_URL"]}}}""",
        )
        val dest = File(root, "community/$news")
        TestDaemonPlugins.copy(File(TestDaemonPlugins.repo, "plugins/examples/$news"), dest)
        if (publicRoute) {
            val manifest = File(dest, "module.json")
            manifest.writeText(manifest.readText().replace(""""daemon": {""", """"daemon": { "publicRoutes": ["GET /plugins/$news/headlines"],"""))
        }
        val config = PluginFolders.config(root)
        val resolution = ModuleResolver.resolve(PluginFolders.scan(root, config, null), config, false)
        val h = DaemonPluginHost.load(resolution, config, TestDaemonPlugins.trustAll, DaemonServices.NONE, File(root, "state"), {})
        return h.also { hosts += it } to root
    }

    @Test fun `a plugin route answers while the plugin is loaded and is gone after it unloads`() {
        val (h, _) = host(publicRoute = false)
        assertEquals("loaded", h.status(news))
        val reply = assertNotNull(h.answerRoute(HttpCall("GET", "/plugins/$news/headlines")))
        assertEquals(200, reply.status)
        h.unload(news)
        assertNull(h.answerRoute(HttpCall("GET", "/plugins/$news/headlines")), "an unloaded plugin's route is unmounted")
    }

    @Test fun `a public route without a declared check is refused at load`() {
        val (h, _) = host(publicRoute = true)
        val status = h.status(news).orEmpty()
        assertTrue(status.startsWith("refused") || status.startsWith("failed"), status)
        assertNull(h.answerRoute(HttpCall("GET", "/plugins/$news/headlines")), "the refused plugin mounted nothing")
    }
}
