// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.protocol

import kotlinx.serialization.Serializable

/**
 * Contract 1.4.0: an agent PROFILE, the template a session is spawned from (`new` with an `agentId`).
 *
 * The same shape travels both ways: a client sends it in `agent_save`, and the daemon answers with the
 * stored profile in `agent` and lists them in `agents`. A profile is not a session: spawning from one
 * starts a fresh session that copies the profile's provider, model and role onto its own record, so
 * editing or deleting a profile never rewrites an existing session.
 *
 * - [id]: null or absent on `agent_save` = create a profile with a new id. An id the daemon does not
 *   know creates a profile with exactly that id; a known one overwrites that profile.
 * - [role]: a canonical role id such as `backend` or `qa`. It is copied onto every session spawned
 *   from the profile, which is what a UI groups and colours sessions by.
 * - [provider] / [model]: null = the daemon's default provider / that provider's default model,
 *   resolved when a session is spawned.
 * - [instructions]: persona text put ahead of a spawned session's first prompt. Null = none.
 *
 * - [projectId]: CP-3, the ONE project the agent belongs to. Absent on `agent_save` = keep the project a
 *   known profile already has; a NEW profile with none is refused by the daemon. Once set it is never
 *   changed: an agent does not move between projects.
 *
 * Plain Kotlin and kotlinx.serialization only, so a non-JVM client can share the type.
 */
@Serializable
data class AgentProfile(
    val id: String? = null,
    val name: String,
    val role: String,
    val provider: String? = null,
    val model: String? = null,
    val instructions: String? = null,
    val projectId: String? = null,
    /** Contract 1.46.0 (role-first S2): the profile's effort level. Null = inherit (spawn choice, then role binding, then daemon default). */
    val effort: String? = null,
    /**
     * Contract 1.50.0 (TRAITS-T3): the trait ids this agent ADDS to its role's list (it cannot remove one). On `agent_save`,
     * absent or null KEEPS the stored list and `[]` clears it, so a pre-1.50.0 client that never heard of traits cannot strip
     * them. `agent` / `agents` frames always carry the stored list (`[]` when none).
     */
    val traitIds: List<String>? = null,
    /**
     * Contract 1.56.0 (P10-K12): the skill ids this agent attaches, in attach order -- its "always-on toolkit". On
     * `agent_save`, absent or null KEEPS the stored list and `[]` clears it (the [traitIds] rule); every id must name an
     * existing skill and appear once, or the save is refused. `agent_swap` keeps it. `agent` / `agents` frames always
     * carry the stored list (`[]` when none). A skill deleted later stays listed.
     */
    val skillIds: List<String>? = null,
    /** RUNNER-AGENT-1: where this agent's sessions run by default (`local` | a runner name | `any`). On `agent_save`, absent or null KEEPS the stored value and blank clears it. */
    val runner: String? = null,
) {
    companion object {
        /** Decode an `agent_save` frame's JSON data (strictly), or throw [IllegalArgumentException]. */
        fun decode(text: String): AgentProfile = StrictJson.decode(serializer(), text, "agent_save payload")
    }
}

/**
 * `agent_roster`: the request data of the live, project-scoped roster. [projectId] names the project
 * whose agents the `roster` frame answers with; a blank one is refused rather than answered with rows.
 *
 * Plain Kotlin and kotlinx.serialization only, so a non-JVM client can share the type.
 */
@Serializable
data class RosterRequest(
    val projectId: String? = null,
) {
    companion object {
        /** Decode an `agent_roster` frame's JSON data (strictly), or throw [IllegalArgumentException]. */
        fun decode(text: String): RosterRequest = StrictJson.decode(serializer(), text, "agent_roster payload")
    }
}

/**
 * CP-3: the optional data of `agent_list`. [projectId] present = only that project's agents; absent or
 * blank = every profile (the legacy, unscoped answer). Empty frame data is the unscoped request.
 */
@Serializable
data class AgentListRequest(
    val projectId: String? = null,
) {
    companion object {
        fun decode(text: String): AgentListRequest =
            if (text.isBlank()) AgentListRequest() else StrictJson.decode(serializer(), text, "agent_list payload")
    }
}

/**
 * Contract 1.47.0 (role-first S3): the payload of `agent_swap` -- this agent now runs on another executor.
 *
 * [provider] is required (the harness to run on); [model] / [effort] null or blank = inherit (role binding, then
 * the harness default). [session] is what happens to the agent's current session: `auto` (the 4.2 table), `keep`
 * (refused when the harness changes) or `new` (always a fresh session). Decoded strictly: an unknown key is refused.
 */
@Serializable
data class AgentSwap(
    val agentId: String,
    val provider: String,
    val model: String? = null,
    val effort: String? = null,
    val session: String = AUTO,
) {
    companion object {
        const val AUTO = "auto"
        const val KEEP = "keep"
        const val NEW = "new"
        val SESSION_MODES = listOf(AUTO, KEEP, NEW)

        fun decode(text: String): AgentSwap = StrictJson.decode(serializer(), text, "agent_swap payload")
    }
}

/** Contract 1.47.0: the executor an agent ran on before a swap. A null provider never appears: it is resolved to the daemon default. */
@Serializable
data class SwapExecutor(
    val provider: String? = null,
    val model: String? = null,
    val effort: String? = null,
)

/**
 * Contract 1.47.0: what a swap did to the agent's session. [outcome] is `kept` (the session's NEXT run uses the
 * new model/effort; across harnesses, HARNESS-CARRY-1, on the new harness with the conversation carried), `new` (a fresh session, [sessionId], on the new executor; the old one stays in history) or
 * `none` (no session touched). [sessionId] names the kept or new session, null for `none`.
 */
@Serializable
data class SwapSession(
    val outcome: String,
    val sessionId: String? = null,
) {
    companion object {
        const val KEPT = "kept"
        const val NEW = "new"
        const val NONE = "none"
    }
}

/** Contract 1.47.0: the `agent_swapped` push, sent to EVERY client after a swap is stored. */
@Serializable
data class AgentSwapped(
    val agent: AgentProfile,
    val from: SwapExecutor,
    val session: SwapSession,
)

/**
 * UX-HARNESS-2: the payload of `session_swap` -- restart ONE session that belongs to no agent (a quick chat, a pod
 * session) on another harness. [provider] is required; [model] / [effort] null or blank = that harness's default.
 * Nothing is saved as an agent. A session bound to an agent is refused: its executor is the agent's (`agent_swap`).
 * Decoded strictly: an unknown key is refused.
 *
 * SWAP-AGENT-1 (contract 1.86.0): [role] swaps the AGENT the session runs as, in place: null / absent = keep the role,
 * blank = no role, else a role id the daemon knows. A new role re-freezes the session's instance and makes the next
 * turn a first turn under it (persona, identity, traits, skills), carrying the conversation so far (HARNESS-CARRY-1).
 */
@Serializable
data class SessionSwap(
    val sessionId: String,
    val provider: String,
    val model: String? = null,
    val effort: String? = null,
    val role: String? = null,
) {
    companion object {
        fun decode(text: String): SessionSwap = StrictJson.decode(serializer(), text, "session_swap payload")
    }
}

/**
 * UX-HARNESS-2: the `session_swapped` push, sent to EVERY client after a `session_swap` is stored. [provider] /
 * [model] are what the session now runs on ([model] null = the harness default, [defaultModel] names it when the
 * harness reports one); [from] is what it ran on before. [newConversation] is always false since HARNESS-CARRY-1: a
 * harness change carries the conversation to the new CLI on its first run (kept on the wire for older clients).
 */
@Serializable
data class SessionSwapped(
    val sessionId: String,
    val provider: String,
    val model: String? = null,
    val defaultModel: String? = null,
    val from: SwapExecutor,
    val newConversation: Boolean,
    /** SWAP-AGENT-1 (1.86.0): the role the session now runs as (null = none), and the one it ran as before. */
    val role: String? = null,
    val fromRole: String? = null,
)
