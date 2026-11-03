package com.superiorsoftllc.infantry.ui.plugin.config

import com.superiorsoftllc.infantry.plugin.api.NodeConfigEntry
import com.superiorsoftllc.infantry.plugin.host.ModuleState
import com.superiorsoftllc.infantry.ui.ConfigTab
import com.superiorsoftllc.infantry.ui.block.layout.Block
import com.superiorsoftllc.infantry.ui.plugin.DesktopPlugins
import com.superiorsoftllc.infantry.ui.shell.ConfigValues
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CONFIG-7 (docs/CONFIG-MANIFEST-DESIGN.md 0a, 9): the owner's rule "a plugin defines its own settings and may ship
 * blocks to render them, with no shell change; removing the jar removes them". A throwaway Acme_Weather plugin (the
 * `com.acme.weather` fixture classes, packed into a real jar in a temp dir, with a module.json in the plugins/community
 * layout) goes through the real desktop loader into a real ConfigTree, snapshot and ResolvedConfig.
 */
class CommunityConfigDecouplingTest {
    private val state = Files.createTempDirectory("weather-state").toFile()
    private val root = File(state, "plugins")
    private val module = File(root, "community/Acme_Weather")

    private val moduleJson = """{
      "name": "Acme_Weather", "version": "1.0.0", "apiVersion": 2, "author": "Acme (example)", "license": "NOASSERTION",
      "entry": "com.acme.weather.WeatherPlugin", "jar": "build/libs/Acme_Weather.jar",
      "blockTypes": [ { "type": "acme.weather.badge", "title": "Weather badge" } ],
      "fieldTypes": [ "stationPicker" ],
      "config": [
        { "node": "NODE", "tab": "Appearance", "group": "Weather badge", "order": 1, "title": "Weather badge",
          "applies": "The header badge.", "keywords": ["temperature", "city"], "fields": [
            { "key": "city", "type": "text", "label": "City", "default": "Oslo" },
            { "key": "unit", "type": "radio", "label": "Unit", "default": "C", "options": ["C", "F"] },
            { "key": "every", "type": "number", "label": "Refresh (min)", "default": 15, "min": 1, "max": 120 },
            { "key": "station", "type": "stationPicker", "label": "Station" } ] } ] }"""
    private val ownNode = "Acme_Weather.badge"

    private fun install(json: String = moduleJson.replace("NODE", ownNode)) {
        File(module, "build/libs").mkdirs()
        File(module, "module.json").writeText(json)
        packFixture(File(module, "build/libs/Acme_Weather.jar"))
        File(root, "modules.json").writeText("""{"modules":{"Acme_Weather":{"enabled":true,"allow":[]}}}""")
    }

    /** The fixture's compiled classes, from the test class path, into [jar]. Only the jar can supply them to the plugin loader. */
    private fun packFixture(jar: File) {
        val url = checkNotNull(javaClass.classLoader.getResource("com/acme/weather/WeatherPlugin.class")) { "fixture classes missing" }
        val dir = File(url.toURI()).parentFile
        JarOutputStream(jar.outputStream()).use { out ->
            for (f in dir.listFiles { x -> x.name.endsWith(".class") }!!) {
                out.putNextEntry(JarEntry("com/acme/weather/${f.name}")); out.write(f.readBytes()); out.closeEntry()
            }
        }
    }

    private fun load() = DesktopPlugins.load(state, env = { null }, property = { null }, print = {})

    private class Rows : NodeConfigApi {
        val rows = LinkedHashMap<Pair<String, String>, NodeConfigEntry>()
        override suspend fun seed(entries: List<NodeConfigEntry>) = Result.success(rows.values.toList())
        override suspend fun put(entry: NodeConfigEntry): Result<List<NodeConfigEntry>> {
            if (entry.value == null) rows.remove(entry.nodeId to entry.key) else rows[entry.nodeId to entry.key] = entry
            return Result.success(rows.values.toList())
        }
    }

    private fun label(b: Block, v: ConfigValues): String =
        b.javaClass.getMethod("label", String::class.java, String::class.java, Integer::class.javaObjectType)
            .invoke(b, v.string("city"), v.string("unit"), v.int("every")) as String

    @Test
    fun `the plugin's own settings and block appear and a user override reaches the block live, with no shell change`() = runBlocking {
        install()
        val loaded = load()
        val f = loaded.factory
        val status = f.statuses.single { it.name == "Acme_Weather" }
        assertEquals(ModuleState.LOADED, status.state, status.reason)
        assertEquals(emptyList(), f.problems)

        // (1) a card with its 4 fields, and the plugin's own editor type.
        val card = generatedCards(f, ConfigTab.APPEARANCE).single { it.module == "Acme_Weather" }
        assertEquals("Weather badge", card.declaration.group)
        assertEquals(listOf("city", "unit", "every", "station"), card.declaration.fields.map { it.key })
        assertEquals(listOf("text", "radio", "number", "stationPicker"), card.declaration.fields.map { it.type })
        assertNotNull(f.fieldTypes["stationPicker"], "the custom editor is registered from the jar")
        assertEquals("Pick a station", f.fieldTypes.validate(card.declaration.fields.last(), JsonPrimitive(3)))
        assertNull(f.fieldTypes.validate(card.declaration.fields.last(), JsonPrimitive("OSL")))

        // (2) the block reads the default, then an override written through NodeConfigState.set, live, no recompile.
        val badge = assertNotNull(f.blockTypes.create("acme.weather.badge", ownNode))
        assertEquals("Acme_Weather", f.blockTypes["acme.weather.badge"]!!.module)
        val cfg = NodeConfigState(f.configTree, Rows())
        val resolved = ResolvedConfig(cfg)
        cfg.load()
        assertEquals("Oslo C /15m", label(badge, resolved.values(ownNode)))
        assertTrue(cfg.set(ownNode, "city", JsonPrimitive("Bergen")))
        assertTrue(cfg.set(ownNode, "unit", JsonPrimitive("F")))
        assertEquals("Bergen F /15m", label(badge, resolved.values(ownNode)))
        loaded.close()
    }

    @Test
    fun `no shell source names the plugin`() {
        val sources = listOf("src/commonMain", "src/jvmMain", "../infantryPlugins/src/commonMain", "../infantryPlugins/src/jvmMain", "../infantry/src/main")
            .map { File(it) }.filter { it.isDirectory }
        assertTrue(sources.size >= 2, "shell source dirs not found from ${File(".").absolutePath}")
        val hits = sources.flatMap { d -> d.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "json") } }
            .filter { "weather" in it.readText().lowercase() }
            .map { it.path }
        assertEquals(emptyList(), hits, "the shell must not know the plugin")
    }

    @Test
    fun `removing the jar removes the card, the fields, the editor and the block, and the saved rows stay as orphans`() = runBlocking {
        install()
        val first = load()
        val api = Rows()
        val cfg = NodeConfigState(first.factory.configTree, api)
        cfg.load()
        assertTrue(cfg.set(ownNode, "city", JsonPrimitive("Bergen")))
        first.close()
        assertTrue(module.deleteRecursively(), "the loader let go of the jar")

        val f = load().factory
        assertTrue(f.statuses.none { it.name == "Acme_Weather" })
        assertTrue(generatedCards(f, ConfigTab.APPEARANCE).none { it.module == "Acme_Weather" })
        assertTrue(f.config.declarations.none { it.module == "Acme_Weather" })
        assertNull(f.configTree.field(ownNode, "city"))
        assertNull(f.fieldTypes["stationPicker"])
        assertNull(f.blockTypes["acme.weather.badge"])

        // The override row is kept (not deleted); reinstalling revives it.
        assertEquals(JsonPrimitive("Bergen"), api.rows.getValue(ownNode to "city").value)
        install()
        val again = NodeConfigState(load().factory.configTree, api).also { it.load() }
        assertEquals("Bergen", ResolvedConfig(again).values(ownNode).string("city"))
    }

    @Test
    fun `a community plugin with no config key still loads, and config is accepted only on its own nodes`() {
        install("""{"name":"Acme_Weather","version":"1.0.0","apiVersion":2,"author":"Acme (example)","license":"NOASSERTION","entry":"com.acme.weather.WeatherPlugin","jar":"build/libs/Acme_Weather.jar","blockTypes":[{"type":"acme.weather.badge"}],"fieldTypes":["stationPicker"]}""")
        val a = load().factory
        assertEquals(ModuleState.LOADED, a.statuses.single { it.name == "Acme_Weather" }.state)
        assertTrue(a.config.declarations.none { it.module == "Acme_Weather" })

        // A foreign (official) node is refused with a line and nothing is merged.
        install(moduleJson.replace("NODE", "infantry.shell.board"))
        val b = load().factory
        assertTrue(b.configTree.problems.any { "Acme_Weather" in it }, b.configTree.problems.toString())
        assertNull(b.configTree.field("infantry.shell.board", "city"))
    }
}
