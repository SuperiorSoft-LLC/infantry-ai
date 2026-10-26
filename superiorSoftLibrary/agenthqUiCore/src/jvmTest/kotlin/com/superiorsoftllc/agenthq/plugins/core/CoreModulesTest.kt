package com.superiorsoftllc.agenthq.plugins.core

import com.superiorsoftllc.agenthq.ui.plugin.AgentHqModule
import com.superiorsoftllc.agenthq.ui.plugin.ModuleManifest
import com.superiorsoftllc.agenthq.ui.plugin.TabContainers
import com.superiorsoftllc.agenthq.ui.plugin.TabFactory
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** PLUGINS-1: the core modules in plugins/core, and the factory the window draws its tabs from. */
class CoreModulesTest {
    private val json = Json { ignoreUnknownKeys = true }

    // Gradle runs the tests in the project folder (agenthqUiCore); plugins/ sits at the repository root.
    private val coreDir = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "plugins/core") }.first { it.isDirectory }

    @Test
    fun `every core folder's module json is the manifest compiled into the app`() {
        val onDisk = coreDir.listFiles { f -> f.isDirectory }!!.associate { dir ->
            dir.name to json.decodeFromString<ModuleManifest>(File(dir, "module.json").readText())
        }
        val compiled = CoreModules.all.associate { it.manifest.name to it.manifest }
        assertEquals(compiled.keys.sorted(), onDisk.keys.sorted(), "one plugins/core folder per compiled-in core module")
        for ((name, manifest) in compiled) assertEquals(manifest, onDisk[name], "plugins/core/$name/module.json")
        for (m in CoreModules.all) assertTrue(m.tabs.all { it.module == m.manifest.name }, "${m.manifest.name}: tabs name their own module")
    }

    @Test
    fun `the core tabs keep the strips' order, tags and shortcuts`() {
        val f = CoreModules.factory
        assertEquals(emptyList(), f.problems)
        assertEquals(
            listOf("OUTPUT", "PODS", "ROLES", "PROFILES", "TRIGGERS", "EPICS", "MEMORY", "RUN", "GIT", "STATS").map { "center-tab-$it" },
            f.tabs(TabContainers.CENTER).map { it.testTag },
        )
        assertEquals(
            listOf("CHANGES", "REVIEW", "ATTENTION", "USAGE", "PROMPTS", "SKILLS").map { "right-tab-$it" },
            f.tabs(TabContainers.RIGHT).map { it.testTag },
        )
        assertEquals("infantry.cli", f.home(TabContainers.CENTER))
        assertEquals("infantry.changes", f.home(TabContainers.RIGHT))
        val shortcuts = mapOf(1 to "infantry.cli", 2 to "infantry.pods", 3 to "infantry.changes", 4 to "infantry.review", 5 to "infantry.roles",
            6 to "infantry.attention", 7 to "infantry.usage", 9 to "infantry.profiles", 0 to "infantry.triggers")
        for ((digit, id) in shortcuts) assertEquals(id, f.byShortcut(digit)?.id, "Ctrl+$digit")
        assertEquals(null, f.byShortcut(8), "Ctrl+8 stays the Board")
    }

    @Test
    fun `a second tab with a taken id is skipped and reported`() {
        val copy = AgentHqModule(ModuleManifest(name = "Acme_Copy", version = "1.0.0"), InfantryGit.tabs)
        val f = TabFactory(CoreModules.all + copy)
        assertEquals(1, f.problems.size)
        assertTrue("Acme_Copy" in f.problems.single() && "infantry.git" in f.problems.single())
        assertEquals("Infantry_Git", f.find("infantry.git")?.module)
    }
}
