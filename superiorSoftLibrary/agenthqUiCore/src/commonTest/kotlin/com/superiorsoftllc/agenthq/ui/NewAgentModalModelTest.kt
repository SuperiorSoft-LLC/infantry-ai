package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.ui.model.NewAgentModalModel
import com.superiorsoftllc.agenthq.ui.model.PickedModel
import com.superiorsoftllc.agenthq.ui.protocol.ProviderInfo
import com.superiorsoftllc.agenthq.ui.protocol.ProviderModels
import com.superiorsoftllc.agenthq.ui.protocol.Role
import com.superiorsoftllc.agenthq.ui.protocol.RoleBinding
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** UX slice 1: the New agent modal's state: role grid -> executor (seeded from the binding) -> task -> Add. */
class NewAgentModalModelTest {
    private val roles = listOf(Role("backend", "Backend Developer", "#112233"), Role("qa", "QA Engineer", "#445566"), Role("pm", "Project Manager", "#778899"))
    private val bindings = mapOf("backend" to RoleBinding("backend", provider = "opencode", model = "deepseek/deepseek-chat", effort = "high"))
    private val harnesses = listOf(ProviderInfo("opencode", default = true), ProviderInfo("claude"))
    private val catalog = listOf(
        ProviderModels("opencode", "deepseek", "opencode / deepseek", listOf("deepseek/deepseek-chat")),
        ProviderModels("opencode", "anthropic", "opencode / anthropic", listOf("anthropic/claude-sonnet-4")),
        ProviderModels("claude", "anthropic", "claude / anthropic", listOf("opus", "sonnet")),
    )

    private fun fresh() = NewAgentModalModel(roles, bindings, harnesses, catalog)

    @Test
    fun `the role search narrows the grid by id and name`() {
        assertEquals(3, fresh().roleOptions.size)
        assertEquals(listOf("qa"), fresh().withRoleQuery("eng").roleOptions.map { it.id })
        assertEquals(listOf("backend"), fresh().withRoleQuery("back").roleOptions.map { it.id })
        assertTrue(fresh().withRoleQuery("zzz").roleOptions.isEmpty())
    }

    @Test
    fun `no roles yet reads as loading and a role search with no hit does not`() {
        assertTrue(NewAgentModalModel().rolesLoading)
        assertFalse(fresh().withRoleQuery("zzz").rolesLoading)
    }

    @Test
    fun `the executor step is closed until a role is picked, and Add says why it is blocked`() {
        val m = fresh()
        assertFalse(m.executorOpen)
        assertEquals("Pick a role first", m.addBlockedReason)
        val withRole = m.withRole("qa")
        assertTrue(withRole.executorOpen)
        assertEquals("Pick a harness", withRole.addBlockedReason)
        val withHarness = withRole.let { it.withExecutor(it.executor.withHarness("claude")) }
        assertEquals("Pick a model", withHarness.addBlockedReason)
        val withModel = withHarness.let { it.withExecutor(it.executor.withModel("opus")) }
        assertEquals("Type the agent's first task", withModel.addBlockedReason)
        assertTrue(withModel.withTask("  ").addBlockedReason != null)
        assertTrue(withModel.withTask("do it").canAdd)
    }

    @Test
    fun `picking a role seeds the harness and model from its binding, a role without one seeds nothing`() {
        val seeded = fresh().withRole("backend").executor
        assertEquals(PickedModel("opencode", "deepseek/deepseek-chat"), seeded.picked)
        assertNull(fresh().withRole("qa").executor.picked)
    }

    @Test
    fun `an unknown role changes nothing`() = assertEquals(fresh(), fresh().withRole("nope"))

    @Test
    fun `choosing another harness replaces the seeded model and the add data carries role harness model and effort`() {
        val m = fresh().withRole("backend").let { it.withExecutor(it.executor.withHarness("claude").withModel("opus")) }.withTask("go")
        assertEquals("""{"provider":"claude","model":"opus","effort":"high","role":"backend"}""", m.spawnData())
    }

    @Test
    fun `a role with no binding and no executor sends just the role`() {
        assertEquals("""{"role":"qa"}""", fresh().withRole("qa").spawnData())
    }
}
