package com.superiorsoftllc.agenthq.role

/**
 * One role: its id, a display [name], the [color] used for the badge, its [persona] markdown and its [icon] key.
 *
 * The persona is not documentation -- it is the text a spawned agent receives as its system prompt
 * (mirroring Phase 9's agent profiles, where a profile's `instructions` are put ahead of the first
 * prompt). A role with a blank persona is legal; it simply contributes no text.
 *
 * Plain Kotlin + kotlinx.serialization only, so a non-JVM client of the catalog can share the type.
 */
@kotlinx.serialization.Serializable
data class Role(
    val id: String,
    val name: String,
    /** A `#rrggbb` colour string; never parsed here so a client can hand it straight to its theme. */
    val color: String,
    val persona: String = "",
    val source: RoleSource = RoleSource.BUILT_IN,
    /**
     * ROLE-ICON-1 (contract 1.93.0): the key of the role's icon in the client's bundled icon set (`[a-z0-9-]`, e.g.
     * `shield`). Blank = none chosen: each client paints its own default for the role's kind. The daemon stores the
     * key and never interprets it, so a client that does not know a key falls back to that same default.
     */
    val icon: String = "",
)

/** Where a [Role] came from, which is what decides whether it is replaceable. */
@kotlinx.serialization.Serializable
enum class RoleSource {
    /** Shipped in the bundled manifest (`/roles/index.json`) or a canonical id with no manifest entry. */
    BUILT_IN,

    /** Loaded from an operator's role file; overrides a built-in with the same id. */
    CUSTOM,

    /** Neither bundled nor defined by the operator: synthesised from the id so a badge always paints. */
    GENERATED,
}
