package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.ui.model.ContextLimitInput
import com.superiorsoftllc.agenthq.ui.model.NewAgentField
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

    private fun ready() = fresh().withRole("qa").let { it.withExecutor(it.executor.withHarness("claude").withModel("opus")) }.withTask("go")

    @Test
    fun `CONTEXT-LIMIT-1 the limit is off by default and the add data carries none`() {
        val m = ready()
        assertFalse(m.contextLimitOn)
        assertNull(m.contextLimit)
        assertTrue(m.canAdd)
        // Text typed while off is not sent: off is the harness's own window.
        assertEquals("""{"provider":"claude","model":"opus","role":"qa"}""", m.withContextLimitText("200k").spawnData())
    }

    @Test
    fun `CONTEXT-LIMIT-1 switched on, a typed size goes on the new frame and a bad one blocks Add with the reason`() {
        val on = ready().withContextLimitOn(true)
        assertEquals(NewAgentField.CONTEXT_LIMIT, on.missingField)
        assertEquals("Context limit: type a size such as 200k or 1m", on.addBlockedReason)
        assertEquals(NewAgentField.CONTEXT_LIMIT, on.withContextLimitText("200").missingField)
        val set = on.withContextLimitText("200k")
        assertTrue(set.canAdd)
        assertEquals(200_000, set.contextLimit)
        assertEquals("""{"provider":"claude","model":"opus","role":"qa","contextLimit":200000}""", set.spawnData())
        assertNull(set.withContextLimitOn(false).contextLimit)
    }

    @Test
    fun `CONTEXT-LIMIT-1 the note names the chosen harness support as the daemon reports it`() {
        val reported = fresh().copy(harnesses = listOf(
            ProviderInfo("opencode", default = true, contextLimit = "unsupported", contextLimitHow = "not mapped yet"),
            ProviderInfo("claude", contextLimit = "supported", contextLimitHow = "auto-compacts"),
            ProviderInfo("pi", contextLimit = "best_effort"),
        ))
        assertEquals("Pick a harness to see whether it honours a limit.", reported.withRole("qa").contextLimitNote)
        fun on(h: String) = reported.withRole("qa").let { it.withExecutor(it.executor.withHarness(h)) }
        assertEquals("claude applies the limit: auto-compacts", on("claude").contextLimitNote)
        assertEquals("opencode does not apply a limit (it is recorded, not applied): not mapped yet", on("opencode").contextLimitNote)
        assertEquals("pi applies it best effort", on("pi").contextLimitNote)
        // An older daemon reports nothing: the note says so instead of guessing.
        assertEquals("This harness does not report whether it honours a limit.", ready().contextLimitNote)
    }

    @Test
    fun `CONTEXT-LIMIT-1 the input reads like the daemon parser`() {
        assertEquals(200_000, ContextLimitInput.parse("200k").value)
        assertEquals(1_000_000, ContextLimitInput.parse(" 1M ").value)
        assertEquals(1_500_000, ContextLimitInput.parse("1.5m").value)
        assertEquals(150_000, ContextLimitInput.parse("150,000").value)
        assertNull(ContextLimitInput.parse("9k").value)
        assertNull(ContextLimitInput.parse("lots").value)
        assertNull(ContextLimitInput.parse("11m").value)
    }
}
