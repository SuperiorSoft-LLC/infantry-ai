package com.superiorsoftllc.infantry.stats

import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * STATS-2 (contract 1.113.0): the CONSISTENCY test. One synthetic request sequence is written as a Claude record, a
 * Pi record and an OpenCode database; every harness must give identical context numbers, billed running totals,
 * request counts, limit and source rows. Samples are in each harness's real shape.
 */
class StatsConsistencyTest {
    private val home: File = Files.createTempDirectory("stats-consistency").toFile()

    @AfterTest
    fun cleanup() {
        home.deleteRecursively()
    }

    /** input, output, cacheRead, cacheWrite; the third request is all-zero (an aborted call) and must not count. */
    private data class Req(val input: Long, val output: Long, val read: Long, val write: Long)

    private val reqs = listOf(Req(100, 20, 900, 0), Req(50, 10, 1300, 200), Req(0, 0, 0, 0), Req(5, 7, 1700, 0))
    private val t0 = Instant.parse("2026-10-21T10:00:00Z").toEpochMilli()
    private fun at(i: Int) = t0 + (i + 1) * 1000L
    private fun iso(i: Int) = Instant.ofEpochMilli(at(i)).toString()
    private val model = "model-x"

    private fun claude(): Pair<String, String> {
        val lines = reqs.mapIndexed { i, r ->
            """{"type":"assistant","timestamp":"${iso(i)}","message":{"id":"m$i","role":"assistant","model":"$model","content":[{"type":"tool_use","id":"t$i","name":"Read","input":{}}],"usage":{"input_tokens":${r.input},"cache_creation_input_tokens":${r.write},"cache_read_input_tokens":${r.read},"output_tokens":${r.output}}}}"""
        }
        File(home, ".claude/projects/p").apply { mkdirs() }
        File(home, ".claude/projects/p/c1.jsonl").writeText(lines.joinToString("\n", postfix = "\n"))
        return "claude" to "c1"
    }

    private fun pi(): Pair<String, String> {
        val lines = reqs.mapIndexed { i, r ->
            """{"type":"message","id":"a$i","timestamp":"${iso(i)}","message":{"role":"assistant","content":[{"type":"toolCall","id":"c$i","name":"Read","arguments":{}}],"provider":"x","model":"$model","usage":{"input":${r.input},"output":${r.output},"cacheRead":${r.read},"cacheWrite":${r.write},"totalTokens":${r.input + r.output + r.read + r.write},"cost":{"total":0.5}},"stopReason":"toolUse","timestamp":${at(i)}}}"""
        }
        File(home, ".pi/agent/sessions/d").apply { mkdirs() }
        File(home, ".pi/agent/sessions/d/2026-10-21T10-00-00-000Z_p1.jsonl").writeText(lines.joinToString("\n", postfix = "\n"))
        return "pi" to "p1"
    }

    private fun openCode(harness: String): Pair<String, String> {
        val dir = File(home, ".local/share/opencode").apply { mkdirs() }
        DriverManager.getConnection("jdbc:sqlite:${File(dir, "opencode.db").absolutePath}").use { c ->
            c.createStatement().use { st ->
                st.executeUpdate("CREATE TABLE `session` (`id` text PRIMARY KEY, `time_created` integer NOT NULL, `time_updated` integer NOT NULL)")
                st.executeUpdate("CREATE TABLE `message` (`id` text PRIMARY KEY, `session_id` text NOT NULL, `time_created` integer NOT NULL, `time_updated` integer NOT NULL, `data` text NOT NULL)")
                st.executeUpdate("CREATE TABLE `part` (`id` text PRIMARY KEY, `message_id` text NOT NULL, `session_id` text NOT NULL, `time_created` integer NOT NULL, `time_updated` integer NOT NULL, `data` text NOT NULL)")
                st.executeUpdate("INSERT INTO session VALUES ('o1',${at(0)},${at(3)})")
            }
            fun ins(sql: String, vararg v: Any) = c.prepareStatement(sql).use { ps ->
                v.forEachIndexed { i, x -> if (x is Long) ps.setLong(i + 1, x) else ps.setString(i + 1, x as String) }; ps.executeUpdate()
            }
            reqs.forEachIndexed { i, r ->
                ins("INSERT INTO message VALUES (?,?,?,?,?)", "m$i", "o1", at(i), at(i), """{"role":"assistant","modelID":"$model","providerID":"x"}""")
                ins("INSERT INTO part VALUES (?,?,?,?,?,?)", "pa$i", "m$i", "o1", at(i) - 1, at(i) - 1, """{"type":"tool","callID":"c$i","tool":"Read","state":{"status":"completed"}}""")
                ins("INSERT INTO part VALUES (?,?,?,?,?,?)", "pf$i", "m$i", "o1", at(i), at(i),
                    """{"type":"step-finish","reason":"tool-calls","cost":0.5,"tokens":{"input":${r.input},"output":${r.output},"reasoning":0,"cache":{"read":${r.read},"write":${r.write}}}}""")
            }
        }
        return harness to "o1"
    }

    private fun read(h: Pair<String, String>) = SessionStatsService(home).read("hq", h.first, h.second, "idle")

    @Test
    fun sameRequestSequenceGivesIdenticalRecordsOnEveryHarness() {
        File(home, ".pi/agent").mkdirs()
        File(home, ".pi/agent/models-store.json").writeText("""{"x":{"models":[{"id":"$model","contextWindow":200000}]}}""")
        val results = listOf(read(claude()), read(pi()))
        val expected = listOf(
            StatsPoint(at(0), 1000, 1020), StatsPoint(at(1), 1550, 2580), StatsPoint(at(3), 1705, 4292),
        )
        val tokenView = { s: SessionStats -> s.series!!.map { Triple(it.at, it.contextTokens, it.cumulativeBilledTokens) } }
        results.forEach { s ->
            assertEquals(expected.map { Triple(it.at, it.contextTokens, it.cumulativeBilledTokens) }, tokenView(s), s.harness)
            assertEquals(3, s.series!!.size, "${s.harness}: the all-zero call is not a request")
            assertEquals(1705L, s.lastContextTokens, s.harness)
            assertEquals(4292L, s.billedTokens, s.harness)
            assertEquals(200_000L, s.modelContextLimit, "${s.harness}: the limit comes from the model, claude included")
            assertEquals(listOf(model), s.models, s.harness)
            assertEquals(4, s.toolUses, s.harness)
        }
        assertEquals(results[0].topSources, results[1].topSources)
        // Cost: only what the harness reports. Claude reports none; pi reports 0.5 per request. (H8b-3: the opencode rows moved to OpenCodeStatsTest; its plugin row has no series.)
        assertNull(results[0].costTotal)
        assertTrue(results[0].series!!.all { it.cumulativeCost == null })
        assertEquals(listOf(0.5, 1.0, 1.5), results[1].series!!.map { it.cumulativeCost })
        assertEquals(1.5, results[1].costTotal)
    }

    @Test
    fun noLimitCatalogLeavesLimitNullForClaudeToo() {
        assertNull(read(claude()).modelContextLimit)
    }
}
