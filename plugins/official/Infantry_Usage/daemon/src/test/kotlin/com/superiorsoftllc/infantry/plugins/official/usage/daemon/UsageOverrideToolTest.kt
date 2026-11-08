// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.usage.daemon

import com.superiorsoftllc.infantry.daemon.api.Caller
import com.superiorsoftllc.infantry.daemon.api.Frame
import com.superiorsoftllc.infantry.daemon.api.ToolCall
import com.superiorsoftllc.infantry.daemon.api.usage.MeterUsage
import com.superiorsoftllc.infantry.daemon.api.usage.PacingRule
import com.superiorsoftllc.infantry.daemon.api.usage.UsageOverride
import com.superiorsoftllc.infantry.daemon.api.usage.UsageReport
import com.superiorsoftllc.infantry.daemon.api.usage.UsageSet
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** USAGE-OVERRIDE-GAP-1: read, set and clear the allowance override; who may; the audit line. */
class UsageOverrideToolTest {
    private val now = 1_000_000_000_000L

    /** A fake daemon: one meter, allowance from the rule (1) unless an override is set. */
    private class Daemon(val now: Long) {
        var override: UsageOverride? = null
        val sent = mutableListOf<UsageSet>()
        fun report() = UsageReport(
            listOf(MeterUsage("claude", 50.0, source = "live", running = 1, canStart = (override?.workers ?: 1) > 1, allowance = override?.let { it.workers } ?: 1, override = override)),
            PacingRule.DEFAULT, now,
        )
        fun answer(type: String, data: String): Frame {
            if (type == "usage_set") {
                val set = UsageSet.decode(data); sent += set
                override = if (set.clearOverride) null else UsageOverride(set.overrideWorkers, set.overrideUntil!!)
            }
            return Frame("usage_report", Json.encodeToString(UsageReport.serializer(), report()))
        }
    }

    private fun call(d: Daemon, session: String?, vararg args: Pair<String, String>) = object : ToolCall {
        override val arguments = JsonObject(args.associate { it.first to JsonPrimitive(it.second) })
        override val caller = Caller(owner = true)
        override val clientSessionId = session
        override fun request(type: String, data: String, sessionId: String?, timeoutMs: Long) = d.answer(type, data)
    }

    private fun tool(log: MutableList<String> = mutableListOf(), roles: List<String> = listOf("owner", "pm")) =
        UsageOverrideTool({ id -> mapOf("s-pm" to "pm", "s-dev" to "fullstack")[id] }, { roles }, { log += it }, { now })

    @Test
    fun `read only shows the allowance and no override`() {
        val d = Daemon(now)
        val r = tool().call(call(d, "s-dev", "meter" to "claude"))
        assertTrue(!r.isError && "no override active" in r.text, r.text)
        assertTrue(d.sent.isEmpty())
    }

    @Test
    fun `pm raises the allowance then clears it and the change is logged`() {
        val d = Daemon(now); val log = mutableListOf<String>()
        val set = tool(log).call(call(d, "s-pm", "meter" to "claude", "value" to "2", "hours" to "1", "reason" to "owner asked"))
        assertTrue(!set.isError && "override active: 2" in set.text && "(in 1h 0m)" in set.text, set.text)
        assertEquals(now + 3_600_000L, d.sent.single().overrideUntil)
        assertTrue(log.single().contains("s-pm") && log.single().contains("owner asked"), log.toString())
        val cleared = tool(log).call(call(d, "s-pm", "meter" to "claude", "value" to "clear"))
        assertTrue("no override active" in cleared.text, cleared.text)
        assertNull(d.override)
    }

    @Test
    fun `a role outside the setting is refused and nothing is sent`() {
        val d = Daemon(now)
        val r = tool().call(call(d, "s-dev", "meter" to "claude", "value" to "2"))
        assertTrue(r.isError && "may not set" in r.text, r.text)
        assertTrue(d.sent.isEmpty())
    }

    @Test
    fun `the owner with no session may set and an unknown session may not`() {
        val d = Daemon(now)
        assertTrue(!tool().call(call(d, null, "meter" to "claude", "value" to "none")).isError)
        assertTrue(tool().call(call(d, "s-ghost", "meter" to "claude", "value" to "3")).isError)
    }
}
