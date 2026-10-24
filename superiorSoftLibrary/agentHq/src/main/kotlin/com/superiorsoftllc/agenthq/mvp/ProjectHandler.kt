package com.superiorsoftllc.agenthq.mvp

import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.protocol.Project
import com.superiorsoftllc.agenthq.protocol.ProjectBaseRequest
import com.superiorsoftllc.agenthq.protocol.ProjectConfigRequest
import com.superiorsoftllc.agenthq.protocol.ProjectSave
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * MVP Sprint-Zero, slice A3: the `project_*` WIRE SURFACE over a [ProjectStore].
 *
 * The seam (slice 0, corrected by `0822d8f`) froze the frames `project_save` / `project_get` /
 * `project_list`, answered by `project` / `projects`, and CONTRACT.md §2.2.5 froze their
 * shape and their refusal convention. A1/A2 built the store those frames speak about. This class
 * is the thin layer between the two: it decodes a request, asks the store, and encodes the answer
 * -- nothing more, so every project rule (what a create needs, what an edit may leave out, how ids
 * are minted, what is refused) stays the store's own.
 *
 * IT IS PERSISTENCE-AGNOSTIC ON PURPOSE. The constructor takes a [ProjectStore] and never asks how
 * it persists: the daemon builds one over `SqlProjectPersistence` and every other caller builds one
 * over the in-memory default. The wiring of that durable store into the daemon, and the router
 * registration that dispatches `project_*` frames here, belong to I0 (integration) -- see
 * [answer] for the two shapes that registration can take.
 *
 * A refusal answers the frozen `error` frame naming its own request (`project_save refused: ...`),
 * the standing convention `ticket_get` and the backlog follow; nothing is written when a request
 * is refused, because the store throws before it writes.
 */
class ProjectHandler(
    private val store: ProjectStore,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
    /**
     * BASE-1a: checks a base branch before `project_base` stores it (see [ProjectBaseCheck]); null = this handler
     * has no repository to check against, and refuses every set (a read and a clear still answer).
     */
    private val baseCheck: ProjectBaseCheck? = null,
    /** LANE-CAPS: told the whole project after a `project_config` changed it, so the daemon can push it to every client. */
    private val onConfigChanged: (Project) -> Unit = {},
    /**
     * ROUTING-1 (contract 1.128.0): normalises and checks a submitted rule list (throws IllegalArgumentException with the
     * reason). The daemon passes [com.superiorsoftllc.agenthq.backlog.RoutingRules.validate] over the harness check
     * ticket create uses; the default has no harness to check against and refuses every non-empty list.
     */
    private val routingValidator: (List<com.superiorsoftllc.agenthq.protocol.RoutingRule>) -> List<com.superiorsoftllc.agenthq.protocol.RoutingRule> = { rules ->
        com.superiorsoftllc.agenthq.backlog.RoutingRules.validate(
            rules,
            checkExecutor = { _, _, _ -> throw IllegalArgumentException("this daemon has no harness list to check a provider / model against") },
            roleKnown = { true },
        )
    },
) {
    /** CP-3: the DEFAULT project (the oldest, the order `project_list` answers in), or null when there is none. */
    fun defaultProjectId(): String? = store.list().firstOrNull()?.id

    /** BUG-M2-15b: whether [id] names a known project (a `session_project` target must). */
    fun exists(id: String): Boolean = store.list().any { it.id == id.trim() }

    /**
     * Answer one frame, or null when the frame is not this handler's.
     *
     * The daemon's router registers its own branch per frame type, so the recommended registration
     * is three trivial lines with no logic of their own:
     *
     *     Envelope.PROJECT_SAVE -> projects.answer(envelope)  // and PROJECT_GET, PROJECT_LIST
     *
     * Returning null for a foreign frame (rather than throwing, or answering an `error`) also makes
     * the single-line fallback safe, for a router that prefers it:
     *
     *     else -> projects?.answer(envelope)
     *         ?: Envelope(Envelope.ERROR, "unknown frame type '${envelope.type}'")
     *
     * Either way the MVP's frames are answered here and EVERY other frame keeps the daemon's own
     * answer untouched -- no story board, ticket or session frame can be swallowed by this layer.
     */
    fun answer(envelope: Envelope): Envelope? = when (envelope.type) {
        Envelope.PROJECT_SAVE -> projectRequest(envelope.type) {
            projectEnvelope(store.save(ProjectSave.decode(envelope.data)))
        }

        // The id travels as the raw payload, not as JSON, exactly as `ticket_get` does.
        Envelope.PROJECT_GET -> projectRequest(envelope.type) {
            projectEnvelope(store.get(envelope.data))
        }

        Envelope.PROJECT_LIST -> projectRequest(envelope.type) {
            projectsEnvelope(store.list())
        }

        // BASE-1a (contract 1.81.0): read, set or clear the project's base branch.
        Envelope.PROJECT_BASE -> projectRequest(envelope.type) {
            val request = ProjectBaseRequest.decode(envelope.data)
            require(!(request.clear && request.branch != null)) { "pass a branch or clear, not both" }
            val project = when {
                request.clear -> store.setBaseBranch(request.projectId, null)
                request.branch != null -> {
                    val name = ProjectBaseCheck.checkName(request.branch)
                    val check = baseCheck ?: throw IllegalArgumentException("this daemon has no repository to check a base branch against")
                    store.setBaseBranch(request.projectId, name) { project, branch -> check.check(project, branch) }
                }
                else -> store.get(request.projectId)
            }
            projectEnvelope(project)
        }

        // LANE-CAPS (contract 1.111.0): read or merge the per-role agent caps.
        Envelope.PROJECT_CONFIG -> projectRequest(envelope.type) {
            val request = ProjectConfigRequest.decode(envelope.data)
            val caps = request.laneCaps
            val autoRemove = request.autoRemoveDoneWorkers
            val rules = request.routingRules
            val experiments = request.experiments
            if (caps.isNullOrEmpty() && autoRemove == null && rules == null && experiments.isNullOrEmpty()) projectEnvelope(store.get(request.projectId))
            else {
                // ROUTING-1: the rules are validated first (a refused list writes nothing), then the caps: a refused cap
                // writes nothing either, so nothing else is changed by a refused request.
                var project = store.get(request.projectId)
                val checkedRules = rules?.let(routingValidator)
                if (!caps.isNullOrEmpty()) project = store.setLaneCaps(request.projectId, caps)
                if (checkedRules != null) project = store.setRoutingRules(request.projectId, checkedRules)
                if (autoRemove != null) project = store.setAutoRemoveDoneWorkers(request.projectId, autoRemove)
                if (!experiments.isNullOrEmpty()) project = store.setExperiments(request.projectId, experiments)
                if (!experiments.isNullOrEmpty()) project = store.setExperiments(request.projectId, experiments)
                projectEnvelope(project.also(onConfigChanged))
            }
        }

        else -> null
    }

    /** One project as a `project` frame, every field present. */
    fun projectEnvelope(project: Project): Envelope =
        Envelope(Envelope.PROJECT, json.encodeToString(Project.serializer(), project))

    /** Every project, in creation order, as a `projects` frame. */
    fun projectsEnvelope(projects: List<Project>): Envelope =
        Envelope(Envelope.PROJECTS, json.encodeToString(ListSerializer(Project.serializer()), projects))

    /**
     * Run one project request for frame [frame]: a refusal ([IllegalArgumentException], a strictly
     * decoded malformed payload included) answers `<frame> refused: <why>`; anything else unexpected
     * answers `<frame> failed: <what>` rather than reaching the client as an exception.
     */
    private inline fun projectRequest(frame: String, block: () -> Envelope): Envelope = try {
        block()
    } catch (e: IllegalArgumentException) {
        Envelope(Envelope.ERROR, "$frame refused: ${e.message ?: "invalid request"}")
    } catch (e: Exception) {
        Envelope(Envelope.ERROR, "$frame failed: ${e.message ?: e::class.simpleName}")
    }
}
