// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.plugin

import com.superiorsoftllc.infantry.plugin.api.ConfigManifestSource
import com.superiorsoftllc.infantry.ui.ConfigTab
import com.superiorsoftllc.infantry.ui.plugin.config.ConfigManifests
import com.superiorsoftllc.infantry.ui.plugin.config.generatedCards
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CONFIG-FIELDS-3 (G10, docs/CONFIG-FIELDS.md section 7): the blank-shell script. A temp state dir with an EMPTY plugins tree
 * and no compiled modules goes through the real desktop loader: only the core config shows, the compiled manifest's sources
 * are core, a config-only plugin dir adds its settings card with no code and no shell change, and removing it removes the card.
 * Run: `gradlew.bat :superiorSoftLibrary:infantryUiCore:jvmTest --tests "*BlankShellE2ETest"` (temp state dir; no daemon and no port, the daemon only stores the manifest).
 */
class BlankShellE2ETest {
    private val state = Files.createTempDirectory("blank-shell-state").toFile()
    private val module = File(state, "plugins/community/Acme_Look")

    private val moduleJson = """{
      "name": "Acme_Look", "version": "1.0.0", "apiVersion": 2, "author": "Acme (example)", "license": "NOASSERTION",
      "config": [ { "node": "Acme_Look.theme", "tab": "Appearance", "group": "Acme look", "order": 1, "title": "Acme look",
        "fields": [ { "key": "accent", "type": "text", "label": "Accent", "default": "teal" } ] } ] }"""

    private fun load() = DesktopPlugins.load(state, env = { null }, property = { null }, compiled = emptyList(), print = {})

    @Test
    fun `blank shell shows core only, a config-only plugin dir adds its card, removing it removes the card`() {
        // (1) blank: no module, no plugin card, the core declarations only.
        val blank = load()
        val f = blank.factory
        assertEquals(emptyList(), f.statuses.map { it.name }, "no plugin is installed")
        assertTrue(f.config.declarations.all { it.module == CORE_SHELL_MODULE }, "every declaration is core: ${f.config.declarations.map { it.module }.toSet()}")
        assertTrue(f.config.declarations.isNotEmpty(), "the core config tabs (Plugins, Modules, ...) are declared")
        assertEquals(emptyList(), generatedCards(f, ConfigTab.APPEARANCE).map { it.module })
        val blankManifest = ConfigManifests.compile(f.configTree, "desktop", f.modules.map { ConfigManifestSource(it.manifest.name, it.manifest.version) })
        assertEquals(emptyList(), blankManifest.sources, "manifest sources: core only, no module")
        blank.close()

        // (2) drop a config-only plugin dir in: its card and field appear, no code in it.
        module.mkdirs()
        File(module, "module.json").writeText(moduleJson)
        File(state, "plugins/modules.json").writeText("""{"modules":{"Acme_Look":{"enabled":true,"allow":[]}}}""")
        val dropped = load()
        val d = dropped.factory
        assertEquals(emptyList(), d.problems)
        assertEquals(listOf("Acme_Look"), d.statuses.map { it.name })
        val card = generatedCards(d, ConfigTab.APPEARANCE).single { it.module == "Acme_Look" }
        assertEquals(listOf("accent"), card.declaration.fields.map { it.key })
        assertEquals(listOf("Acme_Look"), ConfigManifests.compile(d.configTree, "desktop", d.modules.map { ConfigManifestSource(it.manifest.name, it.manifest.version) }).sources.map { it.module })
        dropped.close()

        // (3) remove it: the card is gone again.
        assertTrue(module.deleteRecursively())
        val after = load()
        assertEquals(emptyList(), generatedCards(after.factory, ConfigTab.APPEARANCE).map { it.module })
        assertEquals(emptyList(), after.factory.statuses.map { it.name })
        after.close()
    }
}
