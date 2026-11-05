// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.claude.harness

import com.superiorsoftllc.infantry.harness.api.ApprovalReply
import com.superiorsoftllc.infantry.harness.api.HarnessEvent
import com.superiorsoftllc.infantry.harness.api.HarnessRequest
import com.superiorsoftllc.infantry.harness.api.MCP_PID_FILE_ENV
import com.superiorsoftllc.infantry.harness.api.McpState
import com.superiorsoftllc.infantry.harness.api.SessionOutput
import com.superiorsoftllc.infantry.harness.api.SessionTurnEnd
import com.superiorsoftllc.infantry.harness.api.ToolApproval
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** CLI-PTY-1: the interactive claude's transcript reader, input queue and turn end, driven by hand (no terminal, no poller thread). */
class ClaudeTtySessionTest {
    private val root: File = Files.createTempDirectory("claude-tty-test").toFile()
    private val config = File(root, "config")
    private val state = File(root, "state")
    private val cwd = File(root, "work tree").apply { mkdirs() }
    private var now = 1_000L

    @AfterTest fun cleanup() { root.deleteRecursively() }

    private class Recorder : SessionOutput {
        val written = mutableListOf<String>()
        val lines = mutableListOf<String>()
        val ends = mutableListOf<Triple<SessionTurnEnd, Int?, String?>>()
        val sessions = mutableListOf<String>()
        val mcp = mutableListOf<McpState>()
        override fun mcpState(state: McpState, reason: String?) { mcp += state }
        override fun write(text: String): Boolean { written += text; return true }
        override fun closeInput() {}
        override fun detail(text: String) {}
        override fun providerSession(id: String) { sessions += id }
        override fun output(line: String) { lines += line }
        override fun turnEnded(end: SessionTurnEnd, exitCode: Int?, reason: String?) { ends += Triple(end, exitCode, reason) }
        val starts = mutableListOf<String?>()
        override fun turnStarted(reason: String?) { starts += reason }
        var approvalReply = ApprovalReply.DENY
        val asked = mutableListOf<Pair<String, String>>()
        override fun requestApproval(tool: String, input: String): ApprovalReply { asked += tool to input; return approvalReply }
    }

    private val session = ClaudeTtySession(
        executable = { "claude.exe" }, mcp = { null to emptyMap() }, stateDir = { state }, configDir = { config },
        newSessionId = { "11111111-2222-3333-4444-555555555555" }, clock = { now }, poll = false,
    )

    private fun request(prompt: String, resume: String? = null, approval: ToolApproval = ToolApproval.ALLOW_ALL) =
        HarnessRequest(prompt = prompt, harnessId = "claude", project = cwd.absolutePath, model = "haiku", sessionId = resume, toolApproval = approval)

    private class Started(val out: Recorder, val c: ClaudeTtySession.TtyConnection, val launch: ClaudeTtySession.Launch, val argv: List<String>)

    private fun start(resume: String? = null, approval: ToolApproval = ToolApproval.ALLOW_ALL): Started {
        val launched = session.launch(request("", resume, approval), emptyMap())
        assertTrue(session.terminal(launched), "an interactive launch runs in a terminal")
        val id = resume ?: "11111111-2222-3333-4444-555555555555"
        val launch = ClaudeTtySession.Launch(id, cwd.absolutePath, File(File(state, ClaudeTtySession.TTY_DIR), "$id.events"), File(File(state, ClaudeTtySession.TTY_DIR), "$id.settings.json"))
        val out = Recorder()
        val c = session.connect(out, launched) as ClaudeTtySession.TtyConnection
        return Started(out, c, launch, launched.command)
    }

    private val transcript get() = File(File(File(config, "projects"), ClaudeTranscripts.slug(cwd.absolutePath)), "11111111-2222-3333-4444-555555555555.jsonl")

    private fun record(line: String) { transcript.parentFile.mkdirs(); transcript.appendText(line + "\n") }

    private fun event(s: Started, e: String) = s.launch.events.appendText("$e\r\n")

    /** The CLI came up: SessionStart ran, and the ready delay passed. */
    private fun up(s: Started) {
        s.c.onStdout("\u001b[2J welcome")
        event(s, "start")
        s.c.tick()
        now += ClaudeTtySession.READY_AFTER_START_MS
    }

    private fun assistant(id: String, text: String, output: Long) =
        """{"type":"assistant","uuid":"u-$id-$output","sessionId":"x","entrypoint":"cli","message":{"id":"$id","role":"assistant","model":"claude-haiku-5-5","content":[{"type":"text","text":"$text"}],"usage":{"input_tokens":3,"output_tokens":$output,"cache_read_input_tokens":100,"cache_creation_input_tokens":50}}}"""

    private val toolUse = """{"type":"assistant","uuid":"u-t","message":{"id":"m0","role":"assistant","model":"claude-haiku-5-5","content":[{"type":"tool_use","id":"toolu_1","name":"Read","input":{"file_path":"a.txt"}}],"usage":{"input_tokens":2,"output_tokens":7,"cache_read_input_tokens":10,"cache_creation_input_tokens":0}}}"""
    private val toolResult = """{"type":"user","uuid":"u-r","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_1","content":"hello"}]}}"""
    private val prompt = """{"type":"user","uuid":"u-p","entrypoint":"cli","message":{"role":"user","content":"read a.txt"}}"""

    // ---- argv and settings ------------------------------------------------------------------------------------------

    @Test fun `the launch is the interactive cli with a known session id and one settings file`() {
        val s = start()
        assertEquals("claude.exe", s.argv.first())
        assertFalse("-p" in s.argv || "--print" in s.argv || "--output-format" in s.argv || "--input-format" in s.argv, "no headless flag: ${s.argv}")
        assertEquals("11111111-2222-3333-4444-555555555555", s.argv[s.argv.indexOf("--session-id") + 1])
        assertEquals("bypassPermissions", s.argv[s.argv.indexOf("--permission-mode") + 1])
        assertEquals(1, s.argv.count { it == "--settings" })
        assertTrue("--strict-mcp-config" in s.argv, "a project .mcp.json must not open its approval dialog: ${s.argv}")
        val settings = Json.parseToJsonElement(s.launch.settings.readText()).jsonObject
        assertEquals("true", settings["skipDangerousModePermissionPrompt"]!!.jsonPrimitive.content)
        val hooks = settings["hooks"]!!.jsonObject
        assertTrue(listOf("SessionStart", "UserPromptSubmit", "Stop").all { it in hooks })
        val stop = hooks["Stop"]!!.jsonArray.last().jsonObject["hooks"]!!.jsonArray.first().jsonObject["command"]!!.jsonPrimitive.content
        assertEquals("echo stop>>\"${s.launch.events.absolutePath.replace('\\', '/')}\"", stop)
    }

    @Test fun `a resumed session resumes and asks no bypass switch without bypass mode`() {
        val launched = session.launch(request("", resume = "abc", approval = ToolApproval.ALLOW_EDITS), emptyMap())
        assertEquals("abc", launched.command[launched.command.indexOf("--resume") + 1])
        assertFalse("--session-id" in launched.command)
        val settings = File(File(state, ClaudeTtySession.TTY_DIR), "abc.settings.json").readText()
        assertFalse("skipDangerousModePermissionPrompt" in settings)
    }

    @Test fun `the watch hooks are merged into the one settings file`() {
        val watch = """{"hooks":{"PreToolUse":[{"matcher":"Bash|PowerShell","hooks":[{"type":"http","url":"http://x/watch/rewrite/claude"}]}]}}"""
        val merged = Json.parseToJsonElement(ClaudeCommand.ttySettings("C:\\s\\e.events", watch, bypass = false)).jsonObject["hooks"]!!.jsonObject
        assertTrue("PreToolUse" in merged && "Stop" in merged)
    }

    @Test fun `interactive launch requests summarized thinking by flag and by settings`() {
        val cmd = ClaudeCommand.interactiveCommand("claude", null, null, emptyList(), true, "s1", null)
        assertEquals("summarized", cmd[cmd.indexOf("--thinking-display") + 1])
        val root = Json.parseToJsonElement(ClaudeCommand.ttySettings("e", null, bypass = false)).jsonObject
        assertEquals("true", root["showThinkingSummaries"].toString())
    }

    @Test fun `the mode defaults to interactive and only an explicit headless picks headless`() {
        for (unset in listOf(null, "", "  ", "bogus", "interactive", " Interactive ")) assertTrue(ClaudeCommand.isInteractive(unset), "'$unset' -> interactive")
        for (h in listOf("headless", " HEADLESS ")) assertFalse(ClaudeCommand.isInteractive(h), "'$h' -> headless")
    }

    @Test fun `the mode setting picks the session and is part of the fingerprint`() {
        var mode: String? = "headless"
        val headless = ClaudeSession({ "claude.exe" }, { null to emptyMap() })
        val modal = ClaudeModeSession(headless, session, { mode })
        val r = request("x")
        val a = modal.launch(r, emptyMap())
        assertTrue("-p" in a.command)
        assertFalse(modal.terminal(a))
        val headlessPrint = modal.fingerprint(r, emptyMap())
        mode = null
        assertTrue(modal.fingerprint(r, emptyMap()) != headlessPrint, "switching mode restarts the process")
        val b = modal.launch(r, emptyMap())
        assertFalse("-p" in b.command)
        assertTrue(modal.terminal(b))
    }

    // ---- transcript -> stream-json ----------------------------------------------------------------------------------

    @Test fun `transcript records become the stream-json records the translator reads`() {
        val m = TranscriptMapper("sid")
        assertEquals(listOf(TranscriptSignal.PromptRecorded), m.map(prompt))
        val assistantLine = (m.map(toolUse).single() as TranscriptSignal.Line).json
        val resultLine = (m.map(toolResult).single() as TranscriptSignal.Line).json
        assertTrue(m.map("""{"type":"file-history-snapshot"}""").isEmpty())
        assertTrue(m.map("""{"type":"assistant","isSidechain":true,"message":{"id":"z","content":[]}}""").isEmpty(), "a subagent's record is not the session's")
        assertTrue(m.map("not json").isEmpty())
        val t = ClaudeStreamTranslator()
        val events = t.translate(m.initLine()) + t.translate(assistantLine) + t.translate(resultLine)
        assertTrue(events.any { it is HarnessEvent.SessionStarted && it.sessionId == "sid" }, "$events")
        assertTrue(events.any { it is HarnessEvent.ToolCall && it.name == "Read" && it.callId == "toolu_1" }, "$events")
        assertTrue(events.any { it is HarnessEvent.ToolResult && it.callId == "toolu_1" }, "$events")
    }

    @Test fun `the result sums the last usage of each message and resets the turn`() {
        val m = TranscriptMapper("sid")
        m.map(toolUse)
        m.map(assistant("m1", "partial", 5))
        m.map(assistant("m1", "final answer", 9)) // same message, later block: its usage replaces the earlier one
        val result = Json.parseToJsonElement(m.resultLine()).jsonObject
        assertEquals("success", result["subtype"]!!.jsonPrimitive.content)
        assertEquals("final answer", result["result"]!!.jsonPrimitive.content)
        val usage = result["usage"] as JsonObject
        assertEquals(16L, usage["output_tokens"]!!.jsonPrimitive.content.toLong())
        assertEquals(110L, usage["cache_read_input_tokens"]!!.jsonPrimitive.content.toLong())
        assertEquals(5L, usage["input_tokens"]!!.jsonPrimitive.content.toLong())
        assertNull(Json.parseToJsonElement(m.resultLine()).jsonObject["usage"], "the next turn starts from zero")
        val usageEvent = ClaudeStreamTranslator().translate(Json.parseToJsonElement(TranscriptMapper("s").also { it.map(toolUse) }.resultLine()).toString())
        assertTrue(usageEvent.any { it is HarnessEvent.Usage && it.outputTokens == 7L }, "$usageEvent")
    }

    @Test fun `interrupts, synthetic messages and api errors are signals, not replies`() {
        val m = TranscriptMapper("sid")
        assertEquals(listOf(TranscriptSignal.Interrupted), m.map("""{"type":"user","message":{"role":"user","content":[{"type":"text","text":"[Request interrupted by user]"}]}}"""))
        assertTrue(m.map("""{"type":"assistant","message":{"id":"s","model":"<synthetic>","content":[{"type":"text","text":"No response requested."}]}}""").isEmpty())
        val err = m.map("""{"type":"assistant","isApiErrorMessage":true,"message":{"id":"e","model":"<synthetic>","content":[{"type":"text","text":"API Error: 529 overloaded"}]}}""")
        assertEquals(listOf(TranscriptSignal.ApiError("API Error: 529 overloaded")), err)
        assertTrue(m.map("""{"type":"user","isMeta":true,"message":{"role":"user","content":"<command-name>/model</command-name>"}}""").isEmpty())
    }

    @Test fun `appended lines wait for their newline`() {
        val f = File(root, "t.jsonl")
        val r = AppendedLines { f }
        assertTrue(r.read().isEmpty(), "no file yet")
        f.writeText("{\"a\":1}\n{\"b\":")
        assertEquals(listOf("{\"a\":1}"), r.read())
        f.appendText("2}\r\n")
        assertEquals(listOf("{\"b\":2}"), r.read())
        assertTrue(r.read().isEmpty())
    }

    @Test fun `the transcript is found under the cwd slug or by its id`() {
        assertEquals("C--Users-a-x-y-z", ClaudeTranscripts.slug("C:\\Users\\a\\x y.z"))
        record(prompt)
        assertEquals(transcript.canonicalFile, ClaudeTranscripts.find(config, cwd.absolutePath, "11111111-2222-3333-4444-555555555555")!!.canonicalFile)
        assertEquals(transcript.canonicalFile, ClaudeTranscripts.find(config, "elsewhere", "11111111-2222-3333-4444-555555555555")!!.canonicalFile)
        assertNull(ClaudeTranscripts.find(config, cwd.absolutePath, "nope"))
    }

    // ---- input queue and turn end -----------------------------------------------------------------------------------

    @Test fun `a turn waits for the cli, is pasted, entered, and ends on the Stop hook with a result`() {
        val s = start()
        s.c.sendTurn(request("read a.txt"))
        s.c.tick()
        assertTrue(s.out.written.isEmpty(), "nothing is typed before the CLI is up")
        assertEquals(listOf("11111111-2222-3333-4444-555555555555"), s.out.sessions)
        up(s)
        s.c.tick()
        assertEquals(listOf("\u001b[200~read a.txt\u001b[201~"), s.out.written, "bracketed paste")
        now += ClaudeTtySession.PASTE_SETTLE_MS
        s.c.tick()
        assertEquals("\r", s.out.written.last(), "then Enter")
        event(s, "submit")
        record(prompt); record(toolUse); record(toolResult)
        s.c.tick()
        assertTrue(s.out.ends.isEmpty(), "still running")
        record(assistant("m1", "done", 4))
        event(s, "stop")
        s.c.tick()
        assertTrue(s.out.ends.isEmpty(), "the stop settles first")
        now += ClaudeTtySession.STOP_SETTLE_MS
        s.c.tick()
        assertEquals(listOf(Triple<SessionTurnEnd, Int?, String?>(SessionTurnEnd.COMPLETED, 0, null)), s.out.ends)
        val types = s.out.lines.map { Json.parseToJsonElement(it).jsonObject["type"]!!.jsonPrimitive.content }
        assertEquals(listOf("system", "assistant", "user", "assistant", "result"), types)
        assertEquals(2, s.out.written.size, "no Enter retry once the prompt was taken")
    }

    @Test fun `a turn sent mid-turn waits for the end of the running one`() {
        val s = start()
        up(s)
        s.c.sendTurn(request("one"))
        s.c.sendTurn(request("two"))
        s.c.tick()
        now += ClaudeTtySession.PASTE_SETTLE_MS
        s.c.tick()
        event(s, "submit")
        s.c.tick()
        assertEquals(1, s.out.written.count { it.startsWith(ClaudeTtySession.PASTE_START) }, "the second waits")
        event(s, "stop")
        s.c.tick()
        now += ClaudeTtySession.STOP_SETTLE_MS
        s.c.tick()
        assertEquals(1, s.out.ends.size)
        s.c.tick()
        assertEquals("\u001b[200~two\u001b[201~", s.out.written.last())
    }

    @Test fun `an Enter the paste swallowed is pressed once more`() {
        val s = start()
        up(s)
        s.c.sendTurn(request("x"))
        s.c.tick()
        now += ClaudeTtySession.PASTE_SETTLE_MS
        s.c.tick()
        now += ClaudeTtySession.SUBMIT_RETRY_MS
        s.c.tick()
        now += ClaudeTtySession.SUBMIT_RETRY_MS
        s.c.tick()
        assertEquals(listOf("\r", "\r"), s.out.written.drop(1))
    }

    @Test fun `abort is Esc into the terminal and ends INTERRUPTED once the cli records it`() {
        val s = start()
        up(s)
        s.c.sendTurn(request("long job"))
        s.c.tick(); now += ClaudeTtySession.PASTE_SETTLE_MS; s.c.tick()
        event(s, "submit"); s.c.tick()
        assertTrue(s.c.abortTurn())
        assertEquals(ClaudeTtySession.ESC, s.out.written.last())
        s.c.tick()
        assertTrue(s.out.ends.isEmpty())
        record("""{"type":"user","message":{"role":"user","content":[{"type":"text","text":"[Request interrupted by user]"}]}}""")
        s.c.tick()
        assertEquals(listOf(Triple<SessionTurnEnd, Int?, String?>(SessionTurnEnd.INTERRUPTED, null, null)), s.out.ends)
    }

    @Test fun `abort ends INTERRUPTED after the grace even when nothing is recorded`() {
        val s = start()
        up(s)
        s.c.sendTurn(request("x"))
        s.c.tick()
        s.c.abortTurn()
        now += ClaudeTtySession.ABORT_GRACE_MS
        s.c.tick()
        assertEquals(SessionTurnEnd.INTERRUPTED, s.out.ends.single().first)
    }

    @Test fun `an api error the cli records fails the turn with its words`() {
        val s = start()
        up(s)
        s.c.sendTurn(request("x"))
        s.c.tick(); now += ClaudeTtySession.PASTE_SETTLE_MS; s.c.tick()
        record("""{"type":"assistant","isApiErrorMessage":true,"message":{"id":"e","model":"<synthetic>","content":[{"type":"text","text":"API Error: 401"}]}}""")
        s.c.tick()
        assertEquals(Triple(SessionTurnEnd.FAILED, null, "API Error: 401"), s.out.ends.single())
    }

    @Test fun `the folder trust question is answered Down then Enter before any paste`() {
        val s = start()
        s.c.sendTurn(request("x"))
        s.c.onStdout("\u001b[1mQuick safety check\u001b[0m\r\n\u276f No, exit\r\n  Yes, I\u001b[1Ctrust\u001b[1Cthis folder\r\n")
        s.c.tick()
        assertEquals(listOf(ClaudeTtySession.DOWN), s.out.written)
        event(s, "start")
        now += ClaudeTtySession.KEY_GAP_MS
        s.c.tick()
        assertEquals(listOf(ClaudeTtySession.DOWN), s.out.written, "no Enter until the screen shows the accept option selected")
        s.c.onStdout("\u001b[2A  No, exit\r\n\u001b[38;2;177;185;249m❯\u001b[5GYes,\u001b[1CI trust this folder\r\n")
        s.c.tick()
        assertEquals(listOf(ClaudeTtySession.DOWN, ClaudeTtySession.ENTER), s.out.written)
        now += ClaudeTtySession.READY_AFTER_START_MS
        s.c.tick()
        assertTrue(s.out.written.last().startsWith(ClaudeTtySession.PASTE_START))
        assertEquals(1, s.out.written.count { it == ClaudeTtySession.DOWN }, "answered once")
    }

    @Test fun `a Down the trust menu did not take is sent again, and Enter waits for the selection`() {
        val s = start()
        s.c.onStdout("Quick safety check\r\n❯ No, exit\r\n  Yes, I trust this folder\r\n")
        s.c.tick()
        now += ClaudeTtySession.TRUST_RETRY_MS
        s.c.tick()
        assertEquals(listOf(ClaudeTtySession.DOWN, ClaudeTtySession.DOWN), s.out.written, "no re-render: Down again, never Enter on No, exit")
        s.c.onStdout("  No, exit\r\n❯ Yes, I trust this folder\r\n")
        now += ClaudeTtySession.KEY_GAP_MS
        s.c.tick()
        assertEquals(2, s.out.written.size, "two Downs sent, one redraw seen: the other may still come, so Enter waits for the screen to settle")
        now += ClaudeTtySession.TRUST_RETRY_MS
        s.c.tick()
        assertEquals(ClaudeTtySession.ENTER, s.out.written.last())
        assertTrue(s.c.screenText().contains("trust this folder"), "the screen is kept for debugging after the answer")
    }

    // HARNESS-GAP f0b7173f: the redraws below are the bytes Claude Code 2.1.318 wrote on Windows (ConPTY) for each Down.
    private val redrawYes = "\u001b[1D\u001b[4B\r\u001b[1C\u001b[4A \u001b[4GNo, exit\r\u001b[1C\u001b[1B\u001b[38;2;177;185;249m❯\u001b[4GYes, I trust this folder\u001b[39m\r\n\n\n\u001b[1C\u001b[3A"
    private val redrawNo = "\u001b[1D\u001b[3B\r\u001b[1C\u001b[4A\u001b[38;2;177;185;249m❯\u001b[4GNo, exit\r\u001b[1C\u001b[1B\u001b[39m \u001b[4GYes, I trust this folder\r\n\n\n\u001b[1C\u001b[4A"
    private val trustMenu = "\u001b[2GQuick\u001b[8Gsafety\u001b[15Gcheck:\r\n\u001b[2G\u001b[38;2;177;185;249m❯\u001b[4GNo,\u001b[8Gexit\u001b[39m\r\n\u001b[4GYes,\u001b[9GI\u001b[11Gtrust\u001b[17Gthis\u001b[22Gfolder\r\n"

    @Test fun `a slow redraw after a retried Down never gets an Enter on No, exit`() {
        // The live failure: Down 1 was taken but redrawn late, so Down 2 went out; redraw 1 (Yes) then redraw 2 (No) arrived.
        val s = start()
        s.c.sendTurn(request("x"))
        s.c.onStdout(trustMenu)
        s.c.tick()
        now += ClaudeTtySession.TRUST_RETRY_MS
        s.c.tick()
        assertEquals(listOf(ClaudeTtySession.DOWN, ClaudeTtySession.DOWN), s.out.written)
        s.c.onStdout(redrawYes)
        repeat(3) { now += ClaudeTtySession.TICK_MS; s.c.tick() }
        s.c.onStdout(redrawNo)
        repeat(11) { now += ClaudeTtySession.TICK_MS; s.c.tick() }
        assertTrue(ClaudeTtySession.ENTER !in s.out.written, "never Enter while No, exit may be (or is) selected: " + s.out.written)
        assertEquals(3, s.out.written.count { it == ClaudeTtySession.DOWN }, "No, exit is selected and settled: Down once more")
        s.c.onStdout(redrawYes)
        now += ClaudeTtySession.TRUST_RETRY_MS
        s.c.tick()
        assertEquals(ClaudeTtySession.ENTER, s.out.written.last())
    }

    @Test fun `a slow redraw of the one Down is waited for, never pressed again`() {
        val s = start()
        s.c.onStdout(trustMenu)
        s.c.tick()
        now += ClaudeTtySession.TRUST_RETRY_MS - ClaudeTtySession.TICK_MS
        s.c.tick()
        s.c.onStdout(redrawYes)
        repeat(20) { now += ClaudeTtySession.TICK_MS; s.c.tick() }
        assertEquals(listOf(ClaudeTtySession.DOWN, ClaudeTtySession.ENTER), s.out.written)
    }

    @Test fun `Down is never pressed while the accept row is selected`() {
        val s = start()
        s.c.onStdout(trustMenu)
        s.c.tick()
        s.c.onStdout(redrawYes)
        s.c.tick()
        assertEquals(listOf(ClaudeTtySession.DOWN), s.out.written, "Enter waits KEY_GAP_MS after the Down")
        now += ClaudeTtySession.TRUST_RETRY_MS * 3
        s.c.tick()
        assertEquals(listOf(ClaudeTtySession.DOWN, ClaudeTtySession.ENTER), s.out.written)
    }

    @Test fun `a cli that never comes up fails the waiting turn with the screen`() {
        // No screen and no SessionStart hook: it stays down until the boot timeout.
        val quiet = start()
        quiet.c.sendTurn(request("y"))
        now += 61_000
        quiet.c.tick()
        assertEquals(SessionTurnEnd.FAILED, quiet.out.ends.single().first)
        assertTrue("did not come up" in quiet.out.ends.single().third!!)
    }

    @Test fun `screen text drops escapes for matching`() {
        assertEquals("abc", ScreenText.strip("\u001b[31ma\u001b]0;title\u0007b\u001b(Bc"))
        assertEquals("itrustthisfolder", ScreenText.squeeze("I trust\nthis  Folder"))
    }

    // ---- CLI-PTY-3: the MCP server's link ------------------------------------------------------------------------------

    @Test fun `the MCP server pid file is watched and each change reported once, with no action taken`() {
        val alive = mutableSetOf(4242L)
        val watched = ClaudeTtySession(
            executable = { "claude.exe" }, mcp = { listOf("infantry", "mcp") to emptyMap() }, stateDir = { state }, configDir = { config },
            newSessionId = { "11111111-2222-3333-4444-555555555555" }, clock = { now }, poll = false, pidAlive = { it in alive },
        )
        val launched = watched.launch(request(""), emptyMap())
        if (ClaudeCommand.userMcpConfigs().none(ClaudeCommand::definesInfantry)) {
            val configs = launched.command.withIndex().filter { (i, _) -> i > 0 && launched.command[i - 1] == "--mcp-config" }.map { it.value }
            assertTrue(configs.any { (if (it.trimStart().startsWith("{")) it else File(it).readText()).contains(MCP_PID_FILE_ENV) }, "the server is told its pid file: $configs")
        }
        val pidFile = File(File(state, ClaudeTtySession.TTY_DIR), "11111111-2222-3333-4444-555555555555.mcp.pid")
        val out = Recorder()
        val c = watched.connect(out, launched) as ClaudeTtySession.TtyConnection
        fun step() { now += ClaudeTtySession.MCP_CHECK_MS; c.tick() }
        c.tick()
        assertEquals(emptyList(), out.mcp, "no pid file yet: the server has not started, nothing to say")
        pidFile.writeText("4242")
        step(); step()
        assertEquals(listOf(McpState.CONNECTED), out.mcp)
        alive.clear()
        step(); step()
        assertEquals(listOf(McpState.CONNECTED, McpState.DISCONNECTED), out.mcp, "the server died: reported once")
        pidFile.writeText("5151"); alive += 5151L
        step()
        assertEquals(listOf(McpState.CONNECTED, McpState.DISCONNECTED, McpState.CONNECTED), out.mcp, "a new server came up")
        assertTrue(out.written.isEmpty(), "detection types nothing into the CLI: ${out.written}")
        assertFalse(c.reconnectMcp(), "no in-place reconnect: the host restarts and resumes")
    }

    // ---- CLI-PTY-4: a turn the CLI begins by itself ---------------------------------------------------------------------

    private val notice = """{"type":"user","uuid":"u-n","entrypoint":"cli","origin":{"kind":"task-notification","producer":"session-task"},"message":{"role":"user","content":"<task-notification>\n<task-id>b1</task-id>\n<status>completed</status>\n</task-notification>"}}"""
    private val pasted = """{"type":"user","uuid":"u-h","entrypoint":"cli","origin":{"kind":"human"},"message":{"role":"user","content":"NEW MAIL (1)"}}"""

    private fun settle(s: Started) { s.c.tick(); now += ClaudeTtySession.STOP_SETTLE_MS; s.c.tick() }

    @Test fun `a prompt the cli made itself is told apart from a typed one`() {
        val m = TranscriptMapper("sid")
        assertEquals(listOf(TranscriptSignal.OwnPrompt("task-notification")), m.map(notice))
        assertEquals(listOf(TranscriptSignal.PromptRecorded), m.map(pasted), "a pasted prompt is the human's")
    }

    @Test fun `a background task that ends while idle is a tracked turn, and nothing is pasted into it`() {
        val s = start()
        up(s)
        record(notice); record(toolUse)
        s.c.tick()
        assertEquals(listOf<String?>("a background task ended"), s.out.starts, "reported once, when seen")
        assertTrue(s.out.lines.any { "toolu_1" in it }, "its tool call is live output")
        s.c.sendTurn(request("mail"))
        s.c.tick()
        assertTrue(s.out.written.isEmpty(), "a turn sent meanwhile waits: ${s.out.written}")
        record(toolResult); record(assistant("m9", "checked the log", 3))
        event(s, "stop")
        settle(s)
        assertEquals(listOf(Triple<SessionTurnEnd, Int?, String?>(SessionTurnEnd.COMPLETED, 0, null)), s.out.ends)
        assertEquals("checked the log", Json.parseToJsonElement(s.out.lines.last()).jsonObject["result"]!!.jsonPrimitive.content)
        s.c.tick()
        assertEquals(ClaudeTtySession.PASTE_START + "mail" + ClaudeTtySession.PASTE_END, s.out.written.single(), "then the waiting turn is pasted")
        assertEquals(1, s.out.starts.size)
    }

    @Test fun `a notice the cli takes up right after a sent turn stops is the next turn, not lost`() {
        val s = start()
        up(s)
        s.c.sendTurn(request("build"))
        s.c.tick(); now += ClaudeTtySession.PASTE_SETTLE_MS; s.c.tick()
        event(s, "submit"); record(pasted); s.c.tick()
        record(assistant("m1", "build started", 2))
        event(s, "stop")
        s.c.tick()
        record(notice) // dequeued by the CLI as soon as the sent turn stopped, inside the settle window
        now += ClaudeTtySession.STOP_SETTLE_MS
        s.c.tick()
        assertEquals(1, s.out.ends.size, "the sent turn ended")
        assertEquals("build started", Json.parseToJsonElement(s.out.lines.last { "\"result\"" in it }).jsonObject["result"]!!.jsonPrimitive.content)
        s.c.tick()
        assertEquals(listOf<String?>("a background task ended"), s.out.starts)
        record(assistant("m2", "build passed", 2))
        event(s, "stop")
        settle(s)
        assertEquals(2, s.out.ends.size, "the own turn ended on its own Stop")
    }

    @Test fun `an own turn over before its prompt is read still ends`() {
        val s = start()
        up(s)
        event(s, "stop")
        record(notice); record(assistant("m3", "ok", 1))
        s.c.tick()
        assertEquals(1, s.out.starts.size)
        now += ClaudeTtySession.STOP_SETTLE_MS
        s.c.tick()
        assertEquals(SessionTurnEnd.COMPLETED, s.out.ends.single().first)
    }

    @Test fun `an own turn can be interrupted like a sent one`() {
        val s = start()
        up(s)
        record(notice); s.c.tick()
        assertTrue(s.c.abortTurn())
        assertEquals(ClaudeTtySession.ESC, s.out.written.last())
        now += ClaudeTtySession.ABORT_GRACE_MS
        s.c.tick()
        assertEquals(SessionTurnEnd.INTERRUPTED, s.out.ends.single().first)
    }

    @Test fun `a resumed long session reads only what claude adds after launch, never its history as live`() {
        val id = "11111111-2222-3333-4444-555555555555"
        val interrupted = """{"type":"user","uuid":"u-i","message":{"role":"user","content":[{"type":"text","text":"[Request interrupted by user]"}]}}"""
        repeat(2000) { record(prompt); record(toolUse); record(toolResult); record(assistant("old$it", "old reply $it", 4)) }
        record(notice); record(interrupted) // history ends on a notice and an interruption that are long over
        val s = start(resume = id)
        up(s)
        s.c.tick()
        assertTrue(s.out.lines.none { "old reply" in it || "toolu_1" in it }, "no history replayed into the feed: ${s.out.lines.size} lines")
        assertEquals(1, s.out.lines.size, "only the init line")
        assertTrue(s.out.starts.isEmpty(), "an old notice starts no turn: ${s.out.starts}")
        s.c.sendTurn(request("mail"))
        s.c.tick(); now += ClaudeTtySession.PASTE_SETTLE_MS; s.c.tick()
        event(s, "submit"); record(pasted); record(assistant("m1", "read the mail", 2))
        event(s, "stop")
        settle(s)
        assertEquals(listOf(Triple<SessionTurnEnd, Int?, String?>(SessionTurnEnd.COMPLETED, 0, null)), s.out.ends, "the old interruption does not end the new turn")
        assertEquals("read the mail", Json.parseToJsonElement(s.out.lines.last()).jsonObject["result"]!!.jsonPrimitive.content)
        assertTrue(s.out.lines.none { "old reply" in it })
    }

    // ---- G3: permission dialogs (ticket 11754e3b) ---------------------------------------------------------------------

    private fun settingsOf(s: Started) = Json.parseToJsonElement(s.launch.settings.readText()).jsonObject

    private fun permissionHook(s: Started): JsonObject? =
        settingsOf(s)["hooks"]!!.jsonObject["PermissionRequest"]?.jsonArray?.single()?.jsonObject?.get("hooks")?.jsonArray?.single()?.jsonObject

    private fun post(url: String, body: String): String {
        val http = java.net.http.HttpClient.newHttpClient()
        val req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url)).POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build()
        return http.send(req, java.net.http.HttpResponse.BodyHandlers.ofString()).body()
    }

    private fun behavior(reply: String) = Json.parseToJsonElement(reply).jsonObject["hookSpecificOutput"]!!.jsonObject["decision"]!!.jsonObject["behavior"]!!.jsonPrimitive.content

    private val bashCall = """{"hook_event_name":"PermissionRequest","tool_name":"Bash","tool_input":{"command":"echo ${'$'}HOME"}}"""

    @Test fun `deny runs dontAsk and every mode that can ask carries the permission hook, allowAll none`() {
        val deny = start(approval = ToolApproval.DENY)
        assertEquals("dontAsk", deny.argv[deny.argv.indexOf("--permission-mode") + 1])
        val hook = permissionHook(deny)!!
        assertEquals("http", hook["type"]!!.jsonPrimitive.content)
        assertTrue(hook["url"]!!.jsonPrimitive.content.startsWith("http://127.0.0.1:"), hook.toString())
        assertEquals(ClaudePermissionHook.DEFAULT_TIMEOUT_SEC, hook["timeout"]!!.jsonPrimitive.content.toInt())
        assertTrue(listOf("SessionStart", "UserPromptSubmit", "Stop").all { it in settingsOf(deny)["hooks"]!!.jsonObject }, "the turn hooks stay")
        assertNull(permissionHook(start(approval = ToolApproval.ALLOW_ALL)))
        val ask = start(approval = ToolApproval.ASK)
        assertFalse("--permission-mode" in ask.argv, "ask keeps claude's default mode: ${ask.argv}")
        assertTrue(permissionHook(ask) != null)
    }

    @Test fun `the hook denies under deny and allowEdits and never asks the owner`() {
        for (mode in listOf(ToolApproval.DENY, ToolApproval.ALLOW_EDITS)) {
            val s = start(approval = mode)
            s.out.approvalReply = ApprovalReply.ALLOW_ONCE
            val reply = post(permissionHook(s)!!["url"]!!.jsonPrimitive.content, bashCall)
            assertEquals("deny", behavior(reply), "$mode: $reply")
            assertTrue("Do not retry" in reply, reply)
            assertTrue(s.out.asked.isEmpty(), "$mode never prompts")
        }
    }

    @Test fun `under ask the hook relays the call to the owner and answers with the pick`() {
        val s = start(approval = ToolApproval.ASK)
        val url = permissionHook(s)!!["url"]!!.jsonPrimitive.content
        s.out.approvalReply = ApprovalReply.ALLOW_ONCE
        assertEquals("allow", behavior(post(url, bashCall)))
        assertEquals("Bash" to "echo \$HOME", s.out.asked.single(), "a shell call shows its command")
        s.out.approvalReply = ApprovalReply.DENY
        assertEquals("deny", behavior(post(url, bashCall)))
        assertEquals("deny", behavior(post(url.substringBeforeLast('/') + "/not-a-token", bashCall)), "an unknown token is denied, never allowed")
        assertEquals("deny", behavior(post(url, "not json")), "an unreadable call is denied")
        s.c.onExit(0)
        assertEquals("deny", behavior(post(url, bashCall)), "an exited session no longer answers: denied")
    }

    private val dialog = "\u001b[1mBash command\u001b[0m\r\n  echo ${'$'}HOME\r\nDo you want to proceed?\r\n❯ 1. Yes\r\n  2. No, and tell Claude what to do differently (esc)\r\n"

    @Test fun `a permission dialog nothing answers is answered No and ends the turn instead of hanging`() {
        val s = start(approval = ToolApproval.DENY)
        up(s)
        s.c.sendTurn(request("x"))
        s.c.tick(); now += ClaudeTtySession.PASTE_SETTLE_MS; s.c.tick()
        event(s, "submit")
        s.c.onStdout(dialog)
        s.c.tick()
        now += ClaudeTtySession.DIALOG_GRACE_MS - 1
        s.c.tick()
        assertFalse(ClaudeTtySession.ESC in s.out.written, "not before the grace")
        now += 1
        s.c.tick()
        assertEquals(ClaudeTtySession.ESC, s.out.written.last())
        now += ClaudeTtySession.ABORT_GRACE_MS
        s.c.tick()
        assertEquals(Triple<SessionTurnEnd, Int?, String?>(SessionTurnEnd.INTERRUPTED, null, ClaudeTtySession.DIALOG_DENIED_REASON), s.out.ends.single())
        assertEquals(1, s.out.written.count { it == ClaudeTtySession.ESC }, "answered once")
    }

    @Test fun `a dialog the hook answered is history and is not answered again`() {
        val s = start(approval = ToolApproval.ASK)
        up(s)
        s.c.sendTurn(request("x"))
        s.c.tick(); now += ClaudeTtySession.PASTE_SETTLE_MS; s.c.tick()
        event(s, "submit")
        s.c.onStdout(dialog)
        s.c.tick()
        s.out.approvalReply = ApprovalReply.ALLOW_ONCE
        assertEquals("allow", behavior(s.c.answerPermission(bashCall)))
        now += ClaudeTtySession.DIALOG_GRACE_MS * 2
        s.c.tick()
        assertFalse(ClaudeTtySession.ESC in s.out.written, "the owner answered: no Esc")
        assertTrue(s.out.ends.isEmpty())
    }
}
