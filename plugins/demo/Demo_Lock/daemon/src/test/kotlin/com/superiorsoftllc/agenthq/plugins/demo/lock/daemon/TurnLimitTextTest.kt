package com.superiorsoftllc.agenthq.plugins.demo.lock.daemon

import com.superiorsoftllc.agenthq.plugin.api.PolicyPreset
import com.superiorsoftllc.agenthq.plugin.api.SeatsSpec
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * POLICY-1 gap 4: the turn limit's refusal text is the preset's (`seats.turnLimitText`), not a demo string in core. A preset
 * that names none gets core's neutral text.
 */
class TurnLimitTextTest {
    private val state: File = Files.createTempDirectory("turn-limit-text").toFile()

    @AfterTest fun cleanUp() { state.deleteRecursively() }

    private fun limitOf(preset: PolicyPreset): DemoTurnLimit = DemoLock.of(preset, { null }, state).turnLimit!!

    @Test fun `the demo preset's text is the refusal`() {
        val demo = DemoPreset.preset.let { it.copy(seats = it.seats!!.copy(turnsPerIp = 1, turnWindowHours = 24)) }
        val limit = limitOf(demo)
        limit.record("203.0.113.5")
        assertEquals("demo_limit Demo limit reached: 1 turns per visitor per day", limit.refusal("203.0.113.5"))
    }

    @Test fun `a preset's own text and code are used, the window filled in`() {
        val preset = PolicyPreset(
            id = "metered",
            seats = SeatsSpec(turnsPerIp = 2, turnWindowHours = 6, turnLimitCode = "quota", turnLimitText = "Out of turns ({turns} per {window})"),
        )
        val limit = limitOf(preset)
        repeat(2) { limit.record("198.51.100.7") }
        assertEquals("quota Out of turns (2 per 6 hours)", limit.refusal("198.51.100.7"))
    }

    @Test fun `a preset with no text gets core's neutral one, never the demo's`() {
        val limit = limitOf(PolicyPreset(id = "plain", seats = SeatsSpec(turnsPerIp = 1, turnWindowHours = 24)))
        limit.record("192.0.2.1")
        assertEquals("demo_limit Turn limit reached: 1 turns per visitor per day", limit.refusal("192.0.2.1"))
    }
}
