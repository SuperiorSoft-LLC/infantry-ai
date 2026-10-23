package com.superiorsoftllc.agenthq.harness.pi

import com.superiorsoftllc.agenthq.harness.HarnessEvent
import com.superiorsoftllc.agenthq.harness.HarnessRegistry
import com.superiorsoftllc.agenthq.harness.HarnessRequest
import com.superiorsoftllc.agenthq.harness.PluginFailure
import com.superiorsoftllc.agenthq.harness.PluginOutcome
import com.superiorsoftllc.agenthq.runner.PipedProcessRunner
import com.superiorsoftllc.agenthq.runner.ProcessListener
import com.superiorsoftllc.agenthq.runner.ProcessRunner
import com.superiorsoftllc.agenthq.runner.ProcessSpec
import com.superiorsoftllc.agenthq.runner.RunningProcess
import com.superiorsoftllc.agenthq.runner.ScriptedRunner
import com.superiorsoftllc.agenthq.runner.StubScript
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

// HARNESS 4/4. What is specific to PI. The cross-vendor claim ("the same request yields the same typed
// sequence through Claude and Pi") is HarnessParityTest; this file covers what only Pi has: its argv, the
// way a Pi run ends (exit code 0 even when it failed), automatic retry, and real pipes.

private const val ZERO_USAGE = """{"input":0,"output":0,"cacheRead":0,"cacheWrite":0,"totalTokens":0,"cost":{"input":0,"output":0,"cacheRead":0,"cacheWrite":0,"total":0}}"""
private const val SESSION = """{"type":"session","version":3,"id":"pi-1","timestamp":"2026-10-17T05:24:47.946Z","cwd":"/w"}"""
private const val SETTLED = """{"type":"agent_settled","aborted":false}"""
private fun update(inner: String) = """{"type":"message_update","usage":$ZERO_USAGE,"assistantMessageEvent":$inner}"""
private fun delta(text: String) = update("""{"type":"text_delta","contentIndex":0,"delta":"$text"}""")
private fun assistantEnd(stop: String, input: Int = 0, output: Int = 0, error: String? = null) =
    """{"type":"message_end","message":{"role":"assistant","content":[],"usage":{"input":$input,"output":$output,"cacheRead":0,"cacheWrite":0,"totalTokens":0},"stopReason":"$stop"${error?.let { ""","errorMessage":"$it"""" } ?: ""}}}"""

private fun req(prompt: String = "say hello") = HarnessRequest(prompt = prompt, harnessId = PiHarness.HARNESS_ID, project = "proj")

private class ControlledProcess(private val listener: ProcessListener) : RunningProcess {
    @Volatile private var alive = true
    @Volatile var killed = false
    private val input = StringBuilder()
    override val isAlive: Boolean get() = alive
    override fun writeInput(text: String) { input.append(text) }
    override fun closeInput() {}
    override fun killTree() { alive = false; killed = true }
    override fun awaitExit(): Int = 0
    fun stdout(line: String) = listener.onStdout(line)
    fun exit(code: Int) { alive = false; listener.onExit(code) }
}

private class ControlledRunner : ProcessRunner {
    override val id = "controlled"
    val started = CompletableFuture<ControlledProcess>()
    override fun start(spec: ProcessSpec, listener: ProcessListener): RunningProcess = ControlledProcess(listener).also { started.complete(it) }
}

class PiHarnessPluginTest {
    private val failures = CopyOnWriteArrayList<PluginFailure>()
    private val registry = HarnessRegistry(onFailure = { failures += it })

    private fun piOver(runner: ProcessRunner, specFor: (HarnessRequest) -> ProcessSpec = { ProcessSpec(listOf("pi-test")) }) {
        assertIs<PluginOutcome.Accepted>(registry.register(PiHarnessPlugin(runner, specFor)), "the daemon must accept the Pi plugin as it would any plugin")
    }

    private fun run(lines: List<String>, exit: Int = 0, stderr: List<String> = emptyList(), request: HarnessRequest = req()): List<HarnessEvent> {
        // A fresh registry per call: several tests run more than one scripted stream, and a harness id registers once.
        val fresh = HarnessRegistry(onFailure = { failures += it })
        val runner = ScriptedRunner(StubScript(stdoutLines = lines, stderrLines = stderr, exitCode = exit), delayMs = 0)
        assertIs<PluginOutcome.Accepted>(fresh.register(PiHarnessPlugin(runner) { ProcessSpec(listOf("pi-test")) }))
        return runBlocking { fresh.run(request).toList() }
    }

    // ---- the invocation ------------------------------------------------------------------------

    @Test
    fun theCommandLineIsJsonModeWithModelAndSessionAndNothingElse() {
        val plain = PiCommand.command(listOf("node", "cli.js"), model = null, resumeSessionId = null)
        assertEquals(listOf("node", "cli.js", "--mode", "json"), plain)

        val full = PiCommand.command(listOf("pi-x"), model = "anthropic/sonnet:high", resumeSessionId = "0192-abcd")
        assertEquals(listOf("pi-x", "--mode", "json", "--model", "anthropic/sonnet:high", "--session", "0192-abcd"), full)

        assertTrue(PiCommand.command(listOf("pi"), model = " ", resumeSessionId = "").none { it == "--model" || it == "--session" }, "blank values must not become flags")
        // Print mode returns only the final text; no-session would make the SessionStarted id useless.
        assertTrue(full.none { it == "--print" || it == "-p" || it == "--no-session" }, full.toString())
    }

    @Test
    fun defaultSpecCarriesTheRequestsModelAndSession() {
        val spec = PiHarness.defaultSpec(req().copy(model = "m", sessionId = "s-9"), launcher = { listOf("pi-x") })
        assertEquals(listOf("pi-x", "--mode", "json", "--model", "m", "--session", "s-9"), spec.command)
    }

    @Test
    fun effortReachesPiAsItsThinkingLevel() {
        val spec = PiHarness.defaultSpec(req().copy(effort = "high"), launcher = { listOf("pi-x") })
        assertEquals(listOf("pi-x", "--mode", "json", "--thinking", "high"), spec.command)
        assertTrue(PiCommand.command(listOf("pi"), model = null, resumeSessionId = null, effort = " ").none { it == "--thinking" })
    }

    // BUG-PERM-3: Pi has no approval gate (every enabled tool auto-runs), so no mode adds a flag. One test per mode,
    // json and rpc alike, so a future stricter mapping has to change a test on purpose.
    private fun piArgv(mode: com.superiorsoftllc.agenthq.harness.ToolApproval, rpc: Boolean = false) =
        PiCommand.command(listOf("pi-x"), model = null, resumeSessionId = null, rpc = rpc, toolApproval = mode)

    @Test
    fun toolApprovalDenyAddsNoPiFlag() {
        assertEquals(listOf("pi-x", "--mode", "json"), piArgv(com.superiorsoftllc.agenthq.harness.ToolApproval.DENY))
        assertEquals(listOf("pi-x", "--mode", "rpc"), piArgv(com.superiorsoftllc.agenthq.harness.ToolApproval.DENY, rpc = true))
    }

    @Test
    fun toolApprovalAllowEditsAddsNoPiFlag() {
        assertEquals(listOf("pi-x", "--mode", "json"), piArgv(com.superiorsoftllc.agenthq.harness.ToolApproval.ALLOW_EDITS))
        assertEquals(listOf("pi-x", "--mode", "rpc"), piArgv(com.superiorsoftllc.agenthq.harness.ToolApproval.ALLOW_EDITS, rpc = true))
    }

    @Test
    fun toolApprovalAllowAllAddsNoPiFlagAndReachesTheDefaultSpec() {
        assertEquals(listOf("pi-x", "--mode", "json"), piArgv(com.superiorsoftllc.agenthq.harness.ToolApproval.ALLOW_ALL))
        assertEquals(listOf("pi-x", "--mode", "rpc"), piArgv(com.superiorsoftllc.agenthq.harness.ToolApproval.ALLOW_ALL, rpc = true))
        val spec = PiHarness.defaultSpec(req().copy(toolApproval = com.superiorsoftllc.agenthq.harness.ToolApproval.ALLOW_ALL), launcher = { listOf("pi-x") })
        assertEquals(listOf("pi-x", "--mode", "json"), spec.command)
    }

    @Test
    fun theLauncherIsReportedAsAnErrorEventWhenItCannotBeResolved() {
        piOver(ScriptedRunner(StubScript(), delayMs = 0)) { throw IllegalStateException("pi not found. Set AGENTHQ_PI_EXE") }
        val events = runBlocking { registry.run(req()).toList() }
        val error = events.single()
        assertIs<HarnessEvent.Error>(error)
        assertTrue("pi not found" in error.message, error.message)
        assertTrue(failures.isEmpty(), "a run error the harness reports itself is not a plugin crash: $failures")
    }

    @Test
    fun thePromptReachesPiOnStdinNotOnTheCommandLine() {
        val runner = ScriptedRunner(StubScript(stdoutLines = listOf(SESSION, assistantEnd("stop"), SETTLED)), delayMs = 0)
        piOver(runner)
        runBlocking { registry.run(req("-x @file \"quoted\" prompt")).toList() }
        assertEquals("-x @file \"quoted\" prompt", runner.processes.single().deliveredInput)
    }

    // ---- how a Pi run ends -----------------------------------------------------------------------

    @Test
    fun aRunPiReportsAsFailedBeforeExitingZeroIsAnErrorNotACompletion() {
        // Captured from a real `pi --mode json` run on pi 1.3.0 whose API call was rejected: Pi exited 0.
        val captured = javaClass.getResourceAsStream("/pi/captured-failed-run.jsonl")!!.bufferedReader().readLines()
        assertTrue(captured.any { "\"stopReason\":\"error\"" in it }, "the fixture must really contain the failure")

        val events = run(captured, exit = 0)

        assertEquals(HarnessEvent.SessionStarted("01a14851-e0c9-7584-b50e-50c5c3a15c4e"), events.first())
        val error = events.filterIsInstance<HarnessEvent.Error>().single()
        assertTrue("invalid_request_error" in error.message, "Pi's own reason must survive: ${error.message}")
        assertTrue(events.none { it is HarnessEvent.Completion }, "exit code 0 must not turn a failed Pi run into a success: $events")
        assertTrue(events.none { it is HarnessEvent.Usage }, "a failed run reports no usage, as Claude's does not")
    }

    @Test
    fun anErrorPiRecoveredFromByAutomaticRetryIsNotAnErrorOfTheRun() {
        val events = run(
            listOf(
                SESSION,
                assistantEnd("error", error = "529 overloaded"),
                """{"type":"auto_retry_start","attempt":1,"maxAttempts":3,"delayMs":2000,"errorMessage":"529 overloaded"}""",
                """{"type":"auto_retry_end","success":true,"attempt":2}""",
                delta("recovered"), assistantEnd("stop", input = 5, output = 7),
                SETTLED,
            ),
        )
        assertEquals(
            listOf(HarnessEvent.SessionStarted("pi-1"), HarnessEvent.TextDelta("recovered"), HarnessEvent.Usage(5, 7), HarnessEvent.Completion()),
            events,
        )
    }

    @Test
    fun whenRetryGivesUpPisFinalErrorIsTheReason() {
        val events = run(
            listOf(
                SESSION,
                assistantEnd("error", error = "529 overloaded"),
                """{"type":"auto_retry_end","success":false,"attempt":3,"finalError":"529 overloaded after 3 attempts"}""",
                SETTLED,
            ),
        )
        assertEquals("529 overloaded after 3 attempts", events.filterIsInstance<HarnessEvent.Error>().single().message)
        assertTrue(events.none { it is HarnessEvent.Completion })
    }

    @Test
    fun anAbortedRunIsAnError() {
        val aborted = run(listOf(SESSION, delta("par"), """{"type":"agent_settled","aborted":true}"""))
        assertIs<HarnessEvent.Error>(aborted.last())
        val abortedMessage = run(listOf(SESSION, assistantEnd("aborted"), SETTLED))
        assertIs<HarnessEvent.Error>(abortedMessage.last())
        assertTrue(abortedMessage.none { it is HarnessEvent.Completion })
    }

    @Test
    fun aResponseCutOffAtTheOutputLimitCompletesWithReasonLengthNotStop() {
        val events = run(listOf(SESSION, delta("cut"), assistantEnd("length", output = 4096), SETTLED))
        assertEquals(HarnessEvent.Completion("length"), events.last())
    }

    @Test
    fun usageIsOneEventTheSumOfEveryAssistantMessageAndAbsentWhenNoneEnded() {
        val summed = run(listOf(SESSION, assistantEnd("toolUse", input = 3, output = 4), assistantEnd("stop", input = 10, output = 20), SETTLED))
        assertEquals(listOf(HarnessEvent.Usage(13, 24)), summed.filterIsInstance<HarnessEvent.Usage>())

        val none = run(listOf(SESSION, SETTLED))
        assertTrue(none.none { it is HarnessEvent.Usage }, "no signal is never zeros: $none")
    }

    @Test
    fun cacheBucketsCountTowardTheInputSideLikeClaudesThreeBuckets() {
        val line = """{"type":"message_end","message":{"role":"assistant","content":[],"usage":{"input":1,"output":2,"cacheRead":30,"cacheWrite":400,"totalTokens":0},"stopReason":"stop"}}"""
        assertEquals(HarnessEvent.Usage(431, 2), run(listOf(SESSION, line, SETTLED)).filterIsInstance<HarnessEvent.Usage>().single())
    }

    // ---- what is NOT an event --------------------------------------------------------------------

    @Test
    fun onlyTheDeltasAndTheCompletedToolCallBecomeEvents() {
        val noise = listOf(
            """{"type":"agent_start"}""", """{"type":"turn_start"}""",
            update("""{"type":"start"}"""), update("""{"type":"text_start","contentIndex":0}"""),
            update("""{"type":"thinking_start","contentIndex":0}"""),
            update("""{"type":"toolcall_start","contentIndex":1,"id":"c1","toolName":"read"}"""),
            update("""{"type":"toolcall_delta","contentIndex":1,"delta":"{\"path\":"}"""),
            """{"type":"tool_execution_start","toolCallId":"c1","toolName":"read","args":{}}""",
            """{"type":"tool_execution_update","toolCallId":"c1","toolName":"read","args":{},"partialResult":{"content":[{"type":"text","text":"half"}]}}""",
            """{"type":"queue_update","steering":[],"followUp":[]}""", """{"type":"compaction_start","reason":"threshold"}""",
            "Warning: a plugin printed this", "", "[1,2,3]",
        )
        val events = run(listOf(SESSION) + noise + listOf(assistantEnd("stop"), SETTLED))
        assertEquals(listOf<Class<*>>(HarnessEvent.SessionStarted::class.java, HarnessEvent.Usage::class.java, HarnessEvent.Completion::class.java), events.map { it::class.java }, events.toString())
    }

    @Test
    fun aSecondSessionHeaderIsNotAnnouncedAgain() {
        val events = run(listOf(SESSION, """{"type":"session","version":3,"id":"pi-2","timestamp":"t","cwd":"/w"}""", assistantEnd("stop"), SETTLED))
        assertEquals(listOf(HarnessEvent.SessionStarted("pi-1")), events.filterIsInstance<HarnessEvent.SessionStarted>())
    }

    // ---- incremental, and over real pipes ----------------------------------------------------------

    @Test
    fun eventsArriveWhileTheProcessIsStillRunning() = runBlocking<Unit> {
        val runner = ControlledRunner()
        piOver(runner)
        val received = Channel<HarnessEvent>(Channel.UNLIMITED)
        val job = launch(Dispatchers.Default) {
            registry.run(req()).collect { received.send(it) }
            received.close()
        }
        try {
            val process = runner.started.get(5, TimeUnit.SECONDS)
            process.stdout(SESSION)
            process.stdout(delta("first"))

            assertEquals(HarnessEvent.SessionStarted("pi-1"), withTimeout(3_000) { received.receive() })
            assertEquals(HarnessEvent.TextDelta("first"), withTimeout(3_000) { received.receive() })
            assertTrue(process.isAlive, "the first events must be delivered before the run completes")

            process.stdout(delta("second")); process.stdout(assistantEnd("stop")); process.stdout(SETTLED)
            process.exit(0)
            val rest = withTimeout(3_000) { received.consumeAsFlow().toList() }
            assertEquals(HarnessEvent.TextDelta("second"), rest.first())
            assertIs<HarnessEvent.Completion>(rest.last())
        } finally {
            job.cancel()
        }
    }

    @Test
    fun cancellingTheCollectorStopsThePiProcess() = runBlocking<Unit> {
        val runner = ControlledRunner()
        piOver(runner)
        val job = launch(Dispatchers.Default) { registry.run(req()).collect { } }
        val process = runner.started.get(5, TimeUnit.SECONDS)
        assertTrue(process.isAlive)
        job.cancel()
        job.join()
        assertTrue(process.killed, "an abandoned run must not leave a CLI running")
    }

    @Test
    fun aRealChildProcessStreamsTypedEventsOverRealPipes() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("pi-harness-real").toFile()
        try {
            val gate = File(dir, "gate")
            val source = File(dir, "FakePi.java").apply { writeText(FAKE_PI_JAVA) }
            val java = File(System.getProperty("java.home"), "bin/" + if (System.getProperty("os.name").lowercase().contains("win")) "java.exe" else "java")
            piOver(PipedProcessRunner()) {
                ProcessSpec(listOf(java.path, source.path), env = mapOf("FAKE_PI_GATE" to gate.path))
            }

            val received = Channel<HarnessEvent>(Channel.UNLIMITED)
            val job = launch(Dispatchers.Default) {
                registry.run(req("hi there")).collect { received.send(it) }
                received.close()
            }
            try {
                assertEquals(HarnessEvent.SessionStarted("real-pi-1"), withTimeout(60_000) { received.receive() })
                assertEquals(HarnessEvent.TextDelta("echo:hi there"), withTimeout(60_000) { received.receive() })
                assertTrue(!gate.exists(), "the first events must arrive before the child was allowed to finish")

                gate.writeText("open")
                val rest = withTimeout(60_000) { received.consumeAsFlow().toList() }
                assertEquals(listOf("read"), rest.filterIsInstance<HarnessEvent.ToolCall>().map { it.name })
                assertEquals(HarnessEvent.ToolResult("c9", "ok", isError = false), rest.filterIsInstance<HarnessEvent.ToolResult>().single())
                assertIs<HarnessEvent.Completion>(rest.last())
            } finally {
                job.cancel()
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    private companion object {
        /** A stand-in for pi, run with `java FakePi.java`: prints the session header and a delta, parks on a gate file, then finishes. */
        val FAKE_PI_JAVA = """
            import java.io.PrintStream;
            import java.nio.file.Files;
            import java.nio.file.Path;
            import java.nio.file.Paths;

            public class FakePi {
                public static void main(String[] args) throws Exception {
                    String prompt = new String(System.in.readAllBytes(), "UTF-8");
                    PrintStream out = new PrintStream(System.out, true, "UTF-8");
                    out.println("{\"type\":\"session\",\"version\":3,\"id\":\"real-pi-1\",\"timestamp\":\"t\",\"cwd\":\"/w\"}");
                    out.println("{\"type\":\"message_update\",\"assistantMessageEvent\":{\"type\":\"text_delta\",\"contentIndex\":0,\"delta\":\"echo:" + prompt + "\"}}");
                    out.flush();
                    Path gate = Paths.get(System.getenv("FAKE_PI_GATE"));
                    long end = System.currentTimeMillis() + 60000;
                    while (!Files.exists(gate) && System.currentTimeMillis() < end) Thread.sleep(20);
                    if (!Files.exists(gate)) { System.err.println("gate never opened"); System.exit(9); }
                    out.println("{\"type\":\"message_update\",\"assistantMessageEvent\":{\"type\":\"toolcall_end\",\"contentIndex\":1,\"toolCall\":{\"type\":\"toolCall\",\"id\":\"c9\",\"name\":\"read\",\"arguments\":{\"path\":\"a\"}}}}");
                    out.println("{\"type\":\"tool_execution_end\",\"toolCallId\":\"c9\",\"toolName\":\"read\",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]},\"isError\":false}");
                    out.println("{\"type\":\"message_end\",\"message\":{\"role\":\"assistant\",\"content\":[],\"usage\":{\"input\":1,\"output\":2,\"cacheRead\":0,\"cacheWrite\":0},\"stopReason\":\"stop\"}}");
                    out.println("{\"type\":\"agent_settled\",\"aborted\":false}");
                    out.flush();
                    System.exit(0);
                }
            }
        """.trimIndent()
    }
}
