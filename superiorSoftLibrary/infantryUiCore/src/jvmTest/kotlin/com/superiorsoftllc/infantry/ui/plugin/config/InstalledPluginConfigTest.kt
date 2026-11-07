// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.plugin.config

import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.plugin.api.NodeConfigEntry
import com.superiorsoftllc.infantry.plugin.api.Tier
import com.superiorsoftllc.infantry.plugins.core.CoreModules
import com.superiorsoftllc.infantry.ui.ConfigEntry
import com.superiorsoftllc.infantry.ui.plugin.InfantryModule
import com.superiorsoftllc.infantry.ui.plugin.TabFactory
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** PLUGIN-CONFIG-1: a community plugin installed from the catalog (Infantry_ReadAloud 1.1.1's real module.json) gets its config card. */
class InstalledPluginConfigTest {
    private object NoDaemon : NodeConfigApi {
        override suspend fun seed(entries: List<NodeConfigEntry>) = Result.success(emptyList<NodeConfigEntry>())
        override suspend fun put(entry: NodeConfigEntry) = Result.success(emptyList<NodeConfigEntry>())
    }

    private val json = """{"name":"Infantry_ReadAloud","version":"1.1.1","apiVersion":2,"author":"SuperiorSoft LLC","license":"Apache-2.0",
      "entry":"x.Y","jar":"build/libs/a.jar","services":["speech.out"],
      "panels":[{"id":"infantry.readaloud","panel":"chat.message.header"},{"id":"infantry.readaloud.auto","panel":"chat.composer.actions"}],
      "config":[{"node":"infantry.readaloud","tab":"Chat","group":"Read aloud","order":40,"title":"Read aloud",
        "fields":[{"key":"autoRead","type":"checkbox","label":"a","default":false,"order":1,"scope":"device"},
                  {"key":"maxChars","type":"number","label":"b","default":600,"min":50,"max":20000,"order":2,"scope":"device"}]}]}"""

    @Test fun `community plugin config is in the tree and the plan`() {
        val m = Json { ignoreUnknownKeys = true }.decodeFromString(ModuleManifest.serializer(), json)
        val factory = TabFactory(CoreModules.all + InfantryModule(m, tier = Tier.COMMUNITY), configApi = NoDaemon)
        assertEquals(emptyList(), factory.configTree.problems.filter { "ReadAloud" in it })
        assertEquals(2, factory.configTree.fields["infantry.readaloud"].orEmpty().size)
        val plan = ConfigPlan.of(factory.configTree.declarations, ConfigEntry.entries.toSet(), emptySet())
        assertTrue(plan.cardsOf("Chat").any { it.declaration.node == "infantry.readaloud" }, plan.tabs.toString())
    }
}
