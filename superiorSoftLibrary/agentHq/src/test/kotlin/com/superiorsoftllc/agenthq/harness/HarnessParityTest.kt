package com.superiorsoftllc.agenthq.harness

import com.superiorsoftllc.agenthq.harness.claude.ClaudeHarness
import com.superiorsoftllc.agenthq.harness.claude.ClaudeHarnessPlugin
import com.superiorsoftllc.agenthq.harness.pi.PiHarness
import com.superiorsoftllc.agenthq.harness.pi.PiHarnessPlugin
import com.superiorsoftllc.agenthq.runner.ProcessListener
import com.superiorsoftllc.agenthq.runner.ProcessRunner
import com.superiorsoftllc.agenthq.runner.ProcessSpec
import com.superiorsoftllc.agenthq.runner.RunningProcess
import com.superiorsoftllc.agenthq.runner.ScriptedRunner
import com.superiorsoftllc.agenthq.runner.StubScript
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

// HARNESS 4/4 - THE TEST THAT IS THE TICKET.
//
// The SAME request (same prompt, same project, only harnessId differs) is dispatched through the REAL
// HarnessRegistry to the REAL Claude plugin and the REAL Pi plugin. Only the OS process is replaced by
// a scripted one. Each scenario is one logical conversation told twice, once in each vendor's actual
// wire schema, and the typed event sequence that comes out must be the same.
//
// Where the two vendors genuinely differ, the difference is a DECLARED, PRODUCTION-SIDE mapping
// (HarnessParity), not an allowance written into this test. If the two disagree and the difference is
// not declared, the interface is wrong: fix the interface, never this file.
//
// Provenance of the fixtures. Claude lines are the shapes `claude -p --output-format stream-json
// --verbose` emits (same as ClaudeHarnessPluginTest). Pi lines follow pi 1.3.0's docs/json.md and
// docs/message-types.md; the failed-run shape (stopReason "error" + errorMessage, exit code 0) was also
// captured from a real `pi --mode json` run on this machine. The Pi success-path lines were NOT captured
// live (no usable model credentials here), they are written from the documented schema.

// ---- claude fixtures ----------------------------------------------------------------------
private const val C_INIT = """{"type":"system","subtype":"init","session_id":"s-1","model":"claude-sonnet-5-5"}"""
private fun cText(text: String) = """{"type":"assistant","message":{"content":[{"type":"text","text":"$text"}]},"session_id":"s-1"}"""
private const val C_THINK_TEXT =
    """{"type":"assistant","message":{"content":[{"type":"thinking","thinking":"plan it"},{"type":"text","text":"Hello there"}]},"session_id":"s-1"}"""
private const val C_TOOL_USE =
    """{"type":"assistant","message":{"content":[{"type":"tool_use","id":"toolu_01","name":"Bash","input":{"command":"ls -la"}}]},"session_id":"s-1"}"""
private const val C_TOOL_RESULT =
    """{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"toolu_01","content":"file.txt"}]},"session_id":"s-1"}"""
private const val C_TOOL_RESULT_ERR =
    """{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"toolu_01","content":"no such file","is_error":true}]},"session_id":"s-1"}"""
private const val C_RESULT_OK =
    """{"type":"result","subtype":"success","is_error":false,"result":"ok","usage":{"input_tokens":12,"output_tokens":34,"cache_creation_input_tokens":100,"cache_read_input_tokens":1000},"session_id":"s-1"}"""
private const val C_RESULT_ERR =
    """{"type":"result","subtype":"error_during_execution","is_error":true,"result":"API Error: 401 invalid x-api-key","session_id":"s-1"}"""

// ---- pi fixtures (JSON mode) --------------------------------------------------------------
private const val ZERO_USAGE = """{"input":0,"output":0,"cacheRead":0,"cacheWrite":0,"totalTokens":0,"cost":{"input":0,"output":0,"cacheRead":0,"cacheWrite":0,"total":0}}"""
private fun piUsage(input: Int, output: Int, cacheRead: Int, cacheWrite: Int) =
    """{"input":$input,"output":$output,"cacheRead":$cacheRead,"cacheWrite":$cacheWrite,"totalTokens":${input + output + cacheRead + cacheWrite},"cost":{"input":0,"output":0,"cacheRead":0,"cacheWrite":0,"total":0}}"""
private const val P_SESSION = """{"type":"session","version":3,"id":"s-1","timestamp":"2026-10-17T05:24:47.946Z","cwd":"/work"}"""
private const val P_RUN_START = """{"type":"agent_start"}"""
private const val P_TURN_START = """{"type":"turn_start"}"""
private const val P_USER_START = """{"type":"message_start","message":{"role":"user","content":[{"type":"text","text":"say hello"}]}}"""
private const val P_USER_END = """{"type":"message_end","message":{"role":"user","content":[{"type":"text","text":"say hello"}]}}"""
private const val P_ASSISTANT_START = """{"type":"message_start","message":{"role":"assistant","content":[],"stopReason":"pending","usage":$ZERO_USAGE}}"""
private fun pUpdate(inner: String) = """{"type":"message_update","usage":$ZERO_USAGE,"assistantMessageEvent":$inner}"""
private fun pTextDelta(delta: String) = pUpdate("""{"type":"text_delta","contentIndex":0,"delta":"$delta"}""")
private fun pTextEnd(content: String) = pUpdate("""{"type":"text_end","contentIndex":0,"content":"$content"}""")
private fun pThinkDelta(delta: String) = pUpdate("""{"type":"thinking_delta","contentIndex":0,"delta":"$delta"}""")
private fun pThinkEnd(content: String) = pUpdate("""{"type":"thinking_end","contentIndex":0,"content":"$content"}""")
private fun pAssistantEnd(stop: String, usage: String, error: String? = null) =
    """{"type":"message_end","message":{"role":"assistant","content":[],"usage":$usage,"stopReason":"$stop"${error?.let { ""","errorMessage":"$it"""" } ?: ""}}}"""
private const val P_TURN_END = """{"type":"turn_end","message":{"role":"assistant","content":[]},"toolResults":[]}"""
private const val P_RUN_END = """{"type":"agent_end","messages":[],"willRetry":false}"""
private const val P_SETTLED = """{"type":"agent_settled","aborted":false}"""
private val P_TOOL_CALL = listOf(
    pUpdate("""{"type":"toolcall_start","contentIndex":0,"id":"toolu_01","toolName":"Bash"}"""),
    pUpdate("""{"type":"toolcall_delta","contentIndex":0,"delta":"{\"command\":"}"""),
    pUpdate("""{"type":"toolcall_delta","contentIndex":0,"delta":"\"ls -la\"}"}"""),
    pUpdate("""{"type":"toolcall_end","contentIndex":0,"toolCall":{"type":"toolCall","id":"toolu_01","name":"Bash","arguments":{"command":"ls -la"}}}"""),
)
private const val P_EXEC_START = """{"type":"tool_execution_start","toolCallId":"toolu_01","toolName":"Bash","args":{"command":"ls -la"}}"""
private fun pExecEnd(text: String, isError: Boolean) =
    """{"type":"tool_execution_end","toolCallId":"toolu_01","toolName":"Bash","result":{"content":[{"type":"text","text":"$text"}],"details":{}},"isError":$isError,"durationMs":3}"""
private fun pToolResultMessage(text: String, isError: Boolean) =
    """{"type":"message_end","message":{"role":"toolResult","toolCallId":"toolu_01","toolName":"Bash","content":[{"type":"text","text":"$text"}],"isError":$isError}}"""

/** One logical conversation told in both vendors' schemas, and the one typed sequence it must produce. */
private class Scenario(
    val name: String,
    val claude: List<String>,
    val pi: List<String>,
    val expected: List<HarnessEvent>,
    val claudeExit: Int = 0,
    val piExit: Int = 0,
    val stderr: List<String> = emptyList(),
    /** True where the harness itself (not the CLI) words the Error, so the text legitimately names the vendor. */
    val harnessAuthoredError: Boolean = false,
    val errorMustContain: List<String> = emptyList(),
)

private fun piRun(vararg body: String, tail: List<String> = listOf(P_TURN_END, P_RUN_END, P_SETTLED)): List<String> =
    listOf(P_SESSION, P_RUN_START, P_TURN_START, P_USER_START, P_USER_END, P_ASSISTANT_START) + body.toList() + tail

private val SCENARIOS: Map<String, Scenario> = listOf(
    Scenario(
        name = "plain text",
        claude = listOf(C_INIT, cText("Hello there"), C_RESULT_OK),
        // Pi streams real token deltas AND repeats the whole block in text_end: only the deltas may become text.
        pi = piRun(pTextDelta("Hello"), pTextDelta(" there"), pTextEnd("Hello there"), pAssistantEnd("stop", piUsage(12, 34, 1000, 100))),
        expected = listOf(HarnessEvent.SessionStarted("s-1"), HarnessEvent.TextDelta("Hello there"), HarnessEvent.Usage(1112, 34), HarnessEvent.Completion()),
    ),
    Scenario(
        name = "reasoning then text",
        claude = listOf(C_INIT, C_THINK_TEXT, C_RESULT_OK),
        pi = piRun(
            pThinkDelta("plan "), pThinkDelta("it"), pThinkEnd("plan it"),
            pTextDelta("Hello "), pTextDelta("there"), pTextEnd("Hello there"),
            pAssistantEnd("stop", piUsage(12, 34, 1000, 100)),
        ),
        expected = listOf(
            HarnessEvent.SessionStarted("s-1"), HarnessEvent.Reasoning("plan it"), HarnessEvent.TextDelta("Hello there"),
            HarnessEvent.Usage(1112, 34), HarnessEvent.Completion(),
        ),
    ),
    Scenario(
        name = "tool call, tool result, then text",
        claude = listOf(C_INIT, C_TOOL_USE, C_TOOL_RESULT, cText("done"), C_RESULT_OK),
        pi = listOf(P_SESSION, P_RUN_START, P_TURN_START, P_USER_START, P_USER_END, P_ASSISTANT_START) + P_TOOL_CALL +
            listOf(
                pAssistantEnd("toolUse", piUsage(6, 10, 500, 50)),
                P_EXEC_START, pExecEnd("file.txt", false), pToolResultMessage("file.txt", false), P_TURN_END,
                P_TURN_START, P_ASSISTANT_START, pTextDelta("do"), pTextDelta("ne"), pTextEnd("done"),
                pAssistantEnd("stop", piUsage(6, 24, 500, 50)),
                P_TURN_END, P_RUN_END, P_SETTLED,
            ),
        expected = listOf(
            HarnessEvent.SessionStarted("s-1"),
            HarnessEvent.ToolCall("toolu_01", "Bash", """{"command":"ls -la"}"""),
            HarnessEvent.ToolResult("toolu_01", "file.txt", isError = false),
            HarnessEvent.TextDelta("done"),
            HarnessEvent.Usage(1112, 34), // claude: one run total; pi: the SUM of its two assistant messages
            HarnessEvent.Completion(),
        ),
    ),
    Scenario(
        name = "a failing tool is a ToolResult with isError, and the run still completes",
        claude = listOf(C_INIT, C_TOOL_USE, C_TOOL_RESULT_ERR, cText("done"), C_RESULT_OK),
        pi = listOf(P_SESSION, P_RUN_START, P_TURN_START, P_USER_START, P_USER_END, P_ASSISTANT_START) + P_TOOL_CALL +
            listOf(
                pAssistantEnd("toolUse", piUsage(6, 10, 500, 50)),
                P_EXEC_START, pExecEnd("no such file", true), pToolResultMessage("no such file", true), P_TURN_END,
                P_TURN_START, P_ASSISTANT_START, pTextDelta("done"), pTextEnd("done"),
                pAssistantEnd("stop", piUsage(6, 24, 500, 50)),
                P_TURN_END, P_RUN_END, P_SETTLED,
            ),
        expected = listOf(
            HarnessEvent.SessionStarted("s-1"),
            HarnessEvent.ToolCall("toolu_01", "Bash", """{"command":"ls -la"}"""),
            HarnessEvent.ToolResult("toolu_01", "no such file", isError = true),
            HarnessEvent.TextDelta("done"),
            HarnessEvent.Usage(1112, 34),
            HarnessEvent.Completion(),
        ),
    ),
    Scenario(
        // The CLI reports the failure itself. Claude exits 1; Pi exits 0 (documented, and seen live): the verdict is in the stream.
        name = "a failure the CLI reports keeps the CLI's own reason and is an Error, not a Completion",
        claude = listOf(C_INIT, C_RESULT_ERR), claudeExit = 1,
        pi = piRun(pAssistantEnd("error", ZERO_USAGE, error = "API Error: 401 invalid x-api-key")), piExit = 0,
        expected = listOf(HarnessEvent.SessionStarted("s-1"), HarnessEvent.Error("API Error: 401 invalid x-api-key")),
    ),
    Scenario(
        // Nothing ever said the run finished. A truncated stream must not read as a success.
        name = "a stream that ends with exit 0 and no verdict is an Error",
        claude = listOf(C_INIT, cText("partial")),
        pi = listOf(P_SESSION, P_RUN_START, P_TURN_START, P_USER_START, P_USER_END, P_ASSISTANT_START, pTextDelta("partial")),
        expected = listOf(HarnessEvent.SessionStarted("s-1"), HarnessEvent.TextDelta("partial"), HarnessEvent.Error("")),
        harnessAuthoredError = true,
    ),
    Scenario(
        name = "a non-zero exit with no verdict is an Error carrying the exit code and stderr",
        claude = listOf(C_INIT), claudeExit = 3,
        pi = listOf(P_SESSION), piExit = 3,
        stderr = listOf("fatal: could not reach api"),
        expected = listOf(HarnessEvent.SessionStarted("s-1"), HarnessEvent.Error("")),
        harnessAuthoredError = true,
        errorMustContain = listOf("3", "could not reach api"),
    ),
).associateBy { it.name }

private fun scripted(lines: List<String>, stderr: List<String>, exit: Int): ScriptedRunner =
    ScriptedRunner(StubScript(stdoutLines = lines, stderrLines = stderr, exitCode = exit), delayMs = 0)

/** Remembers every ProcessSpec it was asked to start, so the request->argv mapping can be compared. */
private class RecordingRunner(private val delegate: ProcessRunner) : ProcessRunner {
    override val id: String = delegate.id
    val specs = CopyOnWriteArrayList<ProcessSpec>()
    override fun start(spec: ProcessSpec, listener: ProcessListener): RunningProcess {
        specs += spec
        return delegate.start(spec, listener)
    }
}

private fun request(harnessId: String, sessionId: String? = null, model: String? = null) =
    HarnessRequest(prompt = "say hello", harnessId = harnessId, project = "proj", model = model, sessionId = sessionId)

class HarnessParityTest {
    private val failures = CopyOnWriteArrayList<PluginFailure>()

    /** A registry holding BOTH real plugins, each over its own scripted process. */
    private fun bothHarnesses(s: Scenario): HarnessRegistry =
        HarnessRegistry(onFailure = { failures += it }).also { registry ->
            assertIs<PluginOutcome.Accepted>(
                registry.register(ClaudeHarnessPlugin(scripted(s.claude, s.stderr, s.claudeExit)) { ProcessSpec(listOf("claude-test")) }),
            )
            assertIs<PluginOutcome.Accepted>(
                registry.register(PiHarnessPlugin(scripted(s.pi, s.stderr, s.piExit)) { ProcessSpec(listOf("pi-test")) }),
            )
        }

    private fun dispatch(harnessId: String, s: Scenario): List<HarnessEvent> =
        runBlocking { bothHarnesses(s).run(request(harnessId)).toList() }

    /** Error text is compared only where the CLI relays it; where the harness words it, the vendor name is allowed to differ. */
    private fun shape(s: Scenario, events: List<HarnessEvent>): List<Any> =
        events.map { if (s.harnessAuthoredError && it is HarnessEvent.Error) "Error" else it }

    private fun parity(name: String) {
        val s = SCENARIOS.getValue(name)
        val claude = HarnessParity.canonical(dispatch("claude", s))
        val pi = HarnessParity.canonical(dispatch("pi", s))
        // NOT canonicalised: the declared sequence is written in canonical form, so a canonical() that swallowed a
        // real difference would show up here as actual != declared, instead of agreeing with itself on both sides.
        val expected = s.expected

        assertEquals(shape(s, expected), shape(s, claude), "[$name] CLAUDE diverges from the declared sequence")
        assertEquals(shape(s, expected), shape(s, pi), "[$name] PI diverges from the declared sequence")
        assertEquals(shape(s, claude), shape(s, pi), "[$name] the two harnesses disagree on the same request")
        for ((vendor, events) in listOf("claude" to claude, "pi" to pi)) {
            assertEquals(1, events.count { it is HarnessEvent.Completion || it is HarnessEvent.Error }, "[$name] $vendor: exactly one terminal event")
            assertTrue(events.last().let { it is HarnessEvent.Completion || it is HarnessEvent.Error }, "[$name] $vendor: the terminal event is last")
            for (needle in s.errorMustContain) {
                val message = events.filterIsInstance<HarnessEvent.Error>().single().message
                assertTrue(needle in message, "[$name] $vendor: error must carry '$needle', got: $message")
            }
        }
        assertTrue(failures.isEmpty(), "[$name] a run error must be an event, never a plugin crash: $failures")
    }

    // ---- the same request, the same typed sequence -------------------------------------------

    @Test fun parity_plainText() = parity("plain text")
    @Test fun parity_reasoningThenText() = parity("reasoning then text")
    @Test fun parity_toolCallToolResultThenText() = parity("tool call, tool result, then text")
    @Test fun parity_aFailingToolIsAToolResultWithIsError() = parity("a failing tool is a ToolResult with isError, and the run still completes")
    @Test fun parity_aCliReportedFailureKeepsItsReason() = parity("a failure the CLI reports keeps the CLI's own reason and is an Error, not a Completion")
    @Test fun parity_aTruncatedStreamIsAnError() = parity("a stream that ends with exit 0 and no verdict is an Error")
    @Test fun parity_aNonZeroExitIsAnErrorWithStderr() = parity("a non-zero exit with no verdict is an Error carrying the exit code and stderr")

    // ---- sessions: the id comes back as an event, and resume uses it --------------------------

    @Test
    fun theSessionIdComesBackAsAnEventAndResumingWithItReachesEachCli() {
        val s = SCENARIOS.getValue("plain text")
        val flags = mapOf("claude" to "--resume", "pi" to "--session")
        for ((harnessId, flag) in flags) {
            val claudeRunner = RecordingRunner(scripted(s.claude, emptyList(), 0))
            val piRunner = RecordingRunner(scripted(s.pi, emptyList(), 0))
            val registry = HarnessRegistry(onFailure = { failures += it })
            registry.register(ClaudeHarnessPlugin(claudeRunner) { ClaudeHarness.defaultSpec(it, executable = { "claude-test" }) })
            registry.register(PiHarnessPlugin(piRunner) { PiHarness.defaultSpec(it, launcher = { listOf("pi-test") }) })
            val recorder = if (harnessId == "claude") claudeRunner else piRunner

            val first = runBlocking { registry.run(request(harnessId)).toList() }
            val id = first.filterIsInstance<HarnessEvent.SessionStarted>().single().sessionId
            assertEquals("s-1", id, "[$harnessId] the id the CLI reported must reach the caller")
            assertTrue(recorder.specs.single().command.none { it == flag }, "[$harnessId] a fresh run must not resume: ${recorder.specs.single().command}")

            // The caller never held an id before this run. Resume with the one the run handed back.
            runBlocking { registry.run(request(harnessId, sessionId = id)).toList() }
            val resumed = recorder.specs.last().command
            assertTrue(resumed.zipWithNext().contains(flag to id), "[$harnessId] resume must pass the session id to the CLI: $resumed")
        }
    }

    @Test
    fun theModelReachesEachCli() {
        val s = SCENARIOS.getValue("plain text")
        val claudeRunner = RecordingRunner(scripted(s.claude, emptyList(), 0))
        val piRunner = RecordingRunner(scripted(s.pi, emptyList(), 0))
        val registry = HarnessRegistry(onFailure = { failures += it })
        registry.register(ClaudeHarnessPlugin(claudeRunner) { ClaudeHarness.defaultSpec(it, executable = { "claude-test" }) })
        registry.register(PiHarnessPlugin(piRunner) { PiHarness.defaultSpec(it, launcher = { listOf("pi-test") }) })
        runBlocking { registry.run(request("claude", model = "m-1")).toList() }
        runBlocking { registry.run(request("pi", model = "m-1")).toList() }
        assertTrue(claudeRunner.specs.single().command.zipWithNext().contains("--model" to "m-1"), claudeRunner.specs.toString())
        assertTrue(piRunner.specs.single().command.zipWithNext().contains("--model" to "m-1"), piRunner.specs.toString())
    }

    // ---- declared capability / divergence differences ---------------------------------------

    @Test
    fun bothHarnessesDeclareTheSameCapabilitiesSoThereIsNoCapabilityDivergenceToExplain() {
        val none = scripted(emptyList(), emptyList(), 0)
        val claude = ClaudeHarness(none).capabilities
        val pi = PiHarness(none, { ProcessSpec(listOf("pi-test")) }).capabilities
        assertEquals(HarnessCapability.entries.toSet(), claude, "claude")
        assertEquals(claude, pi, "an undeclared capability difference between the two harnesses")
    }

    @Test
    fun theOnlyDeclaredDivergenceIsStreamGranularity() {
        assertEquals(setOf(HarnessParity.Divergence.STREAM_GRANULARITY), HarnessParity.declared)
    }

    // ---- the declared mapping itself: it may merge granularity and nothing else ---------------

    @Test
    fun streamGranularityIsTheOnlyThingCanonicalFormMerges() {
        val c = HarnessParity::canonical
        assertEquals(listOf<HarnessEvent>(HarnessEvent.TextDelta("ab")), c(listOf(HarnessEvent.TextDelta("a"), HarnessEvent.TextDelta("b"))))
        assertEquals(listOf<HarnessEvent>(HarnessEvent.Reasoning("ab")), c(listOf(HarnessEvent.Reasoning("a"), HarnessEvent.Reasoning("b"))))
        val call = HarnessEvent.ToolCall("1", "x", "{}")
        val keepsApart = listOf(HarnessEvent.TextDelta("a"), call, HarnessEvent.TextDelta("b"))
        assertEquals(keepsApart, c(keepsApart), "text on either side of a tool call is two separate pieces of speech")
        val mixed = listOf(HarnessEvent.TextDelta("a"), HarnessEvent.Reasoning("b"), HarnessEvent.TextDelta("c"))
        assertEquals(mixed, c(mixed), "reasoning is never merged into the answer")
        val twoUsage = listOf(HarnessEvent.Usage(1, 1), HarnessEvent.Usage(2, 2))
        assertEquals(twoUsage, c(twoUsage))
        assertEquals(emptyList(), c(emptyList()))
    }

    @Test
    fun aRealDivergenceIsNeverSwallowedByTheCanonicalForm() {
        val c = HarnessParity::canonical
        val call = HarnessEvent.ToolCall("1", "x", "{}")
        val pairs: List<Pair<List<HarnessEvent>, List<HarnessEvent>>> = listOf(
            listOf(HarnessEvent.TextDelta("a"), HarnessEvent.Completion()) to listOf(HarnessEvent.TextDelta("a"), HarnessEvent.Error("x")),
            listOf(HarnessEvent.Completion()) to listOf(HarnessEvent.Completion("length")),
            listOf(call, HarnessEvent.TextDelta("a")) to listOf(HarnessEvent.TextDelta("a"), call),
            listOf(call, HarnessEvent.ToolResult("1", "out")) to listOf(call),
            listOf(call, HarnessEvent.ToolResult("1", "out")) to listOf(call, HarnessEvent.ToolResult("1", "out", isError = true)),
            listOf(HarnessEvent.Reasoning("a"), HarnessEvent.TextDelta("b")) to listOf(HarnessEvent.TextDelta("b")),
            listOf(HarnessEvent.TextDelta("ab")) to listOf(HarnessEvent.TextDelta("ac")),
            listOf(HarnessEvent.SessionStarted("s"), HarnessEvent.Completion()) to listOf(HarnessEvent.Completion()),
            listOf(HarnessEvent.Usage(1, 2), HarnessEvent.Completion()) to listOf(HarnessEvent.Usage(1, 3), HarnessEvent.Completion()),
        )
        for ((a, b) in pairs) assertNotEquals(c(a), c(b), "this is a real divergence and must stay visible:\n  $a\n  $b")
    }

    // ---- both harnesses are on the translation interface -----------------------------------------

    /** Turns every line into one recognisable event and ends in a recognisable terminal; knows no vendor schema. */
    private class SentinelTranslator : HarnessTranslator {
        override fun translate(line: String): List<HarnessEvent> = listOf(HarnessEvent.TextDelta("T:$line"))
        override fun terminal(exitCode: Int, stderrTail: String): HarnessEvent = HarnessEvent.Completion("sentinel")
    }

    @Test
    fun everythingEitherHarnessEmitsWasProducedByTheTranslationInterface() {
        val expected = listOf(HarnessEvent.TextDelta("T:one"), HarnessEvent.TextDelta("T:two"), HarnessEvent.Completion("sentinel"))
        val lines = listOf("one", "two")
        val spec = { _: HarnessRequest -> ProcessSpec(listOf("x")) }
        val claude = ClaudeHarness(scripted(lines, emptyList(), 0), spec, ::SentinelTranslator)
        val pi = PiHarness(scripted(lines, emptyList(), 0), spec, ::SentinelTranslator)
        assertEquals(expected, runBlocking { claude.run(request("claude")).toList() }, "claude emitted something the translator did not produce")
        assertEquals(expected, runBlocking { pi.run(request("pi")).toList() }, "pi emitted something the translator did not produce")
    }

    // ---- the seam still names no vendor, shared files included ---------------------------------

    @Test
    fun noSharedSeamFileKnowsEitherVendor() {
        val base = File("src/main/kotlin/com/superiorsoftllc/agenthq/harness")
        val vendor = Regex("""\b(claude|pi)\b""", RegexOption.IGNORE_CASE)
        for (name in listOf("Harness.kt", "HarnessPlugin.kt", "HarnessRegistry.kt", "HarnessTranslator.kt", "CliHarness.kt", "HarnessParity.kt")) {
            val file = File(base, name)
            assertTrue(file.isFile, "expected ${file.absolutePath} (the test would otherwise pass vacuously)")
            val hit = vendor.find(file.readText())
            assertTrue(hit == null, "$name names a vendor ('${hit?.value}'): adding a harness may not edit the seam")
        }
    }

    // ---- both are found by the one standard mechanism ---------------------------------------------

    @Test
    fun bothPluginsAreDiscoveredByTheStandardSourceWithNoDaemonCode() {
        val registry = HarnessRegistry(onFailure = { failures += it })
        val report = registry.discover(ServiceLoaderPluginSource())
        assertEquals(listOf("claude"), report.accepted.single { it.pluginId == ClaudeHarnessPlugin.PLUGIN_ID }.harnessIds)
        assertEquals(listOf("pi"), report.accepted.single { it.pluginId == PiHarnessPlugin.PLUGIN_ID }.harnessIds)
        assertTrue(report.refused.isEmpty() && report.failed.isEmpty(), "discovery report: $report")
    }
}
