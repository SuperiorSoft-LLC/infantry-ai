// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.plugins

import com.superiorsoftllc.infantry.daemon.TestDaemonPlugins
import com.superiorsoftllc.infantry.daemon.api.Caller
import com.superiorsoftllc.infantry.daemon.api.ToolInvoker
import com.superiorsoftllc.infantry.plugin.host.ModuleResolver
import com.superiorsoftllc.infantry.plugin.host.PluginFolders
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * PLUGIN-HOTLOAD-1: one daemon half is loaded, unloaded and reloaded while the host runs. The plugins are the repo's BUILT example
 * jars (Acme_DaemonHello, Acme_PolicyHook) in a temp community folder, exactly as a deployment loads them.
 */
class DaemonPluginHostHotloadTest {
    private val hello = "Acme_DaemonHello"
    private val policy = "Acme_PolicyHook"
    private val dirs = mutableListOf<File>()
    private val hosts = mutableListOf<DaemonPluginHost>()

    @AfterTest fun cleanup() {
        hosts.forEach { runCatching { it.close() } }
        dirs.forEach { runCatching { it.deleteRecursively() } }
    }

    private val grants = """{"$hello":{"enabled":true,"allow":["daemon.code","daemon.tool","daemon.route","daemon.hook.ready","daemon.env:ACME_HELLO_FAIL"]},""" +
        """"$policy":{"enabled":true,"allow":["daemon.code","daemon.tool","daemon.hook.frameGate"]}}"""

    private fun newRoot(vararg modules: String): File {
        val root = Files.createTempDirectory("hotload-root").toFile().also { dirs += it }
        File(root, "modules.json").writeText("""{"folders":[{"path":"community","tier":"community"}],"modules":$grants}""")
        modules.forEach { install(root, it) }
        return root
    }

    /** Puts the built example module into the running host's plugins root, as the plugin manager's install does. */
    private fun install(root: File, module: String) = TestDaemonPlugins.copy(File(TestDaemonPlugins.repo, "plugins/examples/$module"), File(root, "community/$module"))

    private fun source(root: File) = {
        val config = PluginFolders.config(root)
        DaemonPluginHost.HotSource(ModuleResolver.resolve(PluginFolders.scan(root, config, null), config, false), config, TestDaemonPlugins.trustAll)
    }

    private fun host(root: File): DaemonPluginHost {
        val src = source(root)()
        val h = DaemonPluginHost.load(src.resolution, src.config, src.trust, DaemonServices.NONE, File(root, "state"), {})
        h.hotSource = source(root)
        return h.also { hosts += it }
    }

    private fun DaemonPluginHost.call(tool: String) = invokeTool(tool, JsonObject(emptyMap()), ToolInvoker(Caller(owner = true)))

    @Test fun `a plugin installed while the host runs is loaded and its tool answers`() {
        val root = newRoot(policy)
        val h = host(root)
        assertTrue(h.toolsOf(hello).isEmpty())
        assertFalse(h.load(hello).ok, "nothing to load before the files are there")
        install(root, hello)
        val out = h.load(hello)
        assertTrue(out.ok, out.message)
        assertEquals(listOf(hello), out.loaded)
        assertEquals(listOf("acme_hello"), h.toolsOf(hello))
        assertEquals("loaded", h.status(hello))
        val r = h.call("acme_hello")
        assertFalse(r.isError, r.text)
        assertTrue(h.load(hello).ok, "loading a loaded plugin is a no-op, not an error")
    }

    @Test fun `unloading drops every registration, frees the jar, and leaves other plugins alone`() {
        val root = newRoot(hello, policy)
        val h = host(root)
        assertFalse(h.call("acme_hello").isError)
        assertTrue(h.isPublicRoute("GET", "/plugins/$hello/hello") || h.toolsOf(hello).isNotEmpty())
        val out = h.unload(hello)
        assertTrue(out.ok, out.message)
        assertEquals(listOf(hello), out.unloaded)
        assertTrue(h.toolsOf(hello).isEmpty())
        assertEquals(null, h.status(hello))
        val gone = h.call("acme_hello")
        assertTrue(gone.isError && "Unknown tool" in gone.text, gone.text)
        // the class loader is closed: on Windows an open jar cannot be deleted
        assertTrue(File(root, "community/$hello").deleteRecursively(), "the unloaded plugin's folder is deletable")
        // the other plugin is unaffected
        assertEquals("loaded", h.status(policy))
        assertFalse(h.call("acme_policy_check").isError)
        assertTrue(h.unload(hello).ok, "unloading what is not loaded is fine")
    }

    @Test fun `reload starts the plugin again from the files as they are now`() {
        val root = newRoot(hello, policy)
        val h = host(root)
        val before = h.toolsOf(hello)
        val out = h.reload(hello)
        assertTrue(out.ok, out.message)
        assertEquals(listOf(hello), out.unloaded)
        assertEquals(listOf(hello), out.loaded)
        assertEquals(before, h.toolsOf(hello))
        assertFalse(h.call("acme_hello").isError)
        assertEquals("loaded", h.status(policy))
    }

    @Test fun `a dependent leaves with the plugin it needs and comes back with a reload`() {
        val root = newRoot(hello, policy)
        val manifest = File(root, "community/$policy/module.json")
        manifest.writeText(manifest.readText().replaceFirst("\"apiVersion\": 2,", "\"apiVersion\": 2,\n  \"dependencies\": [{ \"id\": \"$hello\" }],"))
        val h = host(root)
        assertEquals("loaded", h.status(policy), "the dependent starts at boot")
        val out = h.unload(hello)
        assertEquals(listOf(policy, hello), out.unloaded, "dependents go first, the asked one last")
        assertTrue(h.toolsOf(policy).isEmpty() && h.toolsOf(hello).isEmpty())
        assertFalse(h.load(policy).ok, "a dependent cannot load while its dependency is out")
        assertTrue(h.load(hello).ok)
        assertTrue(h.load(policy).ok)
        val again = h.reload(hello)
        assertTrue(again.ok, again.message)
        assertEquals(listOf(policy, hello), again.unloaded)
        assertTrue(policy in again.loaded && hello in again.loaded, again.message)
        assertFalse(h.call("acme_policy_check").isError)
    }

    /** PLUGIN-HOTSWAP-1: the unload check waits briefly here, so the leak case does not cost the default 5 s. */
    private fun quickHost(root: File): DaemonPluginHost {
        val src = source(root)()
        val services = object : DaemonServices by DaemonServices.NONE {
            override fun limit(def: com.superiorsoftllc.infantry.settings.LimitDef): Long =
                if (def == com.superiorsoftllc.infantry.settings.LimitRegistry.PLUGIN_UNLOAD_CHECK_MS) 500 else def.default
        }
        val h = DaemonPluginHost.load(src.resolution, src.config, src.trust, services, File(root, "state"), {})
        h.hotSource = source(root)
        return h.also { hosts += it }
    }

    @Test fun `a clean unload and reload carry no leak note`() {
        val root = newRoot(hello, policy)
        val h = quickHost(root)
        val out = h.reload(hello)
        assertTrue(out.ok, out.message)
        assertTrue(out.notes.none { "did not unload cleanly" in it }, out.notes.toString())
        val off = h.unload(hello)
        assertTrue(off.notes.none { "did not unload cleanly" in it }, off.notes.toString())
    }

    @Test fun `a half whose old class loader stays reachable is reported, and the daemon keeps serving`() {
        val root = newRoot(hello, policy)
        val h = quickHost(root)
        val held = h.loaderOf(hello) // the leak: a reference from outside the plugin
        assertTrue(held != null)
        val out = h.reload(hello)
        assertTrue(out.ok, out.message)
        assertTrue(out.notes.any { "$hello did not unload cleanly" in it }, out.notes.toString())
        assertFalse(h.call("acme_hello").isError, "the new version serves")
        assertEquals("loaded", h.status(policy), "the other plugin is untouched")
        assertTrue(held !== h.loaderOf(hello))
    }

    @Test fun `a host with no plugin source refuses a live load and says so`() {
        val h = DaemonPluginHost.empty()
        val out = h.load(hello)
        assertFalse(out.ok)
        assertTrue("cannot load plugins live" in out.message, out.message)
    }
}
