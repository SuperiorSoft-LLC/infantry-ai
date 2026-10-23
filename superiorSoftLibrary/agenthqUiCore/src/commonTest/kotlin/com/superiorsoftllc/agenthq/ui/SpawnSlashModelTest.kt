package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.ui.model.PickedModel
import com.superiorsoftllc.agenthq.ui.model.SpawnSlash
import com.superiorsoftllc.agenthq.ui.model.spawnOptionsData
import com.superiorsoftllc.agenthq.ui.model.spawnSlash
import com.superiorsoftllc.agenthq.ui.protocol.CommandCatalogAnswer
import com.superiorsoftllc.agenthq.ui.protocol.SlashCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** CP-4(e): what a `/` line typed in the New agent box means. It is never a task prompt. */
class SpawnSlashModelTest {
    private val catalog = CommandCatalogAnswer(
        "claude", "",
        listOf(
            SlashCommand("model", "Set the model", "SET_MODEL", "<model>"),
            SlashCommand("effort", "Set the effort", "SET_EFFORT", "<level>", listOf("low", "high")),
            SlashCommand("compact", "Compact", "NATIVE", native = "/compact"),
        ),
    )

    @Test
    fun anUnknownCommandIsRefusedWithTheKnownList() {
        val r = spawnSlash("/frobnicate now", catalog) as SpawnSlash.Refuse
        assertTrue(listOf("/model", "/effort", "/compact").all { it in r.reason } && "frobnicate" in r.reason, r.reason)
    }

    @Test
    fun modelAndEffortBecomeTheSpawnSelection() {
        assertEquals(SpawnSlash.SetModel("claude", "opus"), spawnSlash("/model opus", catalog))
        assertEquals(SpawnSlash.SetEffort("high"), spawnSlash("/effort HIGH", catalog))
        assertTrue(spawnSlash("/effort turbo", catalog) is SpawnSlash.Refuse)
        assertTrue(spawnSlash("/model", catalog) is SpawnSlash.Refuse)
    }

    @Test
    fun compactIsRefusedBecauseThereIsNoSessionYet() {
        val r = spawnSlash("/compact", catalog) as SpawnSlash.Refuse
        assertTrue("session" in r.reason, r.reason)
    }

    @Test
    fun theNewFrameCarriesTheEffort() {
        assertEquals("""{"effort":"high"}""", spawnOptionsData(null, "high"))
        assertEquals("""{"provider":"claude","model":"opus","effort":"high"}""", spawnOptionsData(PickedModel("claude", "opus"), "high"))
    }
}
