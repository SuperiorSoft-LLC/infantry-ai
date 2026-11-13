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
    /**
     * RUNNER-GIT-IDENT-1 (runner side): the git author/committer name and email this runner's workers commit with. Default `""`
     * (blank) = the identity the orchestrator sends with each start (its checkout's `user.name` / `user.email`). Set both to make
     * this runner commit under its own identity instead; one alone is ignored. Written into the runner's clone (`git config`), so a
     * worker's `git commit` never asks who it is and never improvises a name.
     */
    const val GIT_NAME = "runner.gitName"
    const val GIT_EMAIL = "runner.gitEmail"
    /**
     * RUNNER-CRED-1 (orchestrator side): the paired runners this app LENDS credentials to at each start (the harness key, the
     * secrets a session may render, the git credential of `runners.gitSecret`). Default `["*"]` = every paired runner, so a remote
     * runner is two steps (`docker run`, then endpoint and bearer); `[]` = none, each runner then needs its own keys.
     */
    const val LEND_CREDENTIALS = "runners.lendCredentials"
    /**
     * RUNNER-CRED-1 (orchestrator side): the name of a keystore secret holding the HTTPS git credential a remote runner clones and
     * pushes the ticket's remote with: field `token` (or its only field) and optional `username` (default `x-access-token`). The
     * project's own secret of that name wins over the app's. Default `""` = none (the runner uses whatever git access it has).
     */
    const val GIT_SECRET = "runners.gitSecret"
    /**
     * RUNNER-CRED-1 (runner side): whether this runner uses the credentials its orchestrator lends with a start (kept in memory for
     * that session only). Default `true`; `false` = ignore them and use only this runner's own keys and git access.
     */
    const val ACCEPT_CREDENTIALS = "runner.acceptCredentials"
    /**
     * RUNNER-UNIFY-2 (orchestrator side): every owner command on a session (prompt, resume, attach, interrupt, queue, kill, delete)
     * goes through the runner the session lives on (`RunnerPool.linkFor`). Default `true`. CONTINGENCY: `false` = a session this
     * daemon holds skips the pool and runs the session manager directly, as before; remote sessions still go to their runner.
     */
    const val UNIFIED_COMMANDS = "runners.unifiedCommands"
    /**
     * RUNNER-UNIFY-3 (orchestrator side, docs/RUNNER-UNIFY.md 5.2): a remote session's stream frames (chat, reasoning, errors, finishes
     * AND `status` with its run activity) are pushed by the runner over the link (`runner_link_frames_pull`, a held call answered the
     * moment a frame is published) instead of read every `limits.runners.outputPollMs`. Default `false`. CONTINGENCY: `false` = the
     * poll path exactly as before; flipping it takes effect live, with no restart, rebuild or revert. A runner too old to push is
     * polled as before even when it is `true`.
     */
    const val PUSH_FRAMES = "runners.pushFrames"
    /**
     * RUNNER-UNIFY-4 (orchestrator side, docs/RUNNER-UNIFY.md 5.3): the session list, header and lookups read ONE directory: this
     * daemon's own sessions plus, for each session placed on a runner, the record the runner itself sent in its `session` frames
     * (its real working dir, harness, model, state and error). Default `true` (proven live; RUNNER-UNIFY-6 removes the flag).
     * CONTINGENCY: `false` = the mirror's records exactly as before; flipping it takes effect live, with no restart, rebuild or revert. A session whose runner has sent no record yet
     * (pushed frames off, an older runner, the first moment after a start) reads the mirror's record either way.
     */
    const val UNIFIED_DIRECTORY = "runners.unifiedDirectory"
    /**
     * RUNNER-UNIFY-5 (orchestrator side, docs/RUNNER-UNIFY.md 5.4): a runner session's approval card opens from the prompt frame on
     * its pushed stream, through the same prompt store as a local session, and a failed turn's reason is the one its frames carry
     * (the record's `lastError`, the `finished` detail, an `error` frame) instead of a scrape of its transcript. Applies to a session
     * whose frames are pushed (`runners.pushFrames`); any other session keeps the event relay. CONTINGENCY: `false` (the default
     * until the live proof) = the outbox event relay and the transcript scrape exactly as before; flipping it takes effect live.
     */
    const val FRAME_PROMPTS = "runners.framePrompts"

    /** The setting text of [ALLOW_PLAIN_TAILNET]; it states the risk. */
    const val ALLOW_PLAIN_TAILNET_TEXT =
        "Serve the runner link as plain ws:// instead of wss://. RISK: the pair credential and every start/stop, brief and mail " +
            "text cross the network unencrypted; anyone on the path can read them and replay the credential to start agents with " +
            "tool permissions on this machine. Only for a private tailnet (Tailscale/WireGuard) you fully control. Default false."

    val KEYS: Set<String> = setOf(ALLOW_PLAIN_TAILNET, PROJECTS, HARNESSES, CLONE_ROOT, ON_LOST, PUSH_ON_COMMIT, ADVERTISE_HOSTS, GIT_NAME, GIT_EMAIL, LEND_CREDENTIALS, GIT_SECRET, ACCEPT_CREDENTIALS, UNIFIED_COMMANDS, PUSH_FRAMES, UNIFIED_DIRECTORY, FRAME_PROMPTS)

    /** What the runner link reads; [stored] is the raw stored JSON of a key (null = unset). */
    data class Values(
        val allowPlainTailnet: Boolean = false,
        val projects: List<String> = emptyList(),
        val harnesses: List<String> = emptyList(),
        val cloneRoot: String = "",
        val onLost: String = ON_LOST_ATTENTION,
        val pushOnCommit: Boolean = false,
        val advertiseHosts: List<String> = emptyList(),
        val gitName: String = "",
        val gitEmail: String = "",
        val lendCredentials: List<String> = listOf(ANY),
        val gitSecret: String = "",
        val acceptCredentials: Boolean = true,
        val unifiedCommands: Boolean = true,
        val pushFrames: Boolean = false,
        val unifiedDirectory: Boolean = true,
        val framePrompts: Boolean = false,
    ) {
        /** RUNNER-CRED-1: whether this orchestrator lends credentials to runner [name]. */
        fun lendsTo(name: String): Boolean = ANY in lendCredentials || name in lendCredentials
        /** RUNNER-GIT-IDENT-1: this runner's own git identity, only when both halves are set. */
        val gitIdentity: Pair<String, String>? get() = if (gitName.isNotBlank() && gitEmail.isNotBlank()) gitName to gitEmail else null
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
        gitName = string(stored(GIT_NAME)),
        gitEmail = string(stored(GIT_EMAIL)),
        lendCredentials = stored(LEND_CREDENTIALS)?.let(::list) ?: listOf(ANY),
        gitSecret = string(stored(GIT_SECRET)),
        acceptCredentials = runCatching { (Json.parseToJsonElement(stored(ACCEPT_CREDENTIALS) ?: "true") as JsonPrimitive).booleanOrNull }.getOrNull() ?: true,
        unifiedCommands = runCatching { (Json.parseToJsonElement(stored(UNIFIED_COMMANDS) ?: "true") as JsonPrimitive).booleanOrNull }.getOrNull() ?: true,
        pushFrames = runCatching { (Json.parseToJsonElement(stored(PUSH_FRAMES) ?: "false") as JsonPrimitive).booleanOrNull }.getOrNull() ?: false,
        unifiedDirectory = runCatching { (Json.parseToJsonElement(stored(UNIFIED_DIRECTORY) ?: "true") as JsonPrimitive).booleanOrNull }.getOrNull() ?: true,
        framePrompts = runCatching { (Json.parseToJsonElement(stored(FRAME_PROMPTS) ?: "false") as JsonPrimitive).booleanOrNull }.getOrNull() ?: false,
    )

    private fun string(raw: String?): String =
        runCatching { (Json.parseToJsonElement(raw ?: "\"\"") as JsonPrimitive).takeIf { it.isString }?.content }.getOrNull().orEmpty().trim()

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
            GIT_NAME to JsonPrimitive(v.gitName),
            GIT_EMAIL to JsonPrimitive(v.gitEmail),
            LEND_CREDENTIALS to JsonArray(v.lendCredentials.map(::JsonPrimitive)),
            GIT_SECRET to JsonPrimitive(v.gitSecret),
            ACCEPT_CREDENTIALS to JsonPrimitive(v.acceptCredentials),
            UNIFIED_COMMANDS to JsonPrimitive(v.unifiedCommands),
            PUSH_FRAMES to JsonPrimitive(v.pushFrames),
            UNIFIED_DIRECTORY to JsonPrimitive(v.unifiedDirectory),
            FRAME_PROMPTS to JsonPrimitive(v.framePrompts),
        )
    }

    /** [value] as stored for [key] (one of [KEYS]); refuses an invalid value. */
    fun validated(key: String, value: JsonElement): JsonElement = when (key) {
        ALLOW_PLAIN_TAILNET -> JsonPrimitive(
            (value as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
                ?: throw IllegalArgumentException("setting '$key' must be a JSON boolean. $ALLOW_PLAIN_TAILNET_TEXT"),
        )
        PROJECTS, HARNESSES, LEND_CREDENTIALS -> JsonArray(
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
        GIT_NAME, GIT_EMAIL -> JsonPrimitive(
            (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { GIT_IDENT.matches(it) }
                ?: throw IllegalArgumentException("setting '$key' must be a JSON string of one line (\"\" = use the identity the orchestrator sends)"),
        )
        GIT_SECRET -> JsonPrimitive(
            (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isEmpty() || com.superiorsoftllc.infantry.keystore.Keystore.isName(it) }
                ?: throw IllegalArgumentException("setting '$key' must be a JSON string: a secret name (lowercase letters, digits, '.', '_', '-'; \"\" = none)"),
        )
        ACCEPT_CREDENTIALS -> JsonPrimitive(
            (value as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
                ?: throw IllegalArgumentException("setting '$key' must be a JSON boolean (false = ignore the credentials an orchestrator lends)"),
        )
        UNIFIED_COMMANDS -> JsonPrimitive(
            (value as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
                ?: throw IllegalArgumentException("setting '$key' must be a JSON boolean (false = this daemon's own sessions skip the runner link, as before RUNNER-UNIFY-2)"),
        )
        PUSH_FRAMES -> JsonPrimitive(
            (value as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
                ?: throw IllegalArgumentException("setting '$key' must be a JSON boolean (true = a runner pushes its sessions' frames; false = they are polled, as before RUNNER-UNIFY-3)"),
        )
        UNIFIED_DIRECTORY -> JsonPrimitive(
            (value as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
                ?: throw IllegalArgumentException("setting '$key' must be a JSON boolean (true = runner sessions show the runner's own record; false = the mirror's, as before RUNNER-UNIFY-4)"),
        )
        FRAME_PROMPTS -> JsonPrimitive(
            (value as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
                ?: throw IllegalArgumentException("setting '$key' must be a JSON boolean (true = a runner session's approval cards and failure reason come from its pushed frames; false = the event relay and the transcript scrape, as before RUNNER-UNIFY-5)"),
        )
        else -> throw IllegalArgumentException("unknown runner link setting '$key'")
    }

    private fun list(raw: String?): List<String> = runCatching {
        (Json.parseToJsonElement(raw ?: return emptyList()) as JsonArray).mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
    }.getOrDefault(emptyList())

    /** A host as the pairing link carries it: a DNS name, IPv4, or IPv6 (bare or in brackets). No spaces, `/`, `?`, `&`, `=` or `#`. */
    val HOST = Regex("""^(\[[0-9A-Fa-f:.]{2,45}]|[0-9A-Fa-f.]*:[0-9A-Fa-f:.]{1,45}|[A-Za-z0-9]([A-Za-z0-9.-]{0,251}[A-Za-z0-9])?)$""")
    private const val ADVERTISE_HOSTS_ERROR = "setting '$ADVERTISE_HOSTS' must be a JSON array of host names or addresses (e.g. [\"100.64.0.7\", \"runner.example.com\"]; [] = detect)"

    /** One line, no control characters or angle brackets (a git identity half). Blank allowed (= unset). */
    private val GIT_IDENT = Regex("""^[^\p{Cntrl}<>]*$""")

    private val ID = Regex("""^(\*|[A-Za-z0-9][A-Za-z0-9._:-]{0,127})$""")

    /** Whether [text] is a project or harness id this setting accepts (`*` included; see [ID]). */
    fun isId(text: String): Boolean = ID.matches(text)
}
