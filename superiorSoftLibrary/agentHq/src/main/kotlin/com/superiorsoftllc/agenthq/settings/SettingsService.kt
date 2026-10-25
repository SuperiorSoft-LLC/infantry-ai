package com.superiorsoftllc.agenthq.settings

import com.superiorsoftllc.agenthq.backlog.WeightExecutors
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
import com.superiorsoftllc.agenthq.usage.ManualUsage
import com.superiorsoftllc.agenthq.usage.PacingRule
import com.superiorsoftllc.agenthq.usage.UsageOverride
import com.superiorsoftllc.agenthq.usage.UsagePacing
import com.superiorsoftllc.agenthq.usage.UsageSet

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
            TICKET_WEIGHT_EXECUTORS to weightExecutors().toJson(),
            USAGE_PACING to UsagePacing.ruleJson(usagePacing()),
            USAGE_MANUAL to UsagePacing.manualJson(usageManual()),
            USAGE_OVERRIDES to UsagePacing.overridesJson(usageOverrides()),
            USAGE_REFRESH_MINUTES to JsonPrimitive(usageRefreshMinutes()),
            PROCESSES_AUTO_REAP to JsonPrimitive(processWatchConfig().autoReap),
            PROCESSES_IDLE_MINUTES to JsonPrimitive(processWatchConfig().idleMinutes),
            PROCESSES_HEAVY_MB to JsonPrimitive(processWatchConfig().heavyMb),
            PROCESSES_FLOOR_MB to JsonPrimitive(processWatchConfig().floorMb),
            LIMITS_BUILD_PRIORITY to JsonPrimitive(buildLimits().priority.key),
            LIMITS_BUILD_MAX_WORKERS to JsonPrimitive(buildLimits().maxWorkers),
            LIMITS_BUILD_MAX_PARALLEL_FORKS to JsonPrimitive(buildLimits().maxParallelForks),
            LIMITS_CPU_ALERT_PERCENT to JsonPrimitive(cpuAlert().percent),
            LIMITS_CPU_ALERT_MINUTES to JsonPrimitive(cpuAlert().minutes),
        ) + (userPortrait()?.let { mapOf(USER_PORTRAIT to JsonPrimitive(it)) } ?: emptyMap()),
    )

    /** USER-PORTRAIT (1.107.0): the stored base64 portrait, or null while unset (clients then show their bundled default). */
    fun userPortrait(): String? = store.settings.get(USER_PORTRAIT)?.value
        ?.let { runCatching { (Json.parseToJsonElement(it) as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.getOrNull() }
        ?.takeIf { it.isNotEmpty() }

    private fun requireUserPortrait(value: JsonElement): String {
        val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw IllegalArgumentException("setting '$USER_PORTRAIT' must be a base64 string (\"\" clears it)")
        if (text.isEmpty()) return text
        val bytes = try {
            java.util.Base64.getDecoder().decode(text)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("setting '$USER_PORTRAIT' is not valid base64")
        }
        try {
            com.superiorsoftllc.agenthq.role.AvatarUploads.validate(bytes)
        } catch (e: com.superiorsoftllc.agenthq.role.InvalidAvatarException) {
            throw IllegalArgumentException("setting '$USER_PORTRAIT': ${e.message}")
        }
        return text
    }

    /** TICKET-WEIGHT: the weight -> executor mapping, read at every start; an unreadable stored value falls back to the default. */
    fun weightExecutors(): WeightExecutors {
        val stored = store.settings.get(TICKET_WEIGHT_EXECUTORS)?.value ?: return WeightExecutors.DEFAULT
        return runCatching { WeightExecutors.parse(Json.parseToJsonElement(stored)) }.getOrDefault(WeightExecutors.DEFAULT)
    }

    /** USAGE-PACE (1.121.0): the pacing rule, read at every report; an unreadable stored value falls back to the default. */
    fun usagePacing(): PacingRule {
        val stored = store.settings.get(USAGE_PACING)?.value ?: return PacingRule.DEFAULT
        return runCatching { PacingRule.parse(Json.parseToJsonElement(stored)) }.getOrDefault(PacingRule.DEFAULT)
    }

    /** USAGE-AUTO (1.124.0): minutes between automatic live usage reads; default 5, 0 = off, read at every tick. */
    fun usageRefreshMinutes(): Int {
        val stored = store.settings.get(USAGE_REFRESH_MINUTES)?.value ?: return USAGE_REFRESH_DEFAULT
        return runCatching { (Json.parseToJsonElement(stored) as JsonPrimitive).content.toInt() }
            .getOrNull()?.takeIf { it in USAGE_REFRESH_RANGE } ?: USAGE_REFRESH_DEFAULT
    }

    /** USAGE-PACE: hand-typed percents per meter (expired ones included; the pacer drops them). */
    fun usageManual(): Map<String, ManualUsage> {
        val stored = store.settings.get(USAGE_MANUAL)?.value ?: return emptyMap()
        return runCatching { UsagePacing.parseManual(Json.parseToJsonElement(stored)) }.getOrDefault(emptyMap())
    }

    /** USAGE-PACE: hand-set allowances per meter (expired ones included; the pacer drops them). */
    fun usageOverrides(): Map<String, UsageOverride> {
        val stored = store.settings.get(USAGE_OVERRIDES)?.value ?: return emptyMap()
        return runCatching { UsagePacing.parseOverrides(Json.parseToJsonElement(stored)) }.getOrDefault(emptyMap())
    }

    /**
     * USAGE-PACE: one `usage_set` ([UsageSet]): the rule replaced and/or one meter's manual entry / override set or
     * cleared; a read-modify-write of the maps, expired entries dropped on the way. Validates everything before writing.
     */
    @Synchronized
    fun applyUsage(request: UsageSet, now: Long = System.currentTimeMillis()) {
        val meter = request.meter.trim().lowercase()
        val touchesMeter = request.clearManual || request.manualPercent != null || request.clearOverride || request.overrideUntil != null
        require(request.rule != null || touchesMeter) { "usage_set changes nothing" }
        require(!touchesMeter || meter.isNotEmpty()) { "usage_set needs a meter" }
        request.manualPercent?.let { pct ->
            require(pct in 0.0..100.0) { "percent must be in 0..100" }
            require(request.manualResetsAt == null || request.manualResetsAt > now) { "resetsAt must be in the future" }
        }
        request.overrideUntil?.let { until ->
            require(until > now) { "override 'until' must be in the future" }
            require(request.overrideWorkers == null || request.overrideWorkers in 0..PacingRule.MAX_WORKERS) {
                "workers must be in 0..${PacingRule.MAX_WORKERS} or none"
            }
        }
        val rule = request.rule?.let { usageSetting(USAGE_PACING) { PacingRule.parse(it) } }
        rule?.let { set(USAGE_PACING, UsagePacing.ruleJson(it)) }
        if (request.clearManual || request.manualPercent != null) {
            val next = usageManual().filterValues { it.expiresAt > now }.toMutableMap()
            if (request.clearManual) next.remove(meter)
            request.manualPercent?.let { next[meter] = ManualUsage(it, request.manualResetsAt, now) }
            set(USAGE_MANUAL, UsagePacing.manualJson(next))
        }
        if (request.clearOverride || request.overrideUntil != null) {
            val next = usageOverrides().filterValues { it.until > now }.toMutableMap()
            if (request.clearOverride) next.remove(meter)
            request.overrideUntil?.let { next[meter] = UsageOverride(request.overrideWorkers, it) }
            set(USAGE_OVERRIDES, UsagePacing.overridesJson(next))
        }
    }

    private fun <T> usageSetting(key: String, parse: () -> T): T = try {
        parse()
    } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("setting '$key' ${e.message}")
    }

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
            TICKET_WEIGHT_EXECUTORS -> try {
                WeightExecutors.parse(value).toJson()
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("setting '$TICKET_WEIGHT_EXECUTORS' ${e.message}")
            }
            USER_PORTRAIT -> JsonPrimitive(requireUserPortrait(value))
            USAGE_PACING -> UsagePacing.ruleJson(usageSetting(key) { PacingRule.parse(value) })
            USAGE_MANUAL -> UsagePacing.manualJson(usageSetting(key) { UsagePacing.parseManual(value) })
            USAGE_OVERRIDES -> UsagePacing.overridesJson(usageSetting(key) { UsagePacing.parseOverrides(value) })
            USAGE_REFRESH_MINUTES -> JsonPrimitive(requireIntIn(key, value, USAGE_REFRESH_RANGE))
            PROCESSES_AUTO_REAP -> JsonPrimitive(requireBoolean(key, value))
            PROCESSES_IDLE_MINUTES -> JsonPrimitive(requireIntIn(key, value, PROCESSES_IDLE_RANGE))
            PROCESSES_HEAVY_MB, PROCESSES_FLOOR_MB -> JsonPrimitive(requireIntIn(key, value, PROCESSES_MB_RANGE))
            LIMITS_BUILD_PRIORITY -> JsonPrimitive(requireBuildPriority(value).key)
            LIMITS_BUILD_MAX_WORKERS, LIMITS_BUILD_MAX_PARALLEL_FORKS -> JsonPrimitive(requireIntIn(key, value, LIMITS_BUILD_COUNT_RANGE))
            LIMITS_CPU_ALERT_PERCENT -> JsonPrimitive(requireIntIn(key, value, LIMITS_CPU_PERCENT_RANGE))
            LIMITS_CPU_ALERT_MINUTES -> JsonPrimitive(requireIntIn(key, value, LIMITS_CPU_MINUTES_RANGE))
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

    /** ROGUE-1 (1.120.0): the rogue process detector's settings, read on every timer pass; a bad stored value falls back. */
    fun processWatchConfig(): com.superiorsoftllc.agenthq.session.ProcessWatchConfig {
        val d = com.superiorsoftllc.agenthq.session.ProcessWatchConfig()
        fun int(key: String, range: IntRange, default: Int): Int {
            val stored = store.settings.get(key)?.value ?: return default
            val parsed = runCatching { (Json.parseToJsonElement(stored) as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull }.getOrNull()
            return parsed?.takeIf { it in range } ?: default
        }
        val autoReap = store.settings.get(PROCESSES_AUTO_REAP)?.value
            ?.let { runCatching { (Json.parseToJsonElement(it) as? JsonPrimitive)?.booleanOrNull }.getOrNull() } ?: d.autoReap
        return com.superiorsoftllc.agenthq.session.ProcessWatchConfig(
            idleMinutes = int(PROCESSES_IDLE_MINUTES, PROCESSES_IDLE_RANGE, d.idleMinutes),
            heavyMb = int(PROCESSES_HEAVY_MB, PROCESSES_MB_RANGE, d.heavyMb.toInt()).toLong(),
            floorMb = int(PROCESSES_FLOOR_MB, PROCESSES_MB_RANGE, d.floorMb.toInt()).toLong(),
            autoReap = autoReap,
        )
    }

    private fun storedInt(key: String, range: IntRange, default: Int): Int {
        val stored = store.settings.get(key)?.value ?: return default
        val parsed = runCatching { (Json.parseToJsonElement(stored) as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull }.getOrNull()
        return parsed?.takeIf { it in range } ?: default
    }

    /**
     * Ticket 66153313: what worker sessions' builds may use, read at every run launch; a bad stored value falls back to the
     * default. Defaults: priority `belownormal`, maxWorkers = logical cores - 2 (0 = unlimited), maxParallelForks unset
     * (0 = each build script's own default).
     */
    fun buildLimits(): com.superiorsoftllc.agenthq.runner.BuildLimits {
        val priority = store.settings.get(LIMITS_BUILD_PRIORITY)?.value
            ?.let { runCatching { (Json.parseToJsonElement(it) as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.getOrNull() }
            ?.let(com.superiorsoftllc.agenthq.runner.BuildPriority::parse)
            ?: com.superiorsoftllc.agenthq.runner.BuildPriority.DEFAULT
        return com.superiorsoftllc.agenthq.runner.BuildLimits(
            priority = priority,
            maxWorkers = storedInt(LIMITS_BUILD_MAX_WORKERS, LIMITS_BUILD_COUNT_RANGE, com.superiorsoftllc.agenthq.runner.BuildLimits.defaultMaxWorkers()),
            maxParallelForks = storedInt(LIMITS_BUILD_MAX_PARALLEL_FORKS, LIMITS_BUILD_COUNT_RANGE, 0),
        )
    }

    /** Ticket 66153313: the sustained-CPU attention rule, read at every process-watch pass. Default 90 % for 10 min; 0 min = off. */
    fun cpuAlert(): com.superiorsoftllc.agenthq.session.CpuAlertConfig {
        val d = com.superiorsoftllc.agenthq.session.CpuAlertConfig()
        return com.superiorsoftllc.agenthq.session.CpuAlertConfig(
            percent = storedInt(LIMITS_CPU_ALERT_PERCENT, LIMITS_CPU_PERCENT_RANGE, d.percent),
            minutes = storedInt(LIMITS_CPU_ALERT_MINUTES, LIMITS_CPU_MINUTES_RANGE, d.minutes),
        )
    }

    private fun requireBuildPriority(value: JsonElement): com.superiorsoftllc.agenthq.runner.BuildPriority =
        (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(com.superiorsoftllc.agenthq.runner.BuildPriority::parse)
            ?: throw IllegalArgumentException(
                "setting '$LIMITS_BUILD_PRIORITY' must be one of ${com.superiorsoftllc.agenthq.runner.BuildPriority.KEYS.joinToString { "\"$it\"" }}",
            )

    private fun requireIntIn(key: String, value: JsonElement, range: IntRange): Int {
        val v = (value as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull()
        require(v != null && v in range) { "setting '$key' must be a JSON integer in $range" }
        return v
    }

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
        /** USER-PORTRAIT: base64 PNG/WebP (<= 256 KB) of the human's portrait; "" clears it. */
        const val USER_PORTRAIT = "user.portrait"
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
        /** TICKET-WEIGHT: JSON object weight ("1".."5") -> {provider, model}; see [WeightExecutors]. */
        const val TICKET_WEIGHT_EXECUTORS = WeightExecutors.SETTING
        /** USAGE-PACE (1.121.0): see [PacingRule], [ManualUsage], [UsageOverride]. */
        const val USAGE_PACING = UsagePacing.RULE_SETTING
        const val USAGE_MANUAL = UsagePacing.MANUAL_SETTING
        const val USAGE_OVERRIDES = UsagePacing.OVERRIDES_SETTING
        /** USAGE-AUTO (1.124.0): minutes between automatic live usage reads (0 = off). */
        const val USAGE_REFRESH_MINUTES = "usage.refreshMinutes"
        const val USAGE_REFRESH_DEFAULT = 5
        private val USAGE_REFRESH_RANGE = 0..1440
        const val EXIT_LEAVE = "leave"
        const val EXIT_DONE = "done"
        private val EXIT_BEHAVIORS = listOf(EXIT_LEAVE, EXIT_DONE)
        /** ROGUE-1 (1.120.0): the rogue process detector (auto-reap is opt-in, default false). */
        const val PROCESSES_AUTO_REAP = "processes.autoReap"
        const val PROCESSES_IDLE_MINUTES = "processes.idleMinutes"
        const val PROCESSES_HEAVY_MB = "processes.heavyMb"
        const val PROCESSES_FLOOR_MB = "processes.floorMb"
        /**
         * Ticket 66153313 (contract 1.132.0): worker build limits. `limits.build.priority` = normal | belownormal | idle
         * (default belownormal); `limits.build.maxWorkers` = Gradle worker cap (default logical cores - 2, 0 = unlimited);
         * `limits.build.maxParallelForks` = test forks per test task (default 0 = the build script's own default);
         * `limits.cpu.alertPercent` / `limits.cpu.alertMinutes` = raise an attention item when agent processes hold more
         * than that share of all cores for that long (default 90 % for 10 min; 0 min = off).
         */
        const val LIMITS_BUILD_PRIORITY = "limits.build.priority"
        const val LIMITS_BUILD_MAX_WORKERS = "limits.build.maxWorkers"
        const val LIMITS_BUILD_MAX_PARALLEL_FORKS = "limits.build.maxParallelForks"
        const val LIMITS_CPU_ALERT_PERCENT = "limits.cpu.alertPercent"
        const val LIMITS_CPU_ALERT_MINUTES = "limits.cpu.alertMinutes"
        private val LIMITS_BUILD_COUNT_RANGE = 0..4096
        private val LIMITS_CPU_PERCENT_RANGE = 1..100
        private val LIMITS_CPU_MINUTES_RANGE = 0..1440
        private val PROCESSES_IDLE_RANGE = 1..1440
        private val PROCESSES_MB_RANGE = 0..1_048_576
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
