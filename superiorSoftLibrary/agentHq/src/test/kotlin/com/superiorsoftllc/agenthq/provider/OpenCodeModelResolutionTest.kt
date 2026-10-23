package com.superiorsoftllc.agenthq.provider

import com.superiorsoftllc.agenthq.opencode.OpenCodeCommand
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * BUG-G2: the `-m` a spawned opencode run gets is the explicit profile / ticket / spawn model first,
 * then `AGENTHQ_OPENCODE_MODEL`, then [OpenCodeCommand.DEFAULT_MODEL]; "default" is never an id.
 */
class OpenCodeModelResolutionTest {
    private val pickle: (String) -> String? = { if (it == "AGENTHQ_OPENCODE_MODEL") "opencode/big-pickle" else null }
    private val unset: (String) -> String? = { null }

    private fun argvModel(requested: String?, env: (String) -> String?): String {
        val argv = OpenCodeCommand.command("opencode.exe", OpenCodeCommand.effectiveModel(requested, env), null)
        return argv[argv.indexOf("-m") + 1]
    }

    @Test
    fun anExplicitModelWins() = assertEquals("vendor/explicit", argvModel("vendor/explicit", pickle))

    @Test
    fun noModelUsesAgentHqOpencodeModel() = assertEquals("opencode/big-pickle", argvModel(null, pickle))

    @Test
    fun theDefaultPlaceholderIsNeverPassedThrough() {
        assertEquals("opencode/big-pickle", argvModel("default", pickle))
        assertEquals("opencode/big-pickle", argvModel(" Default ", pickle))
        assertEquals(OpenCodeCommand.DEFAULT_MODEL, argvModel("default", unset))
        assertEquals(OpenCodeCommand.DEFAULT_MODEL, argvModel(null) { if (it == "AGENTHQ_OPENCODE_MODEL") "default" else null })
    }

    @Test
    fun nothingConfiguredFallsBackToTheFreeModel() = assertEquals(OpenCodeCommand.DEFAULT_MODEL, argvModel(null, unset))
}
