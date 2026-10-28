package com.superiorsoftllc.agenthq.plugins.core

import com.superiorsoftllc.agenthq.plugins.official.stats.InfantryStats
import com.superiorsoftllc.agenthq.plugins.official.epics.InfantryEpics
import com.superiorsoftllc.agenthq.plugins.official.pods.InfantryPods
import com.superiorsoftllc.agenthq.plugins.official.profiles.InfantryProfiles
import com.superiorsoftllc.agenthq.plugins.official.review.InfantryReview
import com.superiorsoftllc.agenthq.plugins.official.usage.InfantryUsage
import com.superiorsoftllc.agenthq.plugins.official.changes.InfantryChanges
import com.superiorsoftllc.agenthq.plugins.official.notes.InfantryNotes
import com.superiorsoftllc.agenthq.plugins.official.prompts.InfantryPrompts
import com.superiorsoftllc.agenthq.plugins.official.skills.InfantrySkills
import com.superiorsoftllc.agenthq.ui.plugin.AgentHqModule
import com.superiorsoftllc.agenthq.plugins.official.InfantrySettings
import com.superiorsoftllc.agenthq.plugins.official.git.InfantryGit
import com.superiorsoftllc.agenthq.plugins.official.run.InfantryRun
import com.superiorsoftllc.agenthq.plugins.official.SETTINGS_TAB_ID
import com.superiorsoftllc.agenthq.plugin.api.ModuleManifest
import com.superiorsoftllc.agenthq.plugin.api.TabDeclaration
import com.superiorsoftllc.agenthq.ui.plugin.TabContainers
import com.superiorsoftllc.agenthq.ui.plugin.TabFactory
import com.superiorsoftllc.agenthq.ui.plugin.TabGroups
import com.superiorsoftllc.agenthq.ui.plugin.config.ChildrenControl
import com.superiorsoftllc.agenthq.plugin.api.NodeConfigEntry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
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
        // RUNTIME-1: `entry` names the module's ModuleSource for the build's generated list; the compiled manifest has none.
        for ((name, manifest) in compiled) assertEquals(manifest, onDisk[name]?.copy(entry = null), "plugins/core/$name/module.json")
        for (m in CoreModules.all) assertEquals(m.manifest.tabs.map { it.id }, m.tabs.map { it.id }, "${m.manifest.name}: declares exactly its tabs")
        for (m in CoreModules.all) assertEquals(m.manifest.panels.map { it.id }, m.panels.map { it.id }, "${m.manifest.name}: declares exactly its panel sections")
        for (m in CoreModules.all) for (t in m.tabs) assertEquals(m.manifest.name, CoreModules.factory.find(t.id)?.module, "${t.id} names its own module")
    }

    @Test
    fun `the generated desktop module list holds the 5 core modules in the old order, without the official modules`() {
        assertEquals(
            listOf("Roles", "Triggers",
                "Attention", "Board", "AppUi").map { "Infantry_$it" },
            CoreModules.all.map { it.manifest.name },
        )
    }

    @Test
    fun `the core tabs keep the strips' order, tags and shortcuts`() {
        val f = TabFactory(CoreModules.all + InfantryPods + InfantryProfiles + InfantryNotes + InfantryChanges + InfantryStats + InfantryUsage + InfantryEpics + InfantryReview + InfantryRun + InfantryGit + InfantryPrompts + InfantrySkills) // RUNTIME-6..10: official modules (their jars on desktop)
        assertEquals(emptyList(), f.problems)
        assertEquals(
            listOf("PODS", "ROLES", "PROFILES", "TRIGGERS", "EPICS", "MEMORY", "RUN", "GIT", "STATS").map { "center-tab-$it" },
            f.tabs(TabContainers.CENTER).map { it.testTag },
        )
        assertEquals(
            listOf("CHANGES", "REVIEW", "ATTENTION", "USAGE", "PROMPTS", "SKILLS").map { "right-tab-$it" },
            f.tabs(TabContainers.RIGHT).map { it.testTag },
        )
        assertEquals("infantry.pods", f.home(TabContainers.CENTER)) // RUNTIME-3: the CLI tab comes from the official jar
        assertEquals("infantry.changes", f.home(TabContainers.RIGHT))
        val shortcuts = mapOf(2 to "infantry.pods", 3 to "infantry.changes", 4 to "infantry.review", 5 to "infantry.roles",
            6 to "infantry.attention", 7 to "infantry.usage", 9 to "infantry.profiles", 0 to "infantry.triggers")
        for ((digit, id) in shortcuts) assertEquals(id, f.byShortcut(digit)?.id, "Ctrl+$digit")
        assertEquals(null, f.byShortcut(8), "Ctrl+8 stays the Board")
    }

    @Test
    fun `a second tab with a taken id is skipped and reported`() {
        val copy = AgentHqModule(ModuleManifest(name = "Acme_Copy", version = "1.0.0", tabs = listOf(TabDeclaration("infantry.git", "center"))), InfantryGit.tabs)
        val f = TabFactory(CoreModules.all + InfantryPods + InfantryProfiles + InfantryNotes + InfantryChanges + InfantryStats + InfantryUsage + InfantryEpics + InfantryReview + InfantryRun + InfantryGit + copy)
        assertEquals(1, f.problems.size)
        assertTrue("Acme_Copy" in f.problems.single() && "infantry.git" in f.problems.single())
        assertEquals("Infantry_Git", f.find("infantry.git")?.module)
    }

    @Test
    fun `SETTINGS-TAB-1 the Settings group is off by default and switchable`() {
        val f = TabFactory(CoreModules.all + InfantryPods + InfantryProfiles + InfantryNotes + InfantryChanges + InfantryStats + InfantryUsage + InfantryEpics + InfantryReview + InfantryRun + InfantryGit + InfantrySettings) // RUNTIME-5: Infantry_Settings is official (its jar on desktop)
        // Off (unset): the strip is the one before the group, Settings hidden, nothing under it.
        assertEquals(false, f.grouped(SETTINGS_TAB_ID))
        assertEquals(null, f.tabs(TabContainers.CENTER).firstOrNull { it.id == SETTINGS_TAB_ID })
        assertEquals(emptyList(), f.children(SETTINGS_TAB_ID))
        assertEquals(null, f.hostOf("infantry.git"))
        assertEquals(listOf("PROFILES", "TRIGGERS", "MEMORY", "RUN", "GIT").map { "center-tab-$it" }, f.groupable(SETTINGS_TAB_ID).map { it.testTag })

        f.config.replace(listOf(NodeConfigEntry(SETTINGS_TAB_ID, TabGroups.GROUPED, JsonPrimitive(true))))
        assertEquals(
            listOf("PODS", "ROLES", "EPICS", "STATS", "SETTINGS").map { "center-tab-$it" },
            f.tabs(TabContainers.CENTER).map { it.testTag },
        )
        assertEquals(listOf("PROFILES", "TRIGGERS", "MEMORY", "RUN", "GIT").map { "center-tab-$it" }, f.children(SETTINGS_TAB_ID).map { it.testTag })
        assertEquals(SETTINGS_TAB_ID, f.hostOf("infantry.git"))
        assertEquals("infantry.pods", f.home(TabContainers.CENTER)) // RUNTIME-3: the CLI tab comes from the official jar
        // The shortcuts still name the sub-tabs; the window opens them inside Settings.
        assertEquals("infantry.profiles", f.byShortcut(9)?.id)
        assertEquals("infantry.triggers", f.byShortcut(0)?.id)
        // A sub-tab the Settings tab's own settings switch off is disabled; Settings itself is not.
        f.config.replace(listOf(
            NodeConfigEntry(SETTINGS_TAB_ID, TabGroups.GROUPED, JsonPrimitive(true)),
            NodeConfigEntry(SETTINGS_TAB_ID, ChildrenControl.DISABLED, JsonArray(listOf(JsonPrimitive("infantry.run")))),
        ))
        assertTrue(f.isDisabled("infantry.run"))
        assertEquals(listOf("infantry.profiles", "infantry.triggers", "infantry.notes", "infantry.git"), f.children(SETTINGS_TAB_ID).map { it.id })

        // Switched back off: the old strip again.
        f.config.replace(listOf(NodeConfigEntry(SETTINGS_TAB_ID, TabGroups.GROUPED, JsonPrimitive(false))))
        assertEquals(
            listOf("PODS", "ROLES", "PROFILES", "TRIGGERS", "EPICS", "MEMORY", "RUN", "GIT", "STATS").map { "center-tab-$it" },
            f.tabs(TabContainers.CENTER).map { it.testTag },
        )
    }

    @Test
    fun `SETTINGS-TAB-1 without the Settings module its tabs stay in the strip even when grouped`() {
        val f = TabFactory(CoreModules.all + InfantryPods + InfantryProfiles + InfantryNotes + InfantryChanges + InfantryStats + InfantryUsage + InfantryEpics + InfantryReview + InfantryRun + InfantryGit) // RUNTIME-5: no Settings jar
        f.config.replace(listOf(NodeConfigEntry(SETTINGS_TAB_ID, TabGroups.GROUPED, JsonPrimitive(true))))
        assertEquals(null, f.hostOf("infantry.git"))
        assertTrue(f.tabs(TabContainers.CENTER).any { it.id == "infantry.git" })
    }
}
