// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.protocol

/**
 * The canonical role-id set: Infantry's own, and a **stable contract**.
 *
 * A role id is a stable string key a ticket, a profile, a session, a binding and a portrait all name, so renaming
 * one is a breaking change, exactly like renaming a protocol frame. The set was redrawn once (ORIGIN-SCRUB-1a,
 * contract 1.90.0); the ids it retired are kept in [RETIRED] so nothing stored under them is lost.
 *
 * Why each id is the word it is:
 *  - `architect`, `backend`, `frontend`, `fullstack`, `qa`, `devops`, `security`, `mobile`, `marketing`, `seo`,
 *    `pm`: the plain industry name of the job. No other word says the same thing as briefly; a coined synonym
 *    would only make the id harder to guess.
 *  - `ideation`: the work (widening the option space before anyone narrows it), named by what it does.
 *  - `assistant`: holds the whole picture and keeps the principal unblocked, matching the project-assistant portrait.
 *  - `automation`: an unattended, scheduled or event-driven job, named by the work rather than as a generic "bot".
 *  - `version-control`: keeps history honest and recoverable; named for the discipline, not one tool.
 *  - `localization`: spelled out rather than as a numeronym, matching the localization portrait.
 *
 * [CUSTOM] is the built-in id for a user-defined role: an actual custom role is identified by the **filename** of
 * its role file (see [CustomRoles]), which is why [CUSTOM] is only the fallback persona, not the only custom id.
 *
 * A role id must match [PATTERN] because it is used as a key and (for avatars) as a filename. The shape is
 * deliberately the same one the Phase 9 agent profiles accept (`[a-z][a-z0-9-]{0,31}`), so a catalog id and a
 * profile role can never disagree about what a legal id is.
 */
object RoleIds {
    /**
     * The 16 canonical ids, in the order they are published. Order is part of the contract only in the sense that
     * it is stable; nothing reads meaning into it.
     */
    val CANONICAL: List<String> = listOf(
        "architect",
        "backend",
        "frontend",
        "fullstack",
        "qa",
        "devops",
        "security",
        "mobile",
        "pm",
        "marketing",
        "version-control",
        "seo",
        "localization",
        "ideation",
        "automation",
        "assistant",
    )

    /**
     * Retired id -> the canonical id that replaced it (contract 1.90.0). Read side only: a stored row is rewritten
     * once by the open-time role-id migration when the database opens, and a request from an older client naming a retired id is
     * accepted through [normalize]. A retired id is never published again.
     */
    val RETIRED: Map<String, String> = mapOf(
        "git" to "version-control",
        "i18n" to "localization",
        "brainstormer" to "ideation",
        "bot" to "automation",
        "chief-of-staff" to "assistant",
    )

    /** The built-in id for a user-defined role (its real id is its file's name). */
    const val CUSTOM: String = "custom"

    /** Every id the catalog ships with: the canonical 16 plus [CUSTOM]. */
    val BUILT_IN: List<String> = CANONICAL + CUSTOM

    /** The one definition of a legal role id, shared with the Phase 9 agent profiles. */
    val PATTERN: Regex = Regex("[a-z][a-z0-9-]{0,31}")

    fun isCanonical(id: String): Boolean = id in CANONICAL

    /** True when [id] is a legal role id at all (canonical, [CUSTOM], or a user-defined one). */
    fun isValid(id: String): Boolean = PATTERN.matches(id)

    /** [id] under its current name: a [RETIRED] id maps to its replacement, anything else is returned unchanged. */
    fun normalize(id: String): String = RETIRED[id] ?: id

    /** [normalize] for a nullable id. */
    fun normalizeOrNull(id: String?): String? = id?.let(::normalize)

    /** The retired ids that now mean [id] (empty for an id that was never renamed). */
    fun retiredNamesOf(id: String): List<String> = RETIRED.filterValues { it == id }.keys.toList()
}
