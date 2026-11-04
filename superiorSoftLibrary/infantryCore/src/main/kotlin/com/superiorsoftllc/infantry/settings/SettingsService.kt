// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.settings

import com.superiorsoftllc.infantry.persistence.InfantryStore
import com.superiorsoftllc.infantry.persistence.SettingRow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import com.superiorsoftllc.infantry.session.host.LifecyclePolicy
import com.superiorsoftllc.infantry.daemon.api.usage.ManualUsage
import com.superiorsoftllc.infantry.daemon.api.usage.PacingRule
import com.superiorsoftllc.infantry.daemon.api.usage.UsageOverride
import com.superiorsoftllc.infantry.usage.UsagePacing
import com.superiorsoftllc.infantry.daemon.api.usage.UsageSet

/**
 * The daemon's closed set of durable project-level settings: `context.includeLinkedTasksContext` and, since P10-K11
 * (contract 1.55.0), `context.includeSkillsContext` (both booleans, default true) and `context.skillIds` (the project
 * baseline skills, an array of existing skill ids, default `[]`). BUG-M2-11b-2: `session.keepAliveMinutes`, the one
 * session-lifecycle setting (docs/INFANTRY-SESSION-HOST-PLAN.md section 8), an int 0..1440, default 30; 0 = every harness
 * runs one-shot. PROMPT-1 (contract 1.77.0): `prompts.timeoutSec`, how long a `prompt_request` waits for an answer
 * before it resolves as deny / no answer, an int 0..86400 (0 = no timer, ASK-TIMER 1.139.0), default 600; ASK-TIMER:
 * `prompts.onTimeout` (`deny` default | `approve`), what an unanswered tool approval resolves as. IMG-5: `images.chatImageRetentionDays`, how
 * many days an unused chat upload is kept before the retention sweep deletes it, an int 0..3650, default 14; 0 = keep
 * forever.
 */
class SettingsService(
    private val store: InfantryStore,
    /** Where [limit] reads the environment layer (`INFANTRY_LIMIT_*`); a test passes its own map. */
    private val env: (String) -> String? = com.superiorsoftllc.infantry.session.InfantryEnv::get,
) : ProjectSettings {
    /**
     * BUG-M2-11f (plan section 8): told after `session.keepAliveMinutes` is stored, so kept processes are re-evaluated
     * against the new value at once instead of at their next launch. The daemon points it at the session manager.
     */
    @Volatile
    var onKeepAliveChanged: (() -> Unit)? = null

    init {
        runCatching { store.settings.get(DISPLAY_TIME_ZONE)?.value?.let { com.superiorsoftllc.infantry.DisplayZone.set((Json.parseToJsonElement(it) as JsonPrimitive).content) } }
    }

    /** SETTINGS-SCOPES-1: the layered view (session > role > project > app > default) over this service. */
    val scoped: ScopedSettings by lazy { ScopedSettings(store, this) }

    /**
     * CONFIG-8: the field a plugin declared for a flat settings [key] (`daemon.config`, `scope:"daemon"`); null = none. [set] falls
     * back to its validator for a key it has no `when` for. The daemon points it at its [com.superiorsoftllc.infantry.daemon.DaemonConfig].
     */
    @Volatile
    var declaredField: (String) -> com.superiorsoftllc.infantry.plugin.api.ConfigFieldDeclaration? = { null }

    /** CONFIG-8: the stored JSON of a plugin-declared key, null when never set or reset. */
    fun storedDeclared(key: String): JsonElement? = store.settings.get(key)?.value
        ?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }?.takeIf { it !is kotlinx.serialization.json.JsonNull }

    /** CONFIG-8: whether [key] is one this service validates itself (so a plugin cannot redeclare it). */
    fun isBuiltIn(key: String): Boolean = key in all() || LimitRegistry.get(key) != null

    override fun all(): JsonObject = JsonObject(
        mapOf(
            INCLUDE_LINKED_TASKS_CONTEXT to (storedBoolean(INCLUDE_LINKED_TASKS_CONTEXT) ?: JsonPrimitive(true)),
            INCLUDE_SKILLS_CONTEXT to (storedBoolean(INCLUDE_SKILLS_CONTEXT) ?: JsonPrimitive(true)),
            INCLUDE_FINISH_INSTRUCTIONS to (storedBoolean(INCLUDE_FINISH_INSTRUCTIONS) ?: JsonPrimitive(true)),
            SKILL_IDS to JsonArray(skillIds().map(::JsonPrimitive)),
            KEEP_ALIVE_MINUTES to JsonPrimitive(keepAliveMinutes()),
            PROMPTS_TIMEOUT_SEC to JsonPrimitive(promptTimeoutSec()),
            PROMPTS_ON_TIMEOUT to JsonPrimitive(promptOnTimeout().wire),
            CHAT_IMAGE_RETENTION_DAYS to JsonPrimitive(chatImageRetentionDays()),
            QUESTIONS_MAX_OPEN_PER_AGENT to JsonPrimitive(questionsMaxOpenPerAgent()),
            QUESTIONS_MAX_PER_HOUR to JsonPrimitive(questionsMaxPerHour()),
            TICKET_EXIT_BEHAVIOR to JsonPrimitive(ticketExitBehavior()),
            TICKET_HANDOFF_NOTE to JsonPrimitive(ticketHandoffNote()),
            TICKET_CLOSE_DONE_WORKER_WHEN_QUEUED to JsonPrimitive(runCatching { storedBoolean(TICKET_CLOSE_DONE_WORKER_WHEN_QUEUED)?.booleanOrNull }.getOrNull() ?: true),
            TICKET_WEIGHT_EXECUTORS to weightExecutors().toJson(),
            TICKET_WEIGHT_CONTEXT to weightContext().toJson(),
            USAGE_PACING to UsagePacing.ruleJson(usagePacing()),
            USAGE_MANUAL to UsagePacing.manualJson(usageManual()),
            USAGE_OVERRIDES to UsagePacing.overridesJson(usageOverrides()),
            USAGE_REFRESH_MINUTES to JsonPrimitive(usageRefreshMinutes()),
            DISPLAY_TIME_ZONE to JsonPrimitive(store.settings.get(DISPLAY_TIME_ZONE)?.value?.let { runCatching { (Json.parseToJsonElement(it) as JsonPrimitive).content }.getOrNull() } ?: ""),
            PROCESSES_AUTO_REAP to JsonPrimitive(processWatchConfig().autoReap),
            PROCESSES_IDLE_MINUTES to JsonPrimitive(processWatchConfig().idleMinutes),
            PROCESSES_HEAVY_MB to JsonPrimitive(processWatchConfig().heavyMb),
            PROCESSES_FLOOR_MB to JsonPrimitive(processWatchConfig().floorMb),
            LIMITS_BUILD_PRIORITY to JsonPrimitive(buildLimits().priority.key),
            SANDBOX_ALLOWED_DIRS to JsonArray(sandboxAllowedDirs().map(::JsonPrimitive)),
            ALLOWED_MODELS to JsonArray(allowedModels().patterns.map(::JsonPrimitive)),
            ALLOWED_ROLES to JsonArray(allowedRoles().patterns.map(::JsonPrimitive)),
            NUDGE_DEFER to JsonArray(nudgeDefer().map(::JsonPrimitive)),
            TOOL_PROFILES to toolProfileOverrides(),
            WATCH_NOTICES to JsonObject(watchNotices().mapValues { JsonPrimitive(it.value) }),
            WATCH_ESCALATE_TO to JsonPrimitive(watchEscalateTo()),
            WATCH_FOLLOW_REDIRECTS to JsonPrimitive(watchFollowRedirects()),
            RELATED_BRIEF to JsonPrimitive(relatedConfig().brief),
            RELATED_BRIEF_INSTRUCTION to JsonPrimitive(relatedConfig().instruction),
            RELATED_BRIEF_MODE to JsonPrimitive(relatedConfig().mode),
            RELATED_FILES_FORMAT to JsonPrimitive(relatedConfig().filesFormat),
            RELATED_DESCRIPTION_BLOCK to JsonPrimitive(relatedConfig().descriptionBlock),
            RELATED_FAMILY_PATTERN to JsonPrimitive(relatedConfig().familyPattern),
            RELATED_SUGGEST_NUMBER to JsonPrimitive(relatedConfig().suggestNumber),
            MAIL_AUTOMATION_FOOTER to JsonPrimitive(automationFooter("system") ?: ""),
            MAIL_WAKE_MODE to JsonPrimitive(wakeMode(null, null)),
            MAIL_DELIVERY_MODE to JsonPrimitive(deliveryMode(null, null)),
        ) + LimitRegistry.ALL.associate { it.key to JsonPrimitive(limit(it)) } + (userPortrait()?.let { mapOf(USER_PORTRAIT to JsonPrimitive(it)) } ?: emptyMap()),
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
            com.superiorsoftllc.infantry.protocol.AvatarUploads.validate(bytes)
        } catch (e: com.superiorsoftllc.infantry.protocol.InvalidAvatarException) {
            throw IllegalArgumentException("setting '$USER_PORTRAIT': ${e.message}")
        }
        return text
    }

    /** TICKET-WEIGHT: the weight -> executor mapping, read at every start; an unreadable stored value falls back to the default. */
    fun weightExecutors(): WeightExecutors {
        val stored = store.settings.get(TICKET_WEIGHT_EXECUTORS)?.value ?: return WeightExecutors.DEFAULT
        return runCatching { WeightExecutors.parse(Json.parseToJsonElement(stored)) }.getOrDefault(WeightExecutors.DEFAULT)
    }

    /** TOKEN-7: the weight -> {contextLimit, effort} defaults; an unreadable stored value falls back to the default. */
    fun weightContext(): WeightContext {
        val stored = store.settings.get(TICKET_WEIGHT_CONTEXT)?.value ?: return WeightContext.DEFAULT
        return runCatching { WeightContext.parse(Json.parseToJsonElement(stored)) }.getOrDefault(WeightContext.DEFAULT)
    }

    /**
     * LIMITS (S1 of the no-hard-coded-limits rule): [def]'s effective value, read at every use so a change applies at once:
     * the stored setting, else the environment, else the default. An unreadable stored or env value falls back to the default.
     */
    fun limit(def: LimitDef): Long {
        val stored = store.settings.get(def.key)?.value
            ?.let { runCatching { (Json.parseToJsonElement(it) as? JsonPrimitive)?.takeIf { p -> !p.isString }?.content?.toLongOrNull() }.getOrNull() }
            ?.takeIf { def.accepts(it) }
        return stored ?: runCatching { LimitRegistry.fromEnv(def, env) }.getOrDefault(def.default)
    }

    /** SANDBOX-1: what the sandbox really does on this machine (set by the daemon); shown under limits.sandbox.mode. */
    @Volatile
    var sandboxStatus: (() -> String)? = null

    private fun sandboxStatusLine(): String {
        val status = runCatching { sandboxStatus?.invoke() }.getOrNull() ?: return ""
        val dirs = sandboxAllowedDirs()
        return " Status: $status. Allowed dirs ($SANDBOX_ALLOWED_DIRS): " + (if (dirs.isEmpty()) "none besides each session's working dir" else dirs.joinToString())
    }

    /**
     * SANDBOX-1: the extra directories confined agents may write besides their working dir: the stored setting (a JSON
     * array of absolute paths), else env INFANTRY_LIMIT_SANDBOX_ALLOWED_DIRS (comma separated), else none.
     */
    fun sandboxAllowedDirs(): List<String> {
        store.settings.get(SANDBOX_ALLOWED_DIRS)?.value
            ?.let { runCatching { (Json.parseToJsonElement(it) as? JsonArray)?.mapNotNull { e -> (e as? JsonPrimitive)?.content } }.getOrNull() }
            ?.let { return it }
        return env(SANDBOX_ALLOWED_DIRS_ENV)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
    }

    /**
     * LIMITS: the model allowlist in force, read at every check: the stored setting (a JSON array
     * of patterns; `[]` = no restriction, overriding the env), else env INFANTRY_ALLOWED_MODELS (comma separated), else none.
     */
    fun allowedModels(): ModelAllowlist {
        store.settings.get(ALLOWED_MODELS)?.value
            ?.let { runCatching { (Json.parseToJsonElement(it) as? JsonArray)?.mapNotNull { e -> (e as? JsonPrimitive)?.content } }.getOrNull() }
            ?.let { return ModelAllowlist.parse(it.joinToString(",")) }
        return ModelAllowlist.parse(env(ModelAllowlist.ENV))
    }

    /** NUDGE-SCRIPT-1: the built-in nudges (event names) that defer to script hooks; default none = today's behaviour. */
    fun nudgeDefer(): List<String> = store.settings.get(NUDGE_DEFER)?.value
        ?.let { runCatching { (Json.parseToJsonElement(it) as? JsonArray)?.mapNotNull { e -> (e as? JsonPrimitive)?.content } }.getOrNull() }.orEmpty()

    private fun requireNudgeNames(value: JsonElement): List<String> {
        val items = (value as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        require(items != null && items.all { it != null && it in com.superiorsoftllc.infantry.mailbox.NudgeGate.ALL }) {
            "setting '$NUDGE_DEFER' must be a JSON array of ${com.superiorsoftllc.infantry.mailbox.NudgeGate.ALL.joinToString { "\"$it\"" }}; [] = every built-in nudge is sent as today"
        }
        return items!!.filterNotNull().distinct()
    }

    /** LIMITS: the role allowlist: stored `limits.roles.allowed` (`[]` = all, beats the env) > env INFANTRY_ALLOWED_ROLES > all. */
    fun allowedRoles(): ModelAllowlist {
        store.settings.get(ALLOWED_ROLES)?.value
            ?.let { runCatching { (Json.parseToJsonElement(it) as? JsonArray)?.mapNotNull { e -> (e as? JsonPrimitive)?.content } }.getOrNull() }
            ?.let { return ModelAllowlist.roles(it.joinToString(",")) }
        return ModelAllowlist.roles(env(ModelAllowlist.ROLES_ENV))
    }

    /** A JSON array of non-blank strings, or one comma separated string (`""` = no restriction). */
    private fun requireAllowedModels(value: JsonElement, key: String = ALLOWED_MODELS): List<String> {
        val items = when (value) {
            is JsonArray -> value.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            is JsonPrimitive -> if (value.isString) value.content.split(',') else listOf(null)
            else -> listOf(null)
        }
        require(items.all { it != null }) {
            "setting '$key' must be a JSON array of ids or patterns (`*` = any characters), e.g. [\"opencode-go/beam-free\"] or [\"pm\", \"backend\"]; [] = no restriction"
        }
        return ModelAllowlist.parse(items.filterNotNull().joinToString(",")).patterns
    }

    private fun requireAllowedDirs(value: JsonElement): List<String> {
        val items = (value as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        require(items != null && items.all { it != null && java.io.File(it).isAbsolute }) {
            "setting '$SANDBOX_ALLOWED_DIRS' must be a JSON array of absolute paths, e.g. [\"/work\"]"
        }
        return items.filterNotNull().map { it.trimEnd('/', '\\').ifEmpty { "/" } }
    }

    /** [limit] as a count: 0 on an unlimited-capable limit becomes [Int.MAX_VALUE] (no limit). */
    fun limitCap(def: LimitDef): Int = LimitRegistry.cap(limit(def), def)

    /**
     * Contract 1.133.0 (`limit_catalog`): every [LimitRegistry] entry with its value in force and its documentation, so the
     * Settings UI and `infantry settings limits` show each limit, its default and where it comes from.
     */
    fun limitCatalog(): JsonObject = JsonObject(
        mapOf(
            "limits" to JsonArray(
                LimitRegistry.ALL.map { def ->
                    JsonObject(
                        buildMap {
                            put("key", JsonPrimitive(def.key))
                            put("value", JsonPrimitive(limit(def)))
                            put("default", JsonPrimitive(def.default))
                            put("unit", JsonPrimitive(def.unit))
                            put("description", JsonPrimitive(def.description + (if (def == LimitRegistry.SANDBOX_MODE) sandboxStatusLine() else "")))
                            put("min", JsonPrimitive(def.min))
                            def.max?.let { put("max", JsonPrimitive(it)) }
                            if (def.zeroIsUnlimited) put("zeroMeans", JsonPrimitive(def.zeroMeans))
                            put("env", JsonPrimitive(def.env))
                        },
                    )
                },
            ),
            // LIMITS: the model allowlist (not a number, so beside the list): [] = every model is allowed.
            "allowedModels" to JsonObject(
                mapOf(
                    "key" to JsonPrimitive(ALLOWED_MODELS),
                    "value" to JsonArray(allowedModels().patterns.map(::JsonPrimitive)),
                    "env" to JsonPrimitive(ModelAllowlist.ENV),
                    "description" to JsonPrimitive(
                        "The models sessions, tickets and roles may run on; empty = every model. `*` matches any characters.",
                    ),
                ),
            ),
        ),
    )

    /** One line per environment variable that holds an unusable limit (reported at daemon start, then ignored). */
    fun limitEnvProblems(): List<String> = LimitRegistry.ALL.mapNotNull { def ->
        runCatching { LimitRegistry.fromEnv(def, env) }.exceptionOrNull()?.message
    }

    private fun pacingCeiling(): Int = limitCap(LimitRegistry.PACING_CEILING)

    private fun requireLimit(def: LimitDef, value: JsonElement): Long {
        val v = (value as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()
        require(v != null && def.accepts(v)) {
            "setting '${def.key}' must be a JSON integer in ${def.range()} (${def.unit}" +
                (if (def.zeroIsUnlimited) "; 0 = ${def.zeroMeans}" else "") + "; default ${def.default})"
        }
        return v
    }

    /** USAGE-PACE (1.121.0): the pacing rule, read at every report; an unreadable stored value falls back to the default. */
    fun usagePacing(): PacingRule {
        val stored = store.settings.get(USAGE_PACING)?.value ?: return PacingRule.DEFAULT
        return runCatching { PacingRule.parse(Json.parseToJsonElement(stored), pacingCeiling()) }.getOrDefault(PacingRule.DEFAULT)
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
        return runCatching { UsagePacing.parseOverrides(Json.parseToJsonElement(stored), pacingCeiling()) }.getOrDefault(emptyMap())
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
            require(request.manualResetsAt.let { it == null || it > now }) { "resetsAt must be in the future" }
        }
        request.overrideUntil?.let { until ->
            require(until > now) { "override 'until' must be in the future" }
            require(request.overrideWorkers == null || request.overrideWorkers in 0..pacingCeiling()) {
                "workers must be in 0..${pacingCeiling()} or none (setting ${LimitRegistry.PACING_CEILING.key})"
            }
        }
        val rule = request.rule?.let { usageSetting(USAGE_PACING) { PacingRule.parse(it, pacingCeiling()) } }
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

    /** `auto` (default), `worker` or `off`: see [TICKET_HANDOFF_NOTE]; read at every unassign. */
    fun ticketHandoffNote(): String {
        val stored = store.settings.get(TICKET_HANDOFF_NOTE)?.value ?: return HANDOFF_NOTES.first()
        val parsed = runCatching { (Json.parseToJsonElement(stored) as? JsonPrimitive)?.takeIf { it.isString }?.content }.getOrNull()
        return parsed?.takeIf { it in HANDOFF_NOTES } ?: HANDOFF_NOTES.first()
    }

    private fun requireHandoffNote(value: JsonElement): String {
        val mode = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
        require(mode != null && mode in HANDOFF_NOTES) {
            "setting '$TICKET_HANDOFF_NOTE' must be one of ${HANDOFF_NOTES.joinToString { "\"$it\"" }}"
        }
        return mode
    }

    private fun requireExitBehavior(value: JsonElement): String {
        val behavior = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
        require(behavior != null && behavior in EXIT_BEHAVIORS) {
            "setting '$TICKET_EXIT_BEHAVIOR' must be one of ${EXIT_BEHAVIORS.joinToString { "\"$it\"" }}"
        }
        return behavior
    }

    override fun set(key: String, value: JsonElement): JsonObject {
        val stored = validated(key, value)
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

    /** SETTINGS-SCOPES-1: [value] as it would be stored for [key]; refuses an unknown key or an invalid value. Shared by every scope. */
    fun validated(key: String, value: JsonElement): JsonElement {
        val stored: JsonElement = when (key) {
            INCLUDE_LINKED_TASKS_CONTEXT, INCLUDE_SKILLS_CONTEXT, INCLUDE_FINISH_INSTRUCTIONS -> JsonPrimitive(requireBoolean(key, value))
            SKILL_IDS -> JsonArray(requireSkillIds(value).map(::JsonPrimitive))
            KEEP_ALIVE_MINUTES -> JsonPrimitive(requireKeepAliveMinutes(value))
            PROMPTS_TIMEOUT_SEC -> JsonPrimitive(requirePromptTimeoutSec(value))
            PROMPTS_ON_TIMEOUT -> JsonPrimitive(requirePromptOnTimeout(value).wire)
            CHAT_IMAGE_RETENTION_DAYS -> JsonPrimitive(requireChatImageRetentionDays(value))
            QUESTIONS_MAX_OPEN_PER_AGENT, QUESTIONS_MAX_PER_HOUR -> JsonPrimitive(requireQuestionLimit(key, value))
            TICKET_EXIT_BEHAVIOR -> JsonPrimitive(requireExitBehavior(value))
            TICKET_HANDOFF_NOTE -> JsonPrimitive(requireHandoffNote(value))
            TICKET_CLOSE_DONE_WORKER_WHEN_QUEUED -> JsonPrimitive(requireBoolean(key, value))
            TICKET_WEIGHT_EXECUTORS -> try {
                WeightExecutors.parse(value).toJson()
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("setting '$TICKET_WEIGHT_EXECUTORS' ${e.message}")
            }
            TICKET_WEIGHT_CONTEXT -> try {
                WeightContext.parse(value).toJson()
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("setting '$TICKET_WEIGHT_CONTEXT' ${e.message}")
            }
            USER_PORTRAIT -> JsonPrimitive(requireUserPortrait(value))
            USAGE_PACING -> UsagePacing.ruleJson(usageSetting(key) { PacingRule.parse(value, pacingCeiling()) })
            USAGE_MANUAL -> UsagePacing.manualJson(usageSetting(key) { UsagePacing.parseManual(value) })
            USAGE_OVERRIDES -> UsagePacing.overridesJson(usageSetting(key) { UsagePacing.parseOverrides(value, pacingCeiling()) })
            USAGE_REFRESH_MINUTES -> JsonPrimitive(requireIntIn(key, value, USAGE_REFRESH_RANGE))
            DISPLAY_TIME_ZONE -> JsonPrimitive(((value as? JsonPrimitive)?.content ?: "").trim().also { com.superiorsoftllc.infantry.DisplayZone.set(it) })
            PROCESSES_AUTO_REAP -> JsonPrimitive(requireBoolean(key, value))
            PROCESSES_IDLE_MINUTES -> JsonPrimitive(requireIntIn(key, value, PROCESSES_IDLE_RANGE))
            PROCESSES_HEAVY_MB, PROCESSES_FLOOR_MB -> JsonPrimitive(requireIntIn(key, value, PROCESSES_MB_RANGE))
            LIMITS_BUILD_PRIORITY -> JsonPrimitive(requireBuildPriority(value).key)
            SANDBOX_ALLOWED_DIRS -> JsonArray(requireAllowedDirs(value).map(::JsonPrimitive))
            ALLOWED_MODELS -> JsonArray(requireAllowedModels(value).map(::JsonPrimitive))
            ALLOWED_ROLES -> JsonArray(requireAllowedModels(value, ALLOWED_ROLES).map { JsonPrimitive(it.lowercase()) })
            NUDGE_DEFER -> JsonArray(requireNudgeNames(value).map(::JsonPrimitive))
            TOOL_PROFILES -> requireToolProfiles(value)
            WATCH_NOTICES -> requireWatchNotices(value)
            WATCH_ESCALATE_TO -> JsonPrimitive(
                (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it in com.superiorsoftllc.infantry.runner.WatchNotices.ESCALATE_VALUES }
                    ?: throw IllegalArgumentException("setting '$WATCH_ESCALATE_TO' must be one of ${com.superiorsoftllc.infantry.runner.WatchNotices.ESCALATE_VALUES.joinToString { "\"$it\"" }}"),
            )
            WATCH_FOLLOW_REDIRECTS -> JsonPrimitive(requireBoolean(key, value))
            RELATED_BRIEF, RELATED_DESCRIPTION_BLOCK, RELATED_SUGGEST_NUMBER -> JsonPrimitive(requireBoolean(key, value))
            RELATED_BRIEF_INSTRUCTION -> JsonPrimitive(
                (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.length <= 1000 && '\n' !in it }
                    ?: throw IllegalArgumentException("setting '$key' must be one line of text, at most 1000 characters (\"\" = no instruction line)"),
            )
            RELATED_BRIEF_MODE -> JsonPrimitive(requireOneOf(key, value, com.superiorsoftllc.infantry.ticketfiles.RelatedConfig.MODES))
            RELATED_FILES_FORMAT -> JsonPrimitive(requireOneOf(key, value, com.superiorsoftllc.infantry.ticketfiles.RelatedConfig.FORMATS))
            RELATED_FAMILY_PATTERN -> JsonPrimitive(requireFamilyPattern(value))
            MAIL_AUTOMATION_FOOTER -> JsonPrimitive(requireFooterText(key, value))
            MAIL_WAKE_MODE -> JsonPrimitive(requireWakeMode(key, value))
            MAIL_DELIVERY_MODE -> JsonPrimitive(requireDeliveryMode(key, value))
            else -> if (key.startsWith("$MAIL_DELIVERY_MODE.") && key.length > MAIL_DELIVERY_MODE.length + 1) {
                JsonPrimitive(requireDeliveryMode(key, value))
            } else if (key.startsWith("${LimitRegistry.SESSION_CONTEXT_CAP_TOKENS.key}.") && key.length > LimitRegistry.SESSION_CONTEXT_CAP_TOKENS.key.length + 1) {
                JsonPrimitive(requireLimit(LimitRegistry.SESSION_CONTEXT_CAP_TOKENS, value))
            } else if (key.startsWith("$MAIL_WAKE_MODE.") && key.length > MAIL_WAKE_MODE.length + 1) {
                JsonPrimitive(requireWakeMode(key, value))
            } else if (key.startsWith("$MAIL_AUTOMATION_FOOTER.") && key.length > MAIL_AUTOMATION_FOOTER.length + 1) {
                JsonPrimitive(requireFooterText(key, value))
            } else LimitRegistry.get(key)?.let { JsonPrimitive(requireLimit(it, value)) }
                ?: declaredField(key)?.let { com.superiorsoftllc.infantry.plugin.api.ConfigFieldValidator.validate(it, value) }
                ?: throw IllegalArgumentException("unknown setting '$key'")
        }
        return stored
    }

    /**
     * KNOWLEDGE-PTR-1 (b8ca2bf7): the related-tickets switches, texts and caps, read at every start and ticket save. An
     * unreadable stored value falls back to its default.
     */
    fun relatedConfig(): com.superiorsoftllc.infantry.ticketfiles.RelatedConfig {
        val d = com.superiorsoftllc.infantry.ticketfiles.RelatedConfig.DEFAULT
        fun bool(key: String, default: Boolean) = runCatching { storedBoolean(key)?.booleanOrNull }.getOrNull() ?: default
        fun text(key: String, default: String) = store.settings.get(key)?.value
            ?.let { runCatching { (Json.parseToJsonElement(it) as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.getOrNull() } ?: default
        fun cap(def: LimitDef) = limit(def).toInt()
        return com.superiorsoftllc.infantry.ticketfiles.RelatedConfig(
            brief = bool(RELATED_BRIEF, d.brief),
            instruction = text(RELATED_BRIEF_INSTRUCTION, d.instruction),
            descriptionBlock = bool(RELATED_DESCRIPTION_BLOCK, d.descriptionBlock),
            familyPattern = text(RELATED_FAMILY_PATTERN, d.familyPattern).takeIf { it.isEmpty() || validFamilyPattern(it) } ?: d.familyPattern,
            suggestNumber = bool(RELATED_SUGGEST_NUMBER, d.suggestNumber),
            mode = text(RELATED_BRIEF_MODE, d.mode).takeIf { it in com.superiorsoftllc.infantry.ticketfiles.RelatedConfig.MODES } ?: d.mode,
            filesFormat = text(RELATED_FILES_FORMAT, d.filesFormat).takeIf { it in com.superiorsoftllc.infantry.ticketfiles.RelatedConfig.FORMATS } ?: d.filesFormat,
            briefTickets = cap(LimitRegistry.RELATED_BRIEF_TICKETS),
            briefFiles = cap(LimitRegistry.RELATED_BRIEF_FILES),
            briefChars = cap(LimitRegistry.RELATED_BRIEF_CHARS),
            blockTickets = cap(LimitRegistry.RELATED_BLOCK_TICKETS),
            blockFiles = cap(LimitRegistry.RELATED_BLOCK_FILES),
            blockChars = cap(LimitRegistry.RELATED_BLOCK_CHARS),
        )
    }

    private fun requireOneOf(key: String, value: JsonElement, allowed: List<String>): String =
        (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it in allowed }
            ?: throw IllegalArgumentException("setting '$key' must be one of $allowed")

    private fun requireDeliveryMode(key: String, value: JsonElement): String =
        (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it in DELIVERY_MODES }
            ?: throw IllegalArgumentException("setting '$key' must be one of $DELIVERY_MODES")

    /**
     * MAIL-TRUNC-1: what a mail wake injects. `notify` (recommended default): a NEW MAIL (n) block, one line per mail
     * (id, sender, subject) and the read command, no body (bodies stay in the mailbox; every injected line is re-read on
     * every later turn). `truncate`: bodies cut at limits.mail.injectMaxChars, the turn at limits.mail.injectTotalChars.
     * `full`: every body, as before. Resolution: `mail.deliveryMode.<sessionId>`, `mail.deliveryMode.<role>`, `mail.deliveryMode`, `notify`.
     */
    fun deliveryMode(role: String?, sessionId: String?): String {
        fun stored(key: String) = store.settings.get(key)?.value
            ?.let { runCatching { (Json.parseToJsonElement(it) as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.getOrNull() }
            ?.takeIf { it in DELIVERY_MODES }
        return (sessionId?.let { stored("$MAIL_DELIVERY_MODE.$it") }) ?: (role?.let { stored("$MAIL_DELIVERY_MODE.$it") }) ?: stored(MAIL_DELIVERY_MODE) ?: "notify"
    }

    /** USAGE-OUT-2: the context cap (tokens, 0 = off) for a session: `<key>.<sessionId>`, `<key>.<role>`, then the global limit. */
    fun contextCap(role: String?, sessionId: String?): Long {
        val def = LimitRegistry.SESSION_CONTEXT_CAP_TOKENS
        fun stored(key: String) = store.settings.get(key)?.value
            ?.let { runCatching { (Json.parseToJsonElement(it) as? JsonPrimitive)?.takeIf { p -> !p.isString }?.content?.toLongOrNull() }.getOrNull() }
            ?.takeIf { def.accepts(it) }
        return (sessionId?.let { stored("${def.key}.$it") }) ?: (role?.takeIf { it.isNotBlank() }?.let { stored("${def.key}.$it") }) ?: limit(def)
    }

    private fun requireWakeMode(key: String, value: JsonElement): String =
        (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it in WAKE_MODES }
            ?: throw IllegalArgumentException("setting '$key' must be one of $WAKE_MODES")

    /**
     * USAGE-REGRESS-1: when mail wakes a resting session. `real` (recommended default): real mail wakes it (after
     * limits.mail.wakeDebounceSeconds), automation mail (system / trigger notices) waits in the inbox and rides in the
     * session's next turn. `all`: every mail wakes it (the old behaviour, most tokens). `never`: no mail wakes it; mail
     * is read only when the session takes a turn for another reason (cheapest, but a role that only works on mail,
     * such as a PM waiting for worker reports, stalls). Resolution: `mail.wakeMode.<sessionId>`, `mail.wakeMode.<role>`, `mail.wakeMode`, `real`.
     */
    fun wakeMode(role: String?, sessionId: String?): String {
        fun stored(key: String) = store.settings.get(key)?.value
            ?.let { runCatching { (Json.parseToJsonElement(it) as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.getOrNull() }
            ?.takeIf { it in WAKE_MODES }
        return (sessionId?.let { stored("$MAIL_WAKE_MODE.$it") }) ?: (role?.let { stored("$MAIL_WAKE_MODE.$it") }) ?: stored(MAIL_WAKE_MODE) ?: "real"
    }

    private fun requireFooterText(key: String, value: JsonElement): String =
        (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.length <= 2000 }
            ?: throw IllegalArgumentException("setting '$key' must be text, at most 2000 characters (\"\" = switched off: the normal answer-this-message footer)")

    /**
     * TRIGGER-FOOTER-1: the footer template for mail from an automatic sender ([sender] = `trigger:<id>` or `system`):
     * the per-trigger `mail.automationFooter.<id>` if stored, else `mail.automationFooter`, else [MailFooter.DEFAULT].
     * "" = off (null: the caller renders the normal block). Read at every delivery.
     */
    fun automationFooter(sender: String): String? {
        fun stored(key: String) = store.settings.get(key)?.value
            ?.let { runCatching { (Json.parseToJsonElement(it) as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.getOrNull() }
        val text = (if (sender.startsWith("trigger:")) stored("$MAIL_AUTOMATION_FOOTER.${sender.removePrefix("trigger:")}") else null)
            ?: stored(MAIL_AUTOMATION_FOOTER)
            ?: com.superiorsoftllc.infantry.mailbox.MailFooter.DEFAULT
        return text.takeIf { it.isNotBlank() }
    }

    private fun validFamilyPattern(text: String): Boolean = runCatching { Regex(text).toPattern().matcher("").groupCount() >= 2 }.getOrDefault(false)

    private fun requireFamilyPattern(value: JsonElement): String {
        val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
        require(text != null && (text.isEmpty() || validFamilyPattern(text))) {
            "setting '$RELATED_FAMILY_PATTERN' must be a regular expression with two groups (1 = series label, 2 = number), or \"\" for no families"
        }
        return text
    }

    override fun includeLinkedTasksContext(): Boolean? = storedBoolean(INCLUDE_LINKED_TASKS_CONTEXT)?.booleanOrNull

    override fun includeSkillsContext(): Boolean? = storedBoolean(INCLUDE_SKILLS_CONTEXT)?.booleanOrNull

    override fun includeFinishInstructions(): Boolean? = storedBoolean(INCLUDE_FINISH_INSTRUCTIONS)?.booleanOrNull

    /** Read on every launch decision, never cached (plan section 8). */
    fun keepAliveMinutes(): Int {
        // LIMITS: no stored value -> env INFANTRY_SESSION_KEEP_ALIVE_MINUTES (a bad one is ignored) -> the default.
        val stored = store.settings.get(KEEP_ALIVE_MINUTES)?.value
            ?: return env(KEEP_ALIVE_ENV)?.trim()?.toIntOrNull()?.takeIf { it in KEEP_ALIVE_RANGE }
                ?: LifecyclePolicy.DEFAULT_KEEP_ALIVE_MINUTES
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

    /** ASK-TIMER (1.139.0): what an unanswered tool approval resolves as at its timeout; read at every prompt open. */
    fun promptOnTimeout(): com.superiorsoftllc.infantry.prompts.TimeoutAction {
        val stored = store.settings.get(PROMPTS_ON_TIMEOUT)?.value ?: return com.superiorsoftllc.infantry.prompts.TimeoutAction.DENY
        val parsed = runCatching { (Json.parseToJsonElement(stored) as? JsonPrimitive)?.takeIf { it.isString }?.content }.getOrNull()
        return com.superiorsoftllc.infantry.prompts.TimeoutAction.parse(parsed) ?: com.superiorsoftllc.infantry.prompts.TimeoutAction.DENY
    }

    /** ASK-TIMER: the global prompt timer (`prompts.timeoutSec`, 0 = none; `prompts.onTimeout`). */
    fun promptTimeoutPolicy(): com.superiorsoftllc.infantry.prompts.PromptTimeoutPolicy =
        com.superiorsoftllc.infantry.prompts.PromptTimeoutPolicy(promptTimeoutSec() * 1_000L, promptOnTimeout())

    private fun requirePromptOnTimeout(value: JsonElement): com.superiorsoftllc.infantry.prompts.TimeoutAction =
        com.superiorsoftllc.infantry.prompts.TimeoutAction.parse((value as? JsonPrimitive)?.takeIf { it.isString }?.content)
            ?: throw IllegalArgumentException(
                "setting '$PROMPTS_ON_TIMEOUT' must be one of ${com.superiorsoftllc.infantry.prompts.TimeoutAction.WIRE.joinToString { "\"$it\"" }}",
            )

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
    fun processWatchConfig(): com.superiorsoftllc.infantry.session.ProcessWatchConfig {
        val d = com.superiorsoftllc.infantry.session.ProcessWatchConfig()
        fun int(key: String, range: IntRange, default: Int): Int {
            val stored = store.settings.get(key)?.value ?: return default
            val parsed = runCatching { (Json.parseToJsonElement(stored) as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull }.getOrNull()
            return parsed?.takeIf { it in range } ?: default
        }
        val autoReap = store.settings.get(PROCESSES_AUTO_REAP)?.value
            ?.let { runCatching { (Json.parseToJsonElement(it) as? JsonPrimitive)?.booleanOrNull }.getOrNull() } ?: d.autoReap
        return com.superiorsoftllc.infantry.session.ProcessWatchConfig(
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
     * STALL-3 (ticket d258b738): the stored tool-profile overrides (`[]` while unset), merged by name over the shipped
     * defaults by [toolProfiles]. A bad stored value is ignored (the defaults stay in force).
     */
    private fun toolProfileOverrides(): JsonArray = store.settings.get(TOOL_PROFILES)?.value
        ?.let { runCatching { Json.parseToJsonElement(it) as? JsonArray }.getOrNull() } ?: JsonArray(emptyList())

    /** STALL-3: the tool profiles in force, read at every run launch: the defaults with the stored overrides merged in. */
    fun toolProfiles(): List<com.superiorsoftllc.infantry.runner.ToolProfile> {
        val tp = com.superiorsoftllc.infantry.runner.ToolProfiles
        return runCatching { tp.merge(tp.DEFAULTS, tp.parse(toolProfileOverrides().toString())) }.getOrDefault(tp.DEFAULTS)
    }

    /** STALL-4: the stored notice-wording overrides (key -> template; empty while unset or unreadable). */
    fun watchNotices(): Map<String, String> = store.settings.get(WATCH_NOTICES)?.value
        ?.let { runCatching { com.superiorsoftllc.infantry.runner.WatchNotices.parse(it) }.getOrNull() } ?: emptyMap()

    /** STALL-4: who else hears a watch escalation: `starter` (default; else the user), `user` or `none`. */
    fun watchEscalateTo(): String = store.settings.get(WATCH_ESCALATE_TO)?.value
        ?.let { runCatching { (Json.parseToJsonElement(it) as? JsonPrimitive)?.content }.getOrNull() }
        ?.takeIf { it in com.superiorsoftllc.infantry.runner.WatchNotices.ESCALATE_VALUES } ?: "starter"

    /** STALL-4: whether the runner follows a file the command line redirects its output to (default off). */
    fun watchFollowRedirects(): Boolean = runCatching { storedBoolean(WATCH_FOLLOW_REDIRECTS)?.booleanOrNull }.getOrNull() ?: false

    private fun requireWatchNotices(value: JsonElement): JsonElement {
        require(value is JsonObject && value.values.all { it is JsonPrimitive && it.isString }) {
            "setting '$WATCH_NOTICES' must be a JSON object of notice key -> template string"
        }
        val map = value.mapValues { (it.value as JsonPrimitive).content }
        try {
            com.superiorsoftllc.infantry.runner.WatchNotices.validate(map)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("setting '$WATCH_NOTICES': ${e.message}")
        }
        return value
    }

    private fun requireToolProfiles(value: JsonElement): JsonElement {
        val tp = com.superiorsoftllc.infantry.runner.ToolProfiles
        require(value is JsonArray) { "setting '$TOOL_PROFILES' must be a JSON array of tool profiles" }
        try {
            tp.merge(tp.DEFAULTS, tp.parse(value.toString()))
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("setting '$TOOL_PROFILES': ${e.message}")
        } catch (e: kotlinx.serialization.SerializationException) {
            throw IllegalArgumentException("setting '$TOOL_PROFILES': ${e.message}")
        }
        return value
    }

    /**
     * Ticket 66153313: what worker sessions' builds may use, read at every run launch; a bad stored value falls back to the
     * default. Defaults: priority `belownormal`, maxWorkers = logical cores - 2 (0 = unlimited), maxParallelForks unset
     * (0 = each build script's own default).
     */
    /** STACK-NEUTRAL-1: `limits.watch.enabled`, read live. */
    /**
     * STACK-NEUTRAL-2: what `api.settings` shows a plugin: the numeric limits of [LimitRegistry] by key, and the stored JSON of
     * `limits.*`, `watch.*` and `processes.*` keys. Read only; nothing else of the store is reachable.
     */
    fun forPlugins(): com.superiorsoftllc.infantry.daemon.api.processes.PluginSettings = object : com.superiorsoftllc.infantry.daemon.api.processes.PluginSettings {
        override fun limit(key: String): Long? = LimitRegistry.ALL.firstOrNull { it.key == key }?.let { this@SettingsService.limit(it) }
        override fun hint(key: String): String = LimitRegistry.ALL.firstOrNull { it.key == key }?.hint().orEmpty()
        override fun stored(key: String): String? =
            if (PLUGIN_READABLE.any { key.startsWith(it) }) store.settings.get(key)?.value else null
    }

    fun watchEnabled(): Boolean = limit(LimitRegistry.WATCH_ENABLED) != 0L

    /**
     * STACK-NEUTRAL-1: the one-time opt-in migration, run at daemon start. While `limits.watch.enabled` is unset (and no
     * `INFANTRY_LIMIT_*` env names it), an install that already has sessions or projects keeps today's behaviour (1); a fresh
     * one starts with the watch off (0). The value is stored, so it runs once. Returns the value written, null = nothing done.
     */
    fun migrateWatchEnabled(existingInstall: () -> Boolean): Long? {
        val def = LimitRegistry.WATCH_ENABLED
        if (store.settings.get(def.key) != null) return null
        if (def.envNames.any { !env(it).isNullOrBlank() }) return null
        val v = if (runCatching(existingInstall).getOrDefault(true)) 1L else 0L
        set(def.key, JsonPrimitive(v))
        return v
    }

    /**
     * STACK-NEUTRAL-6: the one-time migration of this repo's own test-fork properties (read only by this repo's build scripts)
     * out of the bundled profile into the install's `watch.toolProfiles` override. On an existing install with no stored
     * override of that profile, it stores a copy of the bundled [profileName] profile plus the [extra] env templates, so the
     * worker env stays what it was. A fresh install, or one that already overrides the profile, is left alone.
     * Returns true when it wrote the override.
     */
    fun migrateRepoProfileAdditions(existingInstall: () -> Boolean): Boolean {
        val tp = com.superiorsoftllc.infantry.runner.ToolProfiles
        val text = SettingsService::class.java.getResourceAsStream("/watch/repo-profile-additions.json")?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return false
        val additions = runCatching { tp.parse(text) }.getOrNull() ?: return false
        val stored = toolProfileOverrides()
        val storedNames = stored.mapNotNull { (it as? JsonObject)?.get("name")?.let { n -> (n as? JsonPrimitive)?.content } }.toSet()
        val todo = additions.filter { it.name !in storedNames }
        if (todo.isEmpty() || !runCatching(existingInstall).getOrDefault(false)) return false
        val current = runCatching { tp.parse(stored.toString()) }.getOrDefault(emptyList())
        val merged = todo.mapNotNull { a -> tp.DEFAULTS.firstOrNull { it.name == a.name }?.let { b -> b.copy(env = b.env + a.env) } }
        if (merged.isEmpty()) return false
        set(TOOL_PROFILES, Json.parseToJsonElement(tp.encode(current + merged)))
        return true
    }

    fun buildLimits(): com.superiorsoftllc.infantry.runner.BuildLimits {
        val priority = store.settings.get(LIMITS_BUILD_PRIORITY)?.value
            ?.let { runCatching { (Json.parseToJsonElement(it) as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.getOrNull() }
            ?.let(com.superiorsoftllc.infantry.runner.BuildPriority::parse)
            ?: com.superiorsoftllc.infantry.runner.BuildPriority.DEFAULT
        return com.superiorsoftllc.infantry.runner.BuildLimits(
            priority = priority,
            maxWorkers = limit(LimitRegistry.BUILD_MAX_WORKERS).toInt(),
            maxParallelForks = limit(LimitRegistry.BUILD_MAX_PARALLEL_FORKS).toInt(),
        )
    }

    /** Ticket 66153313: the sustained-CPU attention rule, read at every process-watch pass. Default 90 % for 10 min; 0 min = off. */
    fun cpuAlert(): com.superiorsoftllc.infantry.daemon.api.processes.CpuAlertConfig {
        return com.superiorsoftllc.infantry.daemon.api.processes.CpuAlertConfig(
            percent = limit(LimitRegistry.CPU_ALERT_PERCENT).toInt(),
            minutes = limit(LimitRegistry.CPU_ALERT_MINUTES).toInt(),
        )
    }

    private fun requireBuildPriority(value: JsonElement): com.superiorsoftllc.infantry.runner.BuildPriority =
        (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(com.superiorsoftllc.infantry.runner.BuildPriority::parse)
            ?: throw IllegalArgumentException(
                "setting '$LIMITS_BUILD_PRIORITY' must be one of ${com.superiorsoftllc.infantry.runner.BuildPriority.KEYS.joinToString { "\"$it\"" }}",
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
            "setting '$PROMPTS_TIMEOUT_SEC' must be a JSON integer in $PROMPT_TIMEOUT_RANGE (seconds; 0 = no timer)"
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
        const val INCLUDE_FINISH_INSTRUCTIONS = "context.includeFinishInstructions"
        const val SKILL_IDS = "context.skillIds"
        const val KEEP_ALIVE_MINUTES = "session.keepAliveMinutes"
        /** LIMITS: the environment layer of [KEEP_ALIVE_MINUTES] (the stored setting wins). */
        const val KEEP_ALIVE_ENV = "INFANTRY_SESSION_KEEP_ALIVE_MINUTES"
        /** USER-PORTRAIT: base64 PNG/WebP (<= 256 KB) of the human's portrait; "" clears it. */
        const val USER_PORTRAIT = "user.portrait"
        /** STALL-3: tool-profile overrides (docs/STALL.md), a JSON array merged by name over the shipped defaults. */
        const val TOOL_PROFILES = com.superiorsoftllc.infantry.runner.ToolProfiles.SETTING
        /** STALL-4: watch notice wording overrides (docs/STALL.md), a JSON object merged by key over `watch/notices.json`. */
        const val WATCH_NOTICES = com.superiorsoftllc.infantry.runner.WatchNotices.SETTING
        /** STALL-4: the watch's recipient rule for escalations: `starter` | `user` | `none`. */
        const val WATCH_ESCALATE_TO = com.superiorsoftllc.infantry.runner.WatchNotices.ESCALATE_SETTING
        /** STALL-4: `true` = the runner follows files a command line redirects its output to (default `false`). */
        const val WATCH_FOLLOW_REDIRECTS = "watch.followRedirects"
        /** KNOWLEDGE-PTR-1 (b8ca2bf7): see [relatedConfig]; all default on. */
        const val RELATED_BRIEF = "related.brief"
        const val RELATED_BRIEF_INSTRUCTION = "related.briefInstruction"
        /** RELATED-SHAPE-1: `files-command` (default) | `files-inline` | `tickets`. */
        const val RELATED_BRIEF_MODE = "brief.related.mode"
        /** RELATED-SHAPE-1: `paths` (default) | `detailed`. */
        const val RELATED_FILES_FORMAT = "related.files.format"
        const val RELATED_DESCRIPTION_BLOCK = "related.descriptionBlock"
        const val RELATED_FAMILY_PATTERN = "related.familyPattern"
        const val RELATED_SUGGEST_NUMBER = "related.suggestNumber"
        /** TRIGGER-FOOTER-1: footer text for trigger / system mail ("" = off); `.<triggerId>` suffix = per-trigger override. */
        const val MAIL_AUTOMATION_FOOTER = "mail.automationFooter"
        const val MAIL_WAKE_MODE = "mail.wakeMode"
        const val MAIL_DELIVERY_MODE = "mail.deliveryMode"
        val DELIVERY_MODES = setOf("notify", "truncate", "full")
        val WAKE_MODES = setOf("real", "never", "all")
        const val PROMPTS_TIMEOUT_SEC = "prompts.timeoutSec"
        const val DEFAULT_PROMPT_TIMEOUT_SEC = 600
        /** ASK-TIMER (1.139.0): `deny` (default) | `approve`: what an unanswered tool approval resolves as at its timeout. */
        const val PROMPTS_ON_TIMEOUT = "prompts.onTimeout"
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
        /** AGENT-TASKS-2: who writes the handoff note when a ticket is unassigned: `auto` (default, the board), `worker` (one last turn) or `off`. */
        const val TICKET_HANDOFF_NOTE = "tickets.agentTasks.handoffNote"
        /** AGENT-TASKS-3: end a done ticket's idle worker when its agent has a queued ticket (default true). */
        const val TICKET_CLOSE_DONE_WORKER_WHEN_QUEUED = "tickets.agentTasks.closeDoneWorkerWhenQueued"
        private val HANDOFF_NOTES = listOf("auto", "worker", "off")
        /** TICKET-WEIGHT: JSON object weight ("1".."5") -> {provider, model}; see [WeightExecutors]. */
        const val TICKET_WEIGHT_EXECUTORS = WeightExecutors.SETTING

        /** TOKEN-7: JSON object weight ("1".."5") -> {contextLimit, effort}; see [WeightContext]. */
        const val TICKET_WEIGHT_CONTEXT = WeightContext.SETTING
        /** USAGE-PACE (1.121.0): see [PacingRule], [ManualUsage], [UsageOverride]. */
        const val USAGE_PACING = UsagePacing.RULE_SETTING
        const val USAGE_MANUAL = UsagePacing.MANUAL_SETTING
        const val USAGE_OVERRIDES = UsagePacing.OVERRIDES_SETTING
        /** USAGE-AUTO (1.124.0): minutes between automatic live usage reads (0 = off). */
        const val USAGE_REFRESH_MINUTES = "usage.refreshMinutes"
        /** LOCAL-TIME-1: IANA zone every person-facing time and usage day uses; blank = the system zone. */
        const val DISPLAY_TIME_ZONE = "display.timeZone"
        const val USAGE_REFRESH_DEFAULT = 5
        private val USAGE_REFRESH_RANGE = 0..1440
        const val EXIT_LEAVE = "leave"
        const val EXIT_DONE = "done"
        private val EXIT_BEHAVIORS = listOf(EXIT_LEAVE, EXIT_DONE)
        /** ROGUE-1 (1.120.0): the rogue process detector (auto-reap is opt-in, default false). */
        private val PLUGIN_READABLE = listOf("limits.", "watch.", "processes.", "images.", "questions.", "prompts.timeoutSec")
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
        const val SANDBOX_ALLOWED_DIRS = "limits.sandbox.allowedDirs"
        const val SANDBOX_ALLOWED_DIRS_ENV = "INFANTRY_LIMIT_SANDBOX_ALLOWED_DIRS"
        /** LIMITS: the model allowlist, see [ModelAllowlist]. */
        const val ALLOWED_MODELS = ModelAllowlist.SETTING
        const val ALLOWED_ROLES = ModelAllowlist.ROLES_SETTING
        /** NUDGE-SCRIPT-1: built-in nudge event names that defer to script hooks. */
        const val NUDGE_DEFER = com.superiorsoftllc.infantry.mailbox.NudgeGate.SETTING
        val LIMITS_BUILD_MAX_WORKERS = LimitRegistry.BUILD_MAX_WORKERS.key
        val LIMITS_BUILD_MAX_PARALLEL_FORKS = LimitRegistry.BUILD_MAX_PARALLEL_FORKS.key
        val LIMITS_CPU_ALERT_PERCENT = LimitRegistry.CPU_ALERT_PERCENT.key
        val LIMITS_CPU_ALERT_MINUTES = LimitRegistry.CPU_ALERT_MINUTES.key
        private val PROCESSES_IDLE_RANGE = 1..1440
        private val PROCESSES_MB_RANGE = 0..1_048_576
        private val QUESTION_LIMIT_RANGE = 1..100
        /** ASK-TIMER: 0 = no timer (the prompt waits for an answer). */
        private val PROMPT_TIMEOUT_RANGE = 0..86_400
        private val KEEP_ALIVE_RANGE = 0..LifecyclePolicy.MAX_KEEP_ALIVE_MINUTES
    }
}

/**
 * P10-K11 (K-D9): every id in [ids] must name an existing skill and appear once, or [what] is refused. Shared by the
 * project baseline (`context.skillIds`) and `ticket_save`, so both refuse the same way.
 */
fun requireKnownSkills(store: InfantryStore, ids: List<String>, what: String) =
    requireKnownSkills(ids, what) { all -> store.transaction { all.filter { store.skills.get(it) == null } } }
