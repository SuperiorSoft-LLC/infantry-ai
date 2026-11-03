package com.superiorsoftllc.infantry.ui.model

/**
 * ROLE-ICON-1 (contract 1.93.0): which icon a role paints. A role carries an icon KEY (`Role.icon`, chosen in the
 * Edit role dialog and stored by the daemon, so every client agrees); a blank or unknown key falls back to the default
 * for the role's kind, and a role this table does not know paints [FALLBACK]. Pure data, no Compose, so the choice is
 * unit-testable; the vectors live in `AhqRoleIcons`.
 */
object RoleIcons {
    /** The icon every role nobody mapped paints, and what a blank key on such a role means. */
    const val FALLBACK: String = "person"

    /** Every icon key this build ships, in the order the Edit role picker offers them. */
    val KEYS: List<String> = listOf(
        "person", "assignment", "account-tree", "bug-report", "layers", "storage", "web", "cloud", "shield",
        "architecture", "phone-android", "campaign", "travel-explore", "translate", "lightbulb", "smart-toy",
        "support-agent", "code",
    )

    /** The default per role kind. Retired ids (contract 1.90.0) keep their successor's icon. */
    private val DEFAULTS: Map<String, String> = mapOf(
        "pm" to "assignment",
        "version-control" to "account-tree", "git" to "account-tree",
        "qa" to "bug-report",
        "fullstack" to "layers",
        "backend" to "storage",
        "frontend" to "web",
        "devops" to "cloud",
        "security" to "shield",
        "architect" to "architecture",
        "mobile" to "phone-android",
        "marketing" to "campaign",
        "seo" to "travel-explore",
        "localization" to "translate", "i18n" to "translate",
        "ideation" to "lightbulb", "brainstormer" to "lightbulb",
        "automation" to "smart-toy", "bot" to "smart-toy",
        "assistant" to "support-agent", "chief-of-staff" to "support-agent",
        "custom" to "person",
    )

    /** The default icon key for [roleId]'s kind; [FALLBACK] for a role (or no role) this table does not know. */
    fun defaultFor(roleId: String?): String = roleId?.trim()?.lowercase()?.let(DEFAULTS::get) ?: FALLBACK

    /** The key a role paints: its stored [icon] when this build ships that key, else [defaultFor] its id. */
    fun resolve(roleId: String?, icon: String?): String = icon?.trim()?.takeIf { it in KEYS } ?: defaultFor(roleId)
}
