package com.superiorsoftllc.agenthq.tokens

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * MVP 8, leaf 3/4: the usage ledger must SURVIVE a restart, otherwise the cost thesis is judged on
 * samples that vanish the moment the daemon is restarted mid-measurement.
 *
 * The measurement must be append-only: a corrupt line never gets silently skipped, because a hole
 * in the ledger quietly flatters whichever arm it landed on.
 */
class JsonFileUsageLedgerTest {

    private fun sample(
        taskId: String = "task-1",
        arm: Arm = Arm.MULTI_AGENT_ROOM,
        provider: String = "agenthq",
        model: String = "stub",
        tokensIn: Long = 1_000L,
        tokensOut: Long = 2_000L,
        startedAtMs: Long = 1_700_000_000_000L,
        endedAtMs: Long = 1_700_000_060_000L,
        defects: Int = 0,
    ) = UsageSample(taskId, arm, provider, model, tokensIn, tokensOut, startedAtMs, endedAtMs, defects)

    @Test
    fun `a recorded sample is read back by a new instance on the same file`() {
        // The whole point of the leaf: "restart" is a NEW instance over the SAME path.
        val file = tempDir().resolve("usage.jsonl")
        JsonFileUsageLedger(file).record(sample(tokensIn = 1_000L, tokensOut = 2_000L, defects = 1))

        val reopened = JsonFileUsageLedger(file)

        val samples = reopened.samples("task-1")
        assertEquals(1, samples.size)
        assertEquals(sample(tokensIn = 1_000L, tokensOut = 2_000L, defects = 1), samples.single())
    }

    @Test
    fun `a restarted ledger compares the same arms with the same totals`() {
        val file = tempDir().resolve("usage.jsonl")
        JsonFileUsageLedger(file).apply {
            record(sample(arm = Arm.MULTI_AGENT_ROOM, tokensIn = 1_000L, tokensOut = 2_000L, defects = 1))
            record(sample(arm = Arm.SINGLE_AGENT, tokensIn = 3_000L, tokensOut = 4_000L, defects = 2))
        }

        val comparison = JsonFileUsageLedger(file).compare("task-1")

        assertEquals(2, comparison.totals.size)
        assertEquals(3_000L, comparison.totals[Arm.MULTI_AGENT_ROOM]!!.tokensIn + comparison.totals[Arm.MULTI_AGENT_ROOM]!!.tokensOut)
        assertEquals(1, comparison.totals[Arm.MULTI_AGENT_ROOM]!!.runs)
        assertEquals(7_000L, comparison.totals[Arm.SINGLE_AGENT]!!.tokensIn + comparison.totals[Arm.SINGLE_AGENT]!!.tokensOut)
        assertEquals(2, comparison.totals[Arm.SINGLE_AGENT]!!.defects)
    }

    @Test
    fun `a missing file is an empty ledger, not a crash`() {
        // The daemon starts before any run has been measured; that must not be an error.
        val ledger = JsonFileUsageLedger(tempDir().resolve("absent.jsonl"))

        assertTrue(ledger.samples("task-1").isEmpty())
        assertTrue(ledger.compare("task-1").totals.isEmpty())
    }

    @Test
    fun `a corrupt line is refused with its line number`() {
        val file = tempDir().resolve("usage.jsonl")
        // Line 1 is a VALID sample; only line 2 is corrupt, so "line 2" is the real answer.
        val valid = kotlinx.serialization.json.Json.encodeToString(UsageSample.serializer(), sample())
        Files.writeString(file, "$valid\nnot json at all\n")

        val error = assertFailsWith<IllegalStateException> { JsonFileUsageLedger(file) }

        // Match "line N" explicitly: the message also carries a random temp path full of digits.
        assertTrue(
            Regex("""\bline 2\b""").containsMatchIn(error.message!!),
            "the error must name the offending line number, got: ${error.message}",
        )
    }

    private fun tempDir(): Path = Files.createTempDirectory("agenthq-usage-ledger")
}
