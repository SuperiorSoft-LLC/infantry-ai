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
    /** RUNNER-POOL-6: where this runner clones the projects an orchestrator starts here; blank = `<state dir>/runner-clones`. */
    const val CLONE_ROOT = "runners.cloneRoot"
    /**
     * RUNNER-POOL-8 (orchestrator side, docs/RUNNER-POOL.md 3.1): what happens when a runner with workers on it stays silent past
     * `limits.runners.lostAfterMin`: [ON_LOST_ATTENTION] (default) raises an attention item only; [ON_LOST_RESTART] also starts each
     * of its in-progress tickets again via placement (not on the lost runner), from the ticket's last pushed branch.
     */
    const val ON_LOST = "runners.onLost"
    const val ON_LOST_ATTENTION = "attention"
    const val ON_LOST_RESTART = "restart-elsewhere"
    /** RUNNER-POOL-8 (orchestrator side): a remote runner pushes the ticket branch after each worker commit too; default false. */
    const val PUSH_ON_COMMIT = "runners.pushOnCommit"
    /**
     * RUNNER-LINK-1 (runner side): the hosts `runner pair` puts in its pairing link after any `--host` flag, in the order the
     * client tries them (a DNS name, IPv4 or IPv6). Default `[]`: with no flag either, the link carries the detected interface
     * addresses. Set it on a runner behind NAT or in a container, where the detected addresses are not reachable.
     */
    const val ADVERTISE_HOSTS = "runners.advertiseHosts"

    /** The setting text of [ALLOW_PLAIN_TAILNET]; it states the risk. */
    const val ALLOW_PLAIN_TAILNET_TEXT =
        "Serve the runner link as plain ws:// instead of wss://. RISK: the pair credential and every start/stop, brief and mail " +
            "text cross the network unencrypted; anyone on the path can read them and replay the credential to start agents with " +
            "tool permissions on this machine. Only for a private tailnet (Tailscale/WireGuard) you fully control. Default false."

    val KEYS: Set<String> = setOf(ALLOW_PLAIN_TAILNET, PROJECTS, HARNESSES, CLONE_ROOT, ON_LOST, PUSH_ON_COMMIT, ADVERTISE_HOSTS)

    /** What the runner link reads; [stored] is the raw stored JSON of a key (null = unset). */
    data class Values(
        val allowPlainTailnet: Boolean = false,
        val projects: List<String> = emptyList(),
        val harnesses: List<String> = emptyList(),
        val cloneRoot: String = "",
        val onLost: String = ON_LOST_ATTENTION,
        val pushOnCommit: Boolean = false,
        val advertiseHosts: List<String> = emptyList(),
    ) {
        val restartElsewhere: Boolean get() = onLost == ON_LOST_RESTART
        fun allowsProject(id: String?): Boolean = allows(projects, id)
        fun allowsHarness(id: String?): Boolean = allows(harnesses, id)
        private fun allows(list: List<String>, id: String?) = !id.isNullOrBlank() && (ANY in list || id in list)
    }

    fun values(stored: (String) -> String?): Values = Values(
        allowPlainTailnet = runCatching { (Json.parseToJsonElement(stored(ALLOW_PLAIN_TAILNET) ?: "false") as JsonPrimitive).booleanOrNull }.getOrNull() ?: false,
        projects = list(stored(PROJECTS)),
        harnesses = list(stored(HARNESSES)),
        cloneRoot = runCatching { (Json.parseToJsonElement(stored(CLONE_ROOT) ?: "\"\"") as JsonPrimitive).takeIf { it.isString }?.content }.getOrNull().orEmpty(),
        onLost = runCatching { (Json.parseToJsonElement(stored(ON_LOST) ?: "null") as JsonPrimitive).takeIf { it.isString }?.content }.getOrNull()
            ?.takeIf { it in ON_LOST_VALUES } ?: ON_LOST_ATTENTION,
        pushOnCommit = runCatching { (Json.parseToJsonElement(stored(PUSH_ON_COMMIT) ?: "false") as JsonPrimitive).booleanOrNull }.getOrNull() ?: false,
        advertiseHosts = list(stored(ADVERTISE_HOSTS)).filter { HOST.matches(it) },
    )

    private val ON_LOST_VALUES = setOf(ON_LOST_ATTENTION, ON_LOST_RESTART)

    /** The keys for the settings `all()` map. */
    fun json(stored: (String) -> String?): Map<String, JsonElement> = values(stored).let { v ->
        mapOf(
            ALLOW_PLAIN_TAILNET to JsonPrimitive(v.allowPlainTailnet),
            PROJECTS to JsonArray(v.projects.map(::JsonPrimitive)),
            HARNESSES to JsonArray(v.harnesses.map(::JsonPrimitive)),
            CLONE_ROOT to JsonPrimitive(v.cloneRoot),
            ON_LOST to JsonPrimitive(v.onLost),
            PUSH_ON_COMMIT to JsonPrimitive(v.pushOnCommit),
            ADVERTISE_HOSTS to JsonArray(v.advertiseHosts.map(::JsonPrimitive)),
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
        CLONE_ROOT -> JsonPrimitive(
            (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
                ?: throw IllegalArgumentException("setting '$key' must be a JSON string: a folder path (\"\" = <state dir>/runner-clones)"),
        )
        ON_LOST -> JsonPrimitive(
            (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it in ON_LOST_VALUES }
                ?: throw IllegalArgumentException("setting '$key' must be \"$ON_LOST_ATTENTION\" or \"$ON_LOST_RESTART\" (restart-elsewhere loses the lost runner's unpushed work)"),
        )
        PUSH_ON_COMMIT -> JsonPrimitive(
            (value as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
                ?: throw IllegalArgumentException("setting '$key' must be a JSON boolean (true = push the ticket branch after each worker commit)"),
        )
        ADVERTISE_HOSTS -> JsonArray(
            ((value as? JsonArray) ?: throw IllegalArgumentException(ADVERTISE_HOSTS_ERROR))
                .map { e -> (e as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { HOST.matches(it) } ?: throw IllegalArgumentException(ADVERTISE_HOSTS_ERROR) }
                .distinct().map(::JsonPrimitive),
        )
        else -> throw IllegalArgumentException("unknown runner link setting '$key'")
    }

    private fun list(raw: String?): List<String> = runCatching {
        (Json.parseToJsonElement(raw ?: return emptyList()) as JsonArray).mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
    }.getOrDefault(emptyList())

    /** A host as the pairing link carries it: a DNS name, IPv4, or IPv6 (bare or in brackets). No spaces, `/`, `?`, `&`, `=` or `#`. */
    val HOST = Regex("""^(\[[0-9A-Fa-f:.]{2,45}]|[0-9A-Fa-f.]*:[0-9A-Fa-f:.]{1,45}|[A-Za-z0-9]([A-Za-z0-9.-]{0,251}[A-Za-z0-9])?)$""")
    private const val ADVERTISE_HOSTS_ERROR = "setting '$ADVERTISE_HOSTS' must be a JSON array of host names or addresses (e.g. [\"100.64.0.7\", \"runner.example.com\"]; [] = detect)"

    private val ID = Regex("""^(\*|[A-Za-z0-9][A-Za-z0-9._:-]{0,127})$""")

    /** Whether [text] is a project or harness id this setting accepts (`*` included; see [ID]). */
    fun isId(text: String): Boolean = ID.matches(text)
}
