// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.ui.model.PickedModel
import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.model.catalogSections
import com.superiorsoftllc.infantry.ui.model.spawnOptionsData
import com.superiorsoftllc.infantry.ui.protocol.ProviderModels
import kotlin.test.Test
import kotlin.test.assertEquals

/** PICKER 2/2: the picker's data comes ONLY from the `models` frame; no provider is named in the UI. */
class ModelCatalogModelTest {
    private val frame = listOf(
        ProviderModels("zed-agent", "anthropic", "zed-agent / anthropic", listOf("anthropic/opus", "anthropic/haiku")),
        ProviderModels("zed-agent", "openai", "zed-agent / openai", listOf("openai/gpt-x")),
        ProviderModels("quiet-cli", "", "quiet-cli", emptyList(), note = "not logged in"),
    )

    @Test
    fun `every provider of the frame is a section, groups kept, empty provider included with its note`() {
        val sections = catalogSections(frame)
        assertEquals(listOf("zed-agent", "quiet-cli"), sections.map { it.provider })
        assertEquals(listOf("anthropic", "openai"), sections[0].groups.map { it.group })
        assertEquals("not logged in", sections[1].groups.single().note)
        assertEquals(emptyList(), sections[1].groups.single().models)
    }

    @Test
    fun `an empty frame yields no sections`() = assertEquals(emptyList(), catalogSections(emptyList()))

    @Test
    fun `the new payload carries the picked provider and model, empty when nothing is picked`() {
        assertEquals("", spawnOptionsData(null))
        assertEquals("""{"provider":"zed-agent","model":"anthropic/opus"}""", spawnOptionsData(PickedModel("zed-agent", "anthropic/opus")))
    }

    @Test
    fun `the new payload names the selected project, so the session is bound to it (BUG-M2-15)`() {
        assertEquals("""{"projectId":"P1"}""", spawnOptionsData(null, projectId = "P1"))
        assertEquals("""{"provider":"claude","role":"qa","projectId":"P1"}""", spawnOptionsData(PickedModel("claude", null), role = "qa", projectId = "P1"))
        assertEquals("""{"contextLimit":200000}""", spawnOptionsData(null, contextLimit = 200_000))
    }

    @Test
    fun `a binding with a provider but no model seeds the provider and leaves the model to the default`() {
        val binding = com.superiorsoftllc.infantry.ui.protocol.RoleBinding("backend", provider = "claude", effort = "high")
        val seeded = PickedModel.fromBinding(binding)
        assertEquals("claude", seeded?.provider)
        assertEquals(null, seeded?.model)
        assertEquals("""{"provider":"claude","effort":"high"}""", spawnOptionsData(seeded, "high"))
        assertEquals(null, PickedModel.fromBinding(com.superiorsoftllc.infantry.ui.protocol.RoleBinding("backend", effort = "high")))
    }

    @Test
    fun `the models frame lands in the state`() {
        val json = kotlinx.serialization.json.Json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(ProviderModels.serializer()), frame,
        )
        val next = reduceFrameForTest(json)
        assertEquals(frame, next.modelCatalog)
    }

    private fun reduceFrameForTest(data: String): AppState =
        com.superiorsoftllc.infantry.ui.model.reduce(AppState(), com.superiorsoftllc.infantry.ui.protocol.Envelope(com.superiorsoftllc.infantry.ui.protocol.Envelope.MODELS, data), 100)
}
