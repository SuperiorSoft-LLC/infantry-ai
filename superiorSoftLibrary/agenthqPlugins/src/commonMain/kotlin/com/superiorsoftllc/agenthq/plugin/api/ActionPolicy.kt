package com.superiorsoftllc.agenthq.plugin.api

import kotlinx.serialization.Serializable

/**
 * POLICY-1 (docs/POLICY.md): what a named action does. Undeclared controls follow their kind, so a rule such as
 * `kind:destructive` reaches a control whose plugin never declared it.
 */
object ActionKind {
    const val READ = "read"
    const val WRITE = "write"
    const val DESTRUCTIVE = "destructive"
    const val SPAWN = "spawn"
    val ALL: List<String> = listOf(READ, WRITE, DESTRUCTIVE, SPAWN)
}

/**
 * POLICY-1: one entry of module.json `actions`: an action a plugin's controls perform. [frames] are the daemon frames the
 * control sends; the daemon's catalog maps each to this same [id] (PolicyActionParityTest).
 */
@Serializable
data class ActionDeclaration(
    val id: String,
    val label: String = "",
    val kind: String = ActionKind.WRITE,
    val frames: List<String> = emptyList(),
)

/** POLICY-1: a preset's seated lockdown: a project, its role seats and the visitor limits (docs/POLICY.md section 2). */
@Serializable
data class SeatsSpec(
    val projectName: String = "",
    val projectDir: String = "",
    val projectGoal: String = "",
    /** `role/provider/model` each, e.g. `pm/opencode/some-model`. */
    val seats: List<String> = emptyList(),
    /** The first turn each seat runs at start-up (null = no warm-up). */
    val warmUpPrompt: String? = null,
    /** Visitor turns per client IP in [turnWindowHours]; 0 = no limit. */
    val turnsPerIp: Int = 0,
    val turnWindowHours: Int = 0,
    /** The refusal code of the turn limit, declared a notice. */
    val turnLimitCode: String? = null,
    /** Refusal text for a mail that is not between two seats. */
    val mailRefusal: String = "mail is only allowed between the seated agents",
    /** Refusal text for a refused HTTP route; `{method}` and `{path}` are filled in. */
    val httpRefusal: String = "{method} {path} is disabled",
)

/** POLICY-1: one preset file (`policies/<id>.json`). Every field but [id] is optional; see docs/POLICY.md section 2. */
@Serializable
data class PolicyPreset(
    val id: String,
    val label: String = "",
    val description: String = "",
    val extends: List<String> = emptyList(),
    /** Rules: `*`, `kind:<kind>`, `area.*`, or an exact action id. */
    val disable: List<String> = emptyList(),
    /** Exceptions to [disable]; an allow rule always wins. */
    val allow: List<String> = emptyList(),
    /** The hover reason on a dimmed control. */
    val reason: String? = null,
    /** The daemon's refusal text; `{frame}`, `{action}` and `{reason}` are filled in. */
    val refusal: String? = null,
    /** An env var that, set truthy, selects this preset (pinned): a deployment shorthand. */
    val selectWhenEnv: String? = null,
    /** `ready` access=restricted: the UI skips the owner-only reads. */
    val restricted: Boolean? = null,
    /** Refusal codes the UI shows as notices, not error banners. */
    val notices: List<String> = emptyList(),
    /** Extra `ready` keys (a module adapting the UI to this deployment reads them). */
    val ready: Map<String, String> = emptyMap(),
    /** A deployment notice the UI may pin (a module shows it). */
    val notice: String? = null,
    /** Used when the `limits.models.allowed` / `limits.roles.allowed` settings are empty. */
    val allowedModels: List<String> = emptyList(),
    val allowedRoles: List<String> = emptyList(),
    val seats: SeatsSpec? = null,
) {
    /** Production: nothing refused, nothing added. */
    val isEmpty: Boolean get() = disable.isEmpty() && seats == null && restricted != true

    companion object {
        const val PRODUCTION = "production"
        val Production = PolicyPreset(PRODUCTION, label = "Production", description = "Everything enabled.")

        /** What a pinned id that no provider has resolves to: everything refused (fail closed). */
        fun missing(id: String) = PolicyPreset(id, label = id, disable = listOf("*"), reason = "Policy '$id' is not installed")

        /**
         * [id] with its `extends` chain applied: lists unioned parent-first, scalars the child's when set. A cycle or a
         * missing parent stops that branch (the child still applies).
         */
        fun resolve(id: String, byId: (String) -> PolicyPreset?, seen: Set<String> = emptySet()): PolicyPreset? {
            val own = byId(id) ?: return null
            if (id in seen) return own
            var acc: PolicyPreset? = null
            for (parent in own.extends) {
                val p = resolve(parent, byId, seen + id) ?: continue
                acc = acc?.let { merge(it, p) } ?: p
            }
            return acc?.let { merge(it, own) } ?: own
        }

        private fun merge(base: PolicyPreset, top: PolicyPreset) = PolicyPreset(
            id = top.id, label = top.label.ifBlank { base.label }, description = top.description.ifBlank { base.description },
            extends = top.extends,
            disable = (base.disable + top.disable).distinct(), allow = (base.allow + top.allow).distinct(),
            reason = top.reason ?: base.reason, refusal = top.refusal ?: base.refusal, selectWhenEnv = top.selectWhenEnv,
            restricted = top.restricted ?: base.restricted, notices = (base.notices + top.notices).distinct(),
            ready = base.ready + top.ready, notice = top.notice ?: base.notice,
            allowedModels = top.allowedModels.ifEmpty { base.allowedModels }, allowedRoles = top.allowedRoles.ifEmpty { base.allowedRoles },
            seats = top.seats ?: base.seats,
        )
    }
}

/** POLICY-1: a preset a client may pick in Settings. */
@Serializable
data class PolicyChoice(val id: String, val label: String = "", val description: String = "")

/**
 * POLICY-1: the `policy` frame (daemon -> client, after `ready` and on every change). [canSwitch]: this connection is the
 * owner and the selection is not pinned.
 */
@Serializable
data class PolicyWire(
    val preset: String = PolicyPreset.PRODUCTION,
    val label: String = "",
    val pinned: Boolean = false,
    /** Why it is pinned (the env var), when it is. */
    val pinnedBy: String? = null,
    val canSwitch: Boolean = false,
    val presets: List<PolicyChoice> = emptyList(),
    val disable: List<String> = emptyList(),
    val allow: List<String> = emptyList(),
    val reason: String? = null,
    val notice: String? = null,
    val restricted: Boolean = false,
    val notices: List<String> = emptyList(),
    val ready: Map<String, String> = emptyMap(),
) {
    fun allows(actionId: String, kind: String = ActionKind.WRITE): Boolean = ActionPolicyMatcher.allows(disable, allow, actionId, kind)

    /** The `ready` keys this policy contributes (folded into the client's ready fields). */
    fun readyFields(): Map<String, String> = buildMap {
        putAll(ready)
        if (restricted) put("access", "restricted")
        if (notices.isNotEmpty()) put("notices", notices.joinToString(","))
    }

    companion object {
        val Production = PolicyWire()

        fun of(p: PolicyPreset, pinnedBy: String?, canSwitch: Boolean, presets: List<PolicyChoice>) = PolicyWire(
            preset = p.id, label = p.label.ifBlank { p.id }, pinned = pinnedBy != null, pinnedBy = pinnedBy, canSwitch = canSwitch,
            presets = presets, disable = p.disable, allow = p.allow, reason = p.reason, notice = p.notice,
            restricted = p.restricted == true, notices = p.notices, ready = p.ready,
        )
    }
}

/** POLICY-1: the ONE matcher. The daemon's refusal and the UI's dimming both call it with the same action id. */
object ActionPolicyMatcher {
    /** [rule] matches the action [id] of [kind]: `*`, `kind:<kind>`, `prefix.*`, or the exact id. */
    fun matches(rule: String, id: String, kind: String): Boolean {
        val r = rule.trim()
        return when {
            r == "*" -> true
            r.startsWith("kind:") -> r.removePrefix("kind:").trim() == kind
            r.endsWith(".*") -> id.isNotEmpty() && r.removeSuffix(".*").let { id == it || id.startsWith("$it.") }
            else -> r.isNotEmpty() && r == id
        }
    }

    /** Allowed unless a [disable] rule matches and no [allow] rule does. */
    fun allows(disable: List<String>, allow: List<String>, id: String, kind: String): Boolean =
        disable.none { matches(it, id, kind) } || allow.any { matches(it, id, kind) }
}
