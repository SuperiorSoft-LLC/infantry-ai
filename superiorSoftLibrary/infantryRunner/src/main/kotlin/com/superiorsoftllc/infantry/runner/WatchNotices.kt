package com.superiorsoftllc.infantry.runner

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * STALL-4 (ticket 14ac9383) item 1: every text the watch sends to a session or a user (job notices, heartbeats, the stall
 * detector's nudges, the runner's own notes) is a TEMPLATE in the resource [RESOURCE], never a literal in code. A template
 * names no role and assumes no recipient exists: a user may have no PM, no owner and no team at all; [roleWords] is the
 * guard ([validate] refuses an override that uses one, a test fails if a shipped one does).
 *
 * The setting [SETTING] (a JSON object, key -> template) is merged over the defaults by key, so a project or a plugin
 * that writes it rewords any notice. Placeholders are `{name}`; an unknown one is left as written.
 *
 * Where a notice must reach someone besides the session (a long wait at the heartbeat cap, an opt-out, a stall line), the
 * daemon's recipient rule ([ESCALATE_SETTING]) picks the address: the ticket's starter session if there is one, else the
 * user through the UI ([USER]: an attention item, not mail); the text itself names no one.
 */
object WatchNotices {
    const val RESOURCE = "/watch/notices.json"
    const val SETTING = "watch.notices"
    /** `starter` (default: the ticket's starter, else the user), `user` (always the user), `none` (nobody). */
    const val ESCALATE_SETTING = "watch.escalateTo"
    val ESCALATE_VALUES = setOf("starter", "user", "none")
    /** The address that means "the user, through the UI" (the daemon raises an attention item for it). */
    const val USER = "user"

    private val serializer = MapSerializer(String.serializer(), String.serializer())

    val DEFAULTS: Map<String, String> by lazy {
        val text = WatchNotices::class.java.getResourceAsStream(RESOURCE)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("watch notices resource $RESOURCE missing")
        Json.decodeFromString(serializer, text)
    }

    /** The overrides in force (the daemon reads the setting); empty = the shipped wording. */
    @Volatile
    var overrides: () -> Map<String, String> = { emptyMap() }

    /** Words a template may not contain: a role, or an assumed recipient. */
    private val ROLE_WORDS = Regex("""\b(PMs?|owners?|workers?)\b|report to""", RegexOption.IGNORE_CASE)

    fun roleWords(text: String): List<String> = ROLE_WORDS.findAll(text).map { it.value }.toList()

    fun parse(text: String): Map<String, String> = Json.decodeFromString(serializer, text)

    /** Throws when an override names an unknown key or uses a role word. */
    fun validate(overrides: Map<String, String>) {
        for ((k, v) in overrides) {
            require(k in DEFAULTS) { "unknown notice '$k' (known: ${DEFAULTS.keys.sorted().joinToString(", ")})" }
            val bad = roleWords(v)
            require(bad.isEmpty()) { "notice '$k' names a role or a recipient (${bad.distinct().joinToString(", ")}); notices must be role-neutral" }
        }
    }

    /** The template [key] with [values] filled in. */
    fun text(key: String, vararg values: Pair<String, Any?>): String {
        val template = runCatching { overrides()[key] }.getOrNull()?.takeIf { roleWords(it).isEmpty() } ?: DEFAULTS[key] ?: key
        if (values.isEmpty()) return template
        val map = values.associate { it.first to (it.second?.toString() ?: "") }
        return Regex("""\{([A-Za-z]+)}""").replace(template) { m -> map[m.groupValues[1]] ?: m.value }
    }
}
