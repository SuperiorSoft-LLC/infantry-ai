package com.superiorsoftllc.agenthq.agents

import com.superiorsoftllc.agenthq.daemon.AccessPolicy
import com.superiorsoftllc.agenthq.persistence.AgentRepository
import com.superiorsoftllc.agenthq.persistence.RoleBindingRepository
import com.superiorsoftllc.agenthq.protocol.AgentProfile
import com.superiorsoftllc.agenthq.protocol.RoleTraits
import com.superiorsoftllc.agenthq.protocol.TraitView
import com.superiorsoftllc.agenthq.session.RoleInstance
import com.superiorsoftllc.agenthq.session.TraitSnapshot
import com.superiorsoftllc.agenthq.protocol.SpawnOptions
import com.superiorsoftllc.agenthq.role.RoleIds
import com.superiorsoftllc.agenthq.session.SessionManager
import java.util.UUID

/**
 * Story 9.1 (contract 1.4.0): agent profiles, and spawning a session from one.
 *
 * A profile is a TEMPLATE (name, role, provider, model, persona instructions), stored by the daemon.
 * A spawn that names a profile ([SpawnOptions.agentId]) starts a fresh session that records the
 * profile's id and role and runs on the profile's provider and model, with the persona put ahead of
 * the session's first prompt.
 *
 * Everything a client sends is validated here before it is stored, and a profile the daemon could not
 * spawn from (a provider it is not configured with, a model that could change the command line, or a
 * model the provider does not know) is refused at save time with [IllegalArgumentException], never
 * stored to fail later. A spawn is resolved again when the session is actually created, because a
 * profile can change in between.
 */
class AgentProfiles(
    private val repository: AgentRepository,
    private val sessions: SessionManager,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { "agent-" + UUID.randomUUID().toString().take(8) },
    /** ROLE-1b: what a role runs on when neither the spawn nor the profile pins it. Null = no bindings. */
    private val roleBindings: RoleBindingRepository? = null,
    /** Role-first S1: whether a role id exists. Default = the built-in catalog ids; the daemon passes its live catalog. */
    private val roleExists: (String) -> Boolean = { it in RoleIds.BUILT_IN },
    /** Role-first S2: the effort levels a profile may pin; the same set the role-binding check uses. */
    private val effortLevels: Set<String> = com.superiorsoftllc.agenthq.harness.claude.ClaudeCommands.EFFORT_LEVELS.toSet(),
    /** TRAITS-T3: whether a trait id is in the library. Default = none is, so a profile can add no trait unless the daemon wires it. */
    private val traitExists: (String) -> Boolean = { false },
    /** TRAITS-T4: a role's bound trait list (null = none). Default = no role binds any trait. */
    private val roleTraits: (String) -> RoleTraits? = { null },
    /** TRAITS-T4: a library trait by id (null = absent). Default = the library is empty. */
    private val trait: (String) -> TraitView? = { null },
    /**
     * P10-K12: refuses (with [IllegalArgumentException]) an agent skill list naming an unknown skill or one twice. Default
     * = only the duplicate check, so a profile can attach no skill it could not resolve unless the daemon wires the library.
     */
    private val requireKnownSkills: (List<String>) -> Unit = { ids -> require(ids.isEmpty()) { "skillIds: unknown skill ${ids.joinToString { "'${it.take(80)}'" }}" } },
    /** ROLE-EDIT-1: a role's instructions (its persona) from the live catalog, null/blank = none. Default = no role has any. */
    private val rolePersona: (String) -> String? = { null },
) {

    /**
     * TRAITS-T4 (docs/AGENTHQ-ROLE-TRAITS.md section 3): the [RoleInstance] a session spawned NOW for [agentId] and/or
     * [role] is a clone of -- the profile's persona and the effective trait set ([TraitBindings.effective]: the role's
     * list, then the agent's additions) as frozen snapshots. The role is the spawn's, else the profile's. Null for an
     * anonymous spawn. ROLE-EDIT-1: the profile's own instructions win; the role's persona ([rolePersona], read at spawn,
     * so an edit saved before the spawn applies and a running session keeps what it froze) is the fallback. Every spawn path calls this, directly or through [SessionManager.instanceFor], so the stored
     * instance is exactly what was rendered.
     */
    fun instanceFor(agentId: String?, role: String?): RoleInstance? {
        val id = agentId?.trim()?.takeIf { it.isNotEmpty() }
        val profile = id?.let { repository.get(it) }
        val roleId = role?.trim()?.takeIf { it.isNotEmpty() } ?: profile?.role
        if (id == null && roleId == null) return null
        val traitIds = TraitBindings.effective(profile, roleId?.let(roleTraits))
        return RoleInstance(
            agentId = id,
            role = roleId,
            instructions = profile?.instructions?.takeIf { it.isNotBlank() } ?: roleId?.let(rolePersona)?.takeIf { it.isNotBlank() },
            traits = traitIds.mapNotNull { trait(it)?.let(TraitSnapshot::of) },
        )
    }

    /** SWAP-AGENT-1: whether [id] is a role this daemon knows (the live catalog the daemon wired in). */
    fun roleKnown(id: String): Boolean = roleExists(id)

    /** Every profile in profile order: oldest first, then by id. */
    fun list(): List<AgentProfile> = repository.all()

    /** CP-3: only [projectId]'s agents. A blank id lists nobody: an unscoped read is [list]. */
    fun list(projectId: String): List<AgentProfile> {
        val wanted = projectId.trim()
        return if (wanted.isEmpty()) emptyList() else repository.all().filter { it.projectId == wanted }
    }

    fun get(id: String): AgentProfile? = repository.get(id)

    /**
     * Validate [profile], then create it (no id, or an id nobody has) or overwrite the profile with that
     * id. Returns the profile as stored, normalised (trimmed, role and provider lowercased, blank
     * optional fields as null). Throws [IllegalArgumentException] naming the first problem found.
     */
    @Synchronized
    fun save(profile: AgentProfile, requireProject: Boolean = false): AgentProfile {
        val id = profile.id?.trim()?.takeIf { it.isNotEmpty() }?.also { checkId(it) } ?: newId()
        // CP-3: an agent belongs to exactly ONE project and never moves. A blank projectId on a known
        // agent keeps its project; a different one is refused; a NEW agent over the wire (requireProject)
        // must name one, so no agent is created global.
        val existing = repository.get(id)
        val requested = profile.projectId?.trim()?.takeIf { it.isNotEmpty() }
        require(requested == null || (requested.none { it.isISOControl() } && requested.length <= 200)) { "projectId is invalid" }
        val owner = existing?.projectId
        if (owner != null && requested != null && requested != owner) {
            throw IllegalArgumentException("agent '$id' belongs to project '$owner' and cannot move to '$requested'")
        }
        val projectId = requested ?: owner
        require(projectId != null || !requireProject) { "projectId is required: an agent belongs to exactly one project" }
        val name = profile.name.trim()
        require(name.isNotEmpty()) { "name is blank" }
        require(name.length <= MAX_NAME_LENGTH) { "name is longer than $MAX_NAME_LENGTH characters" }
        require(name.none { it.isISOControl() }) { "name contains a control character" }
        // ORIGIN-SCRUB-1a: a retired role id from an older client is saved under the id that replaced it.
        val role = RoleIds.normalize(profile.role.trim().lowercase())
        require(RoleIds.PATTERN.matches(role)) {
            "role '${role.take(40)}' is not a role id: a lowercase letter, then up to 31 lowercase letters, digits or '-' (e.g. backend, qa)"
        }
        val (provider, model, effort) = validateExecutor(profile.provider, profile.model, profile.effort)
        val instructions = profile.instructions?.trim()?.takeIf { it.isNotEmpty() }
        require((instructions?.length ?: 0) <= MAX_INSTRUCTIONS_LENGTH) {
            "instructions are longer than $MAX_INSTRUCTIONS_LENGTH characters"
        }

        // TRAITS-T3: absent/null keeps the stored additions (an older client must not strip them); [] clears them.
        val traitIds = profile.traitIds?.let(::validTraitIds) ?: existing?.traitIds.orEmpty()
        // P10-K12 (K-D9): the same rule for the agent's attached skills -- absent/null keeps, [] clears, unknown refused.
        val skillIds = profile.skillIds?.map { it.trim() }?.also(requireKnownSkills) ?: existing?.skillIds.orEmpty()

        val stored = AgentProfile(id, name, role, provider, model, instructions, projectId, effort, traitIds, skillIds)
        repository.save(stored, clock())
        return repository.get(id) ?: error("profile $id was saved but cannot be read back")
    }

    /**
     * Role-first S3: the ONE executor check, used by [save] and by [swap] so the two can never disagree. Normalises
     * (provider trimmed and lowercased, blank fields as null) and refuses, with [IllegalArgumentException], a model that
     * could change a command line, a provider or model [SessionManager.checkSpawn] refuses, a model the provider does
     * not know, and an effort that is not one of [effortLevels]. Writes nothing.
     */
    fun validateExecutor(provider: String?, model: String?, effort: String?): Executor {
        val p = provider?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val m = model?.trim()?.takeIf { it.isNotEmpty() }
        if (m != null) {
            require(m.none { c -> c.isWhitespace() || c.isISOControl() }) { "model contains whitespace or a control character" }
            require(m.length <= 200) { "model is longer than 200 characters" }
        }
        sessions.checkSpawn(p, m)
        validateModel(p, m)
        val e = effort?.trim()?.ifEmpty { null }
        require(e == null || e in effortLevels) { "unknown effort '$e': one of ${effortLevels.toList()}" }
        return Executor(p, m, e)
    }

    /** TRAITS-T3: an agent's trait additions -- trimmed, at most [MAX_TRAIT_IDS], no duplicates, each in the library. */
    private fun validTraitIds(ids: List<String>): List<String> {
        val trimmed = ids.map { it.trim() }
        require(trimmed.size <= MAX_TRAIT_IDS) { "an agent adds at most $MAX_TRAIT_IDS traits; got ${trimmed.size}" }
        trimmed.groupBy { it }.values.firstOrNull { it.size > 1 }?.let {
            throw IllegalArgumentException("trait '${it.first().take(40)}' is listed twice")
        }
        trimmed.firstOrNull { !traitExists(it) }?.let { throw IllegalArgumentException("unknown trait '${it.take(40)}' (trait_list shows the library)") }
        return trimmed
    }

    /** The profile an agent had before a swap and the one stored by it. */
    data class Swap(val before: AgentProfile, val after: AgentProfile)

    /**
     * Role-first S3 (`agent_swap`): point agent [agentId] at another executor. Every check runs first -- the agent
     * exists, a provider is named, [validateExecutor], then [beforeWrite] (the caller's own checks, e.g. the session
     * mode) -- and only then is the profile written, under the same lock as [save]. Any refusal is an
     * [IllegalArgumentException] with NOTHING written. Only provider/model/effort change; the rest of the profile is
     * kept as stored, so a swap cannot erase a concurrent edit of the persona or name.
     */
    @Synchronized
    fun swap(
        agentId: String,
        provider: String?,
        model: String?,
        effort: String?,
        beforeWrite: (before: AgentProfile, after: AgentProfile) -> Unit = { _, _ -> },
    ): Swap {
        val id = agentId.trim()
        require(id.isNotEmpty()) { "agentId is required" }
        val before = repository.get(id) ?: throw IllegalArgumentException("no agent profile '${id.take(80)}' (agent_list shows the profiles)")
        require(!provider.isNullOrBlank()) { "provider is required: the harness the agent runs on" }
        val executor = validateExecutor(provider, model, effort)
        val after = before.copy(provider = executor.provider, model = executor.model, effort = executor.effort)
        beforeWrite(before, after)
        repository.save(after, clock())
        return Swap(before, repository.get(id) ?: error("profile $id was swapped but cannot be read back"))
    }

    /** Check that [model] is known by [provider] (if the provider can list its models). */
    private fun validateModel(providerId: String?, model: String?) {
        if (model == null) return // no model = provider's default, always accepted
        // BUG-PROF-1: resolved like a spawn (configured OR an adopted registry harness such as claude / pi), never via
        // the configured-only list, whose `first {}` threw for those and failed every save naming their model.
        val provider = providerId?.let { sessions.providerFor(it) } ?: return // no provider = default, validated at spawn time
        val known = provider.knownModels() ?: return // provider cannot list models, accept anything
        if (model in known) return
        val closest = closestMatch(model, known)
        throw IllegalArgumentException(
            "unknown model '$model' for ${provider.id} — did you mean '$closest'?"
        )
    }

    private fun closestMatch(target: String, candidates: List<String>): String {
        var best = candidates.minByOrNull { levenshtein(target, it) } ?: candidates.first()
        return best
    }

    /** Levenshtein edit distance (insert, delete, substitute). */
    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val temp = dp[j]
                dp[j] = minOf(
                    dp[j] + 1,        // deletion
                    dp[j - 1] + 1,    // insertion
                    prev + (if (a[i - 1] == b[j - 1]) 0 else 1) // substitution
                )
                prev = temp
            }
        }
        return dp[b.length]
    }

    /** Delete the profile [id]; throws [IllegalArgumentException] when there is none. */
    @Synchronized
    fun delete(id: String) {
        val wanted = id.trim()
        require(wanted.isNotEmpty()) { "agent_delete expects a profile id" }
        require(repository.delete(wanted)) { "no agent profile '${wanted.take(80)}'" }
    }

    /**
     * Work out how a session is spawned from a connection's [options] (null = a plain spawn on the
     * default provider). Throws [IllegalArgumentException] when it cannot be honoured: an unknown
     * profile, or a provider or model [SessionManager.checkSpawn] refuses.
     *
     * Resolution, first non-null wins: provider = the spawn's, else the profile's, else the default;
     * model = the spawn's, else the profile's, else the provider's default. The profile's model is
     * used only while the session runs on the profile's own provider: a model id belongs to one
     * provider, so a spawn that moves a profile to another provider never carries its model along.
     */
    fun resolveSpawn(options: SpawnOptions?): ResolvedSpawn {
        val agentId = options?.agentId?.trim()?.takeIf { it.isNotEmpty() }
        val profile = agentId?.let {
            repository.get(it) ?: throw IllegalArgumentException("no agent profile '${it.take(80)}' (agent_list shows the profiles)")
        }
        val spawnRole = options?.role?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let(RoleIds::normalize)
        if (spawnRole != null) {
            require(RoleIds.PATTERN.matches(spawnRole) && roleExists(spawnRole)) { "unknown role '${spawnRole.take(40)}'" }
            require(profile == null || profile.role == spawnRole) {
                "agent '${profile?.id}' has role '${profile?.role}', not '$spawnRole'"
            }
        }
        val spawnProvider = options?.provider?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val spawnModel = options?.model?.trim()?.takeIf { it.isNotEmpty() }
        val (provider, model, effort) = executorFor(profile, spawnProvider, spawnModel, options?.effort, spawnRole)
        sessions.checkSpawn(provider, model)
        // CP-3: a profile's project is the session's project. A spawn naming ANOTHER project is refused,
        // so an agent never runs (or gets addressed) inside a project it does not belong to.
        val spawnProject = options?.projectId?.trim()?.takeIf { it.isNotEmpty() }
        val profileProject = profile?.projectId
        require(profileProject == null || spawnProject == null || spawnProject == profileProject) {
            "agent '${profile?.id}' belongs to project '$profileProject', not '$spawnProject'"
        }
        return ResolvedSpawn(provider, model, profile?.id, profile?.role ?: spawnRole, profile?.instructions, profileProject ?: spawnProject, effort)
    }

    /** What a session runs on once every layer has spoken; a null field is "the daemon default". */
    data class Executor(val provider: String?, val model: String?, val effort: String?)

    /**
     * ROLE-1b: the single place the layers meet. Per field, first non-null wins: the caller's own choice
     * ([provider] / [model] / [effort]: a spawn's options or a ticket's pins), then [profile], then the
     * binding of the profile's role, then null (the daemon default). A model id belongs to one provider, so
     * the profile's model and the binding's model are used only while the session runs on the provider
     * they were pinned for. Read at call time and copied into the session: a later rebind never reaches a
     * session that already started, and nothing here writes a binding.
     */
    fun executorFor(profile: AgentProfile?, provider: String?, model: String?, effort: String?, role: String? = null): Executor {
        val binding = (profile?.role ?: role)?.let { roleBindings?.get(it) }
        val resolvedProvider = provider ?: profile?.provider ?: binding?.provider
        val profileProvider = profile?.provider ?: binding?.provider
        val profileModelApplies = provider == null || provider == (profileProvider ?: sessions.providerId)
        val bindingModelApplies = (resolvedProvider ?: sessions.providerId) == (binding?.provider ?: sessions.providerId)
        val resolvedModel = model
            ?: profile?.model?.takeIf { profileModelApplies }
            ?: binding?.model?.takeIf { bindingModelApplies }
        return Executor(resolvedProvider, resolvedModel, effort ?: profile?.effort ?: binding?.effort)
    }

    private fun checkId(id: String) {
        require(ID.matches(id)) {
            "id '${id.take(80)}' is not a profile id: a letter or digit, then up to 63 letters, digits, '.', '_' or '-'"
        }
    }

    private fun newId(): String {
        repeat(10) {
            val candidate = idFactory()
            if (repository.get(candidate) == null) return candidate
        }
        error("could not find a free profile id")
    }

    companion object {
        const val MAX_NAME_LENGTH = 100
        const val MAX_INSTRUCTIONS_LENGTH = 20_000
        /** TRAITS-T3: the most trait ids an agent may add to its role's list. */
        const val MAX_TRAIT_IDS = 8

        /** No ':' on purpose: `role:<role>` is how a ticket will ask for any profile of a role (story 9.3). */
        private val ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
    }
}

/**
 * How one session is spawned, resolved from a connection's spawn options and the profile they name.
 * Null fields mean "the default" ([providerId], [model]) or "no profile" ([agentId], [role],
 * [instructions]).
 */
data class ResolvedSpawn(
    val providerId: String? = null,
    val model: String? = null,
    val agentId: String? = null,
    val role: String? = null,
    val instructions: String? = null,
    val projectId: String? = null,
    /** The reasoning effort the session starts with: the spawn's, else the role binding's. Null = harness default. */
    val effort: String? = null,
) {
    /** The first prompt of a session spawned this way: the persona, then who it runs as, then the client's prompt. */
    fun firstPrompt(prompt: String): String = firstTurn(instructions, role, agentId, prompt)
}

/**
 * BUG-808ce596: the first turn a session's harness receives -- the profile's persona (if any), then the
 * agent's [identityLine] (if it has a role or an agent id), then [prompt]. The one composer for every path
 * that gives a bound session its first turn (a `prompt` frame, a delivered message), so the opening context
 * agrees with the run's env ([AccessPolicy.ROLE_ENV], [AccessPolicy.AGENT_ID_ENV]). A role-only spawn has no
 * persona, so before this its first turn was the bare task. An anonymous session gets [prompt] as typed.
 */
fun firstTurn(instructions: String?, role: String?, agentId: String?, prompt: String): String =
    composeFirstTurn(instructions, emptyList(), role, agentId, prompt)

/**
 * TRAITS-T5 (docs/AGENTHQ-ROLE-TRAITS.md section 4): the first turn of a session spawned from [instance] -- its FROZEN
 * persona, then the [identityLine], then its standing rules ([renderTraits]), then [prompt]. Every first-turn site of a
 * bound session reads the session's own instance ([SessionManager.instance]), never the live profile, so a persona or
 * trait edit after spawn never reaches it. No instance, or one with no traits, gives exactly the [firstTurn] text.
 * P10-K9: [knowledge] is the session's knowledge section ([KnowledgeCounter.section]), rendered after the traits.
 * P10-K15: [skills] is the session's skills section ([SkillsSource.section]), rendered after the knowledge.
 */
fun firstTurn(
    instance: RoleInstance?,
    role: String?,
    agentId: String?,
    prompt: String,
    knowledge: String? = null,
    skills: String? = null,
): String = composeFirstTurn(instance?.instructions, instance?.traits.orEmpty(), role, agentId, prompt, knowledge, skills)

/**
 * P10-K9 / K15: persona -> identity -> traits -> knowledge ([renderKnowledge]) -> skills ([renderSkills]) -> task, on
 * every first-turn path; a null section is absent.
 */
private fun composeFirstTurn(
    persona: String?,
    traits: List<TraitSnapshot>,
    role: String?,
    agentId: String?,
    prompt: String,
    knowledge: String? = null,
    skills: String? = null,
): String =
    listOfNotNull(persona?.takeIf { it.isNotBlank() }, identityLine(role, agentId), renderTraits(traits), knowledge, skills, prompt)
        .joinToString("\n\n")

/** TRAITS-T5: the header [renderTraits] opens with. */
const val TRAITS_HEADER = "Standing rules bound to your role (they apply to this whole session):"

/**
 * TRAITS-T5: the standing rules of a session, one block per trait in order (`## <name> (<id> v<version>)`, then its
 * body), under [TRAITS_HEADER]. Null when there are none, so a session with no traits gets today's text exactly.
 */
fun renderTraits(snapshots: List<TraitSnapshot>): String? {
    if (snapshots.isEmpty()) return null
    return (listOf(TRAITS_HEADER) + snapshots.map { "## ${it.name} (${it.id} v${it.version})\n${it.body}" }).joinToString("\n\n")
}

/** Who an agent runs as, in one line naming the env variables that carry the same facts; null when it has neither. */
fun identityLine(role: String?, agentId: String?): String? {
    val facts = listOfNotNull(
        role?.takeIf { it.isNotBlank() }?.let { "role '$it'" to AccessPolicy.ROLE_ENV },
        agentId?.takeIf { it.isNotBlank() }?.let { "agent id '$it'" to AccessPolicy.AGENT_ID_ENV },
    )
    if (facts.isEmpty()) return null
    val variables = facts.map { it.second } + AccessPolicy.SESSION_ID_ENV
    return "You are running inside AgentHQ as ${facts.joinToString(", ") { it.first }}. " +
        "Your environment carries the same identity (${variables.joinToString(", ")})."
}
