package com.superiorsoftllc.agenthq.stats

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
 * STATS-1: one fixture per harness, in the REAL record shapes (field names sampled from live files on
 * 2026-10-21, content invented), written under a temp home. The owner's real home is never read.
 */
class SessionStatsReadersTest {

    private val home: File = Files.createTempDirectory("stats-home").toFile()

    @AfterTest
    fun cleanup() {
        home.deleteRecursively()
    }

    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    // ---------------------------------------------------------------- Claude Code

    private fun writeClaude(psid: String, lines: List<String>): File {
        val dir = File(home, ".claude/projects/C--proj").apply { mkdirs() }
        return File(dir, "$psid.jsonl").apply { writeText(lines.joinToString("\n", postfix = "\n")) }
    }

    private fun claudeAssistant(ts: String, id: String, model: String, block: String, input: Int, read: Int, write: Int, out: Int) =
        """{"type":"assistant","timestamp":"$ts","sessionId":"c-1","message":{"id":"$id","type":"message","role":"assistant","model":"$model","content":[$block],"stop_reason":null,"usage":{"input_tokens":$input,"cache_creation_input_tokens":$write,"cache_read_input_tokens":$read,"output_tokens":$out}}}"""

    private fun claudeToolResult(ts: String, id: String) =
        """{"type":"user","timestamp":"$ts","sessionId":"c-1","message":{"role":"user","content":[{"tool_use_id":"$id","type":"tool_result","content":"ok","is_error":false}]}}"""

    private val claudeFixture = listOf(
        """{"type":"permission-mode","permissionMode":"default","sessionId":"c-1"}""",
        """{"type":"user","timestamp":"2026-10-21T10:00:00.000Z","sessionId":"c-1","message":{"role":"user","content":"hello"}}""",
        """{"type":"user","isMeta":true,"timestamp":"2026-10-21T10:00:00.500Z","sessionId":"c-1","message":{"role":"user","content":"meta"}}""",
        // One API response split over two lines sharing message.id and repeating the same usage.
        claudeAssistant("2026-10-21T10:00:01.000Z", "msg_a1", "claude-opus-5-5", """{"type":"thinking","thinking":"x","signature":"s"}""", 10, 1000, 200, 50),
        claudeAssistant("2026-10-21T10:00:01.100Z", "msg_a1", "claude-opus-5-5", """{"type":"tool_use","id":"t1","name":"Read","input":{}}""", 10, 1000, 200, 50),
        claudeToolResult("2026-10-21T10:00:02.000Z", "t1"),
        claudeAssistant("2026-10-21T10:00:03.000Z", "msg_a2", "claude-opus-5-5", """{"type":"tool_use","id":"t2","name":"Bash","input":{}}""", 5, 1200, 300, 40),
        claudeAssistant("2026-10-21T10:00:03.100Z", "msg_a2", "claude-opus-5-5", """{"type":"tool_use","id":"t3","name":"Grep","input":{}}""", 5, 1200, 300, 40),
        claudeToolResult("2026-10-21T10:00:04.000Z", "t2"),
        claudeToolResult("2026-10-21T10:00:04.100Z", "t3"),
        claudeAssistant("2026-10-21T10:00:05.000Z", "msg_a3", "claude-sonnet-5-5", """{"type":"text","text":"done"}""", 5, 1500, 100, 30),
        """{"type":"user","timestamp":"2026-10-21T10:00:10.000Z","sessionId":"c-1","message":{"role":"user","content":[{"type":"text","text":"thanks"}]}}""",
        claudeAssistant("2026-10-21T10:00:12.000Z", "msg_a4", "claude-sonnet-5-5", """{"type":"text","text":"ok"}""", 2, 1600, 50, 10),
    )

    @Test
    fun claudeFixtureGivesExpectedStats() {
        writeClaude("c-1", claudeFixture)
        val s = SessionStatsService(home).read("hq-1", "claude", "c-1", "idle")

        assertEquals("hq-1", s.sessionId)
        assertEquals("claude", s.harness)
        assertEquals("c-1", s.providerSessionId)
        assertEquals("idle", s.status)
        assertEquals(ms("2026-10-21T10:00:00.000Z"), s.startedAt)
        assertEquals(12_000L, s.durationMs)
        assertEquals(2, s.userPrompts, "meta lines and tool_result lines are not prompts")
        assertEquals(4, s.assistantTurns, "lines sharing message.id are ONE turn")
        assertEquals(3, s.toolUses)
        assertEquals(listOf("claude-opus-5-5", "claude-sonnet-5-5"), s.models)
        assertEquals(1652L, s.lastContextTokens)
        assertNull(s.modelContextLimit, "Claude Code's record states no context window; it must never be guessed")
        assertEquals(6102L, s.billedTokens, "usage repeated on a split message is counted once")
        assertEquals(
            listOf(
                StatsPoint(ms("2026-10-21T10:00:01.000Z"), 1210, 1260),
                StatsPoint(ms("2026-10-21T10:00:03.000Z"), 1505, 2805),
                StatsPoint(ms("2026-10-21T10:00:05.000Z"), 1605, 4440),
                StatsPoint(ms("2026-10-21T10:00:12.000Z"), 1652, 6102),
            ),
            s.series,
        )
        assertEquals(
            listOf(ContextSource("Read", 1, 295), ContextSource("Bash", 1, null), ContextSource("Grep", 1, null)),
            s.topSources,
        )
        assertEquals(100L, s.unattributedToolTokens, "a two-tool step's growth is never split between them")
    }

    @Test
    fun claudeResolvedPathIsCachedPerSession() {
        writeClaude("c-1", claudeFixture)
        val service = SessionStatsService(home)
        service.read("hq-1", "claude", "c-1", null)
        assertEquals(1, service.resolveScans)
        service.read("hq-1", "claude", "c-1", null)
        assertEquals(1, service.resolveScans, "the projects dir is scanned once per session, not per request")
    }

    @Test
    fun claudeTenThousandLinesStreamQuickly() {
        val lines = ArrayList<String>(10_000)
        for (i in 0 until 5_000) {
            val ts = Instant.ofEpochMilli(ms("2026-10-21T10:00:00.000Z") + i * 1000L).toString()
            lines += """{"type":"user","timestamp":"$ts","sessionId":"big","message":{"role":"user","content":"p$i"}}"""
            lines += claudeAssistant(ts, "msg_$i", "claude-opus-5-5", """{"type":"text","text":"r$i"}""", 1, 100 + i, 0, 1)
        }
        writeClaude("big", lines)
        val started = System.nanoTime()
        val s = SessionStatsService(home).read("hq-big", "claude", "big", null)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(5_000, s.userPrompts)
        assertEquals(5_000, s.assistantTurns)
        assertEquals(5_000, s.series!!.size)
        assertEquals(5_100L, s.lastContextTokens) // input 1 + cache read (100 + 4999)
        assertTrue(elapsedMs < 5_000, "10k lines took ${elapsedMs}ms")
    }

    @Test
    fun unsafeOrUnknownSessionIdGivesNullStatsNotAnException() {
        writeClaude("c-1", claudeFixture)
        val service = SessionStatsService(home)
        val traversal = service.read("hq-1", "claude", "../C--proj/c-1", "idle")
        assertNull(traversal.userPrompts)
        assertNull(traversal.series)
        assertEquals("idle", traversal.status)
        assertNull(service.read("hq-1", "claude", "missing", null).assistantTurns)
        assertNull(service.read("hq-1", "claude", null, null).assistantTurns)
    }

    // ---------------------------------------------------------------- Pi

    private val t0 = ms("2026-10-21T10:00:00.000Z")

    private fun writePi(root: String = ".pi") {
        val dir = File(home, "$root/agent/sessions/--C--proj--").apply { mkdirs() }
        File(dir, "2026-10-21T10-00-00-000Z_01a1-pi.jsonl").writeText(
            listOf(
                """{"type":"session","version":3,"id":"01a1-pi","timestamp":"2026-10-21T10:00:00.000Z","cwd":"C:\\proj"}""",
                """{"type":"model_change","id":"m1","parentId":null,"timestamp":"2026-10-21T10:00:00.100Z","provider":"deepseek","modelId":"deepseek-v4"}""",
                """{"type":"message","id":"u1","parentId":"m1","timestamp":"2026-10-21T10:00:01.000Z","message":{"role":"user","content":[{"type":"text","text":"hi"}],"timestamp":${t0 + 1000}}}""",
                """{"type":"message","id":"a1","parentId":"u1","timestamp":"2026-10-21T10:00:02.000Z","message":{"role":"assistant","content":[{"type":"thinking","thinking":"x"},{"type":"toolCall","id":"c1","name":"read","arguments":{}}],"api":"x","provider":"deepseek","model":"deepseek-v4","usage":{"input":100,"output":20,"cacheRead":900,"cacheWrite":0,"reasoning":5,"totalTokens":1020,"cost":{"input":0,"output":0,"cacheRead":0,"cacheWrite":0,"total":0}},"stopReason":"toolUse","timestamp":${t0 + 2000}}}""",
                """{"type":"message","id":"r1","parentId":"a1","timestamp":"2026-10-21T10:00:03.000Z","message":{"role":"toolResult","toolCallId":"c1","toolName":"read","content":[{"type":"text","text":"x"}],"isError":false,"timestamp":${t0 + 3000}}}""",
                """{"type":"message","id":"a2","parentId":"r1","timestamp":"2026-10-21T10:00:04.000Z","message":{"role":"assistant","content":[{"type":"text","text":"ok"}],"api":"x","provider":"deepseek","model":"deepseek-v4","usage":{"input":50,"output":10,"cacheRead":1300,"cacheWrite":0,"reasoning":0,"totalTokens":1360,"cost":{"input":0,"output":0,"cacheRead":0,"cacheWrite":0,"total":0}},"stopReason":"stop","timestamp":${t0 + 4000}}}""",
            ).joinToString("\n", postfix = "\n"),
        )
    }

    @Test
    fun piFixtureGivesExpectedStats() {
        writePi()
        File(home, ".pi/agent/models-store.json").writeText(
            """{"deepseek":{"models":[{"id":"deepseek-v4","contextWindow":131072,"maxTokens":8192}],"lastModified":"x"}}""",
        )
        val s = SessionStatsService(home).read("hq-2", "pi", "01a1-pi", "busy")

        assertEquals(t0, s.startedAt)
        assertEquals(4_000L, s.durationMs)
        assertEquals(1, s.userPrompts)
        assertEquals(2, s.assistantTurns)
        assertEquals(1, s.toolUses)
        assertEquals(listOf("deepseek-v4"), s.models)
        assertEquals(1350L, s.lastContextTokens)
        assertEquals(131072L, s.modelContextLimit)
        assertEquals(2380L, s.billedTokens)
        assertEquals(listOf(StatsPoint(t0 + 2000, 1000, 1020), StatsPoint(t0 + 4000, 1350, 2380)), s.series)
        assertEquals(listOf(ContextSource("read", 1, 350)), s.topSources)
        assertEquals(0L, s.unattributedToolTokens)
    }

    @Test
    fun piWithoutModelCatalogLeavesLimitNull() {
        writePi()
        assertNull(SessionStatsService(home).read("hq-2", "pi", "01a1-pi", null).modelContextLimit)
    }

    // ---------------------------------------------------------------- Oh My Pi (BUG-STATS-1)

    @Test
    fun ompFixtureUnderItsOwnRootGivesExpectedStats() {
        writePi(root = ".omp")
        File(home, ".omp/agent/models-store.json").writeText(
            """{"deepseek":{"models":[{"id":"deepseek-v4","contextWindow":65536}],"lastModified":"x"}}""",
        )
        val s = SessionStatsService(home).read("hq-5", "omp", "01a1-pi", "idle")

        assertEquals("omp", s.harness)
        assertEquals(t0, s.startedAt)
        assertEquals(1, s.userPrompts)
        assertEquals(2, s.assistantTurns)
        assertEquals(1, s.toolUses)
        assertEquals(listOf("deepseek-v4"), s.models)
        assertEquals(1350L, s.lastContextTokens)
        assertEquals(65536L, s.modelContextLimit)
        assertEquals(2380L, s.billedTokens)
    }

    @Test
    fun ompNeverReadsPiRecords() {
        writePi(root = ".pi")
        assertNull(SessionStatsService(home).read("hq-5", "omp", "01a1-pi", null).userPrompts)
    }

    // ---------------------------------------------------------------- OpenCode

    private fun writeOpenCode() {
        val dir = File(home, ".local/share/opencode").apply { mkdirs() }
        DriverManager.getConnection("jdbc:sqlite:${File(dir, "opencode.db").absolutePath}").use { c ->
            c.createStatement().use { st ->
                st.executeUpdate("CREATE TABLE `session` (`id` text PRIMARY KEY, `project_id` text NOT NULL, `slug` text NOT NULL, `directory` text NOT NULL, `title` text NOT NULL, `version` text NOT NULL, `cost` real DEFAULT 0 NOT NULL, `model` text, `time_created` integer NOT NULL, `time_updated` integer NOT NULL)")
                st.executeUpdate("CREATE TABLE `message` (`id` text PRIMARY KEY, `session_id` text NOT NULL, `time_created` integer NOT NULL, `time_updated` integer NOT NULL, `data` text NOT NULL)")
                st.executeUpdate("CREATE TABLE `part` (`id` text PRIMARY KEY, `message_id` text NOT NULL, `session_id` text NOT NULL, `time_created` integer NOT NULL, `time_updated` integer NOT NULL, `data` text NOT NULL)")
                st.executeUpdate("INSERT INTO session VALUES ('ses_1','p','s','C:/proj','t','1',0,NULL,1000000,1009000)")
                st.executeUpdate("INSERT INTO session VALUES ('ses_other','p','s','C:/proj','t','1',0,NULL,1,2)")
            }
            fun msg(id: String, t: Long, data: String) = c.prepareStatement("INSERT INTO message VALUES (?,?,?,?,?)").use {
                it.setString(1, id); it.setString(2, "ses_1"); it.setLong(3, t); it.setLong(4, t); it.setString(5, data); it.executeUpdate()
            }
            fun part(id: String, mid: String, t: Long, data: String, ses: String = "ses_1") = c.prepareStatement("INSERT INTO part VALUES (?,?,?,?,?,?)").use {
                it.setString(1, id); it.setString(2, mid); it.setString(3, ses); it.setLong(4, t); it.setLong(5, t); it.setString(6, data); it.executeUpdate()
            }
            fun tokens(i: Int, o: Int, r: Int, w: Int) = """{"input":$i,"output":$o,"reasoning":0,"cache":{"read":$r,"write":$w},"total":${i + o + r + w}}"""
            msg("m1", 1_000_500, """{"role":"user","time":{"created":1000500},"agent":"build","model":{"providerID":"zai","modelID":"glm-5"}}""")
            msg("m2", 1_001_000, """{"role":"assistant","time":{"created":1001000,"completed":1002000},"parentID":"m1","modelID":"glm-5","providerID":"zai","mode":"build","agent":"build","cost":0,"tokens":${tokens(150, 30, 1000, 10)},"finish":"stop"}""")
            msg("m3", 1_004_000, """{"role":"user","time":{"created":1004000},"agent":"build","model":{"providerID":"zai","modelID":"glm-5"}}""")
            msg("m4", 1_004_500, """{"role":"assistant","time":{"created":1004500,"completed":1005000},"parentID":"m3","modelID":"glm-5","providerID":"zai","mode":"build","agent":"build","cost":0,"tokens":${tokens(10, 5, 700, 0)},"finish":"stop"}""")
            part("p1", "m2", 1_001_000, """{"type":"step-start"}""")
            part("p2", "m2", 1_001_100, """{"type":"tool","callID":"call_1","tool":"bash","state":{"status":"completed","time":{"start":1001100,"end":1001150}}}""")
            part("p3", "m2", 1_001_200, """{"type":"step-finish","reason":"tool-calls","cost":0,"tokens":${tokens(100, 10, 400, 0)}}""")
            part("p4", "m2", 1_001_300, """{"type":"step-start"}""")
            part("p5", "m2", 1_001_400, """{"type":"text","text":"x","time":{"start":1,"end":2}}""")
            part("p6", "m2", 1_002_000, """{"type":"step-finish","reason":"stop","cost":0,"tokens":${tokens(50, 20, 600, 10)}}""")
            part("p7", "m4", 1_005_000, """{"type":"step-finish","reason":"stop","cost":0,"tokens":${tokens(10, 5, 700, 0)}}""")
            part("px", "mx", 1_001_150, """{"type":"tool","callID":"call_x","tool":"other","state":{"status":"completed"}}""", ses = "ses_other")
        }
    }

    @Test
    fun openCodeFixtureGivesExpectedStats() {
        writeOpenCode()
        File(home, ".cache/opencode").mkdirs()
        File(home, ".cache/opencode/models.json").writeText(
            """{"zai":{"id":"zai","models":{"glm-5":{"id":"glm-5","limit":{"context":200000,"output":8192}}}}}""",
        )
        val s = SessionStatsService(home).read("hq-3", "opencode", "ses_1", "idle")

        assertEquals(1_000_000L, s.startedAt)
        assertEquals(9_000L, s.durationMs)
        assertEquals(2, s.userPrompts)
        assertEquals(2, s.assistantTurns)
        assertEquals(1, s.toolUses, "another session's tool part never leaks in")
        assertEquals(listOf("glm-5"), s.models)
        assertEquals(710L, s.lastContextTokens)
        assertEquals(200_000L, s.modelContextLimit)
        assertEquals(1905L, s.billedTokens)
        assertEquals(
            listOf(StatsPoint(1_001_200, 500, 510), StatsPoint(1_002_000, 660, 1190), StatsPoint(1_005_000, 710, 1905)),
            s.series,
        )
        assertEquals(listOf(ContextSource("bash", 1, 160)), s.topSources)
    }

    @Test
    fun openCodeMissingDatabaseGivesNullStatsAndCreatesNothing() {
        val s = SessionStatsService(home).read("hq-3", "opencode", "ses_1", "idle")
        assertNull(s.assistantTurns)
        assertNull(s.series)
        assertEquals("idle", s.status)
        assertTrue(!File(home, ".local/share/opencode/opencode.db").exists(), "a read-only open must never create the db")
    }

    @Test
    fun unknownHarnessGivesNullStats() {
        val s = SessionStatsService(home).read("hq-4", "gemini", "g-1", "idle")
        assertEquals("gemini", s.harness)
        assertNull(s.userPrompts)
        assertNull(s.models)
        assertNull(s.modelContextLimit)
    }
}
