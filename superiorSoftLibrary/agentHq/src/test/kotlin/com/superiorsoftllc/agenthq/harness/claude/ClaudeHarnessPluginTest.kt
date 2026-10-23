package com.superiorsoftllc.agenthq.harness.claude

import com.superiorsoftllc.agenthq.harness.HARNESS_CONTRACT_VERSION
import com.superiorsoftllc.agenthq.harness.HarnessCapability
import com.superiorsoftllc.agenthq.harness.HarnessEvent
import com.superiorsoftllc.agenthq.harness.HarnessRegistry
import com.superiorsoftllc.agenthq.harness.HarnessRequest
import com.superiorsoftllc.agenthq.harness.PluginFailure
import com.superiorsoftllc.agenthq.harness.PluginOutcome
import com.superiorsoftllc.agenthq.harness.ServiceLoaderPluginSource
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

// HARNESS 3/4. Everything here drives the REAL ClaudeHarnessPlugin through the REAL HarnessRegistry
// (the daemon side of the seam). Only the OS process is replaced, by a scripted/controlled one that
// emits claude's real `--output-format stream-json` line shapes; one test uses a REAL child process.
//
// What these tests do NOT show: that the interface is general. One harness cannot fail a parity claim.

// ---- claude stream-json line shapes (as emitted by `claude -p --output-format stream-json --verbose`) ----
private const val SYSTEM_INIT = """{"type":"system","subtype":"init","session_id":"s-1","model":"claude-sonnet-5-5"}"""
private fun assistantText(text: String) =
    """{"type":"assistant","message":{"content":[{"type":"text","text":"$text"}]},"session_id":"s-1"}"""
private const val ASSISTANT_THINKING_AND_TEXT =
    """{"type":"assistant","message":{"content":[{"type":"thinking","thinking":"plan it"},{"type":"text","text":"Hello there"}]},"session_id":"s-1"}"""
private const val ASSISTANT_TOOL_USE =
    """{"type":"assistant","message":{"content":[{"type":"tool_use","id":"toolu_01","name":"Bash","input":{"command":"ls -la"}}]},"session_id":"s-1"}"""
private const val USER_TOOL_RESULT =
    """{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"toolu_01","content":"file.txt"}]},"session_id":"s-1"}"""
private const val RESULT_SUCCESS =
    """{"type":"result","subtype":"success","is_error":false,"result":"Hello there","usage":{"input_tokens":12,"output_tokens":34,"cache_creation_input_tokens":100,"cache_read_input_tokens":1000},"session_id":"s-1"}"""
private const val RESULT_ERROR =
    """{"type":"result","subtype":"error_during_execution","is_error":true,"result":"API Error: 401 invalid x-api-key","session_id":"s-1"}"""

private fun req(prompt: String = "say hello", model: String? = null, sessionId: String? = null) =
    HarnessRequest(prompt = prompt, harnessId = ClaudeHarness.HARNESS_ID, project = "proj", model = model, sessionId = sessionId)

/** An OS process the test steers by hand, so "the event arrived BEFORE the process exited" is measurable. */
private class ControlledProcess(private val listener: ProcessListener) : RunningProcess {
    @Volatile private var alive = true
    @Volatile var killed = false
    private val input = StringBuilder()
    @Volatile var inputClosed = false
    val deliveredInput: String get() = input.toString()
    override val isAlive: Boolean get() = alive
    override fun writeInput(text: String) { input.append(text) }
    override fun closeInput() { inputClosed = true }
    override fun killTree() { alive = false; killed = true }
    override fun awaitExit(): Int = 0
    fun stdout(line: String) = listener.onStdout(line)
    fun stderr(line: String) = listener.onStderr(line)
    fun exit(code: Int) { alive = false; listener.onExit(code) }
}

private class ControlledRunner : ProcessRunner {
    override val id = "controlled"
    val started = CompletableFuture<ControlledProcess>()
    override fun start(spec: ProcessSpec, listener: ProcessListener): RunningProcess =
        ControlledProcess(listener).also { started.complete(it) }
}

class ClaudeHarnessPluginTest {
    /** Everything the registry reported through its failure sink. */
    private val failures = CopyOnWriteArrayList<PluginFailure>()
    private val registry = HarnessRegistry(onFailure = { failures += it })

    private fun load(plugin: ClaudeHarnessPlugin) {
        val outcome = registry.register(plugin)
        assertIs<PluginOutcome.Accepted>(outcome, "the daemon must accept the Claude plugin as it would any plugin")
    }

    private fun scripted(lines: List<String>, stderr: List<String> = emptyList(), exit: Int = 0): ScriptedRunner =
        ScriptedRunner(StubScript(stdoutLines = lines, stderrLines = stderr, exitCode = exit), delayMs = 0)

    private fun claudeOver(runner: ProcessRunner, specFor: (HarnessRequest) -> ProcessSpec = { ProcessSpec(listOf("claude-test")) }) =
        ClaudeHarnessPlugin(runner, specFor).also(::load)

    // ---- the typed event stream --------------------------------------------------------------

    @Test
    fun aPromptReturnsTheTypedEventStream() = runBlocking<Unit> {
        val runner = scripted(listOf(SYSTEM_INIT, ASSISTANT_THINKING_AND_TEXT, RESULT_SUCCESS))
        claudeOver(runner)

        val events = registry.run(req("say hello")).toList()

        assertEquals(
            listOf(
                HarnessEvent.SessionStarted("s-1"), // contract 1.1.0: the id the CLI reports now reaches the caller
                HarnessEvent.Reasoning("plan it"),
                HarnessEvent.TextDelta("Hello there"),
                HarnessEvent.Usage(inputTokens = 12 + 100 + 1000, outputTokens = 34),
                HarnessEvent.Completion(),
            ),
            events,
        )
        assertEquals("say hello", runner.processes.single().deliveredInput, "the prompt must reach the CLI on stdin")
    }

    @Test
    fun aToolCallIsAToolCallEventAndNeverText() = runBlocking<Unit> {
        claudeOver(scripted(listOf(SYSTEM_INIT, ASSISTANT_TOOL_USE, USER_TOOL_RESULT, assistantText("done"), RESULT_SUCCESS)))

        val events = registry.run(req()).toList()

        val call = events.filterIsInstance<HarnessEvent.ToolCall>().single()
        assertEquals("toolu_01", call.callId)
        assertEquals("Bash", call.name)
        assertEquals("""{"command":"ls -la"}""", call.arguments)
        val text = events.filterIsInstance<HarnessEvent.TextDelta>().map { it.text }
        assertEquals(listOf("done"), text, "only real assistant speech may appear as text; no tool_use / tool_result JSON")
        assertTrue(text.none { "tool_use" in it || "toolu_01" in it || "file.txt" in it })
    }

    @Test
    fun aRunEndsWithExactlyOneTerminalEventAndItIsLast() = runBlocking<Unit> {
        claudeOver(scripted(listOf(ASSISTANT_THINKING_AND_TEXT, ASSISTANT_TOOL_USE, RESULT_SUCCESS)))
        val events = registry.run(req()).toList()
        val terminals = events.filter { it is HarnessEvent.Completion || it is HarnessEvent.Error }
        assertEquals(1, terminals.size)
        assertIs<HarnessEvent.Completion>(events.last())
    }

    @Test
    fun linesThatAreNotJsonAreNotInventedIntoText() = runBlocking<Unit> {
        claudeOver(scripted(listOf("Warning: hook printed this", "", SYSTEM_INIT, assistantText("hi"), RESULT_SUCCESS)))
        val events = registry.run(req()).toList()
        assertEquals(listOf<Class<*>>(HarnessEvent.SessionStarted::class.java, HarnessEvent.TextDelta::class.java, HarnessEvent.Usage::class.java, HarnessEvent.Completion::class.java), events.map { it::class.java })
    }

    // ---- errors are events, not silence ------------------------------------------------------

    @Test
    fun anErrorResultSurfacesAsAnErrorEventAndNotACompletion() = runBlocking<Unit> {
        claudeOver(scripted(listOf(SYSTEM_INIT, RESULT_ERROR), exit = 1))

        val events = registry.run(req()).toList()

        val error = events.filterIsInstance<HarnessEvent.Error>().single()
        assertTrue("401 invalid x-api-key" in error.message, "the CLI's own reason must survive: ${error.message}")
        assertTrue(events.none { it is HarnessEvent.Completion }, "an errored run must not also report Completion")
        assertIs<HarnessEvent.Error>(events.last())
    }

    @Test
    fun aNonZeroExitWithNoResultIsAnErrorCarryingStderr() = runBlocking<Unit> {
        claudeOver(scripted(listOf(SYSTEM_INIT), stderr = listOf("fatal: could not reach api"), exit = 3))

        val events = registry.run(req()).toList()

        val error = events.filterIsInstance<HarnessEvent.Error>().single()
        assertTrue("3" in error.message && "could not reach api" in error.message, error.message)
        assertTrue(events.none { it is HarnessEvent.Completion })
    }

    @Test
    fun aCliThatCannotBeStartedIsAnErrorEventNotAThrow() = runBlocking<Unit> {
        claudeOver(scripted(emptyList())) { throw IllegalStateException("claude.exe not found. Set AGENTHQ_CLAUDE_EXE") }

        val events = registry.run(req()).toList() // must not throw out of the collector

        val error = events.single()
        assertIs<HarnessEvent.Error>(error)
        assertTrue("claude.exe not found" in error.message, error.message)
        assertTrue(failures.isEmpty(), "this is a run error the harness reports itself, not a plugin crash: $failures")
    }

    // ---- incremental, not batched ------------------------------------------------------------

    @Test
    fun eventsArriveWhileTheProcessIsStillRunning() = runBlocking<Unit> {
        val runner = ControlledRunner()
        claudeOver(runner)
        val received = Channel<HarnessEvent>(Channel.UNLIMITED)
        val job = launch(Dispatchers.Default) {
            registry.run(req()).collect { received.send(it) }
            received.close()
        }
        try {
            val process = runner.started.get(5, TimeUnit.SECONDS)
            process.stdout(assistantText("first"))

            // The process has NOT exited. If events were held back until completion this never arrives.
            val first = withTimeout(3_000) { received.receive() }
            assertEquals(HarnessEvent.TextDelta("first"), first)
            assertTrue(process.isAlive, "the first event must be delivered before the run completes")

            process.stdout(assistantText("second"))
            process.stdout(RESULT_SUCCESS)
            process.exit(0)
            val rest = withTimeout(3_000) { received.consumeAsFlow().toList() }
            assertEquals(HarnessEvent.TextDelta("second"), rest.first())
            assertIs<HarnessEvent.Completion>(rest.last())
        } finally {
            job.cancel()
        }
    }

    @Test
    fun cancellingTheCollectorStopsTheCliProcess() = runBlocking<Unit> {
        val runner = ControlledRunner()
        claudeOver(runner)
        val job = launch(Dispatchers.Default) { registry.run(req()).collect { } }
        val process = runner.started.get(5, TimeUnit.SECONDS)
        assertTrue(process.isAlive)
        job.cancel()
        job.join()
        assertTrue(process.killed, "an abandoned run must not leave a CLI running")
    }

    // ---- a REAL child process, over real pipes -----------------------------------------------

    @Test
    fun aRealChildProcessStreamsTypedEventsOverRealPipes() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("claude-harness-real").toFile()
        try {
            val gate = File(dir, "gate")
            val source = File(dir, "FakeClaude.java").apply { writeText(FAKE_CLAUDE_JAVA) }
            val java = File(System.getProperty("java.home"), "bin/" + if (System.getProperty("os.name").lowercase().contains("win")) "java.exe" else "java")
            claudeOver(PipedProcessRunner()) {
                ProcessSpec(listOf(java.path, source.path), env = mapOf("FAKE_CLAUDE_GATE" to gate.path))
            }

            val received = Channel<HarnessEvent>(Channel.UNLIMITED)
            val job = launch(Dispatchers.Default) {
                registry.run(req("hi there")).collect { received.send(it) }
                received.close()
            }
            try {
                // The child printed these lines and is now parked waiting for the gate file: it is alive.
                assertEquals(HarnessEvent.SessionStarted("real-1"), withTimeout(60_000) { received.receive() })
                val first = withTimeout(60_000) { received.receive() }
                assertEquals(HarnessEvent.TextDelta("echo:hi there"), first)
                assertTrue(!gate.exists(), "the first event must arrive before the child was allowed to finish")

                gate.writeText("open")
                val rest = withTimeout(60_000) { received.consumeAsFlow().toList() }
                assertEquals(listOf("Bash"), rest.filterIsInstance<HarnessEvent.ToolCall>().map { it.name })
                assertIs<HarnessEvent.Completion>(rest.last())
            } finally {
                job.cancel()
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---- contract 1.1.0: session id, tool results, and a verdict that never came ----------------

    @Test
    fun theSessionIdIsAnnouncedOnceAndOnlyFromTheInitLine() = runBlocking<Unit> {
        val otherSystem = """{"type":"system","subtype":"hook_started","session_id":"not-this-one"}"""
        val secondInit = """{"type":"system","subtype":"init","session_id":"s-2"}"""
        claudeOver(scripted(listOf(otherSystem, SYSTEM_INIT, secondInit, assistantText("hi"), RESULT_SUCCESS)))
        val ids = registry.run(req()).toList().filterIsInstance<HarnessEvent.SessionStarted>()
        assertEquals(listOf(HarnessEvent.SessionStarted("s-1")), ids)
    }

    @Test
    fun aToolResultWhoseContentIsAListOfBlocksKeepsOnlyTheText() = runBlocking<Unit> {
        val blocks = """{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"toolu_01","content":[{"type":"text","text":"line1"},{"type":"image","source":{}},{"type":"text","text":"line2"}]}]}}"""
        claudeOver(scripted(listOf(SYSTEM_INIT, ASSISTANT_TOOL_USE, blocks, RESULT_SUCCESS)))
        val result = registry.run(req()).toList().filterIsInstance<HarnessEvent.ToolResult>().single()
        assertEquals(HarnessEvent.ToolResult("toolu_01", "line1\nline2", isError = false), result)
    }

    @Test
    fun exitZeroWithNoResultLineIsAnErrorNotACompletion() = runBlocking<Unit> {
        claudeOver(scripted(listOf(SYSTEM_INIT, assistantText("partial")), exit = 0))
        val events = registry.run(req()).toList()
        assertIs<HarnessEvent.Error>(events.last())
        assertTrue(events.none { it is HarnessEvent.Completion }, "a truncated stream must not read as a success: $events")
    }

    // ---- request mapping ---------------------------------------------------------------------

    @Test
    fun effortReachesTheCliCommandLineAndABlankOneDoesNot() {
        val spec = ClaudeHarness.defaultSpec(req().copy(effort = "high"), executable = { "claude-x" })
        assertTrue(spec.command.zipWithNext().contains("--effort" to "high"), spec.command.toString())
        assertTrue(ClaudeHarness.defaultSpec(req().copy(effort = " "), executable = { "claude-x" }).command.none { it == "--effort" })
        assertTrue(ClaudeHarness.defaultSpec(req(), executable = { "claude-x" }).command.none { it == "--effort" }, "no effort, no flag")
    }

    @Test
    fun modelAndSessionResumeReachTheCliCommandLine() {
        val spec = ClaudeHarness.defaultSpec(req(model = "haiku", sessionId = "abc-123"), executable = { "claude-x" })
        assertEquals("claude-x", spec.command.first())
        assertTrue(spec.command.zipWithNext().contains("--model" to "haiku"), spec.command.toString())
        assertTrue(spec.command.zipWithNext().contains("--resume" to "abc-123"), spec.command.toString())
        assertTrue(spec.command.zipWithNext().contains("--output-format" to "stream-json"), spec.command.toString())
    }

    @Test
    fun claudeDeclaresTheCapabilitiesItActuallyImplements() {
        val harness = ClaudeHarness(ScriptedRunner(StubScript(), delayMs = 0))
        assertEquals(
            setOf(
                HarnessCapability.MODEL_SELECTION, HarnessCapability.SESSION_RESUME, HarnessCapability.TOOL_CALLS,
                HarnessCapability.REASONING, HarnessCapability.USAGE, HarnessCapability.EFFORT_SELECTION,
            ),
            harness.capabilities,
        )
    }

    // ---- it is a plugin a third party could have written -------------------------------------

    @Test
    fun claudeIsDiscoveredThroughTheStandardPluginSourceWithNoDaemonCode() {
        val report = registry.discover(ServiceLoaderPluginSource())

        val claude = report.accepted.single { it.pluginId == ClaudeHarnessPlugin.PLUGIN_ID }
        assertEquals(listOf("claude"), claude.harnessIds)
        assertTrue(report.refused.isEmpty() && report.failed.isEmpty(), "discovery report: $report")
        assertTrue(registry.resolve("claude") != null)
    }

    @Test
    fun theClaudePluginDeclaresTheContractVersionItWasBuiltAgainst() {
        assertEquals(HARNESS_CONTRACT_VERSION, ClaudeHarnessPlugin().contractVersion)
        val refusing = HarnessRegistry(supportedVersions = setOf("9.9.9"), onFailure = { })
        val outcome = refusing.register(ClaudeHarnessPlugin())
        assertIs<PluginOutcome.Refused>(outcome, "a daemon that does not implement the plugin's contract version must refuse it")
    }

    @Test
    fun aDaemonThatLoadedNoPluginHasNoClaudeToFallBackOn() {
        // No built-in list, no default: "claude" exists only because a plugin registered it.
        assertEquals(null, registry.resolve("claude"))
        assertTrue(registry.harnessIds().isEmpty())
        val e = kotlin.test.assertFailsWith<com.superiorsoftllc.agenthq.harness.UnknownHarnessException> { registry.run(req()) }
        assertEquals("claude", e.harnessId)

        // ...and once the plugin is unloaded it is gone again, not left behind by a cache or a default.
        load(ClaudeHarnessPlugin(scripted(emptyList())))
        assertTrue(registry.resolve("claude") != null)
        registry.unload(ClaudeHarnessPlugin.PLUGIN_ID)
        assertEquals(null, registry.resolve("claude"))
    }

    @Test
    fun theDaemonSideOfTheSeamNamesNoHarness() {
        val base = File("src/main/kotlin/com/superiorsoftllc/agenthq/harness")
        for (name in listOf("Harness.kt", "HarnessPlugin.kt", "HarnessRegistry.kt")) {
            val file = File(base, name)
            assertTrue(file.isFile, "expected ${file.absolutePath} (the test would otherwise pass vacuously)")
            assertTrue(!file.readText().contains("claude", ignoreCase = true), "$name must not know Claude: adding Claude may not edit the seam")
        }
    }

    private companion object {
        /** A stand-in for claude.exe, run with `java FakeClaude.java`: prints one event, parks on a gate file, then finishes. */
        val FAKE_CLAUDE_JAVA = """
            import java.io.PrintStream;
            import java.nio.file.Files;
            import java.nio.file.Path;
            import java.nio.file.Paths;

            public class FakeClaude {
                public static void main(String[] args) throws Exception {
                    String prompt = new String(System.in.readAllBytes(), "UTF-8");
                    PrintStream out = new PrintStream(System.out, true, "UTF-8");
                    out.println("{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"real-1\"}");
                    out.println("{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"echo:" + prompt + "\"}]}}");
                    out.flush();
                    Path gate = Paths.get(System.getenv("FAKE_CLAUDE_GATE"));
                    long end = System.currentTimeMillis() + 60000;
                    while (!Files.exists(gate) && System.currentTimeMillis() < end) Thread.sleep(20);
                    if (!Files.exists(gate)) { System.err.println("gate never opened"); System.exit(9); }
                    out.println("{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"id\":\"toolu_9\",\"name\":\"Bash\",\"input\":{\"command\":\"pwd\"}}]}}");
                    out.println("{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"result\":\"ok\",\"usage\":{\"input_tokens\":1,\"output_tokens\":2}}");
                    out.flush();
                    System.exit(0);
                }
            }
        """.trimIndent()
    }
}
