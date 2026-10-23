package com.superiorsoftllc.agenthq.provider

import com.superiorsoftllc.agenthq.opencode.OpenCodeCommand
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * BUG-DEFMODEL: [OpenCodeCommand.DEFAULT_MODEL] is what every model-less spawn runs on, so a retired
 * id fails every such spawn with "Model not found". The live check asks the real `opencode models`
 * (skipped where the binary is absent); the denylist pins ids already known to be dead.
 */
class OpenCodeDefaultModelLiveTest {
    /** Ids opencode has retired. Add to it, never remove: a dead id must never come back as the default. */
    private val knownDead = listOf("space-bunny", "minimax-m3.1-flash-free")

    @Test
    fun defaultModelIsNotAKnownDeadId() {
        for (dead in knownDead) {
            assertFalse(OpenCodeCommand.DEFAULT_MODEL.contains(dead), "DEFAULT_MODEL ${OpenCodeCommand.DEFAULT_MODEL} is retired ($dead)")
        }
    }

    @Test
    fun defaultModelIsInTheLiveOpencodeCatalog() {
        val raw = try {
            listOpenCodeModels()
        } catch (e: Exception) {
            null
        }
        assumeTrue("opencode binary absent or `opencode models` failed", raw != null)
        val live = OpenCodeProvider { emptyList() }.parseModels(raw!!.joinToString("\n"))
        assertNotNull(live, "`opencode models` printed no model ids")
        assertTrue(
            OpenCodeCommand.DEFAULT_MODEL in live,
            "DEFAULT_MODEL ${OpenCodeCommand.DEFAULT_MODEL} is not in `opencode models` (${live.size} ids)",
        )
    }
}
