// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.harness.descriptor

import com.superiorsoftllc.infantry.harness.HarnessCapability
import com.superiorsoftllc.infantry.harness.HarnessCommand
import com.superiorsoftllc.infantry.harness.HarnessRequest
import com.superiorsoftllc.infantry.harness.ToolApproval
import com.superiorsoftllc.infantry.runner.ProcessSpec
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * HARNESS-DESC-1 - one entry of a harness descriptor file, validated. A descriptor registers a simple CLI harness
 * with NO code: what to run ([command] + [args] template), what it can do ([capabilities]), how to read its stdout
 * ([output]) and what a user may type at a `/` prompt ([commands], plain [HarnessCommand] data).
 *
 * ```json
 * { "id": "echo-cli", "name": "Echo CLI", "command": "echo-cli",
 *   "args": ["--quiet", ["--model", "{model}"], ["--resume", "{sessionId}"], ["--effort", "{effort}"]],
 *   "capabilities": ["MODEL_SELECTION", "SESSION_RESUME", "USAGE"],
 *   "output": { "format": "jsonl", "map": [
 *       {"match": {"kind": "hello"}, "event": "session", "fields": {"id": "sid"}},
 *       {"match": {"kind": "say"},   "event": "text",    "fields": {"text": "text"}} ] },
 *   "commands": [ {"name": "model", "description": "Switch the model", "action": "SET_MODEL", "argument": "<model>"} ] }
 * ```
 *
 * ARGS TEMPLATE. Each element is a string or an array of strings (a GROUP). `{prompt}`, `{model}`, `{sessionId}` and
 * `{effort}` are filled from the request in ONE pass (a value is never expanded again) and the result is an argv list,
 * never a shell line. A string or group that names a placeholder the request has no value for is dropped whole, so
 * `["--model", "{model}"]` appears only when a model was asked for. The prompt is also written to stdin, as for every
 * harness (see [com.superiorsoftllc.infantry.harness.CliHarness]).
 *
 * OUTPUT. `text`: every stdout line is a text delta. `jsonl`: each stdout line that is a JSON object is tested
 * against the `map` rules (see [FieldRule]). `claude` / `pi`: the stream the shipped Claude / Pi translators read, reused
 * as they are for a CLI that speaks the same stream.
 *
 * TOOL APPROVAL (BUG-PERM-3, optional). `"toolApproval": {"deny": [...], "allowEdits": [...], "allowAll": [...]}` gives
 * the argv each [ToolApproval] mode adds, as plain tokens (no placeholders). They go where a bare `"{toolApproval}"`
 * element sits in `args` (so a CLI with a subcommand can take them after it), else after the whole template. A mode
 * the map does not name adds nothing, and neither does any mode when there is no map: that is today's behaviour, and
 * the harness logs it as unmapped (see [unmappedToolApproval]). Audited mappings for CLIs a descriptor may wrap:
 *
 * | CLI | non-interactive behaviour found | deny | allowEdits | allowAll |
 * | --- | --- | --- | --- | --- |
 * | codex 0.162 (`codex exec`) | never asks; default sandbox read-only, a blocked command just fails | `[]` (its config.toml decides) | `["--sandbox","workspace-write"]` | `["--dangerously-bypass-approvals-and-sandbox"]` |
 * | gemini (`gemini -p`) | never asks; tools that need approval are not run | `[]` (its settings decide) | `["--approval-mode","auto_edit"]` | `["--approval-mode","yolo"]` |
 *
 * DENIAL NOTICE: neither CLI marks a refused call in its stream (codex reports a sandbox-blocked command as an ordinary
 * failed command; gemini's are simply not run), so a descriptor harness emits no `EventNormalizer.Line.Denial`. The
 * gemini row is from its documentation, not from a live run (gemini is not installed on the audit machine).
 *
 * MODELS (ROLE-FIRST S5, optional). `"models": {"list": [{"provider", "id", "label"?}]}` or
 * `"models": {"modelsCommand": [argv]}` -- the harness's rows in the `models` frame. See [DescriptorModels].
 */
class HarnessDescriptor internal constructor(
    val id: String,
    val name: String,
    val command: String,
    internal val args: List<List<String>>,
    val capabilities: Set<HarnessCapability>,
    val output: DescriptorOutput,
    val commands: List<HarnessCommand>,
    /** ROLE-FIRST S5: the optional `models` section (see [DescriptorModels]); null = this harness lists no models. */
    val models: DescriptorModels? = null,
    /** BUG-PERM-3: the argv each Tool approval mode adds; null = the descriptor declares none (see the class KDoc). */
    internal val toolApproval: Map<ToolApproval, List<String>>? = null,
    /** FIRST-RUN-KEYS-1: `"credential": {"env": "X_API_KEY", "label"?, "help"?}`; null = the CLI needs no key from us. */
    val credential: com.superiorsoftllc.infantry.harness.credential.HarnessCredential? = null,
) {
    /** True when [mode] adds nothing because the descriptor does not map it: the run keeps today's behaviour. */
    fun unmappedToolApproval(mode: ToolApproval): Boolean = toolApproval?.containsKey(mode.withoutPrompt()) != true

    /** The process for [request]: the command, then the template with this request's values. */
    internal fun spec(request: HarnessRequest): ProcessSpec {
        val values = mapOf(
            "prompt" to request.prompt,
            "model" to request.model,
            "sessionId" to request.sessionId,
            "effort" to request.effort,
        )
        val approvalArgs = toolApproval?.get(request.toolApproval.withoutPrompt()).orEmpty() // PROMPT-1: a descriptor cannot prompt; ask = deny
        var spliced = false
        val expanded = args.flatMap { group ->
            if (group === TOOL_APPROVAL_SLOT) {
                spliced = true
                return@flatMap approvalArgs
            }
            val tokens = group.map { expand(it, values) }
            if (tokens.any { it == null }) emptyList() else tokens.filterNotNull()
        }
        return ProcessSpec(listOf(command) + expanded + if (spliced) emptyList() else approvalArgs)
    }

    /** [token] with its placeholders filled in one pass, or null when one of them has no value. */
    private fun expand(token: String, values: Map<String, String?>): String? {
        val names = PLACEHOLDER.findAll(token).map { it.groupValues[1] }.toList()
        if (names.any { values[it] == null }) return null
        return PLACEHOLDER.replace(token) { values.getValue(it.groupValues[1])!! }
    }

    companion object {
        private const val ID_PATTERN = "[a-z0-9][a-z0-9._-]{0,63}"
        private val ID = Regex(ID_PATTERN)
        private val PLACEHOLDER = Regex("\\{(prompt|model|sessionId|effort)\\}")
        private val ANY_PLACEHOLDER = Regex("\\{([A-Za-z]+)\\}")
        private val json = Json { ignoreUnknownKeys = true }
        private const val TOOL_APPROVAL_MARKER = "{toolApproval}"

        /** The one `args` element that is the Tool approval slot; compared by identity, so no real group can match it. */
        private val TOOL_APPROVAL_SLOT = listOf(TOOL_APPROVAL_MARKER)

        /**
         * Validate one entry. Throws [InvalidDescriptor] with a FIXED reason: nothing read from the file is ever
         * put into the exception, so a note built from it cannot leak the file (the CP-1 rule).
         */
        fun parse(entry: JsonElement): HarnessDescriptor {
            val obj = entry as? JsonObject ?: throw InvalidDescriptor("an entry is not a JSON object")

            val id = obj.text("id")?.takeIf { ID.matches(it) } ?: throw InvalidDescriptor("field 'id' is missing or not a lower-case id")
            val command = obj.text("command")?.takeIf { it.isNotBlank() } ?: throw InvalidDescriptor("field 'command' is missing or blank")
            val name = obj.text("name")?.takeIf { it.isNotBlank() } ?: id
            return HarnessDescriptor(
                id = id,
                name = name,
                command = command,
                args = parseArgs(obj["args"]),
                capabilities = parseCapabilities(obj["capabilities"]),
                output = DescriptorOutput.parse(obj["output"]),
                commands = parseCommands(obj["commands"]),
                models = DescriptorModels.parse(obj["models"]),
                toolApproval = parseToolApproval(obj["toolApproval"]),
                credential = parseCredential(id, obj["credential"]),
            )
        }

        private fun parseCredential(id: String, element: JsonElement?): com.superiorsoftllc.infantry.harness.credential.HarnessCredential? {
            if (element == null) return null
            val o = element as? JsonObject ?: throw InvalidDescriptor("field 'credential' is not an object")
            val env = o.text("env") ?: throw InvalidDescriptor("'credential' needs an 'env' variable name")
            com.superiorsoftllc.infantry.harness.credential.HarnessCredential.envRefusal(env)?.let { throw InvalidDescriptor(it) }
            return com.superiorsoftllc.infantry.harness.credential.HarnessCredential(id, env, o.text("label")?.takeIf { it.isNotBlank() } ?: id, o.text("help").orEmpty())
        }

        private fun parseArgs(element: JsonElement?): List<List<String>> {
            if (element == null) return emptyList()
            val array = element as? JsonArray ?: throw InvalidDescriptor("field 'args' is not an array")
            var slots = 0
            return array.map { item ->
                // BUG-PERM-3: the Tool approval slot is a bare element of its own, never inside a token or a group.
                if (item.string() == TOOL_APPROVAL_MARKER) {
                    if (++slots > 1) throw InvalidDescriptor("'args' holds more than one {toolApproval} slot")
                    return@map TOOL_APPROVAL_SLOT
                }
                val tokens = when (item) {
                    is JsonArray -> item.map { it.string() ?: throw InvalidDescriptor("an 'args' group holds a non-string") }
                    else -> listOf(item.string() ?: throw InvalidDescriptor("an 'args' element is neither a string nor an array"))
                }
                tokens.forEach { token ->
                    if (ANY_PLACEHOLDER.findAll(token).any { it.groupValues[1] !in PLACEHOLDER_NAMES }) {
                        throw InvalidDescriptor("'args' names an unknown placeholder")
                    }
                }
                tokens
            }
        }

        private val PLACEHOLDER_NAMES = setOf("prompt", "model", "sessionId", "effort")

        /** BUG-PERM-3: `{"deny": [..], "allowEdits": [..], "allowAll": [..]}`, every key optional, values plain tokens. */
        private fun parseToolApproval(element: JsonElement?): Map<ToolApproval, List<String>>? {
            if (element == null) return null
            val obj = element as? JsonObject ?: throw InvalidDescriptor("field 'toolApproval' is not an object")
            return obj.entries.associate { (key, value) ->
                val mode = ToolApproval.parse(key) ?: throw InvalidDescriptor("'toolApproval' names an unknown mode")
                val tokens = (value as? JsonArray ?: throw InvalidDescriptor("a 'toolApproval' mode is not an array"))
                    .map { it.string() ?: throw InvalidDescriptor("a 'toolApproval' mode holds a non-string") }
                if (tokens.any { ANY_PLACEHOLDER.containsMatchIn(it) }) throw InvalidDescriptor("'toolApproval' args take no placeholders")
                mode to tokens
            }
        }

        private fun parseCapabilities(element: JsonElement?): Set<HarnessCapability> {
            if (element == null) return emptySet()
            val array = element as? JsonArray ?: throw InvalidDescriptor("field 'capabilities' is not an array")
            return array.map { item ->
                val wanted = item.string()?.uppercase()
                HarnessCapability.entries.firstOrNull { it.name == wanted } ?: throw InvalidDescriptor("'capabilities' names an unknown capability")
            }.toSet()
        }

        private fun parseCommands(element: JsonElement?): List<HarnessCommand> {
            if (element == null) return emptyList()
            // HarnessCommand is plain @Serializable data that validates itself; its own exceptions can quote the file, so none is passed on.
            return try {
                json.decodeFromJsonElement(ListSerializer(HarnessCommand.serializer()), element)
            } catch (e: Exception) {
                throw InvalidDescriptor("field 'commands' holds an invalid command")
            }
        }
    }
}

/** A descriptor entry that cannot be used. [detail] is always a fixed sentence written in this package. */
class InvalidDescriptor internal constructor(val detail: String) : Exception(detail)

internal fun JsonObject.text(key: String): String? = this[key].string()

internal fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
