package com.superiorsoftllc.agenthq.provider

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenCodeModelsTest {
    @Test
    fun groupsTheCliListingByUpstreamProvider() {
        val p = OpenCodeProvider { listOf("opencode/big-pickle", "anthropic/claude-x", "opencode/beam-free", "", "junk") }
        val groups = p.models()
        assertEquals(listOf("anthropic", "opencode"), groups.map { it.group }.sorted())
        assertEquals(listOf("opencode/big-pickle", "opencode/beam-free"), groups.first { it.group == "opencode" }.models)
        assertTrue(groups.all { it.provider == "opencode" })
    }

    @Test
    fun noModelsFromTheCliIsOneHonestEmptyEntry() {
        val groups = OpenCodeProvider { emptyList() }.models()
        assertEquals(1, groups.size)
        assertTrue(groups[0].models.isEmpty() && groups[0].note != null)
    }
}
