// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.plugin.host.PluginFolders
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** PLUGINS-1: the headless daemon reads the plugins root with the desktop's rules, logs every module and never fails. */
class DaemonPluginsTest {
    @Test
    fun `the daemon lists every module with its state, under the same rules`() {
        val root = Files.createTempDirectory("daemon-plugins").toFile()
        File(root, "core/Infantry_A").apply { mkdirs() }.resolve("module.json").writeText("""{"name":"Infantry_A","version":"1.0.0"}""")
        File(root, "community/Acme_Broken").apply { mkdirs() }.resolve("module.json").writeText("{ nope")
        File(root, "community/Acme_Hello").apply { mkdirs() }.resolve("module.json")
            .writeText("""{"name":"Acme_Hello","version":"1.0.0","author":"Acme","license":"NOASSERTION"}""")
        File(root, "modules.json").writeText("""{"modules":{"Acme_Hello":{"enabled":true}}}""")
        val log = mutableListOf<String>()

        val open = DaemonPlugins.resolve(File("unused"), env = { if (it == PluginFolders.ROOT_ENV) root.absolutePath else null }, property = { null }, log = log::add)
        assertEquals(mapOf("Infantry_A" to "loaded", "Acme_Hello" to "loaded", "Acme_Broken" to "refused"), open.modules.associate { it.name to it.state })
        assertTrue(log.any { it.startsWith("[plugins] Acme_Broken (community): refused - invalid module.json") })

        val locked = DaemonPlugins.resolve(File("unused"), env = { if (it == PluginFolders.ROOT_ENV) root.absolutePath else null }, property = { null }, log = {}, locked = true)
        assertTrue(locked.locked)
        assertEquals("locked", locked.modules.single { it.name == "Acme_Hello" }.state)
        assertEquals("loaded", locked.modules.single { it.name == "Infantry_A" }.state)
    }
}
