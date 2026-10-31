package com.superiorsoftllc.agenthq.usagemeter

import com.superiorsoftllc.agenthq.protocol.MessageOrigin
import com.superiorsoftllc.agenthq.session.SessionEvent
import com.superiorsoftllc.agenthq.session.SessionRecord
import com.superiorsoftllc.agenthq.session.SessionState
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class UsageMeterTest {
    private val claudeLines = listOf(
        """{"type":"assistant","message":{"id":"m1","model":"claude-sonnet-5-5","usage":{"input_tokens":10,"output_tokens":1,"cache_read_input_tokens":1000,"cache_creation_input_tokens":200}}}""",
        """{"type":"assistant","message":{"id":"m1","model":"claude-sonnet-5-5","usage":{"input_tokens":10,"output_tokens":1,"cache_read_input_tokens":1000,"cache_creation_input_tokens":200}}}""",
        """{"type":"assistant","message":{"id":"m2","model":"claude-sonnet-5-5","usage":{"input_tokens":20,"output_tokens":2,"cache_read_input_tokens":1200,"cache_creation_input_tokens":0}}}""",
        """{"type":"result","num_turns":2,"total_cost_usd":0.5,"usage":{"input_tokens":30,"output_tokens":90,"cache_read_input_tokens":2200,"cache_creation_input_tokens":200}}""",
    )

    @Test fun `claude result totals win and assistant lines give turns and context`() {
        val a = StreamUsageAccumulator(); claudeLines.forEach(a::accept)
        val t = assertNotNull(a.totals())
        assertEquals(2, t.turns); assertEquals(30, t.input); assertEquals(90, t.output)
        assertEquals(2200, t.cacheRead); assertEquals(200, t.cacheWrite)
        assertEquals(1210L + 1220L, t.contextSum); assertEquals(0.5, t.cost)
        assertEquals("claude-sonnet-5-5", t.model)
    }

    @Test fun `pi message_end and a run with no usage`() {
        val a = StreamUsageAccumulator()
        a.accept("""{"type":"message_end","message":{"role":"assistant","provider":"anthropic","model":"claude-haiku","usage":{"input":5,"output":7,"cacheRead":100,"cacheWrite":3,"cost":{"total":0.01}}}}""")
        val t = assertNotNull(a.totals())
        assertEquals(1, t.turns); assertEquals(108L, t.contextSum); assertEquals("anthropic/claude-haiku", t.model)
        assertNull(StreamUsageAccumulator().also { it.accept("""{"type":"user"}""") }.totals())
    }

    private fun rec(id: String, role: String?) = SessionRecord(
        id = id, provider = "claude", workingDir = ".", spawnedAt = 0, lastHeartbeat = 0, state = SessionState.IDLE, role = role, model = "m",
    )

    @Test fun `source follows who started the turn`() {
        val m = UsageMeter.open(File.createTempFile("meter", ".db").also { it.deleteOnExit() })
        assertEquals("pm", m.sourceOf(rec("a", "pm"), MessageOrigin.Owner))
        assertEquals("worker", m.sourceOf(rec("a", "backend"), MessageOrigin.Owner))
        assertEquals("mail", m.sourceOf(rec("a", "pm"), MessageOrigin.Agent("x")))
        assertEquals("trigger", m.sourceOf(rec("a", "pm"), MessageOrigin.External("trigger:t1", "mail")))
        m.close()
    }

    @Test fun `weekly retention keeps two weeks of runs and rolls older runs into days, then drops old days`() {
        val now = LocalDate.of(2026, 10, 28).atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli() // Wednesday
        val day = 86_400_000L
        val m = UsageMeter.open(File.createTempFile("meter", ".db").also { it.deleteOnExit() }, { 4 }, { now })
        val t = RunTotals(2, 10, 20, 30, 40, 100, 0.25, null)
        // previous week's Monday (10-19) is kept as a run; 10-18 (Sunday before) is older and rolls up.
        val keptAt = now - 9 * day; val oldAt = now - 10 * day; val ancientAt = now - 60 * day
        m.insert(rec("s1", "pm"), "pm", t, keptAt)
        val old = rec("s2", "pm").copy(runCount = 1); val old2 = rec("s2", "pm").copy(runCount = 2)
        m.insert(old, "pm", t, oldAt); m.insert(old2, "pm", t, oldAt + 1000)
        m.insert(rec("s3", "pm"), "pm", t, ancientAt)
        val before = m.report().rows
        assertEquals(4L, before.sumOf { it.runs })
        val (runs, days) = m.prune()
        assertEquals(3, runs); assertEquals(1, days)
        val after = m.report().rows
        assertEquals(3L, after.sumOf { it.runs })
        val oldDay = after.single { it.day == "2026-10-18" }
        assertEquals(2L, oldDay.runs); assertEquals(20L, oldDay.input); assertEquals(0.5, oldDay.cost)
        assertEquals(1L, after.single { it.day == "2026-10-19" }.runs)
        m.prune() // idempotent
        assertEquals(3L, m.report().rows.sumOf { it.runs })
        m.close()
    }

    @Test fun `a turn's events become one run row`() {
        val m = UsageMeter.open(File.createTempFile("meter", ".db").also { it.deleteOnExit() })
        val r = rec("s9", "backend")
        val sessions: (String) -> SessionRecord? = { r }
        m.onSessionEvent(SessionEvent.Turn("s9", "go"), sessions)
        claudeLines.forEach { m.onSessionEvent(SessionEvent.Stdout("s9", it), sessions) }
        m.onSessionEvent(SessionEvent.Exit("s9", 0), sessions)
        val row = m.report().rows.single()
        assertEquals("worker", row.source); assertEquals(1L, row.runs); assertEquals(2200L, row.cacheRead)
        m.close()
    }

    private fun ticketRec(id: String, provider: String, ticket: String?) = rec(id, "backend").copy(provider = provider, ticketId = ticket)

    @Test fun `each turn is stored per request as it arrives and pushed, before the run exits`() {
        val m = UsageMeter.open(File.createTempFile("meter", ".db").also { it.deleteOnExit() })
        val pushes = java.util.concurrent.atomic.AtomicInteger()
        m.onRecorded = { pushes.incrementAndGet() }
        val r = ticketRec("s1", "claude", "t-1")
        val sessions: (String) -> SessionRecord? = { r }
        m.onSessionEvent(SessionEvent.Turn("s1", "go"), sessions)
        m.onSessionEvent(SessionEvent.Stdout("s1", claudeLines[0]), sessions)
        // no Exit yet: the request and the in-flight run are already in the report, and one push fired.
        assertEquals(1, pushes.get())
        val rep = m.report()
        val q = rep.requests.single()
        assertEquals("claude", q.harness); assertEquals("t-1", q.ticketId); assertEquals("s1", q.sessionId); assertEquals("backend", q.role)
        assertEquals(10L, q.input); assertEquals(1000L, q.cacheRead); assertEquals(200L, q.cacheWrite); assertEquals(1210L, q.context)
        assertNull(q.output, "claude assistant lines carry streaming placeholders: output is n/a per request")
        assertNull(q.cost)
        assertEquals(1L, rep.rows.sumOf { it.turns })
        m.onSessionEvent(SessionEvent.Stdout("s1", claudeLines[2]), sessions)
        assertEquals(2, pushes.get()); assertEquals(2, m.report().requests.size)
        m.onSessionEvent(SessionEvent.Exit("s1", 0), sessions)
        assertEquals(2L, m.report().rows.sumOf { it.turns }, "the exit replaces the in-flight row, no double count")
        m.close()
    }

    @Test fun `usage lines with no Turn event before them are still recorded`() {
        val m = UsageMeter.open(File.createTempFile("meter", ".db").also { it.deleteOnExit() })
        val r = ticketRec("w1", "claude", "t-9")
        val sessions: (String) -> SessionRecord? = { r }
        m.onSessionEvent(SessionEvent.Turn("w1", "go"), sessions)
        m.onSessionEvent(SessionEvent.Stdout("w1", claudeLines[0]), sessions)
        m.onSessionEvent(SessionEvent.Exit("w1", 0), sessions)
        // a woken kept process: no Turn, new message ids, a model id the table never saw
        m.onSessionEvent(SessionEvent.Stdout("w1", claudeLines[2].replace("claude-sonnet-5-5", "claude-opus-5-5")), sessions)
        m.onSessionEvent(SessionEvent.Stdout("w1", claudeLines[2].replace("m2", "m3")), sessions)
        assertEquals(3, m.report().requests.size)
        m.close()
    }

    @Test fun `a field the harness does not report is stored as null`() {
        val m = UsageMeter.open(File.createTempFile("meter", ".db").also { it.deleteOnExit() })
        val r = ticketRec("o1", "opencode", null)
        val sessions: (String) -> SessionRecord? = { r }
        m.onSessionEvent(SessionEvent.Turn("o1", "go"), sessions)
        m.onSessionEvent(SessionEvent.Stdout("o1", """{"type":"step_finish","part":{"tokens":{"input":50,"output":7}}}"""), sessions)
        val q = m.report().requests.single()
        assertEquals(50L, q.input); assertEquals(7L, q.output)
        assertNull(q.cacheRead); assertNull(q.cacheWrite); assertEquals(50L, q.context); assertNull(q.cost); assertNull(q.ticketId)
        m.close()
    }

    @Test fun `a compaction the harness reports is recorded as an event`() {
        val m = UsageMeter.open(File.createTempFile("meter", ".db").also { it.deleteOnExit() })
        val r = ticketRec("c1", "claude", "t-9")
        val sessions: (String) -> SessionRecord? = { r }
        m.onSessionEvent(SessionEvent.Turn("c1", "go"), sessions)
        m.onSessionEvent(SessionEvent.Stdout("c1", """{"type":"system","subtype":"compact_boundary","compact_metadata":{"trigger":"auto","pre_tokens":150000}}"""), sessions)
        val e = m.report().events.single()
        assertEquals("compaction", e.kind); assertEquals("auto", e.trigger); assertEquals(150000L, e.tokensBefore); assertEquals("t-9", e.ticketId)
        m.close()
    }

    @Test fun `the price table rides the report and the debounce coalesces a burst`() {
        val dir = java.nio.file.Files.createTempDirectory("prices").toFile()
        val prices = UsagePrices(File(dir, "usage-prices.json"))
        val m = UsageMeter.open(File(dir, "m.db")); m.prices = prices::current
        assertEquals(UsagePrices.DEFAULTS, m.report().prices)
        assertEquals(true, File(dir, "usage-prices.json").exists())
        m.close()
        val fired = java.util.concurrent.atomic.AtomicInteger()
        val d = PushDebounce(200) { fired.incrementAndGet() }
        repeat(20) { d.poke() }
        Thread.sleep(600)
        assertEquals(2, fired.get(), "one leading push, one trailing push")
    }
}
