package com.superiorsoftllc.agenthq.plugin.host

import com.superiorsoftllc.agenthq.plugin.api.Tier
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** PLUGINS-1: reading the plugins root from disk. */
class PluginFoldersTest {
    private val root = Files.createTempDirectory("plugins-test").toFile()

    private fun module(folder: String, name: String, json: String) =
        File(root, "$folder/$name").apply { mkdirs() }.resolve("module.json").writeText(json)

    @Test
    fun `the root comes from the env, else the property, else the state dir`() {
        val state = File("state")
        assertEquals(File("e"), PluginFolders.root(state, { if (it == PluginFolders.ROOT_ENV) "e" else null }, { "p" }))
        assertEquals(File("p"), PluginFolders.root(state, { null }, { if (it == PluginFolders.ROOT_PROPERTY) "p" else null }))
        assertEquals(File(state, "plugins"), PluginFolders.root(state, { null }, { null }))
    }

    @Test
    fun `the demo lock reads common truthy values`() {
        for (v in listOf("1", "true", "on", "yes")) assertTrue(PluginFolders.demoLock { v }, v)
        for (v in listOf(null, "", "0", "false", "OFF")) assertEquals(false, PluginFolders.demoLock { v }, "$v")
    }

    @Test
    fun `scan reads the configured subfolders, parses each module json and keeps bad json as a refusal`() {
        File(root, "modules.json").writeText("""{"folders":[{"path":"core","tier":"core"},{"path":"extra","tier":"community"}],"modules":{"Acme_Good":{"enabled":true}},"unknown":1}""")
        module("core", "Infantry_A", """{"name":"Infantry_A","version":"1.0.0","futureKey":true}""")
        module("extra", "Acme_Good", """{"name":"Acme_Good","version":"1.0.0","author":"Acme","license":"NOASSERTION"}""")
        module("extra", "Acme_Broken", """{"name": "Acme_Broken", """)
        module("community", "Acme_Ignored", """{"name":"Acme_Ignored","version":"1"}""") // not a configured folder
        File(root, "extra/NotAModule").mkdirs()

        val config = PluginFolders.config(root)
        val found = PluginFolders.scan(root, config)
        assertEquals(listOf("Infantry_A", "Acme_Broken", "Acme_Good"), found.map { it.folderName })
        assertEquals(Tier.COMMUNITY, found.first { it.folderName == "Acme_Good" }.tier)

        val r = ModuleResolver.resolve(found, config)
        assertEquals(listOf("Infantry_A", "Acme_Good"), r.loaded.map { it.name })
        assertEquals(ModuleState.REFUSED, r.status("Acme_Broken")!!.state)
    }

    @Test
    fun `an unreadable modules json falls back to the defaults and says so`() {
        File(root, "modules.json").writeText("{ not json")
        val log = mutableListOf<String>()
        assertEquals(com.superiorsoftllc.agenthq.plugin.api.ModulesConfig(), PluginFolders.config(root, log::add))
        assertTrue(log.single().contains("invalid, using the defaults"))
    }

    @Test
    fun `storage stays inside the module's own folder, settings round trip`() {
        val storage = FilePluginStorage(File(root, "data/Acme_Good"))
        storage.write("note.txt", "hi")
        assertEquals("hi", storage.read("note.txt"))
        assertFailsWith<IllegalArgumentException> { storage.write("../escape.txt", "x") }
        val settings = FilePluginSettings(storage)
        settings.set("greeting", "hello")
        assertEquals("hello", settings.get("greeting"))
        assertEquals(listOf("note.txt", "settings.json"), storage.list())
    }
}
