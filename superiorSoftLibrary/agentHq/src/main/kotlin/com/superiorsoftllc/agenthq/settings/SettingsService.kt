package com.superiorsoftllc.agenthq.settings

import com.superiorsoftllc.agenthq.persistence.AgentHqStore
import com.superiorsoftllc.agenthq.persistence.SettingRow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import com.superiorsoftllc.agenthq.session.host.LifecyclePolicy

/**
 * The daemon's closed set of durable project-level settings: `context.includeLinkedTasksContext` and, since P10-K11
 * (contract 1.55.0), `context.includeSkillsContext` (both booleans, default true) and `context.skillIds` (the project
 * baseline skills, an array of existing skill ids, default `[]`). BUG-M2-11b-2: `session.keepAliveMinutes`, the one
 * session-lifecycle setting (docs/AGENTHQ-SESSION-HOST-PLAN.md section 8), an int 0..1440, default 30; 0 = every harness
 * runs one-shot. PROMPT-1 (contract 1.77.0): `prompts.timeoutSec`, how long a `prompt_request` waits for an answer
 * before it resolves as deny / no answer, an int 1..86400, default 600. IMG-5: `images.chatImageRetentionDays`, how
 * many days an unused chat upload is kept before the retention sweep deletes it, an int 0..3650, default 14; 0 = keep
 * forever.
 */
class SettingsService(private val store: AgentHqStore) : ProjectSettings {
    /**
     * BUG-M2-11f (plan section 8): told after `session.keepAliveMinutes` is stored, so kept processes are re-evaluated
     * against the new value at once instead of at their next launch. The daemon points it at the session manager.
     */
    @Volatile
    var onKeepAliveChanged: (() -> Unit)? = null

    override fun all(): JsonObject = JsonObject(
        mapOf(
            INCLUDE_LINKED_TASKS_CONTEXT to (storedBoolean(INCLUDE_LINKED_TASKS_CONTEXT) ?: JsonPrimitive(true)),
            INCLUDE_SKILLS_CONTEXT to (storedBoolean(INCLUDE_SKILLS_CONTEXT) ?: JsonPrimitive(true)),
            SKILL_IDS to JsonArray(skillIds().map(::JsonPrimitive)),
            KEEP_ALIVE_MINUTES to JsonPrimitive(keepAliveMinutes()),
            PROMPTS_TIMEOUT_SEC to JsonPrimitive(promptTimeoutSec()),
            CHAT_IMAGE_RETENTION_DAYS to JsonPrimitive(chatImageRetentionDays()),
            QUESTIONS_MAX_OPEN_PER_AGENT to JsonPrimitive(questionsMaxOpenPerAgent()),
            QUESTIONS_MAX_PER_HOUR to JsonPrimitive(questionsMaxPerHour()),
            TICKET_EXIT_BEHAVIOR to JsonPrimitive(ticketExitBehavior()),
        ),
    )

    /** `leave` (default) or `done`: what a clean worker exit does to its in_progress ticket; read at every run end. */
    fun ticketExitBehavior(): String {
        val stored = store.settings.get(TICKET_EXIT_BEHAVIOR)?.value ?: return EXIT_LEAVE
        val parsed = runCatching { (Json.parseToJsonElement(stored) as? JsonPrimitive)?.takeIf { it.isString }?.content }.getOrNull()
        return parsed?.takeIf { it in EXIT_BEHAVIORS } ?: EXIT_LEAVE
    }

    private fun requireExitBehavior(value: JsonElement): String {
        val behavior = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
        require(behavior != null && behavior in EXIT_BEHAVIORS) {
            "setting '$TICKET_EXIT_BEHAVIOR' must be one of ${EXIT_BEHAVIORS.joinToString { "\"$it\"" }}"
        }
        return behavior
    }

    override fun set(key: String, value: JsonElement): JsonObject {
        val stored: JsonElement = when (key) {
            INCLUDE_LINKED_TASKS_CONTEXT, INCLUDE_SKILLS_CONTEXT -> JsonPrimitive(requireBoolean(key, value))
            SKILL_IDS -> JsonArray(requireSkillIds(value).map(::JsonPrimitive))
            KEEP_ALIVE_MINUTES -> JsonPrimitive(requireKeepAliveMinutes(value))
            PROMPTS_TIMEOUT_SEC -> JsonPrimitive(requirePromptTimeoutSec(value))
            CHAT_IMAGE_RETENTION_DAYS -> JsonPrimitive(requireChatImageRetentionDays(value))
            QUESTIONS_MAX_OPEN_PER_AGENT, QUESTIONS_MAX_PER_HOUR -> JsonPrimitive(requireQuestionLimit(key, value))
            TICKET_EXIT_BEHAVIOR -> JsonPrimitive(requireExitBehavior(value))
            else -> throw IllegalArgumentException("unknown setting '$key'")
        }
        store.settings.put(
            SettingRow(
                key = key,
                value = stored.toString(),
                updatedAt = System.currentTimeMillis(),
            ),
        )
        if (key == KEEP_ALIVE_MINUTES) runCatching { onKeepAliveChanged?.invoke() }
        return all()
    }

    override fun includeLinkedTasksContext(): Boolean? = storedBoolean(INCLUDE_LINKED_TASKS_CONTEXT)?.booleanOrNull

    override fun includeSkillsContext(): Boolean? = storedBoolean(INCLUDE_SKILLS_CONTEXT)?.booleanOrNull

    /** Read on every launch decision, never cached (plan section 8). */
    fun keepAliveMinutes(): Int {
        val stored = store.settings.get(KEEP_ALIVE_MINUTES)?.value ?: return LifecyclePolicy.DEFAULT_KEEP_ALIVE_MINUTES
        val parsed = (Json.parseToJsonElement(stored) as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
        check(parsed != null && parsed in KEEP_ALIVE_RANGE) { "stored setting '$KEEP_ALIVE_MINUTES' is not an int in $KEEP_ALIVE_RANGE" }
        return parsed
    }

    /** PROMPT-1: read at every prompt open, never cached; an unreadable stored value falls back to the default. */
    fun promptTimeoutSec(): Int {
        val stored = store.settings.get(PROMPTS_TIMEOUT_SEC)?.value ?: return DEFAULT_PROMPT_TIMEOUT_SEC
        val parsed = runCatching { (Json.parseToJsonElement(stored) as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull }.getOrNull()
        return parsed?.takeIf { it in PROMPT_TIMEOUT_RANGE } ?: DEFAULT_PROMPT_TIMEOUT_SEC
    }

    /** IMG-5: read at every sweep, never cached; an unreadable stored value falls back to the default. */
    fun chatImageRetentionDays(): Int {
        val stored = store.settings.get(CHAT_IMAGE_RETENTION_DAYS)?.value ?: return DEFAULT_CHAT_IMAGE_RETENTION_DAYS
        val parsed = runCatching { (Json.parseToJsonElement(stored) as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull }.getOrNull()
        return parsed?.takeIf { it in CHAT_IMAGE_RETENTION_RANGE } ?: DEFAULT_CHAT_IMAGE_RETENTION_DAYS
    }

    /** ASK-1: read at every `question_ask`, never cached; an unreadable stored value falls back to the default. */
    fun questionsMaxOpenPerAgent(): Int = storedQuestionLimit(QUESTIONS_MAX_OPEN_PER_AGENT, DEFAULT_QUESTIONS_MAX_OPEN)

    /** ASK-1: read at every `question_ask`, never cached; an unreadable stored value falls back to the default. */
    fun questionsMaxPerHour(): Int = storedQuestionLimit(QUESTIONS_MAX_PER_HOUR, DEFAULT_QUESTIONS_MAX_PER_HOUR)

    private fun storedQuestionLimit(key: String, default: Int): Int {
        val stored = store.settings.get(key)?.value ?: return default
        val parsed = runCatching { (Json.parseToJsonElement(stored) as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull }.getOrNull()
        return parsed?.takeIf { it in QUESTION_LIMIT_RANGE } ?: default
    }

    private fun requireQuestionLimit(key: String, value: JsonElement): Int {
        val limit = (value as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull()
        require(limit != null && limit in QUESTION_LIMIT_RANGE) { "setting '$key' must be a JSON integer in $QUESTION_LIMIT_RANGE" }
        return limit
    }

    override fun skillIds(): List<String> {
        val stored = store.settings.get(SKILL_IDS)?.value ?: return emptyList()
        val parsed = Json.parseToJsonElement(stored) as? JsonArray
        val ids = parsed?.map { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }
        check(ids != null && ids.none { it == null }) { "stored setting '$SKILL_IDS' is not a JSON array of strings" }
        return ids.filterNotNull()
    }

    private fun requireBoolean(key: String, value: JsonElement): Boolean =
        (value as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
            ?: throw IllegalArgumentException("setting '$key' must be a JSON boolean")

    private fun requireKeepAliveMinutes(value: JsonElement): Int {
        val primitive = (value as? JsonPrimitive)?.takeIf { !it.isString }
        val minutes = primitive?.content?.toIntOrNull()
        require(minutes != null && minutes in KEEP_ALIVE_RANGE) {
            "setting '$KEEP_ALIVE_MINUTES' must be a JSON integer in $KEEP_ALIVE_RANGE (minutes; 0 = keep-alive off)"
        }
        return minutes
    }

    private fun requirePromptTimeoutSec(value: JsonElement): Int {
        val seconds = (value as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull()
        require(seconds != null && seconds in PROMPT_TIMEOUT_RANGE) {
            "setting '$PROMPTS_TIMEOUT_SEC' must be a JSON integer in $PROMPT_TIMEOUT_RANGE (seconds)"
        }
        return seconds
    }

    private fun requireChatImageRetentionDays(value: JsonElement): Int {
        val days = (value as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull()
        require(days != null && days in CHAT_IMAGE_RETENTION_RANGE) {
            "setting '$CHAT_IMAGE_RETENTION_DAYS' must be a JSON integer in $CHAT_IMAGE_RETENTION_RANGE (days; 0 = keep forever)"
        }
        return days
    }

    /** K-D9: unknown ids are refused on save; a duplicate is refused too, so the stored order is the attach order. */
    private fun requireSkillIds(value: JsonElement): List<String> {
        val array = value as? JsonArray ?: throw IllegalArgumentException("setting '$SKILL_IDS' must be a JSON array of skill ids")
        val ids = array.map { element ->
            (element as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content?.trim()?.takeIf(String::isNotEmpty)
                ?: throw IllegalArgumentException("setting '$SKILL_IDS' must be a JSON array of skill ids")
        }
        requireKnownSkills(store, ids, "setting '$SKILL_IDS'")
        return ids
    }

    private fun storedBoolean(key: String): JsonPrimitive? {
        val stored = store.settings.get(key)?.value ?: return null
        val parsed = Json.parseToJsonElement(stored) as? JsonPrimitive
        check(parsed != null && !parsed.isString && parsed.booleanOrNull != null) {
            "stored setting '$key' is not a JSON boolean"
        }
        return parsed
    }

    companion object {
        const val INCLUDE_LINKED_TASKS_CONTEXT = "context.includeLinkedTasksContext"
        const val INCLUDE_SKILLS_CONTEXT = "context.includeSkillsContext"
        const val SKILL_IDS = "context.skillIds"
        const val KEEP_ALIVE_MINUTES = "session.keepAliveMinutes"
        const val PROMPTS_TIMEOUT_SEC = "prompts.timeoutSec"
        const val DEFAULT_PROMPT_TIMEOUT_SEC = 600
        const val CHAT_IMAGE_RETENTION_DAYS = "images.chatImageRetentionDays"
        const val DEFAULT_CHAT_IMAGE_RETENTION_DAYS = 14
        private val CHAT_IMAGE_RETENTION_RANGE = 0..3650
        /** ASK-1: per-agent owner-question limits (open at once; asked per rolling hour). */
        const val QUESTIONS_MAX_OPEN_PER_AGENT = "questions.maxOpenPerAgent"
        const val QUESTIONS_MAX_PER_HOUR = "questions.maxPerHour"
        const val DEFAULT_QUESTIONS_MAX_OPEN = 3
        const val DEFAULT_QUESTIONS_MAX_PER_HOUR = 6
        /** What a clean worker exit does to its ticket: `leave` it in_progress (default) or move it to `done`. */
        const val TICKET_EXIT_BEHAVIOR = "tickets.exitBehavior"
        const val EXIT_LEAVE = "leave"
        const val EXIT_DONE = "done"
        private val EXIT_BEHAVIORS = listOf(EXIT_LEAVE, EXIT_DONE)
        private val QUESTION_LIMIT_RANGE = 1..100
        private val PROMPT_TIMEOUT_RANGE = 1..86_400
        private val KEEP_ALIVE_RANGE = 0..LifecyclePolicy.MAX_KEEP_ALIVE_MINUTES
    }
}

/**
 * P10-K11 (K-D9): every id in [ids] must name an existing skill and appear once, or [what] is refused. Shared by the
 * project baseline (`context.skillIds`) and `ticket_save`, so both refuse the same way.
 */
fun requireKnownSkills(store: AgentHqStore, ids: List<String>, what: String) {
    val duplicate = ids.groupingBy { it }.eachCount().entries.firstOrNull { it.value > 1 }?.key
    require(duplicate == null) { "$what: skill '${duplicate!!.take(80)}' is listed twice" }
    val unknown = store.transaction { ids.filter { store.skills.get(it) == null } }
    require(unknown.isEmpty()) { "$what: unknown skill ${unknown.joinToString { "'${it.take(80)}'" }}" }
}
