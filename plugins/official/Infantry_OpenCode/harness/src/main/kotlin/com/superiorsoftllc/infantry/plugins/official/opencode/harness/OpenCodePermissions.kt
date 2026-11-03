package com.superiorsoftllc.infantry.plugins.official.opencode.harness

import com.superiorsoftllc.infantry.harness.api.ApprovalReply
import com.superiorsoftllc.infantry.harness.api.HarnessProcess
import com.superiorsoftllc.infantry.harness.api.ToolApproval
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * BUG-PERM-2: how opencode honours the run's [ToolApproval], on both paths. Verified against opencode 1.18.34.
 *
 *  - `opencode serve`: each `permission.asked` / `permission.updated` is answered with [serveReply]. The serve API's
 *    `PermissionReplyBody.reply` takes `once` | `always` | `reject`. An allowed call is answered `once`, never `always`:
 *    `always` makes opencode remember the approval for the rest of the session, so a later switch back to `deny` would
 *    not take effect on a live server.
 *  - `opencode run`: the smallest mechanism per mode. `allowAll` is the CLI's own `--auto` flag ("auto-approve permissions
 *    that are not explicitly denied"); `allowEdits` has no flag, so it is an inline `OPENCODE_CONFIG_CONTENT` permission
 *    block that allows `edit` (opencode's one permission for edit / write / patch); `deny` adds nothing, and a
 *    non-interactive `opencode run` rejects every ask, as before.
 *  - BUG-PERM-5: in EVERY mode, on both paths, bash `infantry *` is allowed (Infantry's own CLI): `run` and `serve` get
 *    it merged into `OPENCODE_CONFIG_CONTENT` ([mergeAllowInfantry]), and the serve adapter also answers `once` to an ask
 *    whose every bash pattern [isInfantryCommand]. Nothing else is widened.
 */
object OpenCodePermissions {
    /** opencode reads an inline JSON config from here, merged over the user's config files. */
    const val CONFIG_CONTENT_ENV = "OPENCODE_CONFIG_CONTENT"

    /** The inline config `allowEdits` sets for `opencode run`. */
    const val ALLOW_EDITS_CONFIG = """{"permission":{"edit":"allow"}}"""

    /**
     * The permission names that are file edits. opencode 1.x asks `edit` for every file-changing tool; the tool names are
     * kept too, for a build (or an older event shape) that names the tool instead.
     */
    val EDIT_PERMISSIONS: Set<String> = setOf("edit", "write", "patch", "multiedit", "apply_patch")

    fun isEdit(permission: String?): Boolean = permission?.trim()?.lowercase() in EDIT_PERMISSIONS

    /** BUG-PERM-5: the opencode bash pattern for Infantry's own CLI, allowed in every mode. */
    const val INFANTRY_BASH_PATTERN = "infantry *"

    /** Shell metacharacters that could chain, redirect or substitute a second command onto an `infantry` one. */
    private val CHAINING = Regex("""[;&|`<>\r\n]|\$\(""")

    /** BUG-PERM-5: [command] is a plain `infantry` invocation (`infantry` or `infantry <args>`, nothing chained onto it). */
    fun isInfantryCommand(command: String?): Boolean {
        val c = command?.trim() ?: return false
        return (c == "infantry" || c.startsWith("infantry ")) && !CHAINING.containsMatchIn(c)
    }

    /** Whether [mode] lets a call needing [permission] run. */
    fun allows(mode: ToolApproval, permission: String?): Boolean = when (mode) {
        // PROMPT-1: a path with no prompt relay (`opencode run`) treats `ask` as `deny`; serve asks the owner (PROMPT-4).
        ToolApproval.DENY, ToolApproval.ASK -> false
        ToolApproval.ALLOW_EDITS -> isEdit(permission)
        ToolApproval.ALLOW_ALL -> true
    }

    /**
     * PROMPT-4: the permissions `ask` mode makes opencode ASK about (so `opencode serve` emits `permission.asked`), set to
     * `"ask"` over whatever the inherited config says. Reads (read / glob / grep / list ...) are not gated.
     */
    val ASK_PERMISSIONS: List<String> = listOf("edit", "bash", "webfetch", "websearch")

    /** PROMPT-4: the owner's answer as the serve API's reply: once / always / reject. */
    fun serveReply(decision: ApprovalReply): String = when (decision) {
        ApprovalReply.ALLOW_ONCE -> "once"
        ApprovalReply.ALLOW_SESSION, ApprovalReply.ALLOW_ALWAYS -> "always"
        ApprovalReply.DENY -> "reject"
    }

    /** PROMPT-4: the full `POST /permission/{id}/reply` body for the owner's [decision]. */
    fun serveReplyBody(decision: ApprovalReply): String = buildJsonObject { put("reply", serveReply(decision)) }.toString()

    /**
     * The inline `OPENCODE_CONFIG_CONTENT` an `opencode serve` child gets under [mode]: [inherited] with the infantry allow
     * (BUG-PERM-5) and, in `ask` mode, the [ASK_PERMISSIONS] set to `ask` (PROMPT-4). Merged, never overwritten (PERM-4).
     */
    fun serveConfig(mode: ToolApproval, inherited: String?, mcp: HarnessProcess? = null): String =
        withInfantryMcp(mergeAllowInfantry(if (mode == ToolApproval.ASK) mergeAskRules(inherited) else inherited), mcp)

    /**
     * MCP-1: [config] with the session's own `infantry mcp serve` ([mcp], from the host's mcpLaunch; null = none) as `mcp.infantry` and its tools
     * allowed (`permission."infantry_*"`), in every mode. Merged, never overwritten ([OpenCodeConfig.mergeMcp]).
     */
    fun withInfantryMcp(config: String, mcp: HarnessProcess?): String =
        if (mcp == null) config else OpenCodeConfig.mergeMcp(config, mcp)

    /**
     * PROMPT-4: [existing] with every [ASK_PERMISSIONS] key set to `"ask"`, every other key kept. A `permission` given as
     * one string becomes `{"*": <it>, ...}`. Blank / unparsable input yields the ask rules alone. A `bash` object is
     * replaced by `"ask"`: [mergeAllowInfantry] then turns it into `{"*": "ask", "infantry *": "allow"}`.
     */
    fun mergeAskRules(existing: String?): String {
        val root = existing?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
            ?: JsonObject(emptyMap())
        val base: Map<String, JsonElement> = when (val p = root["permission"]) {
            is JsonObject -> p
            is JsonPrimitive -> mapOf("*" to p)
            else -> emptyMap()
        }
        val ask = JsonPrimitive("ask")
        val permission = JsonObject((base - ASK_PERMISSIONS.toSet()) + ASK_PERMISSIONS.map { it to ask })
        return JsonObject(root + ("permission" to permission)).toString()
    }

    /** The `reply` value for `POST /permission/{id}/reply`. */
    fun serveReply(mode: ToolApproval, permission: String?): String = if (allows(mode, permission)) "once" else "reject"

    /** The full `POST /permission/{id}/reply` body. */
    fun serveReplyBody(mode: ToolApproval, permission: String?): String =
        buildJsonObject { put("reply", serveReply(mode, permission)) }.toString()

    /** Extra `opencode run` argv for [mode]. */
    fun runFlags(mode: ToolApproval): List<String> = if (mode == ToolApproval.ALLOW_ALL) listOf("--auto") else emptyList()

    /**
     * Extra `opencode run` environment for [mode]. [existing] is the `OPENCODE_CONFIG_CONTENT` the child would otherwise
     * inherit (the daemon's own environment): `allowEdits` MERGES its `edit: allow` into it instead of replacing it
     * (BUG-PERM-4), so a user's inline config survives. Other modes leave the variable alone.
     */
    fun runEnv(
        mode: ToolApproval,
        existing: String? = System.getenv(CONFIG_CONTENT_ENV),
        mcp: HarnessProcess? = null,
    ): Map<String, String> =
        mapOf(CONFIG_CONTENT_ENV to withInfantryMcp(mergeAllowInfantry(if (mode == ToolApproval.ALLOW_EDITS) mergeAllowEdits(existing) else existing), mcp))

    /**
     * BUG-PERM-5: [existing] with `permission.bash."infantry *"` set to `allow`, every other key kept (merged, never
     * overwritten, same rule as PERM-4). A `bash` given as one string becomes `{"*": <it>, "infantry *": "allow"}`; a
     * `permission` given as one string becomes `{"*": <it>, "bash": {"*": <it>, "infantry *": "allow"}}`. The infantry rule
     * goes LAST, because opencode resolves the last matching pattern. Blank / unparsable input yields the rule alone.
     */
    fun mergeAllowInfantry(existing: String?): String {
        val root = existing?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
            ?: JsonObject(emptyMap())
        val allow = INFANTRY_BASH_PATTERN to JsonPrimitive("allow")
        fun bashRules(current: JsonElement?): JsonObject = when (current) {
            is JsonObject -> JsonObject((current - INFANTRY_BASH_PATTERN) + allow)
            is JsonPrimitive -> JsonObject(mapOf("*" to current, allow))
            else -> JsonObject(mapOf(allow))
        }
        val permission = when (val p = root["permission"]) {
            is JsonObject -> JsonObject((p - "bash") + ("bash" to bashRules(p["bash"])))
            is JsonPrimitive -> JsonObject(mapOf("*" to p, "bash" to bashRules(p)))
            else -> JsonObject(mapOf("bash" to bashRules(null)))
        }
        return JsonObject(root + ("permission" to permission)).toString()
    }

    /**
     * [existing] with `permission.edit` set to `allow`, every other key kept. A `permission` given as a single string (the
     * shorthand for every tool) becomes `{"*": <it>, "edit": "allow"}`; opencode 1.18 resolves `*` as the fallback rule.
     * Blank, unparsable or non-object input yields [ALLOW_EDITS_CONFIG]: opencode could not have read it either.
     */
    fun mergeAllowEdits(existing: String?): String {
        val root = existing?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
            ?: return ALLOW_EDITS_CONFIG
        val permission = when (val p = root["permission"]) {
            is JsonObject -> JsonObject(p + ("edit" to JsonPrimitive("allow")))
            is JsonPrimitive -> buildJsonObject { put("*", p); put("edit", "allow") }
            else -> buildJsonObject { put("edit", "allow") }
        }
        return JsonObject(root + ("permission" to permission)).toString()
    }

    /**
     * The stdout line the serve adapter emits when it rejects a call: the daemon's normaliser reads it as
     * `EventNormalizer.Line.Denial("opencode", tool)`, i.e. the BUG-PERM-1 chat notice.
     */
    fun denialLine(sessionId: String, tool: String, prompted: Boolean = false): String = buildJsonObject {
        put("type", DENIAL_TYPE)
        // PROMPT-4: the owner was asked and did not approve (deny, timeout, session end): not "can't prompt".
        if (prompted) put(DENIAL_PROMPTED, true)
        put("harness", "opencode")
        put("tool", tool)
        put("sessionID", sessionId)
        put("timestamp", System.currentTimeMillis())
    }.toString()

    /** PROMPT-4: the [denialLine] field saying the owner was asked. */
    const val DENIAL_PROMPTED = "prompted"

    /** The line type of [denialLine]. */
    const val DENIAL_TYPE = "permission_denied"
}
