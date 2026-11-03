package com.superiorsoftllc.infantry.session

import com.superiorsoftllc.infantry.agents.KNOWLEDGE_DIGEST_HEADING
import com.superiorsoftllc.infantry.protocol.EventNormalizer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * P10-K10 (PHASE10 K-D4, §2.9): did a session read the project's knowledge before it started exploring?
 *
 * PURE. It reads one session's provider stdout lines, in order, and returns a [Result]. It touches no store, no
 * clock and no process, and nothing calls it yet: G1 runs it on scripted streams, G2 on live ones, and putting the
 * verdict on `SessionRecord` is a later, optional bump (K16). It cannot BLOCK anything (K-D4 (c)): the daemon sees
 * a tool event only after the harness has already run it.
 *
 * WHY RAW LINES, NOT ONLY [EventNormalizer.Line.Tool] (§2.9): the normalized tool event keeps the call's salient
 * argument clamped to 100 chars, so `cd <long worktree path> && infantry knowledge` arrives with its `infantry` part
 * cut off. Each line still goes through [EventNormalizer.normalise], which stays the one place that decides WHICH
 * lines are tool calls and what each is named; the classifier then reads the same call's full `command` from the
 * raw line's tool input. It never reads any other input field.
 *
 * THE RULES (K-D4):
 *  - **Knowledge read:** a shell command in which some `&&` / `;` / `|` segment (a newline also separates) runs
 *    `infantry` (path and `.cmd/.bat/.exe/.ps1` stripped) with the verb `knowledge`, or with
 *    `memory|decision|issue|skill` followed by `index|list|show|search|precheck`.
 *  - **Neutral:** `cd`; the other `infantry` verbs listed in [NEUTRAL_VERBS]; todo and planning tools.
 *  - **Exploration:** everything else, reads and writes alike (an `infantry memory save` is a write, so it is
 *    exploration, not neutral).
 *
 * THE VERDICT, over the tool calls in stream order:
 *  - [Verdict.NO_TOOLS]: the stream holds no tool call at all.
 *  - [Verdict.NEVER_READ]: tool calls, but no knowledge read (neutral-only streams land here too).
 *  - [Verdict.EXPLORED_FIRST]: an exploring call comes before the first knowledge read.
 *  - [Verdict.READ_FIRST]: the first non-neutral call is a knowledge read.
 *  - [Verdict.INJECTED] (BUG-G2e): the session's FIRST TURN already carried the knowledge digest (the daemon injects
 *    it, see [firstTurnCarriesKnowledge]), so whatever the tools did, the session had the knowledge before it began.
 *    It wins over every other verdict, and [Verdict.satisfiesReadFirst] counts it with [Verdict.READ_FIRST].
 */
object RetrievalClassifier {

    enum class Verdict(val wire: String) {
        READ_FIRST("read_first"),
        EXPLORED_FIRST("explored_first"),
        NEVER_READ("never_read"),
        NO_TOOLS("no_tools"),

        /** BUG-G2e: the knowledge was in the first prompt. Not on the wire (nothing serializes a verdict yet). */
        INJECTED("injected"),
        ;

        /** G2's read-first check: the session read the knowledge before exploring, or was handed it up front. */
        val satisfiesReadFirst: Boolean get() = this == READ_FIRST || this == INJECTED
    }

    /** BUG-G2e: does this first-turn text carry an injected knowledge digest? */
    fun firstTurnCarriesKnowledge(firstTurn: String?): Boolean = firstTurn?.contains(KNOWLEDGE_DIGEST_HEADING) == true

    enum class Kind { KNOWLEDGE_READ, NEUTRAL, EXPLORATION }

    /**
     * One tool call seen in the stream.
     *
     * @param line the 0-based index, in the classified list, of the provider line that carried the call.
     * @param name the tool name exactly as [EventNormalizer] reported it.
     * @param command the call's FULL shell command from the raw input, or null for a tool without one.
     * @param activity the normalized, capped activity text, kept so a reader can see what a UI showed.
     */
    data class ToolCall(
        val line: Int,
        val name: String,
        val command: String?,
        val activity: String?,
        val kind: Kind,
    )

    data class Result(
        val verdict: Verdict,
        val calls: List<ToolCall>,
        val firstRead: ToolCall?,
        val firstExploration: ToolCall?,
    )

    /**
     * Classifies a session's provider stdout lines, in the order the provider wrote them. [firstTurn] is the text the
     * daemon delivered as the session's first turn, when the caller has it (BUG-G2e: a carried digest -> INJECTED).
     */
    fun classifyLines(lines: List<String>, firstTurn: String? = null): Result {
        val calls = mutableListOf<ToolCall>()
        lines.forEachIndexed { index, line ->
            val tools = EventNormalizer.normalise(line).filterIsInstance<EventNormalizer.Line.Tool>()
            if (tools.isEmpty()) return@forEachIndexed
            val inputs = toolInputsOf(line)
            tools.forEachIndexed { i, tool ->
                // The two lists are built by the same location rules; if a future provider shape ever makes them
                // disagree, the capped activity is the only command left, which is still better than none.
                val command = if (inputs.size == tools.size) commandOf(inputs[i]) else tool.activity
                calls += ToolCall(index, tool.name, command, tool.activity, kindOf(tool.name, command))
            }
        }
        return classify(calls, firstTurnCarriesKnowledge(firstTurn))
    }

    /** The verdict over already-extracted calls, in stream order; [injected] = the first turn carried the digest. */
    fun classify(calls: List<ToolCall>, injected: Boolean = false): Result {
        val firstRead = calls.firstOrNull { it.kind == Kind.KNOWLEDGE_READ }
        val firstExploration = calls.firstOrNull { it.kind == Kind.EXPLORATION }
        val verdict = when {
            injected -> Verdict.INJECTED
            calls.isEmpty() -> Verdict.NO_TOOLS
            firstRead == null -> Verdict.NEVER_READ
            firstExploration != null && calls.indexOf(firstExploration) < calls.indexOf(firstRead) ->
                Verdict.EXPLORED_FIRST
            else -> Verdict.READ_FIRST
        }
        return Result(verdict, calls, firstRead, firstExploration)
    }

    /** The kind of one call: by its shell [command] when it has one, else by its tool name. */
    fun kindOf(toolName: String, command: String?): Kind {
        if (command == null) {
            return if (toolName.lowercase() in NEUTRAL_TOOLS) Kind.NEUTRAL else Kind.EXPLORATION
        }
        // A shell call that runs nothing is not neutral by default: only a recognised neutral word makes it so.
        if (command.isBlank()) return Kind.EXPLORATION
        val segments = command.split(SEGMENT_SEPARATOR).map { segmentKind(it) }
        return when {
            Kind.KNOWLEDGE_READ in segments -> Kind.KNOWLEDGE_READ
            segments.all { it == Kind.NEUTRAL } -> Kind.NEUTRAL
            else -> Kind.EXPLORATION
        }
    }

    /**
     * One command segment. An empty segment (the gap inside `||`, or after a trailing `;`) is neutral, so it never
     * turns an otherwise-neutral command into exploration. A wholly blank command is handled in [kindOf].
     */
    private fun segmentKind(segment: String): Kind {
        val words = words(segment)
        if (words.isEmpty()) return Kind.NEUTRAL
        val program = programName(words[0])
        if (program == "cd") return Kind.NEUTRAL
        if (program != "infantry") return Kind.EXPLORATION
        val verb = words.getOrNull(1)?.lowercase() ?: return Kind.EXPLORATION
        val sub = words.getOrNull(2)?.lowercase()
        return when {
            verb == "knowledge" -> Kind.KNOWLEDGE_READ
            verb in READ_NOUNS && sub in READ_SUBVERBS -> Kind.KNOWLEDGE_READ
            verb in NEUTRAL_VERBS -> Kind.NEUTRAL
            else -> Kind.EXPLORATION
        }
    }

    /**
     * The segment's words, after the shell wrappers that do not change which program runs: leading `NAME=value`
     * environment assignments and PowerShell's `&` call operator. Double- and single-quoted words stay whole, so
     * `& "C:\Program Files\infantry\infantry.exe" knowledge` names `infantry.exe`.
     */
    private fun words(segment: String): List<String> {
        val words = WORD.findAll(segment).map { m -> m.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: "" }.toList()
        return words.dropWhile { it == "&" || ENV_ASSIGNMENT.matches(it) }
    }

    /** `C:\x\infantry.cmd` / `/usr/local/bin/infantry` / `INFANTRY.BAT` -> `infantry`. */
    private fun programName(word: String): String {
        val base = word.substringAfterLast('/').substringAfterLast('\\').lowercase()
        val ext = base.substringAfterLast('.', "")
        return if (ext in STRIPPED_EXTENSIONS) base.dropLast(ext.length + 1) else base
    }

    /**
     * The tool inputs on one raw line, in the order [EventNormalizer] reports that line's tool calls, under the
     * same location rules: claude's `tool_use` blocks with a non-blank `name` (each carrying `input`), or one
     * flat `tool_use` / opencode `tool` line carrying `input`, `part.input` or `part.state.input`.
     */
    private fun toolInputsOf(line: String): List<JsonObject?> {
        val obj = runCatching { json.parseToJsonElement(line.trim()) as? JsonObject }.getOrNull() ?: return emptyList()
        return when ((obj["type"] as? JsonPrimitive)?.content?.lowercase()) {
            "assistant" -> {
                val content = (obj["message"] as? JsonObject)?.get("content")
                val blocks = when (content) {
                    is JsonArray -> content.mapNotNull { it as? JsonObject }
                    is JsonObject -> listOf(content)
                    else -> emptyList()
                }
                blocks.filter { block ->
                    (block["type"] as? JsonPrimitive)?.content == "tool_use" &&
                        (block["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.isNotBlank() == true
                }.map { it["input"] as? JsonObject }
            }
            "tool_use", "tool" -> {
                val part = obj["part"] as? JsonObject
                val state = part?.get("state") as? JsonObject
                listOf(
                    obj["input"] as? JsonObject
                        ?: part?.get("input") as? JsonObject
                        ?: state?.get("input") as? JsonObject,
                )
            }
            else -> emptyList()
        }
    }

    private fun commandOf(input: JsonObject?): String? =
        (input?.get("command") as? JsonPrimitive)?.takeIf { it.isString }?.content

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val SEGMENT_SEPARATOR = Regex("&&|;|\\||\\r?\\n")
    private val WORD = Regex("\"([^\"]*)\"|'([^']*)'|(\\S+)")
    private val ENV_ASSIGNMENT = Regex("[A-Za-z_][A-Za-z0-9_]*=.*")
    private val STRIPPED_EXTENSIONS = setOf("cmd", "bat", "exe", "ps1")

    /** `infantry <noun> <subverb>` reads, per K-D4. */
    private val READ_NOUNS = setOf("memory", "decision", "issue", "skill")
    private val READ_SUBVERBS = setOf("index", "list", "show", "search", "precheck")

    /** The `infantry` verbs that neither read knowledge nor explore (K-D4 "Neutral"). */
    private val NEUTRAL_VERBS = setOf("inbox", "read", "agents", "send", "tickets", "ticket", "threads")

    /** Todo and planning tools, by the names claude, opencode and pi give them (lower-cased). */
    private val NEUTRAL_TOOLS = setOf(
        "todowrite", "todoread", "todo_write", "todo_read", "update_plan", "enterplanmode", "exitplanmode",
    )
}
