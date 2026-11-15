// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.cli

import com.superiorsoftllc.infantry.cli.mcp.McpPluginTools
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * ACCOUNT-1: `infantry account status|login <provider>|logout <provider>`. Each is one `infantry_capability_call` of the
 * `account.status` / `account.signIn` / `account.signOut` capability (whichever plugin declares it answers; the CLI knows no
 * provider). The CLI never asks for `account.token`, and [redact] drops any token-like key from what it prints.
 */
internal object AccountCli {
    const val USAGE = "usage: infantry account status | infantry account login <provider> | infantry account logout <provider>"
    private const val CALL_TOOL = "infantry_capability_call"
    private val SECRET_KEY = Regex("token|secret|password|refresh", RegexOption.IGNORE_CASE)

    fun usageError(args: List<String>): String? = when {
        args.size == 1 && args[0] == "status" -> null
        args.size == 2 && (args[0] == "login" || args[0] == "logout") -> null
        else -> USAGE
    }

    fun run(stream: FrameStream, args: List<String>, out: Appendable, err: Appendable, getenv: (String) -> String?): Int = runBlocking {
        val capability = when (args[0]) {
            "login" -> "account.signIn"
            "logout" -> "account.signOut"
            else -> "account.status"
        }
        val call = JsonObject(buildMap {
            put("capability", JsonPrimitive(capability))
            if (args.size > 1) put("args", JsonObject(mapOf("provider" to JsonPrimitive(args[1]))))
        })
        val r = McpPluginTools(getenv).call(stream, CALL_TOOL, call)
        if (r.isError) {
            err.appendLine(r.text.removePrefix("$CALL_TOOL: "))
            return@runBlocking 1
        }
        val parsed = runCatching { Json.parseToJsonElement(r.text) }.getOrNull()
        val shown = (parsed as? JsonObject)?.get("result") ?: parsed
        out.appendLine(if (shown == null) "ok" else redact(shown).toString())
        0
    }

    /** Removes every key that looks like a credential, at any depth. */
    fun redact(e: JsonElement): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.filterKeys { !SECRET_KEY.containsMatchIn(it) }.mapValues { redact(it.value) })
        is JsonArray -> JsonArray(e.map { redact(it) })
        else -> e
    }
}
