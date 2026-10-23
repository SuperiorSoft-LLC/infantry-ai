package com.superiorsoftllc.agenthq.session

import com.superiorsoftllc.agenthq.protocol.EventNormalizer
import com.superiorsoftllc.agenthq.session.RetrievalClassifier.Kind
import com.superiorsoftllc.agenthq.session.RetrievalClassifier.Verdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P10-K10: [RetrievalClassifier], table-driven over REAL provider output (K-D4, PHASE10 §2.9).
 *
 * Every stream below is made of whole, unedited lines captured from real `opencode run --format json` and
 * `claude -p --output-format stream-json` runs (see `/retrieval/PROVENANCE.txt`). A row picks lines out of those
 * captures by their 1-based line number and feeds them, in that order, through the classifier, which in turn
 * runs each one through [EventNormalizer]. No event is hand-built.
 */
class RetrievalClassifierTest {

    private fun fixture(name: String): List<String> =
        checkNotNull(javaClass.getResourceAsStream("/retrieval/$name.jsonl")) { "fixture $name missing" }
            .bufferedReader().readLines().filter { it.isNotBlank() }

    private val oc = fixture("opencode-bash-run")
    private val ot = fixture("opencode-tools-run")
    private val cl = fixture("claude-run")

    // opencode-bash-run: each tool line sits between its step_start and step_finish.
    private val ocInbox = oc.slice(0..2)          // agenthq inbox
    private val ocKnowledge = oc.slice(3..5)      // agenthq knowledge
    private val ocCmdSearch = oc.slice(6..8)      // agenthq.cmd memory search archive
    private val ocLongShow = oc.slice(9..11)      // cd <long path> && C:/Users/dev/bin/agenthq.cmd decision show D2
    private val ocGrep = oc.slice(12..14)         // rtk grep -rn archive src
    private val ocSend = oc.slice(24..26)         // agenthq send pm hello
    private val ocText = oc.slice(27..29)         // the closing reply, no tool

    // opencode-tools-run
    private val otTodo = ot.slice(0..2)           // todowrite
    private val otRead = ot.slice(3..5)           // read src/a.txt
    private val otSemicolon = ot.slice(6..8)      // agenthq tickets; agenthq skill list
    private val otPipe = ot.slice(9..11)          // echo src/a.txt | agenthq issue precheck src/a.txt
    private val otMemorySave = ot.slice(12..14)   // agenthq memory save archive-note --body x

    // claude-run: each assistant tool_use line is followed by its user tool_result line.
    private val clTodo = cl.slice(0..1)           // TodoWrite
    private val clInboxPath = cl.slice(2..3)      // /usr/local/bin/agenthq inbox
    private val clExeIndex = cl.slice(4..5)       // C:/Users/dev/bin/agenthq.exe memory index
    private val clGrep = cl.slice(6..7)           // Grep tool
    private val clLongKnowledge = cl.slice(8..9)  // cd <long path> && agenthq knowledge
    private val clDecisionList = cl.slice(10..11) // agenthq decision list
    private val clText = cl.slice(12..12)         // the closing reply, no tool

    private data class Row(
        val name: String,
        val lines: List<String>,
        val verdict: Verdict,
        val firstReadCommand: String? = null,
    )

    private val rows = listOf(
        // --- read_first ---------------------------------------------------------------------------------------
        Row("opencode: neutral inbox, then knowledge", ocInbox + ocKnowledge + ocGrep + ocText,
            Verdict.READ_FIRST, "agenthq knowledge"),
        Row("opencode: agenthq.cmd form", ocCmdSearch + ocGrep, Verdict.READ_FIRST,
            "agenthq.cmd memory search archive"),
        Row("opencode: full path .cmd behind a >100-char cd", ocLongShow + ocGrep, Verdict.READ_FIRST,
            LONG_OC),
        Row("opencode: `;` segment after a neutral verb", otTodo + otSemicolon + otRead, Verdict.READ_FIRST,
            "agenthq tickets; agenthq skill list"),
        Row("opencode: `|` segment", otPipe + otRead, Verdict.READ_FIRST,
            "echo src/a.txt | agenthq issue precheck src/a.txt"),
        Row("claude: todo + full-path inbox are neutral, then agenthq.exe memory index", cl, Verdict.READ_FIRST,
            "C:/Users/dev/bin/agenthq.exe memory index"),
        Row("claude: >100-char cd && agenthq knowledge", clTodo + clLongKnowledge + clGrep, Verdict.READ_FIRST,
            LONG_CL),
        Row("claude: decision list", clInboxPath + clDecisionList, Verdict.READ_FIRST, "agenthq decision list"),

        // --- explored_first -----------------------------------------------------------------------------------
        Row("opencode: grep, then knowledge", ocGrep + ocKnowledge, Verdict.EXPLORED_FIRST, "agenthq knowledge"),
        Row("opencode: the read tool is exploration", otTodo + otRead + otSemicolon, Verdict.EXPLORED_FIRST,
            "agenthq tickets; agenthq skill list"),
        Row("opencode: a write verb is exploration, not neutral", otMemorySave + ocKnowledge,
            Verdict.EXPLORED_FIRST, "agenthq knowledge"),
        Row("claude: Grep tool, then the long knowledge read", clGrep + clLongKnowledge, Verdict.EXPLORED_FIRST,
            LONG_CL),

        // --- never_read ---------------------------------------------------------------------------------------
        Row("opencode: inbox, grep, send", ocInbox + ocGrep + ocSend + ocText, Verdict.NEVER_READ),
        Row("opencode: neutral verbs only", ocInbox + ocSend + otTodo, Verdict.NEVER_READ),
        Row("claude: todo, inbox, Grep", clTodo + clInboxPath + clGrep + clText, Verdict.NEVER_READ),

        // --- no_tools -----------------------------------------------------------------------------------------
        Row("opencode: text only", ocText, Verdict.NO_TOOLS),
        Row("claude: text only", clText, Verdict.NO_TOOLS),
        Row("empty stream", emptyList(), Verdict.NO_TOOLS),
    )

    @Test
    fun eachRowOfRealLinesGetsItsVerdictAndNamesItsFirstRead() {
        for (row in rows) {
            val result = RetrievalClassifier.classifyLines(row.lines)
            assertEquals(row.verdict, result.verdict, "${row.name}: ${result.calls}")
            assertEquals(row.firstReadCommand, result.firstRead?.command, "${row.name}: first read")
            result.firstRead?.let { read ->
                // The reported line really is the provider line that carried the call.
                assertTrue(read.command!! in row.lines[read.line].replace("\\\"", "\""), "${row.name}: line ${read.line}")
            }
        }
    }

    /**
     * BUG-G2e: a session whose first turn carried the injected digest is INJECTED whatever its tools did (G2's run 1
     * explored before it read: that is still the knowledge-in-the-prompt case), and INJECTED satisfies read-first.
     */
    @Test
    fun aFirstTurnCarryingTheDigestIsInjectedWhateverTheToolsDid() {
        val digest = com.superiorsoftllc.agenthq.agents.renderKnowledge(
            "p1",
            com.superiorsoftllc.agenthq.agents.KnowledgeCounts(
                0, 1, 0,
                digest = com.superiorsoftllc.agenthq.agents.KnowledgeDigest(listOf("D1 use sqlite"), emptyList(), emptyList()),
            ),
        )
        for (lines in listOf(ocGrep + ocKnowledge, ocKnowledge, ocText, emptyList())) {
            assertEquals(Verdict.INJECTED, RetrievalClassifier.classifyLines(lines, firstTurn = "PERSONA\n\n$digest\n\ntask").verdict)
        }
        // The pointer form (no digest) is not injection: the tools still decide.
        val pointer = com.superiorsoftllc.agenthq.agents.renderKnowledge("p1", com.superiorsoftllc.agenthq.agents.KnowledgeCounts(0, 1, 0))
        assertEquals(Verdict.EXPLORED_FIRST, RetrievalClassifier.classifyLines(ocGrep + ocKnowledge, firstTurn = pointer).verdict)
        assertEquals(Verdict.EXPLORED_FIRST, RetrievalClassifier.classifyLines(ocGrep + ocKnowledge, firstTurn = null).verdict)
        assertEquals(
            setOf(Verdict.READ_FIRST, Verdict.INJECTED),
            Verdict.entries.filter { it.satisfiesReadFirst }.toSet(),
        )
    }

    @Test
    fun everyCapturedToolCallHasItsKind() {
        val expected = listOf(
            // opencode-bash-run (the model ran the grep four times)
            "agenthq inbox" to Kind.NEUTRAL,
            "agenthq knowledge" to Kind.KNOWLEDGE_READ,
            "agenthq.cmd memory search archive" to Kind.KNOWLEDGE_READ,
            LONG_OC to Kind.KNOWLEDGE_READ,
            "rtk grep -rn archive src" to Kind.EXPLORATION,
            "rtk grep -rn archive src" to Kind.EXPLORATION,
            "rtk grep -rn archive src" to Kind.EXPLORATION,
            "rtk grep -rn archive src" to Kind.EXPLORATION,
            "agenthq send pm hello" to Kind.NEUTRAL,
            // opencode-tools-run
            "todowrite" to Kind.NEUTRAL,
            "read" to Kind.EXPLORATION,
            "agenthq tickets; agenthq skill list" to Kind.KNOWLEDGE_READ,
            "echo src/a.txt | agenthq issue precheck src/a.txt" to Kind.KNOWLEDGE_READ,
            "agenthq memory save archive-note --body x" to Kind.EXPLORATION,
            // claude-run
            "TodoWrite" to Kind.NEUTRAL,
            "/usr/local/bin/agenthq inbox" to Kind.NEUTRAL,
            "C:/Users/dev/bin/agenthq.exe memory index" to Kind.KNOWLEDGE_READ,
            "Grep" to Kind.EXPLORATION,
            LONG_CL to Kind.KNOWLEDGE_READ,
            "agenthq decision list" to Kind.KNOWLEDGE_READ,
        )
        val calls = RetrievalClassifier.classifyLines(oc).calls +
            RetrievalClassifier.classifyLines(ot).calls +
            RetrievalClassifier.classifyLines(cl).calls
        assertEquals(expected, calls.map { (it.command ?: it.name) to it.kind })
    }

    @Test
    fun aCommandLongerThan100CharsIsClassifiedFromTheRawInputNotTheCappedActivity() {
        for ((lines, command) in listOf(ocLongShow to LONG_OC, clLongKnowledge to LONG_CL)) {
            assertTrue(command.length > 100, command)
            // The normalized event alone has lost the agenthq part: this is the §2.9 trap the row guards.
            val activity = lines.flatMap(EventNormalizer::normalise)
                .filterIsInstance<EventNormalizer.Line.Tool>().single().activity
            assertNotNull(activity)
            assertTrue(activity.endsWith("…"), activity)
            assertFalse("agenthq" in activity, activity)

            val read = assertNotNull(RetrievalClassifier.classifyLines(lines).firstRead)
            assertEquals(command, read.command)
            assertEquals(activity, read.activity)
        }
    }

    @Test
    fun verdictWireNamesMatchKD4() {
        assertEquals(
            listOf("read_first", "explored_first", "never_read", "no_tools", "injected"),
            Verdict.entries.map { it.wire },
        )
    }

    /**
     * The segment parser on its own, for spellings no captured run produced. The verdict rows above are the real
     * evidence; this table only pins the K-D4 grammar's edges.
     */
    @Test
    fun theCommandGrammarEdges() {
        val table = listOf(
            "agenthq knowledge" to Kind.KNOWLEDGE_READ,
            "AGENTHQ.BAT knowledge" to Kind.KNOWLEDGE_READ,
            "C:\\tools\\agenthq.ps1 memory show x" to Kind.KNOWLEDGE_READ,
            "& \"C:\\Program Files\\agenthq\\agenthq.exe\" issue list" to Kind.KNOWLEDGE_READ,
            "AGENTHQ_SESSION_ID=s1 agenthq issue precheck a.kt" to Kind.KNOWLEDGE_READ,
            "cd /x || agenthq skill search pdf" to Kind.KNOWLEDGE_READ,
            "cd /x" to Kind.NEUTRAL,
            "cd /x && agenthq read 12" to Kind.NEUTRAL,
            "agenthq agents" to Kind.NEUTRAL,
            "agenthq threads" to Kind.NEUTRAL,
            "agenthq ticket show T1" to Kind.NEUTRAL,
            "agenthq memory" to Kind.EXPLORATION,
            "agenthq decision add --summary x" to Kind.EXPLORATION,
            "agenthq issue attempt failed" to Kind.EXPLORATION,
            "agenthqx knowledge" to Kind.EXPLORATION,
            "echo agenthq knowledge" to Kind.EXPLORATION,
            "cd /x && ls" to Kind.EXPLORATION,
            "" to Kind.EXPLORATION,
        )
        for ((command, kind) in table) {
            assertEquals(kind, RetrievalClassifier.kindOf("bash", command), "'$command'")
        }
        assertEquals(Kind.NEUTRAL, RetrievalClassifier.kindOf("TodoWrite", null))
        assertEquals(Kind.NEUTRAL, RetrievalClassifier.kindOf("update_plan", null))
        assertEquals(Kind.EXPLORATION, RetrievalClassifier.kindOf("Read", null))
        assertNull(RetrievalClassifier.classifyLines(listOf("not json at all")).firstRead)
    }

    private companion object {
        const val LONG_OC = "cd C:/Users/dev/AppData/Local/Temp/very/long/worktree/path/that/keeps/going/and/going/" +
            "for/a/while && C:/Users/dev/bin/agenthq.cmd decision show D2"
        const val LONG_CL = "cd C:/Users/dev/AppData/Local/Temp/very/long/worktree/path/that/keeps/going/and/going/" +
            "for/a/while && agenthq knowledge"
    }
}
