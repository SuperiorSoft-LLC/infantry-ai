// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.agents.daemon

import com.superiorsoftllc.infantry.daemon.api.DaemonApi
import com.superiorsoftllc.infantry.daemon.api.DaemonPlugin
import com.superiorsoftllc.infantry.daemon.api.DaemonTool
import com.superiorsoftllc.infantry.daemon.api.ToolCall
import com.superiorsoftllc.infantry.daemon.api.ToolResult
import com.superiorsoftllc.infantry.daemon.api.harness.HarnessCatalogText
import com.superiorsoftllc.infantry.daemon.api.harness.HarnessInstallReport
import com.superiorsoftllc.infantry.daemon.api.harness.HarnessInstallResult
import com.superiorsoftllc.infantry.daemon.api.harness.HarnessInstallText
import com.superiorsoftllc.infantry.daemon.api.harness.ProviderInfo
import com.superiorsoftllc.infantry.daemon.api.harness.ProviderModels
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * RUNTIME-LOAD-1: Infantry_Agents' daemon half. `infantry_harness_status` (HARNESS-INSTALL-1, contract 1.100.0) was built into the
 * CLI's MCP server; now it is one `harness_install_status` frame on the caller's own connection, printed by the API's shared
 * [HarnessInstallText]. Same name, schema, answer and gating (`harness.install.read`). `infantry_harness_install` stays in core: a
 * plugin tool call is cut off after limits.plugins.toolTimeoutMs, and an npm install runs for minutes.
 * RUNTIME-LOAD-2: a tool declares its own [DaemonTool.timeoutMs]; `infantry_harness_install` moved here with it.
 */
class AgentsDaemonPlugin : DaemonPlugin {
    override fun start(api: DaemonApi) {
        api.tools.register(HarnessStatusTool)
        api.tools.register(HarnessInstallTool)
        api.tools.register(ListHarnessesTool)
        // RUNTIME-LOAD-4 (F5): the session's own progress report.
        api.tools.register(ProgressTool)
        // RUNTIME-LOAD-5 (F5 remainder): the session reads, over the host's sessions read model.
        api.tools.register(SessionReadTools.ListSessions(api))
        api.tools.register(SessionReadTools.GetSession(api))
        api.tools.register(SessionReadTools.PodState(api))
        // RUNTIME-LOAD-7 (F5 last): spawn, prompt, kill, restart and remove, over the host spawn service.
        api.tools.register(SpawnTools.Spawn(api))
        api.tools.register(SpawnTools.Prompt(api))
        api.tools.register(SpawnTools.Kill(api))
        api.tools.register(SpawnTools.Restart(api))
        api.tools.register(SpawnTools.Remove(api))
        // AGENT-REGISTRY-1: the agent registry's list / get / enable / disable.
        AgentRegistryTools.all { api.agents }.forEach(api.tools::register)
    }
}

object HarnessStatusTool : DaemonTool {
    const val NAME = "infantry_harness_status"
    /** The 15 minutes an `npm view` per harness may take, as the CLI allowed. */
    private const val CHECK_LATEST_TIMEOUT_MS = 15 * 60_000L
    private const val ANSWER_TIMEOUT_MS = 20_000L
    private val readJson = Json { ignoreUnknownKeys = true }
    private val outJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = true }

    override val name = NAME
    override val description =
        "Which harness CLIs (claude, opencode, pi) are installed on the daemon's machine: state installed / missing / " +
            "outdated, the version, where it runs from, and whether npm is available. checkLatest=true asks npm for the " +
            "latest version too (slower). Install the missing ones with infantry_harness_install."
    override val inputSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("checkLatest") {
                put("type", "boolean")
                put("description", "Also ask npm for each CLI's latest version (default false).")
            }
        }
        put("additionalProperties", false)
    }
    override val annotations = buildJsonObject { put("readOnlyHint", true) }
    /** `checkLatest=true` runs `npm view` per harness; the CLI allowed 15 minutes. */
    override val timeoutMs: Long = CHECK_LATEST_TIMEOUT_MS

    override fun call(call: ToolCall): ToolResult = try {
        val check = (call.arguments["checkLatest"] as? JsonPrimitive)?.contentOrNull == "true"
        val reply = call.request(
            "harness_install_status", if (check) """{"checkLatest":true}""" else "{}",
            timeoutMs = if (check) CHECK_LATEST_TIMEOUT_MS else ANSWER_TIMEOUT_MS,
        )
        when (reply.type) {
            "harness_install_status" -> {
                val report = readJson.decodeFromString(HarnessInstallReport.serializer(), reply.data)
                ToolResult(
                    outJson.encodeToString(
                        JsonObject.serializer(),
                        buildJsonObject {
                            put("report", outJson.encodeToJsonElement(HarnessInstallReport.serializer(), report))
                            put("text", StringBuilder().also { HarnessInstallText.render(report, it) }.toString())
                        },
                    ),
                )
            }
            "error" -> ToolResult(reply.data.ifBlank { "harness_install_status failed" }, isError = true)
            else -> ToolResult("harness_install_status: unexpected answer ${reply.type}", isError = true)
        }
    } catch (e: Exception) {
        ToolResult(e.message ?: "harness_install_status failed", isError = true)
    }
}

/** HARNESS-INSTALL-1 -> RUNTIME-LOAD-2: the same `harness_install` frame as `infantry harness-install`; a failed harness makes the result an error. */
object HarnessInstallTool : DaemonTool {
    const val NAME = "infantry_harness_install"
    /** An npm install of three CLIs can take minutes (the CLI allowed 15). */
    private const val INSTALL_TIMEOUT_MS = 15 * 60_000L
    private val readJson = Json { ignoreUnknownKeys = true }
    private val outJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = true }

    override val name = NAME
    override val description =
        "npm-install harness CLIs on the daemon's machine, into Infantry AI's own prefix dir (no admin rights, no user data " +
            "touched; idempotent). ids: which ones (default: every missing one). update=true also refreshes outdated ones. " +
            "Needs Node/npm on that machine (the answer says so when it is missing). Sign-in is still the user's: each CLI keeps " +
            "its own login. Can take minutes."
    override val inputSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("ids") {
                put("type", "array")
                putJsonObject("items") { put("type", "string") }
                put("description", "Harness ids to install, from: claude, opencode, pi. Default: all missing.")
            }
            putJsonObject("update") {
                put("type", "boolean")
                put("description", "Also update installed CLIs that are outdated (default false).")
            }
        }
        put("additionalProperties", false)
    }
    override val annotations = buildJsonObject { put("readOnlyHint", true) }
    override val timeoutMs: Long = INSTALL_TIMEOUT_MS

    override fun call(call: ToolCall): ToolResult = try {
        val ids = (call.arguments["ids"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { s -> s.isNotEmpty() } }.orEmpty()
        val update = (call.arguments["update"] as? JsonPrimitive)?.contentOrNull == "true"
        val data = outJson.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("ids", JsonArray(ids.map { JsonPrimitive(it) }))
            put("update", update)
        })
        val reply = call.request("harness_install", data, timeoutMs = INSTALL_TIMEOUT_MS)
        when (reply.type) {
            "harness_install_result" -> {
                val result = readJson.decodeFromString(HarnessInstallResult.serializer(), reply.data)
                ToolResult(
                    outJson.encodeToString(
                        JsonObject.serializer(),
                        buildJsonObject {
                            put("result", outJson.encodeToJsonElement(HarnessInstallResult.serializer(), result))
                            put("text", StringBuilder().also { HarnessInstallText.render(result, it) }.toString())
                        },
                    ),
                    isError = result.outcomes.any { it.result == "failed" },
                )
            }
            "error" -> ToolResult(reply.data.ifBlank { "harness_install failed" }, isError = true)
            else -> ToolResult("harness_install: unexpected answer ${reply.type}", isError = true)
        }
    } catch (e: Exception) {
        ToolResult(e.message ?: "harness_install failed", isError = true)
    }
}

/** BUG-ASSIGN-1 -> RUNTIME-LOAD-2: what a ticket may pin, from the `providers` and `models` frames; merged by the API's [HarnessCatalogText]. */
object ListHarnessesTool : DaemonTool {
    const val NAME = "infantry_list_harnesses"
    private const val ANSWER_TIMEOUT_MS = 20_000L
    private val readJson = Json { ignoreUnknownKeys = true }
    private val outJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = true }

    override val name = NAME
    override val description =
        "List the harnesses a ticket can run on: each harness id (the value of a ticket's provider) with its " +
            "models (the values of its model), which one is the default, and whether its CLI is available. " +
            "Pin them with infantry_ticket_create / infantry_ticket_update, then start the ticket with " +
            "infantry_ticket_start."
    override val inputSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {}
        put("additionalProperties", false)
    }
    override val annotations = buildJsonObject { put("readOnlyHint", true) }

    override fun call(call: ToolCall): ToolResult = try {
        val providersReply = call.request("providers", timeoutMs = ANSWER_TIMEOUT_MS)
        if (providersReply.type == "error") {
            ToolResult(providersReply.data.ifBlank { "providers failed" }, isError = true)
        } else {
            val providers = readJson.decodeFromString(ListSerializer(ProviderInfo.serializer()), providersReply.data)
            var catalogError: String? = null
            val catalog = try {
                val reply = call.request("models", timeoutMs = ANSWER_TIMEOUT_MS)
                check(reply.type != "error") { reply.data.ifBlank { "models failed" } }
                readJson.decodeFromString(ListSerializer(ProviderModels.serializer()), reply.data)
            } catch (e: Exception) {
                catalogError = e.message ?: e.javaClass.simpleName
                emptyList()
            }
            val harnesses = HarnessCatalogText.merge(providers, catalog, catalogError)
            ToolResult(
                outJson.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        put("harnesses", buildJsonArray {
                            harnesses.forEach { harness ->
                                add(buildJsonObject {
                                    put("id", harness.id)
                                    put("default", harness.default)
                                    put("defaultModel", harness.defaultModel)
                                    put("models", harness.models?.let { models -> buildJsonArray { models.forEach { add(JsonPrimitive(it)) } } } ?: JsonNull)
                                    put("available", harness.available)
                                    put("unavailableReason", harness.unavailableReason)
                                    put("note", harness.note)
                                    // CONTEXT-LIMIT-1: how a ticket / spawn contextLimit is honoured on this harness.
                                    harness.contextLimit?.let { put("contextLimit", it) }
                                    harness.contextLimitHow?.let { put("contextLimitHow", it) }
                                    // EFFORT-1: how a ticket / spawn effort is honoured on this harness.
                                    harness.effort?.let { put("effort", it) }
                                    harness.effortHow?.let { put("effortHow", it) }
                                })
                            }
                        })
                        put("howToUse", HarnessCatalogText.HOW_TO_PIN)
                    },
                ),
            )
        }
    } catch (e: Exception) {
        ToolResult(e.message ?: "list_harnesses failed", isError = true)
    }
}
