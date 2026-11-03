package com.superiorsoftllc.infantry.plugin.api

import kotlinx.serialization.Serializable

/**
 * AGENT-REGISTRY-1 (docs/AGENT-REGISTRY.md section 3): one agent a module contributes, as data. An agent is a definition
 * (who it is, what it is told, what it runs on and may use); a session is one instance of it in a chat. Nothing here names
 * tickets, boards, worktrees or branches: binding a session to work items is a capability a higher layer (the
 * orchestration plugin) adds on top, never a field of the agent.
 *
 * module.json: `"agents": [ { "id": "acme-news", "name": "News", "instructions": "...", "tools": ["acme_news_fetch"] } ]`.
 * Unknown keys are ignored, so a newer definition still parses.
 */
@Serializable
data class AgentDefinition(
    /** `[a-z][a-z0-9-]{0,31}`, the role-id shape; a community module's ids start with its lowercase vendor and `-`. */
    val id: String,
    /** Display name; blank = the id. */
    val name: String = "",
    /** One line for lists and editors; never sent to a session. */
    val description: String = "",
    /** The agent's own instructions (its system prompt). Blank with [role] set = the role's persona. */
    val instructions: String = "",
    /** A role id whose persona the agent uses when [instructions] is blank (read at spawn, so a role edit applies). */
    val role: String? = null,
    /**
     * Owner O4: a named behaviour preset (an agent profile: instructions, tools, harness, model, context limit) this agent uses.
     * Every field set HERE overrides the profile's; a field left blank / null / empty here takes the profile's. Null = none.
     */
    val profile: String? = null,
    /** The harness (provider) id a session of this agent runs on by default; null = the caller's / the role binding's. */
    val harness: String? = null,
    /** The model by default; null = the harness default. */
    val model: String? = null,
    /** The context window cap in tokens by default; null = the harness / model default. */
    val contextLimit: Long? = null,
    /** EFFORT-1: the reasoning effort level by default (low / medium / high / xhigh / max); null = the harness default. Additive. */
    val effort: String? = null,
    /** The daemon tools (by name) the agent may use; empty = no narrowing (what its session would get anyway). */
    val tools: List<String> = emptyList(),
    /** Optional: run a turn on a period. Declared data; who runs it is docs/AGENT-REGISTRY.md section 3.5. */
    val schedule: AgentSchedule? = null,
    /** Optional hints for clients that draw the agent (badge colour, icon key). Never interpreted by the daemon. */
    val ui: AgentUiHints? = null,
)

/** `agents[].schedule`: every [everyMinutes] (at least 1), the agent is given [prompt]. */
@Serializable
data class AgentSchedule(val everyMinutes: Int, val prompt: String = "")

/** `agents[].ui`: a `#rrggbb` [color] and a client icon key [icon] (`[a-z0-9-]`). */
@Serializable
data class AgentUiHints(val color: String? = null, val icon: String? = null)

/** AGENT-REGISTRY-1: the rules every contributed [AgentDefinition] passes, the same on every platform. */
object AgentDefinitions {
    /** EFFORT-1: the effort levels (the daemon's `session.EffortLevels`, mirrored here: this module has no daemon dependency). */
    val EFFORT_LEVELS = listOf("low", "medium", "high", "xhigh", "max")

    /** The role-id shape, so an agent id and a role id can never disagree about what a legal id is. */
    val ID = Regex("[a-z][a-z0-9-]{0,31}")

    /** A profile id (the daemon's are `agent-<8 hex>`). */
    val PROFILE_ID = Regex("[a-z][a-z0-9-]{0,63}")

    /**
     * Owner O4: [def] over the behaviour preset [profile]: what [def] sets wins, what it leaves blank the profile fills
     * (instructions, tools, harness, model, context limit). Identity, role, schedule and ui stay [def]'s.
     */
    fun withProfile(def: AgentDefinition, profile: AgentDefinition?): AgentDefinition = if (profile == null) def else def.copy(
        instructions = def.instructions.ifBlank { profile.instructions },
        tools = def.tools.ifEmpty { profile.tools },
        harness = def.harness ?: profile.harness,
        model = def.model ?: profile.model,
        contextLimit = def.contextLimit ?: profile.contextLimit,
        effort = def.effort ?: profile.effort,
    )

    const val MAX_NAME = 60
    const val MAX_DESCRIPTION = 200
    const val MAX_INSTRUCTIONS = 20_000

    /** Why [def] may not be contributed by module [module] of tier [community]; null = it may. */
    fun refusal(def: AgentDefinition, module: String, community: Boolean): String? {
        if (!ID.matches(def.id)) return "agent id '${def.id.take(40)}' must match ${ID.pattern}"
        if (community) {
            val vendor = module.substringBefore('_').lowercase() + "-"
            if (!def.id.startsWith(vendor)) return "community agent id '${def.id}' must start with '$vendor'"
        }
        if (def.name.length > MAX_NAME) return "agent '${def.id}': name is longer than $MAX_NAME characters"
        if (def.description.length > MAX_DESCRIPTION) return "agent '${def.id}': description is longer than $MAX_DESCRIPTION characters"
        if (def.instructions.length > MAX_INSTRUCTIONS) return "agent '${def.id}': instructions are longer than $MAX_INSTRUCTIONS characters"
        def.role?.let { if (!ID.matches(it)) return "agent '${def.id}': role '${it.take(40)}' must match ${ID.pattern}" }
        def.profile?.let { if (!PROFILE_ID.matches(it)) return "agent '${def.id}': profile '${it.take(40)}' must match ${PROFILE_ID.pattern}" }
        def.contextLimit?.let { if (it <= 0) return "agent '${def.id}': contextLimit must be positive" }
        def.effort?.let { if (it !in EFFORT_LEVELS) return "agent '${def.id}': effort '${it.take(40)}' must be one of $EFFORT_LEVELS" }
        def.schedule?.let { if (it.everyMinutes < 1) return "agent '${def.id}': schedule.everyMinutes must be at least 1" }
        if (def.tools.toSet().size != def.tools.size) return "agent '${def.id}': tools lists a tool twice"
        def.ui?.color?.let { if (!Regex("#[0-9a-fA-F]{6}").matches(it)) return "agent '${def.id}': ui.color must be #rrggbb" }
        def.ui?.icon?.let { if (!Regex("[a-z0-9][a-z0-9-]{0,31}").matches(it)) return "agent '${def.id}': ui.icon must match [a-z0-9][a-z0-9-]{0,31}" }
        return null
    }
}
