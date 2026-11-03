package com.superiorsoftllc.infantry.session

import com.superiorsoftllc.infantry.protocol.EventNormalizer
import com.superiorsoftllc.infantry.session.RetrievalClassifier.Kind
import com.superiorsoftllc.infantry.session.RetrievalClassifier.Verdict
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
    private val ocInbox = oc.slice(0..2)          // infantry inbox
    private val ocKnowledge = oc.slice(3..5)      // infantry knowledge
    private val ocCmdSearch = oc.slice(6..8)      // infantry.cmd memory search archive
    private val ocLongShow = oc.slice(9..11)      // cd <long path> && C:/Users/dev/bin/infantry.cmd decision show D2
    private val ocGrep = oc.slice(12..14)         // rtk grep -rn archive src
    private val ocSend = oc.slice(24..26)         // infantry send pm hello
    private val ocText = oc.slice(27..29)         // the closing reply, no tool

    // opencode-tools-run
    private val otTodo = ot.slice(0..2)           // todowrite
    private val otRead = ot.slice(3..5)           // read src/a.txt
    private val otSemicolon = ot.slice(6..8)      // infantry tickets; infantry skill list
    private val otPipe = ot.slice(9..11)          // echo src/a.txt | infantry issue precheck src/a.txt
    private val otMemorySave = ot.slice(12..14)   // infantry memory save archive-note --body x

    // claude-run: each assistant tool_use line is followed by its user tool_result line.
    private val clTodo = cl.slice(0..1)           // TodoWrite
    private val clInboxPath = cl.slice(2..3)      // /usr/local/bin/infantry inbox
    private val clExeIndex = cl.slice(4..5)       // C:/Users/dev/bin/infantry.exe memory index
    private val clGrep = cl.slice(6..7)           // Grep tool
    private val clLongKnowledge = cl.slice(8..9)  // cd <long path> && infantry knowledge
    private val clDecisionList = cl.slice(10..11) // infantry decision list
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
            Verdict.READ_FIRST, "infantry knowledge"),
        Row("opencode: infantry.cmd form", ocCmdSearch + ocGrep, Verdict.READ_FIRST,
            "infantry.cmd memory search archive"),
        Row("opencode: full path .cmd behind a >100-char cd", ocLongShow + ocGrep, Verdict.READ_FIRST,
            LONG_OC),
        Row("opencode: `;` segment after a neutral verb", otTodo + otSemicolon + otRead, Verdict.READ_FIRST,
            "infantry tickets; infantry skill list"),
        Row("opencode: `|` segment", otPipe + otRead, Verdict.READ_FIRST,
            "echo src/a.txt | infantry issue precheck src/a.txt"),
        Row("claude: todo + full-path inbox are neutral, then infantry.exe memory index", cl, Verdict.READ_FIRST,
            "C:/Users/dev/bin/infantry.exe memory index"),
        Row("claude: >100-char cd && infantry knowledge", clTodo + clLongKnowledge + clGrep, Verdict.READ_FIRST,
            LONG_CL),
        Row("claude: decision list", clInboxPath + clDecisionList, Verdict.READ_FIRST, "infantry decision list"),

        // --- explored_first -----------------------------------------------------------------------------------
        Row("opencode: grep, then knowledge", ocGrep + ocKnowledge, Verdict.EXPLORED_FIRST, "infantry knowledge"),
        Row("opencode: the read tool is exploration", otTodo + otRead + otSemicolon, Verdict.EXPLORED_FIRST,
            "infantry tickets; infantry skill list"),
        Row("opencode: a write verb is exploration, not neutral", otMemorySave + ocKnowledge,
            Verdict.EXPLORED_FIRST, "infantry knowledge"),
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
        val digest = com.superiorsoftllc.infantry.agents.renderKnowledge(
            "p1",
            com.superiorsoftllc.infantry.agents.KnowledgeCounts(
                0, 1, 0,
                digest = com.superiorsoftllc.infantry.agents.KnowledgeDigest(listOf("D1 use sqlite"), emptyList(), emptyList()),
            ),
        )
        for (lines in listOf(ocGrep + ocKnowledge, ocKnowledge, ocText, emptyList())) {
            assertEquals(Verdict.INJECTED, RetrievalClassifier.classifyLines(lines, firstTurn = "PERSONA\n\n$digest\n\ntask").verdict)
        }
        // The pointer form (no digest) is not injection: the tools still decide.
        val pointer = com.superiorsoftllc.infantry.agents.renderKnowledge("p1", com.superiorsoftllc.infantry.agents.KnowledgeCounts(0, 1, 0))
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
            "infantry inbox" to Kind.NEUTRAL,
            "infantry knowledge" to Kind.KNOWLEDGE_READ,
            "infantry.cmd memory search archive" to Kind.KNOWLEDGE_READ,
            LONG_OC to Kind.KNOWLEDGE_READ,
            "rtk grep -rn archive src" to Kind.EXPLORATION,
            "rtk grep -rn archive src" to Kind.EXPLORATION,
            "rtk grep -rn archive src" to Kind.EXPLORATION,
            "rtk grep -rn archive src" to Kind.EXPLORATION,
            "infantry send pm hello" to Kind.NEUTRAL,
            // opencode-tools-run
            "todowrite" to Kind.NEUTRAL,
            "read" to Kind.EXPLORATION,
            "infantry tickets; infantry skill list" to Kind.KNOWLEDGE_READ,
            "echo src/a.txt | infantry issue precheck src/a.txt" to Kind.KNOWLEDGE_READ,
            "infantry memory save archive-note --body x" to Kind.EXPLORATION,
            // claude-run
            "TodoWrite" to Kind.NEUTRAL,
            "/usr/local/bin/infantry inbox" to Kind.NEUTRAL,
            "C:/Users/dev/bin/infantry.exe memory index" to Kind.KNOWLEDGE_READ,
            "Grep" to Kind.EXPLORATION,
            LONG_CL to Kind.KNOWLEDGE_READ,
            "infantry decision list" to Kind.KNOWLEDGE_READ,
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
            // The normalized event alone has lost the infantry part: this is the §2.9 trap the row guards.
            val activity = lines.flatMap(EventNormalizer::normalise)
                .filterIsInstance<EventNormalizer.Line.Tool>().single().activity
            assertNotNull(activity)
            assertTrue(activity.endsWith("…"), activity)
            assertFalse("infantry" in activity, activity)

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
            "infantry knowledge" to Kind.KNOWLEDGE_READ,
            "INFANTRY.BAT knowledge" to Kind.KNOWLEDGE_READ,
            "C:\\tools\\infantry.ps1 memory show x" to Kind.KNOWLEDGE_READ,
            "& \"C:\\Program Files\\infantry\\infantry.exe\" issue list" to Kind.KNOWLEDGE_READ,
            "INFANTRY_SESSION_ID=s1 infantry issue precheck a.kt" to Kind.KNOWLEDGE_READ,
            "cd /x || infantry skill search pdf" to Kind.KNOWLEDGE_READ,
            "cd /x" to Kind.NEUTRAL,
            "cd /x && infantry read 12" to Kind.NEUTRAL,
            "infantry agents" to Kind.NEUTRAL,
            "infantry threads" to Kind.NEUTRAL,
            "infantry ticket show T1" to Kind.NEUTRAL,
            "infantry memory" to Kind.EXPLORATION,
            "infantry decision add --summary x" to Kind.EXPLORATION,
            "infantry issue attempt failed" to Kind.EXPLORATION,
            "infantryx knowledge" to Kind.EXPLORATION,
            "echo infantry knowledge" to Kind.EXPLORATION,
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
            "for/a/while && C:/Users/dev/bin/infantry.cmd decision show D2"
        const val LONG_CL = "cd C:/Users/dev/AppData/Local/Temp/very/long/worktree/path/that/keeps/going/and/going/" +
            "for/a/while && infantry knowledge"
    }
}
