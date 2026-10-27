package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.ui.model.ExecutorPickerModel
import com.superiorsoftllc.agenthq.ui.model.NewAgentModalModel
import com.superiorsoftllc.agenthq.ui.model.PickedModel
import com.superiorsoftllc.agenthq.ui.protocol.ProviderInfo
import com.superiorsoftllc.agenthq.ui.protocol.ProviderModels
import com.superiorsoftllc.agenthq.ui.protocol.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** PICKER-UX: a harness whose catalog lists exactly one model has it named and, in the New agent flows, picked. */
class SoleModelPickTest {
    private val harnesses = listOf(ProviderInfo("opencode", default = true), ProviderInfo("claude"))
    private val catalog = listOf(
        ProviderModels("opencode", "opencode-go", "opencode / opencode-go", listOf("opencode-go/beam-free")),
        ProviderModels("claude", "anthropic", "claude / anthropic", listOf("opus", "sonnet")),
    )

    @Test
    fun `one listed model is the sole model and is picked when nothing is`() {
        val m = ExecutorPickerModel(harnesses, catalog).withHarness("opencode")
        assertEquals("opencode-go/beam-free", m.soleModel?.id)
        assertNull(m.model)
        assertEquals(PickedModel("opencode", "opencode-go/beam-free"), m.withSoleModel().picked)
    }

    @Test
    fun `several models, or a model already picked, change nothing`() {
        val claude = ExecutorPickerModel(harnesses, catalog).withHarness("claude")
        assertNull(claude.soleModel)
        assertEquals(claude, claude.withSoleModel())
        val picked = claude.withModel("sonnet")
        assertEquals(picked, picked.withSoleModel())
        val none = ExecutorPickerModel(harnesses, catalog)
        assertEquals(none, none.withSoleModel())
    }

    @Test
    fun `the New agent modal picks the sole model so Add is not blocked on it`() {
        val m = NewAgentModalModel(listOf(Role("architect", "Architect", "#112233")), mapOf(), harnesses, catalog)
            .withRole("architect")
            .let { it.withExecutor(it.executor.withHarness("opencode")) }
            .withTask("go")
        assertEquals("opencode-go/beam-free", m.executor.model)
        assertTrue(m.canAdd)
    }
}
