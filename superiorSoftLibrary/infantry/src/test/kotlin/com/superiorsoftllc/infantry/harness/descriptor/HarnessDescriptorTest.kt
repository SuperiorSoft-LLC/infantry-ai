package com.superiorsoftllc.infantry.harness.descriptor

import com.superiorsoftllc.infantry.harness.CliHarness
import com.superiorsoftllc.infantry.harness.CommandAction
import com.superiorsoftllc.infantry.harness.HARNESS_CONTRACT_VERSION
import com.superiorsoftllc.infantry.harness.Harness
import com.superiorsoftllc.infantry.harness.HarnessCapability
import com.superiorsoftllc.infantry.harness.HarnessEvent
import com.superiorsoftllc.infantry.harness.HarnessPlugin
import com.superiorsoftllc.infantry.harness.HarnessRegistrar
import com.superiorsoftllc.infantry.harness.HarnessRegistry
import com.superiorsoftllc.infantry.harness.HarnessRequest
import com.superiorsoftllc.infantry.harness.MissingCapabilityException
import com.superiorsoftllc.infantry.runner.PipedProcessRunner
import com.superiorsoftllc.infantry.runner.ProcessRunner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** HARNESS-DESC-1: a harness registered from a JSON descriptor, with no code, beside the ServiceLoader ones. */
class HarnessDescriptorTest {
    private val runner: ProcessRunner = PipedProcessRunner()

    /** A code plugin registering a harness under [harnessId] - what ServiceLoader would have found. */
    private class CodeHarness(override val id: String) : Harness {
        override val capabilities: Set<HarnessCapability> = emptySet()
        override fun events(request: HarnessRequest): Flow<HarnessEvent> = emptyFlow()
    }

    private fun codePlugin(harnessId: String) = object : HarnessPlugin {
        override val pluginId = "code-$harnessId"
        override val contractVersion = HARNESS_CONTRACT_VERSION
        override fun register(registrar: HarnessRegistrar) = registrar.register(CodeHarness(harnessId))
    }

    private fun dirWith(vararg files: Pair<String, String>): File {
        val dir = EchoCliFixture.tempDir("loader")
        files.forEach { (name, text) -> File(dir, name).writeText(text) }
        return dir
    }

    private fun entry(id: String, extraFields: String = "", output: String = """"output": {"format": "text"}""") =
        """{"id": "$id", "name": "N", "command": "some-cli", "args": ["{prompt}"], "capabilities": [], $output$extraFields}"""

    private fun load(dir: File, registry: HarnessRegistry = HarnessRegistry()): Pair<HarnessRegistry, DescriptorReport> {
        val report = HarnessDescriptorLoader(dir) { runner }.loadInto(registry)
        return registry to report
    }

    private fun descriptorHarness(json: String): CliHarness {
        val (registry, report) = load(dirWith("one.json" to json))
        assertTrue(report.notes.isEmpty(), "unexpected notes: ${report.notes}")
        return registry.resolve(report.loaded.single()) as CliHarness
    }

    // --- registration ---------------------------------------------------------------------------

    @Test
    fun aDescriptorRegistersAHarnessNextToACodePlugin() {
        val registry = HarnessRegistry().also { it.register(codePlugin("claude")) }
        val dir = dirWith("echo.json" to entry("echo-cli"))

        val (_, report) = load(dir, registry)

        assertEquals(listOf("echo-cli"), report.loaded)
        assertTrue(report.notes.isEmpty(), "notes: ${report.notes}")
        assertEquals(listOf("claude", "echo-cli"), registry.harnessIds())
        val harness = assertNotNull(registry.resolve("echo-cli"))
        assertIs<CliHarness>(harness, "a descriptor harness is a CliHarness so a spawn can drive it")
        assertEquals(HARNESS_CONTRACT_VERSION, harness.contractVersion)
    }

    @Test
    fun aFileMayHoldSeveralEntriesAsAnArray() {
        val dir = dirWith("many.json" to "[${entry("one")}, ${entry("two")}]")
        val (registry, report) = load(dir)
        assertEquals(listOf("one", "two"), report.loaded)
        assertEquals(listOf("one", "two"), registry.harnessIds())
    }

    @Test
    fun aMissingDirectoryIsNotAnError() {
        val (registry, report) = load(File(EchoCliFixture.tempDir("none"), "does-not-exist"))
        assertTrue(report.loaded.isEmpty() && report.notes.isEmpty())
        assertTrue(registry.harnessIds().isEmpty())
    }

    @Test
    fun oneBadDescriptorNeverHidesTheOthersAndNotesCarryNoFileContent() {
        val secret = "SECRET-TOKEN-8f3a"
        val dir = dirWith(
            "a-broken.json" to """{"id": "x", "command": "$secret" """, // not JSON
            "b-shape.json" to """{"id": "shape", "name": "N", "capabilities": ["$secret"], "output": {"format": "text"}}""", // no command
            "c-good.json" to entry("good"),
            "d-notjson.txt" to secret, // not a .json file: ignored outright
        )

        val (registry, report) = load(dir)

        assertEquals(listOf("good"), report.loaded)
        assertEquals(listOf("good"), registry.harnessIds())
        assertEquals(
            listOf(DescriptorNoteReason.INVALID_JSON, DescriptorNoteReason.INVALID_DESCRIPTOR),
            report.notes.map { it.reason },
        )
        assertEquals(listOf("a-broken.json", "b-shape.json"), report.notes.map { it.source })
        // CP-1 leak rule: a note is a file name + a fixed reason, never anything read from the file.
        assertFalse(report.notes.toString().contains(secret), "notes leaked file content: ${report.notes}")
        assertFalse(report.notes.any { it.detail.contains(secret) })
    }

    @Test
    fun aCodePluginWinsAnIdClashAndTheDescriptorIsNoted() {
        val registry = HarnessRegistry().also { it.register(codePlugin("claude")) }
        val code = registry.resolve("claude")

        val (_, report) = load(dirWith("clash.json" to entry("claude")), registry)

        assertSame(code, registry.resolve("claude"), "the code plugin's harness must stay registered")
        assertTrue(report.loaded.isEmpty())
        val note = report.notes.single()
        assertEquals(DescriptorNoteReason.CODE_PLUGIN_WINS, note.reason)
        assertEquals("claude", note.harnessId)
        assertEquals("clash.json", note.source)
    }

    @Test
    fun theFirstDescriptorWinsADuplicateDescriptorIdAndTheSecondIsNoted() {
        val dir = dirWith(
            "a.json" to entry("same", """, "commands": []"""),
            "b.json" to entry("same"),
        )
        val (registry, report) = load(dir)
        assertEquals(listOf("same"), registry.harnessIds())
        assertEquals(DescriptorNoteReason.DUPLICATE_DESCRIPTOR_ID, report.notes.single().reason)
        assertEquals("b.json", report.notes.single().source)
    }

    @Test
    fun anInvalidDescriptorIsRefusedForEachBrokenField() {
        val bad = mapOf(
            "blank id" to entry(" "),
            "upper-case id" to entry("Echo"),
            "id with a space" to entry("a b"),
            "unknown capability" to entry("c1", "").replace("\"capabilities\": []", "\"capabilities\": [\"TELEPORT\"]"),
            "unknown placeholder" to entry("c2", "").replace("{prompt}", "{promt}"),
            "unknown format" to entry("c3", output = """"output": {"format": "xml"}"""),
            "jsonl without a map" to entry("c4", output = """"output": {"format": "jsonl"}"""),
            "jsonl rule missing a required field" to entry(
                "c5",
                output = """"output": {"format": "jsonl", "map": [{"match": {"k": "v"}, "event": "text", "fields": {}}]}""",
            ),
            "jsonl rule with an unknown event" to entry(
                "c6",
                output = """"output": {"format": "jsonl", "map": [{"match": {}, "event": "dance", "fields": {}}]}""",
            ),
            "native command without native text" to entry(
                "c7", """, "commands": [{"name": "compact", "description": "d", "action": "NATIVE"}]""",
            ),
        )
        for ((label, json) in bad) {
            val (registry, report) = load(dirWith("bad.json" to json))
            assertTrue(registry.harnessIds().isEmpty(), "$label: must not register")
            assertEquals(DescriptorNoteReason.INVALID_DESCRIPTOR, report.notes.singleOrNull()?.reason, "$label: ${report.notes}")
        }
    }

    // --- args template --------------------------------------------------------------------------

    @Test
    fun theArgsTemplateFillsPlaceholdersAndDropsGroupsWithAnUnresolvedOne() {
        val harness = descriptorHarness(
            """
            {"id": "tpl", "name": "T", "command": "tool",
             "args": ["run", "--p={prompt}", ["--model", "{model}"], ["--resume", "{sessionId}"], ["--effort", "{effort}"]],
             "capabilities": ["MODEL_SELECTION", "SESSION_RESUME", "EFFORT_SELECTION"], "output": {"format": "text"}}
            """.trimIndent(),
        )

        val bare = harness.processSpec(HarnessRequest("hi", "tpl", "p")).command
        assertEquals(listOf("tool", "run", "--p=hi"), bare)

        val full = harness.processSpec(HarnessRequest("hi", "tpl", "p", model = "m1", sessionId = "s1", effort = "high")).command
        assertEquals(listOf("tool", "run", "--p=hi", "--model", "m1", "--resume", "s1", "--effort", "high"), full)
    }

    @Test
    fun aValueIsNeverReExpandedAndNeverReachesAShell() {
        val harness = descriptorHarness(
            """{"id": "tpl2", "name": "T", "command": "tool", "args": ["{prompt}", ["--model", "{model}"]],
               "capabilities": ["MODEL_SELECTION"], "output": {"format": "text"}}""",
        )
        // The prompt itself names a placeholder: a single pass must leave it alone.
        val spec = harness.processSpec(HarnessRequest("say {model} ; rm -rf /", "tpl2", "p", model = "SECRET-MODEL"))
        assertEquals(listOf("tool", "say {model} ; rm -rf /", "--model", "SECRET-MODEL"), spec.command)
    }

    // --- capabilities, commands -----------------------------------------------------------------

    @Test
    fun capabilitiesAreTheDeclaredOnesAndGateARequest() {
        val harness = descriptorHarness(entry("caps"))
        assertTrue(harness.capabilities.isEmpty())
        assertFailsWith<MissingCapabilityException> { harness.run(HarnessRequest("hi", "caps", "p", model = "m")) }

        val withModel = descriptorHarness(
            entry("caps2").replace("\"capabilities\": []", "\"capabilities\": [\"model_selection\", \"USAGE\"]"),
        )
        assertEquals(setOf(HarnessCapability.MODEL_SELECTION, HarnessCapability.USAGE), withModel.capabilities)
    }

    @Test
    fun commandsAreHarnessCommandDataServedAsDeclared() {
        val harness = descriptorHarness(
            entry(
                "cmds",
                """, "commands": [
                    {"name": "model", "description": "Switch", "action": "SET_MODEL", "argument": "<model>", "values": ["a", "b"]},
                    {"name": "compact", "description": "Compact", "action": "NATIVE", "native": "/compact"}
                ]""",
            ),
        )
        assertEquals(listOf("model", "compact"), harness.commands.map { it.name })
        assertEquals(CommandAction.SET_MODEL, harness.commands[0].action)
        assertEquals(listOf("a", "b"), harness.commands[0].values)
        assertEquals("/compact", harness.commands[1].native)
    }

    // --- output: text ---------------------------------------------------------------------------

    @Test
    fun textOutputBecomesTextAndACleanExitCompletes() {
        val translator = descriptorHarness(entry("txt")).newTranslator()

        assertEquals(listOf<HarnessEvent>(HarnessEvent.TextDelta("hello")), translator.translate("hello"))
        assertEquals(listOf<HarnessEvent>(HarnessEvent.TextDelta("\nworld")), translator.translate("world"))
        assertEquals(HarnessEvent.Completion(), translator.terminal(0, ""))
    }

    @Test
    fun aFailedExitIsAnErrorEventNamingTheStderrTail() {
        val translator = descriptorHarness(entry("txt2")).newTranslator()
        val terminal = translator.terminal(3, "boom")
        assertIs<HarnessEvent.Error>(terminal)
        assertTrue("3" in terminal.message && "boom" in terminal.message, terminal.message)
    }

    // --- output: jsonl --------------------------------------------------------------------------

    private fun jsonlHarness(): CliHarness = descriptorHarness(
        """
        {"id": "jl", "name": "J", "command": "tool", "args": [], "capabilities": ["USAGE", "TOOL_CALLS", "REASONING"],
         "output": {"format": "jsonl", "map": [
           {"match": {"t": "start"}, "event": "session", "fields": {"id": "session.id"}},
           {"match": {"t": "msg", "role": "assistant"}, "event": "text", "fields": {"text": "body.text"}},
           {"match": {"t": "think"}, "event": "reasoning", "fields": {"text": "body.text"}},
           {"match": {"t": "tool"}, "event": "tool", "fields": {"id": "id", "name": "name", "arguments": "args"}},
           {"match": {"t": "tool_out"}, "event": "tool_result", "fields": {"id": "id", "output": "out", "isError": "bad"}},
           {"match": {"t": "usage"}, "event": "usage", "fields": {"input": "in", "output": "out"}},
           {"match": {"t": "end"}, "event": "completion", "fields": {"reason": "why"}},
           {"match": {"t": "fail"}, "event": "error", "fields": {"message": "msg"}}
         ]}}
        """.trimIndent(),
    )

    @Test
    fun jsonlFieldsMapToEveryEventKind() {
        val t = jsonlHarness().newTranslator()

        assertEquals(listOf<HarnessEvent>(HarnessEvent.SessionStarted("S1")), t.translate("""{"t":"start","session":{"id":"S1"}}"""))
        assertEquals(listOf<HarnessEvent>(HarnessEvent.TextDelta("hi")), t.translate("""{"t":"msg","role":"assistant","body":{"text":"hi"}}"""))
        assertEquals(emptyList(), t.translate("""{"t":"msg","role":"user","body":{"text":"not mapped"}}"""), "every key in match must equal")
        assertEquals(listOf<HarnessEvent>(HarnessEvent.Reasoning("hmm")), t.translate("""{"t":"think","body":{"text":"hmm"}}"""))
        assertEquals(
            listOf<HarnessEvent>(HarnessEvent.ToolCall("c1", "read", """{"path":"a"}""")),
            t.translate("""{"t":"tool","id":"c1","name":"read","args":{"path":"a"}}"""),
        )
        assertEquals(
            listOf<HarnessEvent>(HarnessEvent.ToolResult("c1", "ok", true)),
            t.translate("""{"t":"tool_out","id":"c1","out":"ok","bad":true}"""),
        )
        assertEquals(listOf<HarnessEvent>(HarnessEvent.Usage(7, 9)), t.translate("""{"t":"usage","in":7,"out":9}"""))
        assertEquals(emptyList(), t.translate("not json"), "a line that is not JSON is never guessed into prose")
        assertEquals(emptyList(), t.translate("""[1,2]"""))
        assertEquals(emptyList(), t.translate("""{"t":"unmapped"}"""))
    }

    @Test
    fun aMissingFieldInAMatchedRecordEmitsNothingRatherThanABlankEvent() {
        val t = jsonlHarness().newTranslator()
        assertEquals(emptyList(), t.translate("""{"t":"msg","role":"assistant","body":{}}"""))
        assertEquals(emptyList(), t.translate("""{"t":"tool","id":"c1","args":{}}"""), "a tool call needs a name")
    }

    @Test
    fun jsonlRunEndsWithTheMappedCompletionReasonOrAnError() {
        val done = jsonlHarness().newTranslator()
        done.translate("""{"t":"end","why":"length"}""")
        assertEquals(HarnessEvent.Completion("length"), done.terminal(0, ""))

        val silent = jsonlHarness().newTranslator()
        assertEquals(HarnessEvent.Completion(), silent.terminal(0, ""), "no completion record: a clean exit still completes with stop")

        val failed = jsonlHarness().newTranslator()
        failed.translate("""{"t":"fail","msg":"rate limited"}""")
        assertEquals(HarnessEvent.Error("rate limited"), failed.terminal(0, ""), "a mapped error wins over a clean exit")

        val crashed = jsonlHarness().newTranslator()
        val terminal = crashed.terminal(2, "trace")
        assertIs<HarnessEvent.Error>(terminal)
        assertTrue("2" in terminal.message && "trace" in terminal.message)
    }

    @Test
    fun theClaudeAndPiFormatsReuseTheShippedTranslatorsInsteadOfASecondReading() {
        val pi = descriptorHarness(entry("pi-like", output = """"output": {"format": "pi"}""")).newTranslator()
        assertEquals(
            listOf<HarnessEvent>(HarnessEvent.SessionStarted("p1")),
            pi.translate("""{"type":"session","id":"p1"}"""),
            "the descriptor must read a Pi stream exactly as the Pi harness does",
        )
        val claude = descriptorHarness(entry("claude-like", output = """"output": {"format": "claude"}""")).newTranslator()
        assertEquals(emptyList(), claude.translate("not json"))
        assertIs<HarnessEvent.Error>(claude.terminal(1, "x"))
    }

    // --- the whole path, with a real child process ----------------------------------------------

    @Test
    fun aPromptRunsThroughARealCliDescribedOnlyByJson() {
        val work = EchoCliFixture.tempDir("run")
        val source = EchoCliFixture.writeSource(work)
        File(work, "echo.json").writeText(EchoCliFixture.descriptorJson("echo-cli", source))
        val registry = HarnessRegistry()
        val report = HarnessDescriptorLoader(work) { runner }.loadInto(registry)
        assertEquals(listOf("echo-cli"), report.loaded, "notes: ${report.notes}")

        val events = runBlocking {
            withTimeout(60_000) {
                registry.run(HarnessRequest("hello there", "echo-cli", "p", model = "m1")).toList()
            }
        }

        assertEquals(
            listOf(
                HarnessEvent.SessionStarted("echo-session-1"),
                HarnessEvent.TextDelta("echo: hello there"),
                // (the JDK's source launcher consumes the .java path itself: the program sees the rest of the template)
                HarnessEvent.TextDelta("argv: --model|m1"),
                HarnessEvent.Usage(3, 5),
                HarnessEvent.Completion("stop"),
            ),
            events,
        )
        assertEquals(listOf("model"), (registry.resolve("echo-cli") as CliHarness).commands.map { it.name })
    }
}
