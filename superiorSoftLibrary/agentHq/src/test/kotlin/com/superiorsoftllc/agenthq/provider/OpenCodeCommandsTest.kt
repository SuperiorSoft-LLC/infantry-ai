package com.superiorsoftllc.agenthq.provider

import com.superiorsoftllc.agenthq.harness.CommandAction
import com.superiorsoftllc.agenthq.harness.CommandLines
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** GAP-4: an opencode session lists its `/` commands in the composer, like claude's. */
class OpenCodeCommandsTest {
    private val provider = OpenCodeProvider(modelLister = { emptyList() })

    @Test
    fun opencodeOffersItsModelAndCompactCommands() {
        val names = CommandLines.complete(provider.commands, "").map { it.name }
        assertTrue(names.isNotEmpty(), "an opencode session must list slash commands, got $names")
        assertEquals(listOf("model", "compact"), names)
    }

    @Test
    fun opencodeHasNoEffortCommandBecauseItsRunHasNoEffortFlag() {
        assertTrue(provider.commands.none { it.action == CommandAction.SET_EFFORT })
        assertEquals("/compact", provider.commands.single { it.name == "compact" }.native)
    }
}
