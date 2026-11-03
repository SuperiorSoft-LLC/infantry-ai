package com.superiorsoftllc.infantry.ui.model

import com.superiorsoftllc.infantry.ui.protocol.ProviderInfo
import com.superiorsoftllc.infantry.ui.protocol.ProviderModels
import com.superiorsoftllc.infantry.ui.protocol.Role
import com.superiorsoftllc.infantry.ui.protocol.RoleBinding

/**
 * UX slice 1: the state behind the New agent modal. Pure and immutable, so it is tested without a UI.
 * Flow: pick a [role] (searchable grid), then the [executor] (harness -> provider -> model, seeded from the
 * role's binding), type the first [task], press Add. Add is the existing spawn: `new` (with the role) + `prompt`.
 */
data class NewAgentModalModel(
    val roles: List<Role> = emptyList(),
    val bindings: Map<String, RoleBinding> = emptyMap(),
    val harnesses: List<ProviderInfo> = emptyList(),
    val catalog: List<ProviderModels> = emptyList(),
    val roleQuery: String = "",
    val roleId: String? = null,
    val executor: ExecutorPickerModel = ExecutorPickerModel(harnesses, catalog),
    val task: String = "",
    /** CONTEXT-LIMIT-1: the off switch; off (the default) = the harness's own window, nothing is sent. */
    val contextLimitOn: Boolean = false,
    /** CONTEXT-LIMIT-1: the limit as typed while the switch is on (`200k`, `1m`, `150000`). */
    val contextLimitText: String = "",
    /** EFFORT-1: the off switch; off (the default) = the role binding's effort, else the harness's own default. */
    val effortOn: Boolean = false,
    /** EFFORT-1: the chosen level id while the switch is on. */
    val effortLevel: String = EffortLevels.ALL.first { it.id == "medium" }.id,
) {
    /** The role grid, narrowed by what was typed (id and name), best match first. */
    val roleOptions: List<Role>
        get() = filterRanked(roles, roleQuery) { SearchText(id = it.id, label = it.name) }

    /** Nothing in the catalog yet (not connected, or the `roles` frame has not landed): the grid says so instead of looking empty. */
    val rolesLoading: Boolean get() = roles.isEmpty()

    /** The executor step stays closed until a role is chosen. */
    val executorOpen: Boolean get() = roleId != null

    /** The first required field still empty, top to bottom, or null when Add can go. The modal jumps to it. */
    val missingField: NewAgentField?
        get() = when {
            roleId == null -> NewAgentField.ROLE
            executor.harness == null -> NewAgentField.HARNESS
            executor.model == null -> NewAgentField.MODEL
            contextLimitOn && contextLimitParsed.error != null -> NewAgentField.CONTEXT_LIMIT
            task.isBlank() -> NewAgentField.TASK
            else -> null
        }

    /** Why Add is disabled, or null when it is enabled. Shown beside the button, never silent. */
    val addBlockedReason: String?
        get() = when (missingField) {
            NewAgentField.ROLE -> "Pick a role first"
            NewAgentField.HARNESS -> "Pick a harness"
            NewAgentField.MODEL -> "Pick a model"
            NewAgentField.CONTEXT_LIMIT -> "Context limit: " + contextLimitParsed.error
            NewAgentField.TASK -> "Type the agent's first task"
            null -> null
        }

    val canAdd: Boolean get() = addBlockedReason == null

    /**
     * Choose a role (an unknown id changes nothing); the executor re-seeds from that role's binding. PICKER-UX: a
     * harness that lists one model has it picked, so Add is not blocked on a choice there is none of.
     */
    fun withRole(id: String): NewAgentModalModel {
        if (roles.none { it.id == id }) return this
        return copy(roleId = id, executor = ExecutorPickerModel.seeded(harnesses, catalog, bindings[id]?.let { PickedModel.fromBinding(it) }).withSoleModel())
    }

    fun withRoleQuery(q: String): NewAgentModalModel = copy(roleQuery = q)

    fun withExecutor(next: ExecutorPickerModel): NewAgentModalModel = copy(executor = next.withSoleModel())

    fun withTask(text: String): NewAgentModalModel = copy(task = text)

    fun withContextLimitOn(on: Boolean): NewAgentModalModel = copy(contextLimitOn = on)

    fun withContextLimitText(text: String): NewAgentModalModel = copy(contextLimitText = text)

    fun withEffortOn(on: Boolean): NewAgentModalModel = copy(effortOn = on)

    fun withEffortLevel(id: String): NewAgentModalModel = copy(effortLevel = id)

    /** The level Add sends: the picked one while the switch is on, else null (the role binding's effort / the harness default apply). */
    val effort: String? get() = if (effortOn) effortLevel else null

    /** How the chosen harness treats an effort, or null when no harness is chosen or the daemon is older and does not say. */
    val effortSupport: ProviderInfo? get() = effortProvider(harnesses, executor.harness)

    /** The line under the effort field: the chosen harness's support in words. */
    val effortNote: String get() = effortNoteFor(harnesses, executor.harness)

    private val contextLimitParsed: ContextLimitInput.Parsed get() = ContextLimitInput.parse(contextLimitText)

    /** The limit Add sends, in tokens: null while the switch is off (the harness default) or the text does not parse. */
    val contextLimit: Int? get() = if (contextLimitOn) contextLimitParsed.value else null

    /**
     * How the chosen harness treats a limit, as the daemon reports it (`supported` / `best_effort` / `unsupported`, plus
     * how), or null when no harness is chosen or the daemon is older and does not say.
     */
    val contextLimitSupport: ProviderInfo? get() = contextLimitProvider(harnesses, executor.harness)

    /** The line under the context limit field: the chosen harness's support in words. */
    val contextLimitNote: String get() = contextLimitNoteFor(harnesses, executor.harness)

    /** The `new` frame's data for Add: the picked harness+model (null = daemon defaults), the role, the role binding's effort, the context limit. */
    fun spawnData(): String = spawnOptionsData(executor.picked, effort ?: roleId?.let { bindings[it]?.effort }, roleId, contextLimit = contextLimit)
}

/** The New agent modal's fields Add checks, in screen order. CONTEXT_LIMIT is optional: only a switched-on, unreadable value blocks. */
enum class NewAgentField { ROLE, HARNESS, MODEL, CONTEXT_LIMIT, TASK }

/**
 * CONTEXT-LIMIT-1: reads a typed context limit the way the daemon's `ContextLimits.parse` does (the UI cannot import the
 * daemon): `200000`, `200k`, `1m`, `1.5m`; 10k..10m tokens. Blank is no value and says what to type.
 */
object ContextLimitInput {
    const val MIN = 10_000
    const val MAX = 10_000_000

    data class Parsed(val value: Int?, val error: String?)

    fun parse(raw: String): Parsed {
        val t = raw.trim().lowercase().replace("_", "").replace(",", "")
        if (t.isEmpty()) return Parsed(null, "type a size such as 200k or 1m")
        val (num, mult) = when {
            t.endsWith("k") -> t.dropLast(1) to 1_000.0
            t.endsWith("m") -> t.dropLast(1) to 1_000_000.0
            else -> t to 1.0
        }
        val n = num.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }?.let { it * mult }
            ?: return Parsed(null, "'$raw' is not a size (try 200k, 1m or 150000)")
        if (n < MIN || n > MAX) return Parsed(null, "must be between 10k and 10m tokens")
        return Parsed(n.toInt(), null)
    }
}

/**
 * DEMO-RESTRICT (ticket f9e181af): the daemon's role allowlist (`limits.roles.allowed` in the `settings` frame). Empty =
 * every role. Same matching as the daemon: case-insensitive, `*` = any run of characters.
 */
object AllowedRoles {
    const val SETTING = "limits.roles.allowed"

    /** The list a `settings` frame carries, or null when the frame does not carry it (it then changes nothing). */
    fun fromSettingsFrame(data: String): List<String>? {
        val obj = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(data) as? kotlinx.serialization.json.JsonObject }.getOrNull() ?: return null
        val arr = obj[SETTING] as? kotlinx.serialization.json.JsonArray ?: return null
        return arr.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim()?.takeIf(String::isNotEmpty) }
    }

    /** DEMO-RESTRICT: `limits.concurrency.maxSessions` in a `settings` frame, or null when the frame does not carry it. */
    fun maxSessionsFromSettingsFrame(data: String): Long? {
        val obj = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(data) as? kotlinx.serialization.json.JsonObject }.getOrNull() ?: return null
        val v = obj["limits.concurrency.maxSessions"] as? kotlinx.serialization.json.JsonPrimitive ?: return null
        return v.content.toLongOrNull()
    }

    fun allows(patterns: List<String>, roleId: String): Boolean = patterns.isEmpty() || patterns.any { matches(it, roleId) }

    fun filter(patterns: List<String>, roles: List<Role>): List<Role> = if (patterns.isEmpty()) roles else roles.filter { allows(patterns, it.id) }

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
