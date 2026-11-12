// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.agents

import com.superiorsoftllc.infantry.session.TraitSnapshot

// The env variable names are AccessPolicy.ROLE_ENV / AGENT_ID_ENV / SESSION_ID_ENV in the daemon; spelled out here because this module cannot see it.
/** TRAITS-T5: the header [renderTraits] opens with. */
const val TRAITS_HEADER = "Standing rules bound to your role (they apply to this whole session):"

/**
 * TRAITS-T5: the standing rules of a session, one block per trait in order (`## <name> (<id> v<version>)`, then its
 * body), under [TRAITS_HEADER]. Null when there are none, so a session with no traits gets today's text exactly.
 */
fun renderTraits(snapshots: List<TraitSnapshot>): String? {
    if (snapshots.isEmpty()) return null
    return (listOf(TRAITS_HEADER) + snapshots.map { "## ${it.name} (${it.id} v${it.version})\n${it.body}" }).joinToString("\n\n")
}

/** Who an agent runs as, in one line naming the env variables that carry the same facts; null when it has neither. */
fun identityLine(role: String?, agentId: String?): String? {
    val facts = listOfNotNull(
        role?.takeIf { it.isNotBlank() }?.let { "role '$it'" to "INFANTRY_ROLE" },
        agentId?.takeIf { it.isNotBlank() }?.let { "agent id '$it'" to "INFANTRY_AGENT_ID" },
    )
    if (facts.isEmpty()) return null
    val variables = facts.map { it.second } + "INFANTRY_SESSION_ID"
    return "You are running inside Infantry AI as ${facts.joinToString(", ") { it.first }}. " +
        "Your environment carries the same identity (${variables.joinToString(", ")})."
}

/**
 * P10-K9 / K15: persona -> identity -> traits -> knowledge ([renderKnowledge]) -> skills ([renderSkills]) -> task, on
 * every first-turn path; a null section is absent.
 */
fun composeFirstTurn(
    persona: String?,
    traits: List<TraitSnapshot>,
    role: String?,
    agentId: String?,
    prompt: String,
    knowledge: String? = null,
    skills: String? = null,
    environment: String? = null,
): String =
    listOfNotNull(persona?.takeIf { it.isNotBlank() }, identityLine(role, agentId), renderTraits(traits), knowledge, skills, environment, prompt)
        .joinToString("\n\n")
