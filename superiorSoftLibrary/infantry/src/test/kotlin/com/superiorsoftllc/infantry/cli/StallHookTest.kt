package com.superiorsoftllc.infantry.cli

import com.superiorsoftllc.infantry.daemon.WatchHooks
import com.superiorsoftllc.infantry.harness.ToolApproval
import com.superiorsoftllc.infantry.runner.ProcessTree
import com.superiorsoftllc.infantry.runner.WatchRewrite
import com.superiorsoftllc.infantry.runner.WatchRewrite.Shell
import com.superiorsoftllc.infantry.session.BgJobRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * STALL-2 (ticket a46744ec) item 1: the per-harness hooks' ONE rule set ([WatchRewrite]), the daemon's answer per harness
 * and Tool approval ([WatchHooks.decide]), the claude `--settings` hook and the opencode plugin entry, and a hooked script
 * really running through the runner with its exit code (bash and PowerShell).
 */
class StallHookTest {
    private fun rw(cmd: String, shell: Shell = Shell.BASH, bg: Boolean = false) =
        WatchRewrite.rewrite(WatchRewrite.Request(cmd, shell, bg, "claude", launcher = null, windows = false))

    private fun script(r: WatchRewrite.Result) = WatchRewrite.decode(r.command.substringAfter("--script-b64 ").substringBefore(' '))

    @Test
    fun pathNamedWrappers_javaJar_andBareBuildTools_areRewritten_wholeCommandKept() {
        for (c in listOf("./gradlew test", ".\\gradlew.bat build", "gradlew.bat :x:test", "/repo/gradlew check", "mvn -q verify", "./mvnw package", "java -jar app.jar")) {
            val r = assertNotNull(rw(c), c)
            assertTrue(r.command.startsWith("infantry bg run --attached --hook claude"), r.command)
            assertEquals(c, script(r))
        }
        val chain = "./gradlew test --console=plain 2>&1 | tail -20 && echo \"ok\""
        assertEquals(chain, script(assertNotNull(rw(chain))), "pipes, redirections, quotes: the whole command is the script")
    }

    @Test
    fun aLeadingCd_staysOutside_soTheHarnessKeepsTheDirectoryChange() {
        val r = assertNotNull(rw("cd superiorSoftLibrary && ./gradlew test"))
        assertTrue(r.command.startsWith("cd superiorSoftLibrary && infantry bg run"), r.command)
        assertEquals("./gradlew test", script(r))
    }

    @Test
    fun backgroundedCommands_becomeDetachedWatchedRuns_withTheirAmpersandRemoved() {
        val amp = assertNotNull(rw("./gradlew test > out.log 2>&1 &"))
        assertTrue(amp.detached && amp.command.contains("bg run --notify --hook claude"), amp.command)
        assertEquals("./gradlew test > out.log 2>&1", script(amp))
        assertTrue(assertNotNull(rw("nohup sleep 900")).detached, "nohup = background, whatever the program")
        assertTrue(assertNotNull(rw("sleep 900", bg = true)).detached, "the harness's own background flag")
        assertTrue(assertNotNull(rw("Start-Process -NoNewWindow .\\gradlew.bat test", Shell.POWERSHELL)).detached)
    }

    @Test
    fun shortCommands_infantryCalls_andTheOptOut_areNeverRewritten() {
        for (c in listOf("ls -la", "git status && git diff", "echo gradlew", "cat build.gradle | grep gradle", "infantry bg run --notify -- ./gradlew test",
            "infantry unwatched --reason \"needs a tty\" -- ./gradlew --scan", "infantry run-watched -- mvn test", "infantry run --label t -- ./gradlew test")) {
            assertNull(rw(c), c)
        }
        assertNull(rw("2>&1 echo x"), "a 2>&1 is not a background &")
    }

    @Test
    fun powershell_callOperator_isNotBackground_andTheShellIsItsOwnProcess() {
        val r = assertNotNull(rw("& '.\\gradlew.bat' test", Shell.POWERSHELL))
        assertTrue(!r.detached, r.command)
        assertTrue(r.command.contains("--shell (Get-Process -Id \$PID).Path"), r.command)
        val bash = assertNotNull(rw("./gradlew test"))
        assertTrue(bash.command.contains("--shell \"\${BASH:-\${SHELL:-sh}}\""), bash.command)
        val l = WatchRewrite.rewrite(WatchRewrite.Request("./gradlew test", Shell.BASH, false, "pi", "C:\\infantry\\bin\\infantry.bat", windows = true))!!
        assertTrue(l.command.startsWith("'/c/infantry/bin/infantry.bat' bg run --attached --hook pi"), l.command)
    }

    @Test
    fun theDaemonAnswers_claudeOnlyInBypass_opencodeOnlyInAllowAll_piAlways() {
        WatchHooks.approvalExempt = setOf("pi") // what CoreHarnessContributions registers
        fun claude(mode: String, bg: Boolean = false) = WatchHooks.decide(
            "claude",
            """{"hook_event_name":"PreToolUse","tool_name":"Bash","permission_mode":"$mode","tool_input":{"command":"./gradlew test","description":"tests","run_in_background":$bg}}""",
            null, null, windows = false,
        )
        // BG-WAKE-1: daemon mode is the default (the end reaches the daemon); native mode leaves run_in_background alone.
        WatchHooks.nativeBackground = { true }
        try { assertEquals("{}", claude("bypassPermissions", bg = true).body, "native: no rewrite") } finally { WatchHooks.nativeBackground = { false } }
        val daemonBody = claude("bypassPermissions", bg = true).body
        val yes = Json.parseToJsonElement(daemonBody).jsonObject["hookSpecificOutput"]!!.jsonObject
        assertEquals("PreToolUse", yes["hookEventName"]!!.jsonPrimitive.content)
        assertNull(yes["permissionDecision"], "never a decision: the harness's own permission check still runs")
        val input = yes["updatedInput"]!!.jsonObject
        assertTrue(input["command"]!!.jsonPrimitive.content.contains("bg run --notify --hook claude"), input.toString())
        assertEquals("false", input["run_in_background"]!!.jsonPrimitive.content, "the runner detaches; the harness must not")
        assertEquals("tests", input["description"]!!.jsonPrimitive.content, "the rest of the input is kept")
        for (mode in listOf("default", "acceptEdits", "plan")) assertEquals("{}", claude(mode).body, mode)

        val body = """{"tool":"bash","command":"./gradlew test"}"""
        assertEquals("{}", WatchHooks.decide("opencode", body, ToolApproval.ASK, null).body)
        assertEquals("{}", WatchHooks.decide("opencode", body, null, null).body, "unknown session: never")
        assertTrue(WatchHooks.decide("opencode", body, ToolApproval.ALLOW_ALL, null).body.contains("--hook opencode"))
        assertTrue(WatchHooks.decide("pi", body, ToolApproval.DENY, null).body.contains("--hook pi"), "pi has no permission system")
        assertEquals("{}", WatchHooks.decide("pi", """{"tool":"read","command":"./gradlew"}""", null, null).body)
    }

    @Test
    fun anotherHooksRewriteWinning_isSeenAfterTheCall_andToldToTheAgent() {
        // Live-run finding: the owner's own `rtk hook claude` also rewrites Bash; Claude Code applied ITS rewrite for ./gradlew.
        fun pre(id: String) = WatchHooks.decide(
            "claude",
            """{"hook_event_name":"PreToolUse","tool_name":"Bash","tool_use_id":"$id","permission_mode":"bypassPermissions","tool_input":{"command":"./gradlew test"}}""",
            null, null, windows = false,
        )
        fun post(id: String, ran: String) = WatchHooks.decide(
            WatchHooks.CLAUDE_POST,
            Json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), kotlinx.serialization.json.buildJsonObject {
                put("hook_event_name", kotlinx.serialization.json.JsonPrimitive("PostToolUse")); put("tool_name", kotlinx.serialization.json.JsonPrimitive("Bash"))
                put("tool_use_id", kotlinx.serialization.json.JsonPrimitive(id))
                put("tool_input", kotlinx.serialization.json.buildJsonObject { put("command", kotlinx.serialization.json.JsonPrimitive(ran)) })
            }),
            null, null, windows = false,
        )
        val ours = assertNotNull(pre("t1").rewrite).command
        val kept = post("t1", ours)
        assertEquals("{}", kept.body, "our rewrite ran: nothing to say")
        assertNull(kept.overriddenBy)
        pre("t2")
        val lost = post("t2", "rtk gradle test")
        assertEquals("rtk gradle test", lost.overriddenBy)
        val out = Json.parseToJsonElement(lost.body).jsonObject["hookSpecificOutput"]!!.jsonObject
        assertEquals("PostToolUse", out["hookEventName"]!!.jsonPrimitive.content)
        assertTrue(out["additionalContext"]!!.jsonPrimitive.content.contains("UNWATCHED"))
        assertEquals("{}", post("t2", "rtk gradle test").body, "said once per call")
        assertEquals("{}", post("never-rewritten", "ls").body, "a call we did not rewrite is not ours to judge")
    }

    // ---- a hooked script really runs through the runner ----

    private val reqs = CopyOnWriteArrayList<BgJobRequest>()

    private fun runHooked(shell: String, script: String): Pair<Int, String> {
        val b64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(script.toByteArray())
        val p = BgCli.parse(listOf("bg", "run", "--attached", "--hook", "claude", "--name", "t", "--shell", shell, "--script-b64", b64).drop(1)) as BgCli.Parsed.Run
        val out = StringBuilder()
        val code = BgCli.runJob(p, "sess-1", System::getenv, out, StringBuilder(), File(".").absoluteFile) { BgCli.Reporter({ reqs += it }, {}) }
        return code to out.toString()
    }

    private fun bash(): String? = listOf("C:\\Program Files\\Git\\usr\\bin\\bash.exe", "C:\\Program Files\\Git\\bin\\bash.exe", "/bin/bash", "/usr/bin/bash")
        .firstOrNull { File(it).isFile }

    @Test
    fun aHookedBashScript_runsWithItsQuotesPipesAndExitCode() {
        val sh = bash() ?: return // no bash on this host (Windows without Git for Windows)
        // Builtins only: the test JVM's PATH need not hold Git's /usr/bin.
        val (code, out) = runHooked(sh, "v=\"a 'quoted' & piped\"; echo \"\${v^^}\" | { read -r l; echo \"\$l\"; }; exit 7")
        assertEquals(7, code)
        assertTrue(out.contains("A 'QUOTED' & PIPED"), out)
        assertEquals(7, reqs.last { it.op == "done" }.exitCode)
        val log = File(reqs.first { it.op == "beat" }.job!!.logPath!!).readText()
        assertTrue(log.contains("via=claude-hook") && log.contains("cmd=v=\"a 'quoted'"), log)
    }

    @Test
    fun aHookedPowerShellScript_runsEncoded_withLastExitCode() {
        val ps = listOf("C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe").firstOrNull { File(it).isFile } ?: return
        val (code, out) = runHooked(ps, "Write-Output \"x & 'y' | \$(1+1)\"; cmd /c exit 5")
        assertEquals(5, code)
        assertTrue(out.contains("x & 'y' | 2"), out)
    }

    @Test
    fun scriptAndCommandAreExclusive_andABadScriptIsRefused() {
        assertTrue(BgCli.parse(listOf("run", "--script-b64", "ZWNobw", "--", "ls")) is BgCli.Parsed.Bad)
        assertTrue(BgCli.parse(listOf("run", "--script-b64", "***")) is BgCli.Parsed.Bad)
        val ok = BgCli.parse(listOf("run", "--notify", "--hook", "pi", "--shell", "bash", "--script-b64", "ZWNobyBoaQ")) as BgCli.Parsed.Run
        assertEquals("echo hi", ok.script)
        assertEquals(listOf("--notify", "--hook", "pi", "--shell", "bash", "--script-b64", "ZWNobyBoaQ"), ok.optionArgs(), "the detached wrapper gets the same script")
    }

    @Test
    fun windowsCommandLines_areRead_soTheDaemonFilterMatches() {
        if (!System.getProperty("os.name").lowercase().contains("win")) return
        val p = ProcessBuilder(StallFixture.javaCmd(), "-cp", System.getProperty("java.class.path"), "-Dmark=GradleDaemon", StallFixture::class.java.name, "sleep", "5000").start()
        try {
            Thread.sleep(500)
            assertTrue(ProcessTree.commandLine(p.toHandle()).contains("-Dmark=GradleDaemon"), ProcessTree.commandLine(p.toHandle()))
        } finally {
            p.destroyForcibly()
        }
    }
}
