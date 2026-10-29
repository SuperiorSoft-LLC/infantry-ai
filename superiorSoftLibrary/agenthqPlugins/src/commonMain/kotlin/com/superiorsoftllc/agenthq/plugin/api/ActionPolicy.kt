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
    /** The turn limit's refusal text; `{turns}` and `{window}` (`day`, or `N hours`) are filled in. Null = core's neutral text. */
    val turnLimitText: String? = null,
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
    /** POLICY-1 (COPY-1): how a `limits.concurrency.maxSessions` refusal starts. Null = core's neutral text. */
    val sessionCapText: String? = null,
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
            seats = top.seats ?: base.seats, sessionCapText = top.sessionCapText ?: base.sessionCapText,
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
    /**
     * GAP-AUDIT-1 (f): why the daemon could not seat the active preset (its project or seats) after the last switch;
     * null = seated or nothing to seat. The Settings Policy card shows it to the owner.
     */
    val seatingError: String? = null,
    /** GAP-AUDIT-1 (h): the preset's refusal template (`{frame}`, `{action}`, `{reason}`), the same text the daemon refuses with. */
    val refusal: String? = null,
    /**
     * GAP-AUDIT-1 (h): the daemon's frame -> action catalog (empty under production and from an older daemon), so a
     * client refuses before sending exactly what the daemon would refuse ([refusesFrame]).
     */
    val frames: Map<String, FrameAction> = emptyMap(),
) {
    fun allows(actionId: String, kind: String = ActionKind.WRITE): Boolean = ActionPolicyMatcher.allows(disable, allow, actionId, kind)

    /**
     * GAP-AUDIT-1 (h), the send-side gate: the refusal of a frame of [type] under this policy, or null = send it. Every
     * client command goes through it, so a control that never asked a gate (a raw clickable) still cannot send a write
     * the policy turns off: it gets the policy's reason at once, as the daemon would answer. A frame the catalog does
     * not map is left to the daemon (it refuses it under any non-production preset).
     */
    fun refusesFrame(type: String): String? {
        if (frames.isEmpty() || disable.isEmpty()) return null
        val a = frames[type] ?: return null
        return if (allows(a.action, a.kind)) null else PolicyRefusal.text(refusal, reason, preset, type, a.action)
    }

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
            restricted = p.restricted == true, notices = p.notices, ready = p.ready, refusal = p.refusal,
        )
    }
}

/** GAP-AUDIT-1 (h): one catalog entry of [PolicyWire.frames]: the action a frame performs and its kind. */
@Serializable
data class FrameAction(val action: String, val kind: String)

/** GAP-AUDIT-1 (h): the ONE refusal text, the daemon's and the client's send gate's (a preset's `refusal` template). */
object PolicyRefusal {
    fun text(template: String?, reason: String?, presetId: String, type: String, actionId: String?): String {
        val shown = type.filter { it in ' '..'~' }.take(64)
        val why = reason ?: "disabled by policy '$presetId'"
        return (template ?: "policy: '{frame}' ({action}) is disabled: {reason}")
            .replace("{frame}", shown).replace("{action}", actionId ?: "unmapped").replace("{reason}", why)
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
