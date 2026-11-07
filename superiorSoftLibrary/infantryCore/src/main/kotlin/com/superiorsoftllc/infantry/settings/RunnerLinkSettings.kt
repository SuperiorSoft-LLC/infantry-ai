// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.settings

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * RUNNER-POOL-5 (docs/RUNNER-POOL.md sections 7 and 8): the runner link's non-numeric settings. The numeric ones (pair code
 * expiry, pairing attempts per hour, credential rotation, link port) are [LimitRegistry] entries (`limits.runners.*`).
 *
 * * [ALLOW_PLAIN_TAILNET] `runners.allowPlainTailnet` (default `false`): see [ALLOW_PLAIN_TAILNET_TEXT].
 * * [PROJECTS] `runner.projects` and [HARNESSES] `runner.harnesses` (runner side, default `[]`): the projects and harnesses this
 *   runner agrees to run for its paired orchestrator. EMPTY = NONE: a fresh runner runs nothing remote until the owner lists them.
 *   `*` allows every one.
 */
object RunnerLinkSettings {
    const val ALLOW_PLAIN_TAILNET = "runners.allowPlainTailnet"
    const val PROJECTS = "runner.projects"
    const val HARNESSES = "runner.harnesses"
    const val ANY = "*"

    /** The setting text of [ALLOW_PLAIN_TAILNET]; it states the risk. */
    const val ALLOW_PLAIN_TAILNET_TEXT =
        "Serve the runner link as plain ws:// instead of wss://. RISK: the pair credential and every start/stop, brief and mail " +
            "text cross the network unencrypted; anyone on the path can read them and replay the credential to start agents with " +
            "tool permissions on this machine. Only for a private tailnet (Tailscale/WireGuard) you fully control. Default false."

    val KEYS: Set<String> = setOf(ALLOW_PLAIN_TAILNET, PROJECTS, HARNESSES)

    /** What the runner link reads; [stored] is the raw stored JSON of a key (null = unset). */
    data class Values(val allowPlainTailnet: Boolean = false, val projects: List<String> = emptyList(), val harnesses: List<String> = emptyList()) {
        fun allowsProject(id: String?): Boolean = allows(projects, id)
        fun allowsHarness(id: String?): Boolean = allows(harnesses, id)
        private fun allows(list: List<String>, id: String?) = !id.isNullOrBlank() && (ANY in list || id in list)
    }

    fun values(stored: (String) -> String?): Values = Values(
        allowPlainTailnet = runCatching { (Json.parseToJsonElement(stored(ALLOW_PLAIN_TAILNET) ?: "false") as JsonPrimitive).booleanOrNull }.getOrNull() ?: false,
        projects = list(stored(PROJECTS)),
        harnesses = list(stored(HARNESSES)),
    )

    /** The keys for the settings `all()` map. */
    fun json(stored: (String) -> String?): Map<String, JsonElement> = values(stored).let { v ->
        mapOf(
            ALLOW_PLAIN_TAILNET to JsonPrimitive(v.allowPlainTailnet),
            PROJECTS to JsonArray(v.projects.map(::JsonPrimitive)),
            HARNESSES to JsonArray(v.harnesses.map(::JsonPrimitive)),
        )
    }

    /** [value] as stored for [key] (one of [KEYS]); refuses an invalid value. */
    fun validated(key: String, value: JsonElement): JsonElement = when (key) {
        ALLOW_PLAIN_TAILNET -> JsonPrimitive(
            (value as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
                ?: throw IllegalArgumentException("setting '$key' must be a JSON boolean. $ALLOW_PLAIN_TAILNET_TEXT"),
        )
        PROJECTS, HARNESSES -> JsonArray(
            ((value as? JsonArray) ?: throw IllegalArgumentException("setting '$key' must be a JSON array of ids (\"*\" = every one, [] = none)"))
                .map { e ->
                    (e as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { ID.matches(it) }
                        ?: throw IllegalArgumentException("setting '$key' must be a JSON array of ids (\"*\" = every one, [] = none)")
                }.distinct().map(::JsonPrimitive),
        )
        else -> throw IllegalArgumentException("unknown runner link setting '$key'")
    }

    private fun list(raw: String?): List<String> = runCatching {
        (Json.parseToJsonElement(raw ?: return emptyList()) as JsonArray).mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
    }.getOrDefault(emptyList())

    private val ID = Regex("""^(\*|[A-Za-z0-9][A-Za-z0-9._:-]{0,127})$""")
}
