// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.shell

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PLUGIN-HOTSWAP-1: the desktop host's live swap and its leak check. A plugin whose old version stays reachable (here: the
 * test keeps its module object, as a leaky plugin's static or global listener would) is reported, so the shell falls back
 * to the soft restart; a clean one is not. The built Acme_Hello jar is the plugin.
 */
class JvmPlatformHostSwapTest {
    private val state = Files.createTempDirectory("host-swap-state").toFile()
    private val home = Files.createTempDirectory("host-swap-home").toFile()
    private val module = File(state, "plugins/community/Acme_Hello")

    @AfterTest fun cleanup() {
        state.deleteRecursively()
        home.deleteRecursively()
    }

    private fun enable(on: Boolean) =
        File(state, "plugins/modules.json").writeText("""{"modules":{"Acme_Hello":{"enabled":$on,"allow":["events","storage","daemon","notifications"]}}}""")

    private fun host(): JvmPlatformHost {
        val source = File(System.getProperty("acme.hello.dir") ?: error("acme.hello.dir not set: run through Gradle jvmTest"))
        File(source, "module.json").copyTo(File(module, "module.json"))
        File(source, "build/libs/Acme_Hello.jar").copyTo(File(module, "build/libs/Acme_Hello.jar"))
        enable(true)
        return JvmPlatformHost(
            env = { if (it == "INFANTRY_PLUGINS_UNLOAD_CHECK_MS") "500" else null }, home = home.absolutePath,
            property = { if (it == "INFANTRY_STATE_DIR") state.absolutePath else null },
        )
    }

    @Test
    fun `nothing loaded yet or nothing changed means no live swap (the shell soft-restarts)`() {
        val host = host()
        assertNull(host.swapPlugins(), "no set loaded yet")
        assertNotNull(host.tabFactory.find("acme.hello"))
        assertNull(host.swapPlugins(), "nothing changed")
    }

    @Test
    fun `a clean unload passes the leak check`() {
        val host = host()
        assertNotNull(host.tabFactory.find("acme.hello"))
        enable(false)
        val swap = assertNotNull(host.swapPlugins())
        assertTrue("unloaded Acme_Hello" in swap.message, swap.message)
        assertNull(swap.factory.find("acme.hello"))
        assertEquals(emptyList(), swap.leaked())
        assertNull(host.tabFactory.find("acme.hello"), "the host draws the swapped set from now on")
    }

    @Test
    fun `a plugin whose old version stays reachable is reported, so the shell falls back`() {
        val host = host()
        val held = host.tabFactory.modules.single { it.manifest.name == "Acme_Hello" } // the leak: a reference from outside
        enable(false)
        val swap = assertNotNull(host.swapPlugins())
        assertEquals(listOf("Acme_Hello"), swap.leaked(), "the reachable class loader is detected")
        assertEquals("Acme_Hello", held.manifest.name)
    }
}
