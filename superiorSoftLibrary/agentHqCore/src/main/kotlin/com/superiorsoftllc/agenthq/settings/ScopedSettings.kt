package com.superiorsoftllc.agenthq.settings

import com.superiorsoftllc.agenthq.persistence.AgentHqStore
import com.superiorsoftllc.agenthq.persistence.SettingRow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** SETTINGS-SCOPES-1: where a setting value is stored. Resolution order, strongest first: session, role, project, app, default. */
enum class SettingScope(val wire: String) {
    SESSION("session"), ROLE("role"), PROJECT("project"), APP("app");

    companion object {
        fun parse(text: String?): SettingScope = entries.firstOrNull { it.wire == text?.trim()?.lowercase() }
            ?: throw IllegalArgumentException("unknown scope '${text ?: ""}' (one of app, project, role, session)")
    }
}

/** Who is asking: the project, the role in it and the session. A null part skips that scope; no context at all = app scope. */
data class SettingContext(val projectId: String? = null, val role: String? = null, val sessionId: String? = null)

/** The value [ScopedSettings.resolve] found, the scope it came from (`session|role|project|app|default`) and every layer that holds one. */
data class ResolvedSetting(val key: String, val value: JsonElement?, val scope: String, val layers: List<Layer>) {
    data class Layer(val scope: String, val target: String, val value: JsonElement)

    fun toJson(): JsonObject = buildJsonObject {
        put("key", key)
        put("value", value ?: JsonNull)
        put("scope", scope)
        put("layers", buildJsonArray {
            layers.forEach { add(buildJsonObject { put("scope", it.scope); put("target", it.target); put("value", it.value) }) }
        })
    }
}

/**
 * What a setting allows: [scopes], one line of [doc], and for a trade-off setting what its costlier value costs ([cost]; the
 * default is always the cheapest, a costlier value is an opt-in).
 */
data class SettingSpec(val scopes: Set<SettingScope>, val doc: String, val cost: String = "")

/** SETTINGS-SCOPES-1: the scopes each setting allows. A key not listed is application-wide only. */
object SettingScopes {
    private val ALL = SettingScope.entries.toSet()
    private val APP_ONLY = setOf(SettingScope.APP)

    private val specs: Map<String, SettingSpec> = mapOf(
        SettingsService.RELATED_BRIEF to SettingSpec(ALL, "Put the related-tickets file list into a started ticket's brief.", "on adds the list to every worker's first turn"),
        SettingsService.RELATED_BRIEF_MODE to SettingSpec(ALL, "How a ticket's brief shows related work: files-command (one line naming the command), files-inline, or tickets.", "files-inline puts every related path in each worker's brief, re-read every turn; tickets adds ids, titles, statuses and file snippets, the most tokens"),
        SettingsService.RELATED_FILES_FORMAT to SettingSpec(ALL, "What `ticket files <id> --related` prints: paths (bare list) or detailed.", "detailed adds ticket ids, status and counts per path, several times the output tokens"),
        SettingsService.RELATED_BRIEF_INSTRUCTION to SettingSpec(ALL, "The one instruction line printed above the related-files list."),
        SettingsService.RELATED_DESCRIPTION_BLOCK to SettingSpec(ALL, "Paste the related-files block into a new ticket's description.", "on makes every ticket description longer"),
        SettingsService.RELATED_SUGGEST_NUMBER to SettingSpec(ALL, "Suggest the next number of a ticket family when a ticket is created."),
        SettingsService.MAIL_WAKE_MODE to SettingSpec(ALL, "Which mail wakes a resting session: real | never | all.", "all wakes on every automation mail, each wake re-reads the whole context"),
        SettingsService.MAIL_DELIVERY_MODE to SettingSpec(ALL, "What a mail wake injects: notify | truncate | full.", "full injects every body, re-read on every later turn"),
        LimitRegistry.SESSION_CONTEXT_CAP_TOKENS.key to SettingSpec(ALL, "Context size past which a resting session is restarted with a handoff when mail wakes it (0 = off).", "a higher cap or 0 keeps longer context, each turn costs more tokens"),
        SettingsService.INCLUDE_LINKED_TASKS_CONTEXT to SettingSpec(setOf(SettingScope.APP, SettingScope.PROJECT, SettingScope.ROLE, SettingScope.SESSION), "Include the linked-tickets section in a started ticket's brief."),
        SettingsService.INCLUDE_SKILLS_CONTEXT to SettingSpec(ALL, "Include the skills section in a started ticket's brief."),
        SettingsService.INCLUDE_FINISH_INSTRUCTIONS to SettingSpec(ALL, "Include the report-instruction lines in a started ticket's brief."),
        SettingsService.KEEP_ALIVE_MINUTES to SettingSpec(ALL, "Minutes a resting harness process is kept alive (0 = one-shot).", "a longer keep-alive holds more RAM"),
    )

    fun spec(key: String): SettingSpec = specs[key] ?: SettingSpec(APP_ONLY, "Application-wide setting.")
    fun allowed(key: String): Set<SettingScope> = spec(key).scopes
    fun scopedKeys(): Set<String> = specs.keys
}

/**
 * SETTINGS-SCOPES-1: one resolution order for a key: session > role (in a project) > project > application > the built-in
 * default. Each scope stores only the keys set on it, as rows `@scope/<scope>/<target>/<key>` in the settings table (the flat
 * rows stay app scope, unchanged). Values are validated by [SettingsService.validated], so every scope accepts what app accepts.
 */
class ScopedSettings(private val store: AgentHqStore, private val settings: SettingsService) {

    /** The value of [key] for [context] and the scope it comes from; an unknown key is refused. No context = app scope. */
    fun resolve(key: String, context: SettingContext = SettingContext()): ResolvedSetting {
        require(isKnown(key)) { "unknown setting '$key'" }
        val layers = mutableListOf<ResolvedSetting.Layer>()
        fun layer(scope: SettingScope, target: String?) {
            if (target == null || scope !in SettingScopes.allowed(key)) return
            storedAt(scope, target, key)?.let { layers += ResolvedSetting.Layer(scope.wire, target, it) }
        }
        layer(SettingScope.SESSION, context.sessionId?.takeIf { it.isNotBlank() })
        layer(SettingScope.ROLE, roleTarget(context))
        layer(SettingScope.PROJECT, context.projectId?.takeIf { it.isNotBlank() })
        val appStored = store.settings.get(key) != null
        val appValue = settings.all()[key] ?: settings.storedDeclared(key)
        appValue?.let { layers += ResolvedSetting.Layer(if (appStored) "app" else "default", "", it) }
        val winner = layers.firstOrNull()
        return ResolvedSetting(key, winner?.value, winner?.scope ?: "default", layers)
    }

    /** Set (or with a null [value], clear) [key] at [scope] on the target [context] names. A scope the key does not allow is refused. */
    fun set(key: String, value: JsonElement?, scope: SettingScope, context: SettingContext = SettingContext()): ResolvedSetting {
        if (scope == SettingScope.APP) {
            if (value != null) settings.set(key, value) else throw IllegalArgumentException("the app scope cannot be cleared, set a value")
            return resolve(key, context)
        }
        require(isKnown(key)) { "unknown setting '$key'" }
        val allowed = SettingScopes.allowed(key)
        require(scope in allowed) {
            "setting '$key' cannot be set at the ${scope.wire} scope; it allows: ${allowed.sortedBy { it.ordinal }.joinToString { it.wire }}"
        }
        val target = when (scope) {
            SettingScope.SESSION -> context.sessionId?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("scope session needs a session id")
            SettingScope.ROLE -> roleTarget(context) ?: throw IllegalArgumentException("scope role needs a project and a role")
            SettingScope.PROJECT -> context.projectId?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("scope project needs a project id")
            SettingScope.APP -> ""
        }
        val stored: JsonElement = if (value == null) JsonNull else settings.validated(key, value)
        store.settings.put(SettingRow(rowKey(scope, target, key), stored.toString(), System.currentTimeMillis()))
        return resolve(key, context)
    }

    /** `explain`: the winner and every layer, as a readable block. */
    fun explain(key: String, context: SettingContext = SettingContext()): String {
        val r = resolve(key, context)
        val spec = SettingScopes.spec(key)
        return buildString {
            append(key).append(" = ").append(r.value ?: "(none)").append("  (from ").append(r.scope).append(")\n")
            append("  allowed scopes: ").append(spec.scopes.sortedBy { it.ordinal }.joinToString { it.wire }).append('\n')
            append("  ").append(spec.doc).append('\n')
            if (spec.cost.isNotBlank()) append("  costlier value: ").append(spec.cost).append('\n')
            append("  order: session > role > project > app > default\n")
            r.layers.forEach { append("  ").append(it.scope).append(if (it.target.isEmpty()) "" else " ${it.target}").append(" = ").append(it.value).append('\n') }
        }.trimEnd()
    }

    private fun isKnown(key: String) = key in settings.all() || settings.isBuiltIn(key) || settings.storedDeclared(key) != null || settings.declaredField(key) != null

    private fun roleTarget(context: SettingContext): String? {
        val project = context.projectId?.takeIf { it.isNotBlank() } ?: return null
        val role = context.role?.takeIf { it.isNotBlank() } ?: return null
        return "$project/$role"
    }

    private fun rowKey(scope: SettingScope, target: String, key: String) = "@scope/${scope.wire}/$target/$key"

    private fun storedAt(scope: SettingScope, target: String, key: String): JsonElement? =
        store.settings.get(rowKey(scope, target, key))?.value
            ?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }
            ?.takeIf { it !is JsonNull }

}
