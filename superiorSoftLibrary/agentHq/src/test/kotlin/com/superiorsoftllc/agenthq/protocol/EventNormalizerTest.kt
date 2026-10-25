package com.superiorsoftllc.agenthq.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 9: the provider -> neutral mapping, asserted directly.
 *
 * These tests are the executable form of the table in [EventNormalizer.normalise] and of
 * `CONTRACT.md`. If a provider changes its event schema, the failure lands HERE -- in the one place
 * that knows a provider schema -- rather than in a client that was rendering the wrong thing.
 */
class EventNormalizerTest {

    // --- opencode: the Phase 1 provider ----------------------------------------------------

    @Test
    fun opencodeTextBecomesADelta() {
        val lines = EventNormalizer.normalise("""{"type":"text","text":"hello"}""")
        assertEquals(listOf(EventNormalizer.Line.Text("hello")), lines)
    }

    @Test
    fun opencodeNestedPartTextBecomesADelta() {
        // The real shape seen in Phase 1: the text lives under `part`.
        val lines = EventNormalizer.normalise("""{"type":"text","part":{"text":"under part"}}""")
        assertEquals(listOf(EventNormalizer.Line.Text("under part")), lines)
    }

    @Test
    fun opencodeReasoningPartBecomesReasoningLine() {
        // Contract 1.23.0: the model's reasoning is its own line kind, never answer text, so a UI
        // can render it collapsed. opencode carries it under `part` (as with `text`).
        val nested = EventNormalizer.normalise("""{"type":"reasoning","part":{"text":"pondering the fix"}}""")
        assertEquals(listOf(EventNormalizer.Line.Reasoning("pondering the fix")), nested)
        // ... and, like `text`, it may also sit at the top level.
        val top = EventNormalizer.normalise("""{"type":"reasoning","text":"top-level thought"}""")
        assertEquals(listOf(EventNormalizer.Line.Reasoning("top-level thought")), top)
    }

    // --- pi: message_update wraps an assistantMessageEvent ---------------------------------

    @Test
    fun piTextDeltaBecomesText() {
        val raw = """{"type":"message_update","assistantMessageEvent":{"type":"text_delta","contentIndex":0,"delta":"hi there"}}"""
        assertEquals(listOf(EventNormalizer.Line.Text("hi there")), EventNormalizer.normalise(raw))
    }

    @Test
    fun piThinkingDeltaBecomesReasoning() {
        val raw = """{"type":"message_update","assistantMessageEvent":{"type":"thinking_delta","delta":"hmm"}}"""
        assertEquals(listOf(EventNormalizer.Line.Reasoning("hmm", delta = true)), EventNormalizer.normalise(raw))
    }

    @Test
    fun piOtherMessageUpdatesAreNotMapped() {
        // text_end repeats the block already streamed; mapping it would duplicate the reply.
        assertEquals(emptyList(), EventNormalizer.normalise("""{"type":"message_update","assistantMessageEvent":{"type":"text_end","content":"hi"}}"""))
        assertEquals(emptyList(), EventNormalizer.normalise("""{"type":"message_update","assistantMessageEvent":{"type":"text_delta","delta":""}}"""))
        assertEquals(emptyList(), EventNormalizer.normalise("""{"type":"message_update"}"""))
    }

    @Test
    fun opencodeStepStartIsNotMapped() {
        // Provider bookkeeping, NOT a lifecycle frame: the daemon derives lifecycle from process
        // facts, and letting a provider's "step" win would re-introduce the inversion Phase 3 removed.
        assertEquals(emptyList(), EventNormalizer.normalise("""{"type":"step_start","sessionID":"s1"}"""))
    }

    @Test
    fun opencodeStepFinishIsNotMapped() {
        assertEquals(emptyList(), EventNormalizer.normalise("""{"type":"step_finish"}"""))
    }

    @Test
    fun opencodeErrorBecomesAFailure() {
        val lines = EventNormalizer.normalise("""{"type":"error","error":{"message":"boom"}}""")
        assertEquals(listOf(EventNormalizer.Line.Failure("boom")), lines)
    }

    // --- claude: the Phase 2 provider ------------------------------------------------------

    @Test
    fun claudeAssistantTextBlockBecomesADelta() {
        val raw = """{"type":"assistant","message":{"content":[{"type":"text","text":"first"}]}}"""
        assertEquals(listOf(EventNormalizer.Line.Text("first")), EventNormalizer.normalise(raw))
    }

    @Test
    fun claudeThinkingBlockBecomesReasoningLine() {
        // Contract 1.23.0: claude's `thinking` content block is reasoning, not an answer, so it must
        // arrive as a Reasoning line rather than being dropped (the pre-1.23.0 filter) or leaked
        // into the transcript as Text.
        val raw = """{"type":"assistant","message":{"content":[{"type":"thinking","thinking":"let me reason"}]}}"""
        assertEquals(listOf(EventNormalizer.Line.Reasoning("let me reason")), EventNormalizer.normalise(raw))
    }

    @Test
    fun claudeThinkingAndAnswerStayDistinctAndOrdered() {
        // The realistic shape: a thinking block then a text block. A UI must be able to tell the two
        // apart and see them in the order the model produced them.
        val raw = """{"type":"assistant","message":{"content":[
            {"type":"thinking","thinking":"weighing options"},
            {"type":"text","text":"here is the answer"}
        ]}}"""
        assertEquals(
            listOf(
                EventNormalizer.Line.Reasoning("weighing options"),
                EventNormalizer.Line.Text("here is the answer"),
            ),
            EventNormalizer.normalise(raw),
        )
    }

    @Test
    fun claudeToolUseBlockBecomesAToolLine() {
        // Contract 1.25.0: a tool_use block is an action, but the ACTION carries a tool NAME, and
        // that name is what a UI shows ("Running Bash"). It must surface as its own line -- never be
        // dropped (the pre-1.25.0 behaviour) and never leak into the transcript as assistant speech.
        val raw = """{"type":"assistant","message":{"content":[{"type":"tool_use","name":"Bash"}]}}"""
        assertEquals(listOf(EventNormalizer.Line.Tool("Bash")), EventNormalizer.normalise(raw))
    }

    @Test
    fun claudeToolUseRidesBesideTextInBlockOrder() {
        // The realistic shape: text, then the call it leads into. Order is preserved and the two are
        // different line kinds, so a UI keeps "let me look" in the transcript and shows "Running Read".
        val raw = """{"type":"assistant","message":{"content":[
            {"type":"text","text":"let me look"},
            {"type":"tool_use","name":"Read"}
        ]}}"""
        assertEquals(
            listOf(
                EventNormalizer.Line.Text("let me look"),
                EventNormalizer.Line.Tool("Read"),
            ),
            EventNormalizer.normalise(raw),
        )
    }

    @Test
    fun opencodeToolPartBecomesAToolLine() {
        // The default provider: opencode names the call on the part (`part.tool`).
        val raw = """{"type":"tool","part":{"type":"tool","tool":"bash","state":{"status":"running"}}}"""
        assertEquals(listOf(EventNormalizer.Line.Tool("bash")), EventNormalizer.normalise(raw))
    }

    @Test
    fun aToolLineWithoutANameIsDropped() {
        // No name, nothing to show: dropping is correct, and it must never fall through to Text.
        assertEquals(emptyList(), EventNormalizer.normalise("""{"type":"tool_use"}"""))
        assertEquals(emptyList(), EventNormalizer.normalise("""{"type":"tool","part":{"state":"running"}}"""))
    }

    // --- Contract 1.26.0 (AGENT-UX-12): the tool call's salient ARGUMENT rides beside its name. ---

    @Test
    fun claudeToolUseCarriesItsSalientArgument() {
        // The command a Bash call is running: the "what is it DOING" a bare tool name cannot carry.
        val raw = """{"type":"assistant","message":{"content":[
            {"type":"tool_use","name":"Bash","input":{"command":"ls -la src"}}
        ]}}"""
        assertEquals(listOf(EventNormalizer.Line.Tool("Bash", "ls -la src")), EventNormalizer.normalise(raw))
    }

    @Test
    fun opencodeToolPartCarriesItsSalientArgument() {
        // opencode nests the call arguments under the part's state.input, beside part.tool.
        val raw = """{"type":"tool","part":{"type":"tool","tool":"bash",
            "state":{"status":"running","input":{"command":"grep -rn TODO src"}}}}"""
        assertEquals(listOf(EventNormalizer.Line.Tool("bash", "grep -rn TODO src")), EventNormalizer.normalise(raw))
    }

    @Test
    fun aSearchShowsThePathItIsSearchingNotThePattern() {
        // The owner asked for "what directories it is searching": on a search tool the PATH is the
        // salient argument and it is chosen over the pattern it is matching.
        val raw = """{"type":"tool_use","name":"Grep","input":{"pattern":"TODO","path":"src/auth"}}"""
        assertEquals(listOf(EventNormalizer.Line.Tool("Grep", "src/auth")), EventNormalizer.normalise(raw))
    }

    @Test
    fun toolActivityNeverDumpsContent() {
        // PRIVACY: only the allow-listed argument keys are read. A Read's `content` (file bytes) and any
        // other field are never surfaced -- an argument-less tool yields no activity at all.
        // UX-WRITE-1 (1.80.0): what a Write WROTE rides apart, on `write`; the activity line itself still never
        // carries the content.
        val raw = """{"type":"tool_use","name":"Write","input":{"file_path":"a.txt","content":"SECRET BODY"}}"""
        assertEquals("a.txt", (EventNormalizer.normalise(raw).single() as EventNormalizer.Line.Tool).activity)

        val onlyContent = """{"type":"tool_use","name":"Write","input":{"content":"SECRET BODY"}}"""
        assertEquals(listOf(EventNormalizer.Line.Tool("Write")), EventNormalizer.normalise(onlyContent))
    }

    // --- Contract 1.80.0 (UX-WRITE-1): a file-writing tool call carries what it wrote. ---

    private fun writeOf(raw: String) = EventNormalizer.normalise(raw).filterIsInstance<EventNormalizer.Line.Tool>().single().write

    @Test
    fun claudeWriteCarriesTheWholeContent() {
        val raw = """{"type":"assistant","message":{"content":[
            {"type":"tool_use","name":"Write","input":{"file_path":"src/A.kt","content":"one\ntwo"}}
        ]}}"""
        assertEquals(FileWrite("src/A.kt", content = "one\ntwo"), writeOf(raw))
    }

    @Test
    fun claudeEditAndMultiEditCarryTheirReplacements() {
        val edit = """{"type":"tool_use","name":"Edit","input":{"file_path":"a.kt","old_string":"x = 1","new_string":"x = 2"}}"""
        assertEquals(FileWrite("a.kt", edits = listOf(FileEdit("x = 1", "x = 2"))), writeOf(edit))
        val multi = """{"type":"tool_use","name":"MultiEdit","input":{"file_path":"a.kt","edits":[
            {"old_string":"a","new_string":"b"},{"old_string":"c","new_string":"d"}]}}"""
        assertEquals(FileWrite("a.kt", edits = listOf(FileEdit("a", "b"), FileEdit("c", "d"))), writeOf(multi))
    }

    @Test
    fun opencodeWriteEditAndPatchCarryWhatTheyWrote() {
        val write = """{"type":"tool_use","part":{"tool":"write","state":{"status":"completed","input":{"filePath":"/w/b.ts","content":"let b"}}}}"""
        assertEquals(FileWrite("/w/b.ts", content = "let b"), writeOf(write))
        val edit = """{"type":"tool_use","part":{"tool":"edit","state":{"status":"completed","input":{"filePath":"/w/b.ts","oldString":"let b","newString":"const b"}}}}"""
        assertEquals(FileWrite("/w/b.ts", edits = listOf(FileEdit("let b", "const b"))), writeOf(edit))
        val patchText = "*** Begin Patch\n*** Update File: src/c.ts\n@@\n-old\n+new\n*** End Patch"
        val patch = """{"type":"tool_use","part":{"tool":"patch","state":{"status":"completed","input":{"patchText":${kotlinx.serialization.json.JsonPrimitive(patchText)}}}}}"""
        assertEquals(FileWrite("src/c.ts", patch = patchText), writeOf(patch))
    }

    @Test
    fun aFailedOrNonWriteToolCarriesNoWrite() {
        val failed = """{"type":"tool_use","part":{"tool":"write","state":{"status":"error","error":"rejected","input":{"filePath":"b.ts","content":"x"}}}}"""
        assertEquals(null, writeOf(failed))
        val read = """{"type":"tool_use","name":"Read","input":{"file_path":"a.kt","content":"not a write"}}"""
        assertEquals(null, writeOf(read))
    }

    @Test
    fun anOversizedWriteIsCappedAndMarkedTruncated() {
        val big = "x".repeat(EventNormalizer.WRITE_MAX_CHARS + 10)
        val w = writeOf("""{"type":"tool_use","name":"Write","input":{"file_path":"big.txt","content":"$big"}}""")!!
        assertEquals(EventNormalizer.WRITE_MAX_CHARS, w.content!!.length)
        assertTrue(w.truncated)
    }

    @Test
    fun longToolActivityIsTruncated() {
        // A long command is clamped so a runaway argument cannot flood the status line.
        val long = "echo " + "x".repeat(400)
        val raw = """{"type":"tool_use","name":"Bash","input":{"command":"$long"}}"""
        val activity = (EventNormalizer.normalise(raw).single() as EventNormalizer.Line.Tool).activity
        assertEquals(101, activity!!.length, "100 chars plus the ellipsis")
        assertTrue(activity.endsWith("…"), "a truncated argument is marked: $activity")
    }

    @Test
    fun claudeErrorResultBecomesAFailure() {
        val raw = """{"type":"result","subtype":"error_max_turns","is_error":true}"""
        assertEquals(
            listOf(EventNormalizer.Line.Failure("provider reported an error")),
            EventNormalizer.normalise(raw),
        )
    }

    @Test
    fun claudeSuccessResultIsNotMapped() {
        // Ending the run is the daemon's job (from the exit code). Mapping a provider's claim to be
        // done would let a UI end a run before the process actually exited.
        assertEquals(emptyList(), EventNormalizer.normalise("""{"type":"result","subtype":"success"}"""))
    }

    // --- things that must never look like a delta -------------------------------------------

    @Test
    fun nonJsonLinesAreNotDeltas() {
        // The decision, tested: no "this looks like prose" heuristic exists. A bare line from a CLI
        // (a progress bar, a warning, chatter) must not leak into a UI transcript on a guess. The
        // line is still delivered verbatim on `stdout`, so nothing is lost.
        assertEquals(emptyList(), EventNormalizer.normalise("not json at all"))
        assertEquals(emptyList(), EventNormalizer.normalise("Installing dependencies... 45%"))
        assertEquals(emptyList(), EventNormalizer.normalise("[stub] no provider process was started"))
    }

    @Test
    fun aJsonLineWithoutATypeIsNotADelta() {
        assertEquals(emptyList(), EventNormalizer.normalise("""{"foo":"bar"}"""))
    }

    @Test
    fun unknownProviderTypeIsNotADelta() {
        assertEquals(emptyList(), EventNormalizer.normalise("""{"type":"heartbeat"}"""))
    }

    @Test
    fun garbageIsNotADelta() {
        assertEquals(emptyList(), EventNormalizer.normalise("not json at all"))
        assertEquals(emptyList(), EventNormalizer.normalise("[1,2,3]"))
    }

    @Test
    fun blankIsNotADelta() {
        assertEquals(emptyList(), EventNormalizer.normalise(""))
        assertEquals(emptyList(), EventNormalizer.normalise("   "))
    }

    // --- the versioning rule ---------------------------------------------------------------

    @Test
    fun theVocabularyIsVersioned() {
        // A client is entitled to refuse a boundary it does not understand, which requires that the
        // version be a real, published constant rather than an undocumented convention.
        assertEquals(Envelope.TEXT_DELTA, EventVocabulary.TEXT_DELTA)
        assertEquals(Envelope.STATUS, EventVocabulary.STATUS)
        assertEquals(Envelope.ERROR, EventVocabulary.ERROR)
        assertEquals(Envelope.FINISHED, EventVocabulary.FINISHED)
        assertTrue(EventVocabulary.VERSION.isNotBlank(), "the contract must carry a version")
    }
}
