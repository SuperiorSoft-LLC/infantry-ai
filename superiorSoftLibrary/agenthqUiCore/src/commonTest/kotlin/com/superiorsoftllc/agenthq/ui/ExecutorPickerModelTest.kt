package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.ui.model.ExecutorPickerModel
import com.superiorsoftllc.agenthq.ui.model.PickedModel
import com.superiorsoftllc.agenthq.ui.protocol.ProviderInfo
import com.superiorsoftllc.agenthq.ui.protocol.ProviderModels
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ROLE-FIRST S6: the Harness -> Provider(vendor group) -> Model picker state, fed by the `providers` and `models`
 * frames (docs/AGENTHQ-ROLE-FIRST.md section 7). Pure state; no Compose.
 */
class ExecutorPickerModelTest {
    private val harnesses = listOf(
        ProviderInfo("opencode", default = true),
        ProviderInfo("claude"),
        ProviderInfo("pi", defaultModel = "pi-default"),
        ProviderInfo("quiet"),
    )

    private val catalog = listOf(
        ProviderModels("opencode", "anthropic", "opencode / anthropic", listOf("anthropic/claude-sonnet-4-5", "anthropic/claude-sonnet-4", "anthropic/claude-opus-4")),
        ProviderModels("opencode", "deepseek", "opencode / deepseek", listOf("deepseek/deepseek-chat", "deepseek/deepseek-reasoner")),
        ProviderModels("opencode", "openrouter", "opencode / openrouter", listOf("openrouter/anthropic/claude-sonnet-4")),
        ProviderModels("claude", "anthropic", "claude / anthropic", listOf("opus", "sonnet", "haiku")),
        ProviderModels("quiet", "", "quiet", emptyList(), note = "could not list models: boom"),
    )

    private fun fresh() = ExecutorPickerModel(harnesses, catalog)
    private fun opencode() = fresh().withHarness("opencode")

    // --- harness -------------------------------------------------------------------------------------

    @Test
    fun `with no harness chosen there is nothing to pick from and nothing picked`() {
        val m = fresh()
        assertEquals(emptyList(), m.providerOptions)
        assertEquals(emptyList(), m.modelOptions)
        assertNull(m.picked)
    }

    @Test
    fun `choosing a harness lists its vendor groups in the frame's order with their model counts`() {
        val m = opencode()
        assertEquals(listOf("anthropic", "deepseek", "openrouter"), m.providerOptions.map { it.group })
        assertEquals(listOf(3, 2, 1), m.providerOptions.map { it.modelCount })
    }

    @Test
    fun `with Provider on All the model list is every model of the harness, tagged with its vendor`() {
        val m = opencode()
        assertNull(m.selectedGroup)
        assertEquals(6, m.modelOptions.size)
        assertEquals("deepseek", m.modelOptions.first { it.id == "deepseek/deepseek-chat" }.group)
        assertEquals("openrouter", m.modelOptions.first { it.id == "openrouter/anthropic/claude-sonnet-4" }.group)
    }

    @Test
    fun `an unknown harness changes nothing`() {
        val m = opencode()
        assertEquals(m, m.withHarness("nope"))
    }

    @Test
    fun `choosing the harness already chosen keeps the group, the model and the open queries`() {
        val m = opencode().withGroup("deepseek").withModel("deepseek/deepseek-chat").withModelQuery("chat")
        assertEquals(m, m.withHarness("opencode"))
    }

    @Test
    fun `changing harness clears a provider and a model the new harness does not list, and the queries`() {
        val m = opencode().withGroup("deepseek").withModel("deepseek/deepseek-chat").withModelQuery("x").withProviderQuery("y").withHarness("claude")
        assertEquals("claude", m.harness)
        assertNull(m.model)
        assertNull(m.group)
        assertEquals("", m.modelQuery)
        assertEquals("", m.providerQuery)
    }

    @Test
    fun `changing harness keeps a vendor group the new harness also lists`() {
        val m = opencode().withGroup("anthropic").withHarness("claude")
        assertEquals("anthropic", m.selectedGroup)
        assertNull(m.model)
    }

    // --- provider ------------------------------------------------------------------------------------

    @Test
    fun `a harness with exactly one vendor group auto-selects it and collapses the provider step`() {
        val m = fresh().withHarness("claude")
        assertEquals("anthropic", m.selectedGroup)
        assertTrue(m.providerCollapsed)
        assertEquals(listOf("opus", "sonnet", "haiku"), m.modelOptions.map { it.id })
    }

    @Test
    fun `a harness with several vendor groups does not collapse the provider step`() = assertFalse(opencode().providerCollapsed)

    @Test
    fun `choosing a provider narrows the model list to that vendor`() {
        val m = opencode().withGroup("deepseek")
        assertEquals("deepseek", m.selectedGroup)
        assertEquals(listOf("deepseek/deepseek-chat", "deepseek/deepseek-reasoner"), m.modelOptions.map { it.id })
    }

    @Test
    fun `choosing All again widens the model list`() {
        assertEquals(6, opencode().withGroup("deepseek").withGroup(null).modelOptions.size)
    }

    @Test
    fun `an unknown provider changes nothing`() {
        val m = opencode()
        assertEquals(m, m.withGroup("nope"))
    }

    @Test
    fun `narrowing to another vendor clears a model that left the list but keeps one still listed`() {
        val chosen = opencode().withModel("deepseek/deepseek-chat")
        assertEquals("deepseek/deepseek-chat", chosen.withGroup("deepseek").model)
        assertEquals("deepseek/deepseek-chat", chosen.withGroup(null).model)
        assertNull(chosen.withGroup("anthropic").model)
    }

    // --- model, and the reverse arrow ----------------------------------------------------------------

    @Test
    fun `picking a model while Provider is All sets the Provider to the model's vendor`() {
        val m = opencode().withModel("openrouter/anthropic/claude-sonnet-4")
        assertEquals("openrouter", m.selectedGroup)
        assertEquals("openrouter/anthropic/claude-sonnet-4", m.model)
    }

    @Test
    fun `a model id listed under two vendors is placed by the vendor the caller names`() {
        val twin = ExecutorPickerModel(
            listOf(ProviderInfo("h")),
            listOf(ProviderModels("h", "a", "h / a", listOf("same")), ProviderModels("h", "b", "h / b", listOf("same"))),
        ).withHarness("h")
        assertEquals("a", twin.withModel("same").selectedGroup)
        assertEquals("b", twin.withModel("same", group = "b").selectedGroup)
    }

    @Test
    fun `a model the harness does not list is refused when a catalog exists`() {
        val m = opencode()
        assertEquals(m, m.withModel("gpt-9"))
    }

    @Test
    fun `with a vendor chosen a model of another vendor is refused`() {
        val m = opencode().withGroup("deepseek")
        assertEquals(m, m.withModel("anthropic/claude-opus-4"))
    }

    @Test
    fun `picking a model closes its search box`() {
        assertEquals("", opencode().withModelQuery("opus").withModel("anthropic/claude-opus-4").modelQuery)
    }

    @Test
    fun `picking a provider closes its search box`() {
        assertEquals("", opencode().withProviderQuery("deep").withGroup("deepseek").providerQuery)
    }

    // --- type to filter ------------------------------------------------------------------------------

    @Test
    fun `typing in the provider box filters the provider list`() {
        val m = opencode()
        assertEquals(listOf("deepseek"), m.withProviderQuery("deep").providerOptions.map { it.group })
        assertEquals(listOf("anthropic", "deepseek", "openrouter"), m.withProviderQuery("").providerOptions.map { it.group })
    }

    @Test
    fun `typing in the model box filters and ranks the model list, 'son 4' finds the sonnet 4 models`() {
        val m = opencode().withModelQuery("son 4")
        assertEquals(
            listOf("anthropic/claude-sonnet-4-5", "anthropic/claude-sonnet-4", "openrouter/anthropic/claude-sonnet-4"),
            m.modelOptions.map { it.id },
        )
    }

    @Test
    fun `the model filter works inside the chosen vendor only`() {
        val m = opencode().withGroup("anthropic").withModelQuery("son 4")
        assertEquals(listOf("anthropic/claude-sonnet-4-5", "anthropic/claude-sonnet-4"), m.modelOptions.map { it.id })
    }

    @Test
    fun `the model box also matches the vendor name`() {
        assertEquals(
            listOf("deepseek/deepseek-chat", "deepseek/deepseek-reasoner"),
            opencode().withModelQuery("deepseek").modelOptions.map { it.id },
        )
    }

    // --- no catalog ----------------------------------------------------------------------------------

    @Test
    fun `a harness with no models row falls back to free text with its default model and lists nothing`() {
        val m = fresh().withHarness("pi")
        assertTrue(m.freeText)
        assertEquals("pi-default", m.freeTextDefault)
        assertEquals(emptyList(), m.providerOptions)
        assertEquals(emptyList(), m.modelOptions)
        assertFalse(m.providerCollapsed)
    }

    @Test
    fun `free text accepts any model id and a blank one means the harness default`() {
        val typed = fresh().withHarness("pi").withModel("zai/glm-4.6")
        assertEquals(PickedModel("pi", "zai/glm-4.6"), typed.picked)
        assertEquals(PickedModel("pi", null), typed.withModel("   ").picked)
    }

    @Test
    fun `a failed listing is free text carrying the row's note, never an empty selectable list`() {
        val m = fresh().withHarness("quiet")
        assertTrue(m.freeText)
        assertEquals(listOf("could not list models: boom"), m.notes)
        assertEquals(emptyList(), m.modelOptions)
    }

    @Test
    fun `a harness with a catalog is not free text and reports no notes when every row listed`() {
        val m = opencode()
        assertFalse(m.freeText)
        assertEquals(emptyList(), m.notes)
    }

    @Test
    fun `one failed row of a listed harness keeps the catalog and surfaces the note`() {
        val partial = ExecutorPickerModel(
            listOf(ProviderInfo("h")),
            listOf(ProviderModels("h", "a", "h / a", listOf("m1")), ProviderModels("h", "b", "h / b", emptyList(), note = "b is not logged in")),
        ).withHarness("h")
        assertFalse(partial.freeText)
        assertEquals(listOf("b is not logged in"), partial.notes)
        assertEquals(listOf("m1"), partial.modelOptions.map { it.id })
    }

    // --- the carried value ---------------------------------------------------------------------------

    @Test
    fun `picked carries the harness as the wire provider and the model, or null model for the default`() {
        assertEquals(PickedModel("opencode", null), opencode().picked)
        assertEquals(PickedModel("opencode", "deepseek/deepseek-chat"), opencode().withModel("deepseek/deepseek-chat").picked)
    }

    @Test
    fun `seeding from a role default selects its harness, model and vendor`() {
        val m = ExecutorPickerModel.seeded(harnesses, catalog, PickedModel("opencode", "deepseek/deepseek-chat"))
        assertEquals("opencode", m.harness)
        assertEquals("deepseek", m.selectedGroup)
        assertEquals("deepseek/deepseek-chat", m.model)
        assertFalse(m.modelMissing)
    }

    @Test
    fun `seeding from a provider-only default selects the harness and leaves the model to its default`() {
        val m = ExecutorPickerModel.seeded(harnesses, catalog, PickedModel("claude", null))
        assertEquals("claude", m.harness)
        assertNull(m.model)
    }

    @Test
    fun `seeding keeps a stored model the catalog no longer lists, verbatim and flagged`() {
        val m = ExecutorPickerModel.seeded(harnesses, catalog, PickedModel("opencode", "retired/old-model"))
        assertEquals("retired/old-model", m.model)
        assertTrue(m.modelMissing)
        assertEquals(PickedModel("opencode", "retired/old-model"), m.picked)
    }

    @Test
    fun `seeding with nothing, or with a harness the daemon does not report, selects nothing`() {
        assertNull(ExecutorPickerModel.seeded(harnesses, catalog, null).harness)
        assertNull(ExecutorPickerModel.seeded(harnesses, catalog, PickedModel("gone", "m")).harness)
    }

    @Test
    fun `a model typed into free text is never flagged missing`() = assertFalse(fresh().withHarness("pi").withModel("zai/glm-4.6").modelMissing)

    @Test
    fun `PICKER-ALL-2 a harness the models frame does not list falls back to its providers list grouped by prefix`() {
        val hs = listOf(ProviderInfo("opencode", default = true, models = listOf("opencode-go/b", "opencode/a", "opencode-go/a")), ProviderInfo("claude", models = listOf("x")))
        val frame = listOf(ProviderModels("claude", "", "claude", listOf("sonnet")))
        val full = ExecutorPickerModel.catalogWithProviderLists(hs, frame)
        assertEquals(frame, full.filter { it.provider == "claude" }, "a harness the frame lists keeps only the frame's rows")
        assertEquals(
            listOf(ProviderModels("opencode", "opencode", "opencode", listOf("opencode/a")), ProviderModels("opencode", "opencode-go", "opencode-go", listOf("opencode-go/a", "opencode-go/b"))),
            full.filter { it.provider == "opencode" },
        )
        val m = ExecutorPickerModel(hs, full).withHarness("opencode")
        assertFalse(m.freeText, "providers-frame models make it a searchable list, not a text box")
        assertEquals(2, m.providerOptions.size)
    }
}
