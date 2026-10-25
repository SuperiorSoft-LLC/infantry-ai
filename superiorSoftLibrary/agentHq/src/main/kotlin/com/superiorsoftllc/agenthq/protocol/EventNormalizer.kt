package com.superiorsoftllc.agenthq.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * Translates a provider's raw stdout line into the neutral vocabulary of [EventVocabulary].
 *
 * This is the ONE place a provider's event schema is known. Above it, nothing knows whether
 * `opencode` or `claude` (or the stub) is running; below it, [Envelope] still carries the raw
 * provider line on `stdout` for anyone who wants the bytes. That is the whole boundary: a client
 * renders [Envelope.TEXT_DELTA] / [Envelope.STATUS] / [Envelope.ERROR] / [Envelope.FINISHED] and
 * parses no CLI schema ever again.
 *
 * WHAT IS DELIBERATELY *NOT* MAPPED (recorded so nobody re-adds it by guessing):
 *  - `step_start` / `step_finish` (opencode) and `system` (claude) are provider *bookkeeping*. They
 *    carry no user-visible text, and the daemon already derives real lifecycle from process facts
 *    (spawn -> first output -> exit). Turning them into `status` frames would let a provider's
 *    notion of "a step" override the daemon's notion of "a session", which is the exact inversion
 *    Phase 3 removed. They stay on `stdout` for debugging and are otherwise dropped.
 *  - a session id on any line is NOT an event: `SessionManager` already force-persists it the
 *    moment it is observed (see `AgentProvider.sessionIdFromLine`), so a client learns it on the
 *    `session` frame that follows.
 *  - `exit` is derived by the daemon from the child's exit code, never from a provider line -- a
 *    provider that claims to be "done" is still running until it exits, and treating its claim as
 *    the end is how a UI ends a run early.
 *  - a line that is not JSON at all is NOT mapped to a text delta. A heuristic "this looks like
 *    prose" rule would be a guess, and a guess that leaks a CLI's incidental chatter (progress
 *    bars, warnings, "press any key") into a UI transcript is worse than a missing line: the line
 *    is still there verbatim on `stdout`. A provider that streams plain text needs an explicit row
 *    in the table below, added on evidence, not inferred by shape.
 *
 * UNRECOGNISED LINES map to nothing: they are still delivered verbatim on `stdout`, so nothing is
 * lost, and a client that renders only the neutral frames simply shows a shorter transcript than
 * one that also reads `stdout`. That asymmetry is intentional and documented.
 */
object EventNormalizer {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * One provider line, in neutral terms.
     *
     *  - [Text] incremental assistant text. A provider that streams one big blob still produces
     *    exactly one [Text] with the whole thing -- the client appends, it does not care.
     *  - [Reasoning] the model's own chain of thought, kept apart from [Text] so a UI never renders
     *    thinking as the answer. Contract 1.23.0.
     *  - [Status] a provider-signalled lifecycle beat that is genuinely user-visible (it is asking
     *    a question, it needs permission, it hit a limit).
     *  - [Tool] a provider tool CALL, carrying the tool's name. Contract 1.25.0. It is an action,
     *    not speech, but the name is what a UI shows ("Running read"), so it is surfaced as its own
     *    line kind rather than dropped.
     *  - [Failure] an error the provider reported about ITSELF, distinct from a non-zero exit.
     */
    sealed interface Line {
        data class Text(val delta: String) : Line
        data class Reasoning(val text: String, val delta: Boolean = false) : Line
        data class Status(val label: String) : Line
        /** Contract 1.25.0: a provider tool call, so a UI can name the tool a run is using now. */
        data class Tool(val name: String, val activity: String? = null, val write: FileWrite? = null, val todos: List<com.superiorsoftllc.agenthq.protocol.todo.TodoItemPayload>? = null, val waitSeconds: Long? = null) : Line
        /**
         * Contract 1.95.0 (1c24fdc4): a tool call ENDED (its result came back). Carries nothing a UI renders; it is
         * what lets the daemon's [RunActivityTracker] move a run from `tool_running` back to `thinking`.
         */
        data object ToolEnd : Line
        /** The harness began compacting its context (claude `system/status: compacting`, pi `compaction_start`). */
        data object CompactionStart : Line
        /** The harness finished compacting (claude `system/compact_boundary` or a `status` that is no longer compacting, pi `compaction_end`). */
        data object CompactionEnd : Line
        data class Failure(val message: String) : Line
        /**
         * BUG-PERM-1: [harness] refused to run [tool] because the run's Tool approval did not allow it (no harness can
         * relay an approval prompt yet). The daemon turns it into a one-line chat notice naming the mode.
         */
        /** [prompted] (PROMPT-4): the owner was asked and did not approve, so the notice must not say "can't prompt". */
        data class Denial(val harness: String, val tool: String, val prompted: Boolean = false) : Line
    }

    /**
     * THE MAPPING, in one table. Every row is a provider line shape and the neutral line(s) it
     * becomes. This table is the contract; the code below is only its implementation.
     *
     * | Provider | Line shape | -> neutral |
     * | --- | --- | --- |
     * | opencode | `{"type":"text","text":"..."}` or `{"type":"text","part":{"text":"..."}}` | `Text` |
     * | opencode | `{"type":"reasoning","text":"..."}` or `{"type":"reasoning","part":{"text":"..."}}` | `Reasoning` (1.23.0) |
     * | opencode | `{"type":"error", ...}` | `Failure` |
     * | opencode | `{"type":"tool","part":{"tool":"bash", …}}` | `Tool("bash")` (1.25.0) |
     * | opencode | a tool part whose `part.state.status` is `completed` or `error` (`opencode run` only reports these) | `Tool(..)` then `ToolEnd` (1.95.0) |
     * | opencode | `{"type":"permission_denied","harness":"opencode","tool":"bash"}` (emitted by the serve adapter on a reject) | `Denial(harness, tool)` (BUG-PERM-2) |
 * | opencode | `{"type":"tool_use","part":{"tool":"bash","state":{"status":"error","error":"The user rejected permission..."}}}` (`opencode run`'s auto-reject), unless marked `agenthqDenialReported` | `Tool("bash")` then `Denial("opencode", "bash")` (BUG-PERM-4) |
     * | claude | `{"type":"assistant","message":{"content":[{"type":"text","text":"..."}]}}` | one `Text` per content block, blocks joined by a newline |
     * | claude | `{"type":"assistant","message":{"content":[{"type":"thinking","thinking":"..."}]}}` | `Reasoning` (1.23.0), in block order beside any `Text` |
     * | claude | `{"type":"assistant","message":{"content":[{"type":"tool_use","name":"Bash"}]}}` | `Tool("Bash")` (1.25.0), in block order beside any `Text` |
     * | claude | `{"type":"user","message":{"content":[{"type":"tool_result", …}]}}` | one `ToolEnd` per `tool_result` block (1.95.0) |
     * | claude | `{"type":"result","subtype":"error_..."}` / `"is_error":true` | `Failure` |
     * | claude | `{"type":"result","subtype":"success"}` | nothing (exit is the daemon's to report) |
     * | claude | `{"type":"result",...,"permission_denials":[{"tool_name":"Bash",...}]}` | one `Denial("claude", name)` per distinct tool, before any `Failure` (BUG-PERM-1) |
     * | pi | `{"type":"message_update","assistantMessageEvent":{"type":"text_delta","delta":"..."}}` | `Text` |
     * | pi | `{"type":"message_update","assistantMessageEvent":{"type":"thinking_delta","delta":"..."}}` | `Reasoning` |
     * | pi | `{"type":"tool_execution_start","toolName":"read","args":{…}}` | `Tool("read")` (1.95.0) |
     * | pi | `{"type":"tool_execution_end","toolCallId":"…", …}` | `ToolEnd` (1.95.0) |
     * | stub | whatever the script says, already neutral | passed through unchanged |
     * | any | a line that is not JSON | **nothing** -- still delivered on `stdout` (see the note below) |
     *
     * The last row is a decision, not an omission: no "this looks like prose" heuristic exists,
     * because a guess that leaks a CLI's incidental chatter into a UI transcript is worse than a
     * missing line. A provider that streams plain text gets an explicit row, added on evidence.
     */
    fun normalise(line: String, afterInterrupt: Boolean = false): List<Line> {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return emptyList()
        val obj = runCatching { json.parseToJsonElement(trimmed) as? JsonObject }.getOrNull()
            ?: return emptyList()
        val type = (obj["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.lowercase()
            ?: return emptyList()

        return when (type) {
            // --- opencode: the Phase 1 provider -----------------------------------------------
            "text" -> textOf(obj).let { if (it.isNullOrEmpty()) emptyList() else listOf(Line.Text(it)) }

            // Contract 1.23.0: opencode streams the model's reasoning as its own part type. Same
            // location rule as `text` (top-level or under `part`), but a DISTINCT line kind so a UI
            // never renders thinking as the answer.
            "reasoning" -> textOf(obj).let {
                if (it.isNullOrEmpty()) emptyList() else listOf(Line.Reasoning(it))
            }

            "error" -> listOf(Line.Failure(messageOf(obj) ?: "provider reported an error"))

            // Contract 1.25.0: a provider tool CALL, surfaced with the tool's name so a UI can say
            // "Running read". It stays an ACTION (never assistant speech), but the name is the one
            // thing the transcript could not previously carry. claude's `tool_use` block under
            // `assistant` is handled in [assistantLines]; a flat `tool_use` and opencode's `tool`
            // part are handled here. A tool line with no name is dropped: there is nothing to show.
            // BUG-PERM-4: `opencode run` auto-rejects an ask and reports it ONLY as the tool part's error, so that line also
            // carries the denial notice. The serve adapter reports its own rejects (`permission_denied`) and marks the
            // forwarded part, so a serve reject is never announced twice.
            // 1.95.0: a part that is already `completed` / `error` is a call that started AND ended, so it also ends.
            // A running tool part (serve adapter's `tool_start`): the call began, nothing more is known.
            "tool_start" -> toolNameOf(obj)?.let { listOf(Line.Tool(it, toolActivityOf(obj))) } ?: emptyList()

            "tool_use", "tool" -> toolNameOf(obj)?.let { name ->
                listOf(Line.Tool(name, toolActivityOf(obj), if (isOpenCodeFailedCall(obj)) null else fileWriteOf(name, obj), todoListOf(name, obj))) +
                    (if (isOpenCodeRejection(obj)) listOf(Line.Denial("opencode", name)) else emptyList()) +
                    (if (isOpenCodeEndedCall(obj)) listOf(Line.ToolEnd) else emptyList()) +
                    // This line repeats a start already counted by `tool_start`: close that one too.
                    (if (isOpenCodeEndedCall(obj) && (obj[OPENCODE_START_REPORTED] as? JsonPrimitive)?.content == "true") listOf(Line.ToolEnd) else emptyList())
            } ?: emptyList()

            // 1.95.0: pi reports a tool's real execution start and end on their own records.
            "tool_execution_start" -> (obj["toolName"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf(String::isNotBlank)?.let { name ->
                listOf(Line.Tool(name, (obj["args"] as? JsonObject)?.let { toolActivityOf(JsonObject(mapOf("input" to it))) }, todos = com.superiorsoftllc.agenthq.protocol.todo.TodoAdapters.parse(name, obj["args"] as? JsonObject), waitSeconds = waitSecondsOf(name, obj["args"] as? JsonObject, claudeTimeoutMs = false)))
            } ?: emptyList()
            "tool_execution_end" -> listOf(Line.ToolEnd)

            // BUG-PERM-2: the opencode serve adapter's own line for a permission it rejected.
            "permission_denied" -> {
                val tool = (obj["tool"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf(String::isNotBlank) ?: "unknown"
                val harness = (obj["harness"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf(String::isNotBlank) ?: "opencode"
                val prompted = (obj["prompted"] as? JsonPrimitive)?.content == "true"
                listOf(Line.Denial(harness, tool, prompted))
            }

            // --- pi: streams tokens as message_update { assistantMessageEvent: {type, delta} } ----
            // Only the two real delta kinds map. `text_end` / `thinking_end` repeat what was already
            // streamed, so mapping them would double the reply.
            "message_update" -> piUpdateLines(obj)

            // --- claude: the Phase 2 provider ------------------------------------------------
            "assistant" -> assistantLines(obj)

            // BUG-M2-11c-INTERRUPT: claude answers an owner interrupt with `error_during_execution`.
            // The turn already ends INTERRUPTED; an ERROR frame on top would blame the owner's stop.
            "result" -> claudeDenials(obj) + if (afterInterrupt && subtypeOf(obj) == "error_during_execution") {
                emptyList()
            } else if (resultIsError(obj)) {
                listOf(Line.Failure(messageOf(obj) ?: "provider reported an error"))
            } else {
                // Success is reported by the daemon, from the exit code. Not mapped twice.
                emptyList()
            }

            // A claude "user" frame is tool output echoed back; it is not the agent speaking. 1.95.0: each
            // `tool_result` it carries ends one tool call.
            "user" -> List(toolResultCount(obj)) { Line.ToolEnd }
            "tool_result" -> listOf(Line.ToolEnd)

            "compaction_start" -> listOf(Line.CompactionStart)
            "compaction_end" -> listOf(Line.CompactionEnd)
            "system" -> when (subtypeOf(obj)) {
                "compact_boundary" -> listOf(Line.CompactionEnd)
                "status" -> if ((obj["status"] as? JsonPrimitive)?.takeIf { it.isString }?.content == "compacting") listOf(Line.CompactionStart) else listOf(Line.CompactionEnd)
                else -> emptyList()
            }

            "step_start", "step_finish" -> emptyList()

            else -> emptyList()
        }
    }

    /**
     * BUG-PERM-4: an opencode tool part whose call the user (here: `opencode run`'s non-interactive auto-reject) refused,
     * verified against opencode 1.18.35: `part.state = {"status":"error","error":"The user rejected permission to use this
     * specific tool call."}`. A part already marked [OPENCODE_DENIAL_REPORTED] was announced by the serve adapter.
     */
    private fun isOpenCodeRejection(obj: JsonObject): Boolean {
        if ((obj[OPENCODE_DENIAL_REPORTED] as? JsonPrimitive)?.content == "true") return false
        val state = ((obj["part"] as? JsonObject)?.get("state") as? JsonObject) ?: return false
        val status = (state["status"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val error = (state["error"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return false
        return status == "error" && error.startsWith(OPENCODE_REJECTION_PREFIX)
    }

    /** The start of opencode's error text for a refused permission ask (`PermissionNext.RejectedError`). */
    const val OPENCODE_REJECTION_PREFIX = "The user rejected permission"

    /** Set (`true`) by the serve adapter on a forwarded tool part whose reject it already reported. */
    const val OPENCODE_DENIAL_REPORTED = "agenthqDenialReported"

    /** Set (`true`) by the serve adapter on a finished tool part whose running start it already forwarded as `tool_start`. */
    const val OPENCODE_START_REPORTED = "agenthqStartReported"

    /** BUG-PERM-1: claude's `result.permission_denials`, one [Line.Denial] per distinct tool name. */
    private fun claudeDenials(obj: JsonObject): List<Line> =
        (obj["permission_denials"] as? JsonArray).orEmpty()
            .mapNotNull { ((it as? JsonObject)?.get("tool_name") as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.takeIf(String::isNotBlank) }
            .distinct()
            .map { Line.Denial("claude", it) }

    /** pi's `message_update`: `text_delta` -> [Line.Text], `thinking_delta` -> [Line.Reasoning]. */
    private fun piUpdateLines(obj: JsonObject): List<Line> {
        val inner = obj["assistantMessageEvent"] as? JsonObject ?: return emptyList()
        val kind = (inner["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val delta = (inner["delta"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (delta.isNullOrEmpty()) return emptyList()
        return when (kind) {
            "text_delta" -> listOf(Line.Text(delta))
            "thinking_delta" -> listOf(Line.Reasoning(delta, delta = true))
            else -> emptyList()
        }
    }

    /** opencode puts the text either at the top level or under `part`. */
    private fun textOf(obj: JsonObject): String? {
        (obj["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { return it }
        val part = obj["part"] as? JsonObject
        return (part?.get("text") as? JsonPrimitive)?.takeIf { it.isString }?.content
    }

    /**
     * The tool's name from a tool line, in the shapes the two providers use:
     *  - claude's `tool_use` block (and a flat `tool_use` frame) names it at `name`;
     *  - opencode names the call on the part, at `part.tool`.
     * Returns null when no non-blank name is present, so a nameless tool line is dropped.
     */
    private fun toolNameOf(obj: JsonObject): String? {
        fun nonBlank(o: JsonObject?, key: String): String? =
            (o?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

        nonBlank(obj, "name")?.let { return it }
        val part = obj["part"] as? JsonObject
        nonBlank(part, "tool")?.let { return it }
        nonBlank(part, "name")?.let { return it }
        return nonBlank(obj, "tool")
    }

    /**
     * The seconds a shell tool call only waits (`sleep N` ...), read from its own `command` / `timeout` /
     * `run_in_background` arguments by [SleepHint]; null for anything else. claude's `timeout` is in ms, pi's in seconds.
     */
    private fun waitSecondsOf(name: String, input: JsonObject?, claudeTimeoutMs: Boolean): Long? {
        input ?: return null
        val command = (input["command"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val background = (input["run_in_background"] as? JsonPrimitive)?.content == "true"
        val timeout = (input["timeout"] as? JsonPrimitive)?.content?.toDoubleOrNull()?.let { if (claudeTimeoutMs) it / 1000 else it }?.toLong()
        return com.superiorsoftllc.agenthq.session.SleepHint.parse(name, command, background, timeout)
    }

    /**
     * Contract 1.26.0: the tool call's salient ARGUMENT -- the path being searched, the command being
     * run -- so a UI can say what a run is DOING, not merely name the tool.
     *
     * PRIVACY IS THE DESIGN: only a small allow-list of argument keys ([ACTIVITY_ARG_KEYS]) is read,
     * only their scalar string value, and the result is clamped to [ACTIVITY_MAX] characters. The whole
     * input map is never surfaced, file CONTENTS are never read (a `content` / `body` / `patch` key is
     * not on the list), and an argument-less tool yields `null` rather than a fabricated line.
     *
     * The arguments live under `input` at one of three places, in the shapes the providers use:
     *  - claude's `tool_use` block carries `input` directly on the block;
     *  - opencode's `tool` part carries it at `part.state.input` (its current shape) or `part.input`.
     */
    private fun toolActivityOf(obj: JsonObject): String? {
        val part = obj["part"] as? JsonObject
        val state = part?.get("state") as? JsonObject
        val inputs = listOfNotNull(
            obj["input"] as? JsonObject,
            part?.get("input") as? JsonObject,
            state?.get("input") as? JsonObject,
        )
        for (input in inputs) {
            for (key in ACTIVITY_ARG_KEYS) {
                val value = (input[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?.takeIf { it.isNotBlank() } ?: continue
                return redactActivity(value)
            }
        }
        return null
    }

    /**
     * The salient argument keys, in the order one is chosen over the next. `path` and `file_path` come
     * BEFORE `pattern` so a search reads "src/auth" (where it is looking) rather than the string it is
     * matching: the owner asked for what a run is searching, not a copy of the needle.
     */
    private val ACTIVITY_ARG_KEYS = listOf(
        "command", "file_path", "filePath", "path", "pattern", "url", "query",
    )

    /** Contract 1.80.0: the cap on each text a [FileWrite] carries, so one huge write cannot flood a frame. */
    const val WRITE_MAX_CHARS = 256_000

    private val WRITE_TOOLS = setOf("write")
    private val EDIT_TOOLS = setOf("edit", "multiedit")
    private val PATCH_TOOLS = setOf("patch", "apply_patch")

    /** An opencode tool part whose call ERRORED (rejected, failed): it wrote nothing, so it carries no [FileWrite]. */
    /** 1.95.0: an opencode tool part that has finished (`completed`) or failed (`error`). */
    private fun isOpenCodeEndedCall(obj: JsonObject): Boolean =
        (((obj["part"] as? JsonObject)?.get("state") as? JsonObject)?.get("status") as? JsonPrimitive)?.content.let { it == "completed" || it == "error" }

    /** 1.95.0: how many `tool_result` blocks a claude `user` frame carries. */
    private fun toolResultCount(obj: JsonObject): Int =
        ((obj["message"] as? JsonObject)?.get("content") as? JsonArray).orEmpty()
            .count { ((it as? JsonObject)?.get("type") as? JsonPrimitive)?.content == "tool_result" }

    private fun isOpenCodeFailedCall(obj: JsonObject): Boolean =
        (((obj["part"] as? JsonObject)?.get("state") as? JsonObject)?.get("status") as? JsonPrimitive)?.content == "error"

    /**
     * Contract 1.80.0 (UX-WRITE-1): what a file-writing tool call wrote, or null for any other tool. Chosen by the tool's
     * NAME (case-insensitive: claude's `Write` / `Edit` / `MultiEdit`, opencode's `write` / `edit` / `multiedit` /
     * `patch` / `apply_patch`), then read from the input in the shape that tool uses. Only the agent's OWN output is read
     * here -- the content it is writing -- never a file it reads. A call whose input does not have that shape yields null.
     */
    /** Contract 1.127.0 (TODO-OVERLAY-1): the to-do list a to-do tool call carries, or null for any other call. Only the items are kept. */
    internal fun todoListOf(toolName: String, obj: JsonObject): List<com.superiorsoftllc.agenthq.protocol.todo.TodoItemPayload>? {
        val part = obj["part"] as? JsonObject
        val state = part?.get("state") as? JsonObject
        val input = listOfNotNull(obj["input"] as? JsonObject, state?.get("input") as? JsonObject, part?.get("input") as? JsonObject)
            .firstOrNull() ?: return null
        return com.superiorsoftllc.agenthq.protocol.todo.TodoAdapters.parse(toolName, input)
    }

    internal fun fileWriteOf(toolName: String, obj: JsonObject): FileWrite? {
        val name = toolName.lowercase()
        if (name !in WRITE_TOOLS && name !in EDIT_TOOLS && name !in PATCH_TOOLS) return null
        val part = obj["part"] as? JsonObject
        val state = part?.get("state") as? JsonObject
        val input = listOfNotNull(obj["input"] as? JsonObject, state?.get("input") as? JsonObject, part?.get("input") as? JsonObject)
            .firstOrNull() ?: return null
        fun str(o: JsonObject, vararg keys: String): String? =
            keys.firstNotNullOfOrNull { k -> (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content }
        var cut = false
        fun capped(text: String): String = if (text.length <= WRITE_MAX_CHARS) text else { cut = true; text.take(WRITE_MAX_CHARS) }
        val path = str(input, "file_path", "filePath", "path")?.takeIf { it.isNotBlank() }
        return when (name) {
            in WRITE_TOOLS -> {
                val content = str(input, "content") ?: return null
                FileWrite(path ?: return null, content = capped(content)).copy(truncated = cut)
            }
            in EDIT_TOOLS -> {
                val single = str(input, "old_string", "oldString", "oldText")?.let { old ->
                    str(input, "new_string", "newString", "newText")?.let { FileEdit(old, it) }
                }
                val many = (input["edits"] as? JsonArray)?.mapNotNull { e ->
                    val o = e as? JsonObject ?: return@mapNotNull null
                    val old = str(o, "old_string", "oldString", "oldText") ?: return@mapNotNull null
                    val new = str(o, "new_string", "newString", "newText") ?: return@mapNotNull null
                    FileEdit(old, new)
                }.orEmpty()
                val edits = (listOfNotNull(single) + many).map { FileEdit(capped(it.oldText), capped(it.newText)) }
                if (edits.isEmpty()) null else FileWrite(path ?: return null, edits = edits).copy(truncated = cut)
            }
            else -> {
                val patch = str(input, "patchText", "patch", "input")?.takeIf { it.isNotBlank() } ?: return null
                FileWrite(path ?: patchPathOf(patch) ?: "patch", patch = capped(patch)).copy(truncated = cut)
            }
        }
    }

    private val PATCH_FILE = Regex("""^(?:\*\*\* (?:Update|Add|Delete) File: |\+\+\+ (?:b/)?)(.+)$""", RegexOption.MULTILINE)

    /** The first file a patch names (`*** Update File: x`, `*** Add File: x`, or a unified `+++ b/x`). */
    private fun patchPathOf(patch: String): String? =
        PATCH_FILE.find(patch)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() && it != "/dev/null" }

    /** Cap on the activity argument, so a runaway command cannot flood the status line. */
    private const val ACTIVITY_MAX = 100

    /** One line, no control characters, clamped to [ACTIVITY_MAX] with a trailing ellipsis when cut. */
    private fun redactActivity(raw: String): String {
        val oneLine = raw.replace('\n', ' ').replace('\r', ' ').trim()
        return if (oneLine.length <= ACTIVITY_MAX) oneLine else oneLine.take(ACTIVITY_MAX) + "…"
    }

    /**
     * claude's assistant turn: a list of content blocks, of which `text` blocks are speech and
     * `thinking` blocks (contract 1.23.0) are the model's reasoning. Both are surfaced, as DIFFERENT
     * line kinds, in the order the blocks appear; any other block type is not speech and is dropped.
     *
     * Contiguous `text` blocks are joined by a newline rather than concatenated, so a thinking block
     * followed by an answer does not read as one run-on sentence -- the pre-1.23.0 behaviour for
     * text blocks, unchanged.
     */
    private fun assistantLines(obj: JsonObject): List<Line> {
        val message = obj["message"] as? JsonObject ?: return emptyList()
        val content = message["content"]
        val blocks: List<JsonObject> = when (content) {
            is JsonArray -> content.mapNotNull { it as? JsonObject }
            is JsonObject -> listOf(content)
            else -> emptyList()
        }
        val lines = mutableListOf<Line>()
        val text = StringBuilder()
        fun flushText() {
            if (text.isNotEmpty()) {
                lines += Line.Text(text.toString())
                text.clear()
            }
        }
        for (block in blocks) {
            val blockType = (block["type"] as? JsonPrimitive)?.content
            when (blockType) {
                "text" -> (block["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?.takeIf { it.isNotEmpty() }
                    ?.let {
                        if (text.isNotEmpty()) text.append('\n')
                        text.append(it)
                    }

                "thinking" -> (block["thinking"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { thought ->
                        flushText()
                        lines += Line.Reasoning(thought)
                    }

                // Contract 1.25.0: a tool call inside an assistant turn, carrying the tool's name.
                // Flushed beside any text so order is preserved and never run together with speech.
                "tool_use" -> (block["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?.takeIf { it.isNotBlank() }
                    ?.let { name ->
                        flushText()
                        lines += Line.Tool(name, toolActivityOf(block), fileWriteOf(name, block), todoListOf(name, block), waitSecondsOf(name, block["input"] as? JsonObject, claudeTimeoutMs = true))
                    }

                else -> Unit
            }
        }
        flushText()
        return lines
    }

    private fun subtypeOf(obj: JsonObject): String? =
        (obj["subtype"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.lowercase()

    /** claude marks a failed turn either by `subtype` or by an explicit `is_error`. */
    private fun resultIsError(obj: JsonObject): Boolean {
        val subtype = (obj["subtype"] as? JsonPrimitive)
            ?.takeIf { it.isString }?.content?.lowercase()
        if (subtype != null && subtype.startsWith("error")) return true
        val flag = obj["is_error"]
        return flag is JsonPrimitive && (flag.content == "true" || flag.content == "1")
    }

    /** A provider-reported error message: `error` (object or string) or `message`. */
    private fun messageOf(obj: JsonObject): String? {
        val error = obj["error"]
        when (error) {
            is JsonPrimitive -> if (error.isString && error.content.isNotBlank()) return error.content
            is JsonObject -> (error["message"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?.let { if (it.isNotBlank()) return it }
            else -> Unit
        }
        return (obj["message"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?.takeIf { it.isNotBlank() }
    }
}
