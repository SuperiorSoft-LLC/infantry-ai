package com.superiorsoftllc.agenthq.settings

/**
 * LIMITS: the models this daemon lets a session, ticket or role run on. Empty = no restriction
 * (the normal app). Read at every check from the setting `limits.models.allowed` (a JSON array of patterns), else env
 * `AGENTHQ_ALLOWED_MODELS` (comma separated), else empty.
 *
 * A pattern is a model id exactly as the harness lists it (`opencode-go/beam-free`), compared case-insensitively; `*`
 * matches any run of characters (`*-free` matches every id ending in -free). Nothing else is special.
 */
data class ModelAllowlist(
    val patterns: List<String>,
    /** What the list restricts, its setting and env, for [describe] (the role allowlist reuses this class). */
    val what: String = "models",
    val setting: String = SETTING,
    val envName: String = ENV,
) {
    /** No pattern = no restriction. */
    val restricts: Boolean get() = patterns.isNotEmpty()

    /** Whether [model] may be used. Always true without a restriction; a null / blank model is never allowed under one. */
    fun allows(model: String?): Boolean {
        if (!restricts) return true
        val id = model?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        return patterns.any { matches(it, id) }
    }

    /** [models] narrowed to the allowed ones, order kept. */
    fun filter(models: List<String>): List<String> = if (!restricts) models else models.filter { allows(it) }

    /** The sentence a refusal ends with: which models are allowed and where that is set. */
    fun describe(): String =
        "Allowed $what: ${patterns.joinToString()} (setting '$setting', env $envName)."

    companion object {
        const val SETTING = "limits.models.allowed"
        const val ENV = "AGENTHQ_ALLOWED_MODELS"
        val NONE = ModelAllowlist(emptyList())

        /** LIMITS: the role allowlist's setting and env (same matching, see [roles]). */
        const val ROLES_SETTING = "limits.roles.allowed"
        const val ROLES_ENV = "AGENTHQ_ALLOWED_ROLES"

        /** A role allowlist from a comma list; empty = every role. */
        fun roles(raw: String?): ModelAllowlist = parse(raw).copy(what = "roles", setting = ROLES_SETTING, envName = ROLES_ENV)

        /** A comma (or newline) separated list; blanks dropped, duplicates removed, order kept. */
        fun parse(raw: String?): ModelAllowlist =
            ModelAllowlist(raw.orEmpty().split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct())

        /** [pattern] against [id]: case-insensitive, `*` = any run of characters (none included). */
        fun matches(pattern: String, id: String): Boolean {
            val p = pattern.trim().lowercase()
            val s = id.trim().lowercase()
            if ('*' !in p) return p == s
            val parts = p.split('*')
            if (!s.startsWith(parts.first())) return false
            var at = parts.first().length
            for (i in 1 until parts.size - 1) {
                val found = s.indexOf(parts[i], at)
                if (found < 0) return false
                at = found + parts[i].length
            }
            val last = parts.last()
            return s.length - at >= last.length && s.endsWith(last)
        }
    }
}
