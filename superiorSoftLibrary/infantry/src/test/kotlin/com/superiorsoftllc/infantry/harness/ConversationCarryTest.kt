package com.superiorsoftllc.infantry.harness

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** HARNESS-CARRY-1: the replayed history fits its budget: recent exchanges verbatim, older ones summarised, newest always in. */
class ConversationCarryTest {
    private fun turn(i: Int, size: Int = 1_000) = CarriedTurn("question $i " + "q".repeat(20), "answer $i " + "a".repeat(size))

    @Test
    fun aHistoryUnderBudgetIsCarriedWholeOldestFirstThenThePrompt() {
        val seeded = ConversationCarry.REPLAY.seed(listOf(turn(1), turn(2)), "now?")
        assertTrue(seeded.startsWith(ReplayCarry.OPEN))
        assertTrue(seeded.indexOf("question 1") < seeded.indexOf("question 2"))
        assertTrue(seeded.contains("answer 2 "))
        assertFalse(seeded.contains("summarised"), "nothing trimmed, nothing summarised")
        assertTrue(seeded.endsWith("\n\nnow?"))
        assertEquals("now?", ConversationCarry.REPLAY.seed(emptyList(), "now?"), "no history, the prompt alone")
    }

    @Test
    fun anOverBudgetHistoryKeepsTheRecentTurnsAndSummarisesTheOlderOnes() {
        val history = (1..100).map { turn(it) }
        val seeded = ReplayCarry(budgetChars = 10_000, summaryChars = 600).seed(history, "what did I ask first?")
        val block = seeded.substringBefore(ReplayCarry.CLOSE)
        assertTrue(block.length < 10_000 + 600 + 200, "the block stays within budget + summary: ${block.length}")
        assertTrue(seeded.contains("answer 100 "), "the newest exchange is always carried verbatim")
        assertTrue(seeded.contains("answer 92 "), "recent exchanges are carried verbatim")
        assertFalse(seeded.contains("answer 50 "), "older replies are not")
        assertTrue(seeded.contains("Earlier (summarised):"), seeded.take(800))
        assertTrue(seeded.contains("- the user asked: question 90 "), "the most recent trimmed exchanges are summarised")
        assertTrue(seeded.contains("earlier exchange(s) not shown"), "and the rest are counted")
        assertTrue(seeded.endsWith("what did I ask first?"))
    }

    @Test
    fun aNewestTurnLargerThanTheBudgetIsStillCarriedCutToFit() {
        val seeded = ReplayCarry(budgetChars = 2_000, summaryChars = 300).seed(listOf(turn(1), turn(2, size = 50_000)), "go")
        assertTrue(seeded.contains("question 2 "), "the newest exchange is never dropped")
        assertTrue(seeded.contains("- the user asked: question 1 "), "the older one is summarised")
        assertTrue(seeded.substringBefore(ReplayCarry.CLOSE).length < 2_000 + 300 + 200)
        assertTrue(seeded.endsWith("go"))
    }
}
