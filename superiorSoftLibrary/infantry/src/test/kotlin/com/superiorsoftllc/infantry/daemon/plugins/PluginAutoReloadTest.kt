// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.plugins

import com.superiorsoftllc.infantry.daemon.TestDaemonPlugins
import com.superiorsoftllc.infantry.daemon.api.Caller
import com.superiorsoftllc.infantry.daemon.api.ToolInvoker
import com.superiorsoftllc.infantry.plugin.host.ModuleResolver
import com.superiorsoftllc.infantry.plugin.host.PluginFolders
import com.superiorsoftllc.infantry.settings.LimitDef
import com.superiorsoftllc.infantry.settings.LimitRegistry
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * SCRIPTING-NCDFE-1: a daemon plugin marked failed at run time (one call threw) comes back by itself after
 * limits.plugins.autoReload.delayMs, at most limits.plugins.autoReload.attempts times; 0 attempts = it stays down.
 * The plugin is the repo's built Acme_DaemonHello, whose ready hook throws while ACME_HELLO_FAIL=1.
 */
class PluginAutoReloadTest {
    private val hello = "Acme_DaemonHello"
    private val dirs = mutableListOf<File>()
    private val hosts = mutableListOf<DaemonPluginHost>()
    @Volatile private var fail = true

    @AfterTest fun cleanup() {
        hosts.forEach { runCatching { it.close() } }
        dirs.forEach { runCatching { it.deleteRecursively() } }
    }

    private fun host(attempts: Long): DaemonPluginHost {
        val root = Files.createTempDirectory("autoreload-root").toFile().also { dirs += it }
        File(root, "modules.json").writeText("""{"folders":[{"path":"community","tier":"community"}],"modules":{"$hello":{"enabled":true,"allow":["daemon.code","daemon.tool","daemon.route","daemon.hook.ready","daemon.env:ACME_HELLO_FAIL"]}}}""")
        TestDaemonPlugins.copy(File(TestDaemonPlugins.repo, "plugins/examples/$hello"), File(root, "community/$hello"))
        val services = object : DaemonServices by DaemonServices.NONE {
            override fun env(name: String): String? = if (name == "ACME_HELLO_FAIL" && fail) "1" else null
            override fun limit(def: LimitDef): Long = when (def) {
                LimitRegistry.PLUGIN_AUTO_RELOAD_ATTEMPTS -> attempts
                LimitRegistry.PLUGIN_AUTO_RELOAD_DELAY_MS -> 0
                else -> def.default
            }
        }
        val source = {
            val config = PluginFolders.config(root)
            DaemonPluginHost.HotSource(ModuleResolver.resolve(PluginFolders.scan(root, config, null), config, false), config, TestDaemonPlugins.trustAll)
        }
        val src = source()
        return DaemonPluginHost.load(src.resolution, src.config, src.trust, services, File(root, "state"), {}).also { it.hotSource = source; hosts += it }
    }

    private fun DaemonPluginHost.hello() = invokeTool("acme_hello", JsonObject(emptyMap()), ToolInvoker(Caller(owner = true)))

    private fun waitFor(what: String, cond: () -> Boolean) {
        val until = System.currentTimeMillis() + 10_000
        while (!cond()) { check(System.currentTimeMillis() < until) { "timed out waiting for $what" }; Thread.sleep(20) }
    }

    @Test fun `a plugin that failed at run time is reloaded by itself and serves again`() {
        val h = host(attempts = 3)
        assertFalse(h.hello().isError)
        h.readyFields() // the ready hook throws: the plugin is marked failed and unregistered
        assertTrue(h.status(hello)!!.startsWith("failed"), h.status(hello))
        fail = false
        waitFor("the automatic reload") { h.status(hello) == "loaded" }
        val r = h.hello()
        assertFalse(r.isError, r.text)
        assertEquals("hello", h.readyFields()["acmeHello"])
    }

    @Test fun `automatic reloads stop at the attempts limit`() {
        val h = host(attempts = 2)
        repeat(2) { n ->
            h.readyFields()
            waitFor("reload ${n + 1}") { h.status(hello) == "loaded" }
        }
        h.readyFields()
        Thread.sleep(300)
        assertTrue(h.status(hello)!!.startsWith("failed"), "a third failure stays down: ${h.status(hello)}")
        assertTrue(h.hello().isError)
    }

    @Test fun `attempts 0 turns automatic reload off`() {
        val h = host(attempts = 0)
        h.readyFields()
        fail = false
        Thread.sleep(300)
        assertTrue(h.status(hello)!!.startsWith("failed"), h.status(hello))
    }
}
