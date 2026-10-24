package com.superiorsoftllc.agenthq.mvp

import com.superiorsoftllc.agenthq.protocol.Project
import com.superiorsoftllc.agenthq.protocol.ProjectSave
import java.util.UUID

/**
 * MVP Sprint-Zero, slice A: the local store of PROJECT scopes.
 *
 * The seam (slice 0, corrected by 0822d8f) froze `Project` / `ProjectSave` and the
 * `project_save` / `project_get` / `project_list` frames. This is the half that decides what those
 * frames DO: it owns every project and mints its ids, so a ticket or a story names its project by an
 * id the store chose. Slice B's story store owns its own rows and neither reads the other, which is
 * what keeps the two slices independently buildable.
 *
 * A1's slice of that is the operations only. The store is deliberately in memory BY DEFAULT:
 * persistence across a daemon restart is slice A2, provided as an injected [ProjectPersistence] a
 * durable caller (the daemon) passes and everyone else leaves at its in-memory default; the wire
 * frames that publish these answers are slice A3, so neither is built here.
 *
 * RETIRED (contract 1.29.0): `Project` used to carry `storyIds`, an ordered list I0 maintained as
 * stories were created via [appendStoryId]. Both are gone -- a story's order now lives entirely on
 * [com.superiorsoftllc.agenthq.epics.Epic.storyIds], which the daemon's integration wiring
 * (`AgentHqDaemon.build`) appends to instead. See `protocol/Projects.kt` for why.
 *
 * A refusal throws [IllegalArgumentException] naming the problem, and nothing is written -- the
 * wire layer turns that into `project_save refused: ...` / `project_get refused: ...` (slice A3).
 */
class ProjectStore(
    /**
     * Where projects outlive the store. The default is A1's in-memory behaviour; the daemon passes
     * [SqlProjectPersistence] over its own database, and a restart -- a new store over the same
     * file -- reads back everything the old one wrote (see `ProjectStoreRestartTest`).
     */
    private val persistence: ProjectPersistence = InMemoryProjectPersistence(),
    private val idFactory: () -> String = { "project-" + UUID.randomUUID().toString().take(8) },
) {
    /** Insertion ordered, so `list` answers in the order projects were created. */
    private val projects = LinkedHashMap<String, Project>()

    init {
        // A restart looks exactly like this constructor call over an already-populated database.
        persistence.loadAll().forEach { projects[it.id] = it }
    }

    /** Create a project (no id) or edit one (see [ProjectSave]). Answered with the whole [Project]. */
    @Synchronized
    fun save(request: ProjectSave): Project {
        val editing = request.id?.trim()?.takeIf { it.isNotEmpty() }
        if (editing == null) {
            val name = request.name?.let { checkName(it) }
                ?: throw IllegalArgumentException("name is required to create a project")
            val goal = request.goal?.let { checkGoal(it) } ?: ""
            val directory = request.directory?.let { checkDirectory(it) } ?: ""
            val created = Project(id = mintId(), name = name, goal = goal, directory = directory)
            projects[created.id] = created
            persist(created)
            return created
        }
        val existing = projects[editing]
            ?: throw IllegalArgumentException(
                "no project '${editing.take(80)}' (project_save creates a project only when it carries no id)",
            )
        val name = request.name?.let { checkName(it) } ?: existing.name
        val goal = if (request.goal == null) existing.goal else checkGoal(request.goal)
        val directory = if (request.directory == null) existing.directory else checkDirectory(request.directory)
        val updated = existing.copy(name = name, goal = goal, directory = directory)
        projects[editing] = updated
        persist(updated)
        return updated
    }

    /** Project [id]; throws [IllegalArgumentException] when there is none. */
    @Synchronized
    fun get(id: String): Project {
        val wanted = id.trim()
        require(wanted.isNotEmpty()) { "expects a project id" }
        return projects[wanted] ?: throw IllegalArgumentException("no project '${wanted.take(80)}'")
    }

    /**
     * BASE-1a: set project [id]'s base branch to [branch] (null or blank clears it), after [check] accepted it
     * (it throws [IllegalArgumentException] with the reason, and then nothing is written). Answers the whole project.
     */
    @Synchronized
    fun setBaseBranch(id: String, branch: String?, check: (Project, String) -> Unit = { _, _ -> }): Project {
        val existing = get(id)
        val wanted = branch?.trim().orEmpty()
        if (wanted.isNotEmpty()) check(existing, wanted)
        val updated = existing.copy(baseBranch = wanted)
        projects[existing.id] = updated
        persist(updated)
        return updated
    }

    /**
     * LANE-CAPS: merge [caps] (role -> max agents) into project [id]'s caps. A cap below 1 is refused; 1 is the default,
     * so it removes the entry. Nothing is written on a refusal.
     */
    @Synchronized
    fun setLaneCaps(id: String, caps: Map<String, Int>): Project {
        val existing = get(id)
        val merged = existing.laneCaps.toMutableMap()
        for ((role, cap) in caps) {
            val key = role.trim()
            require(key.isNotEmpty()) { "role is blank" }
            require(cap >= 1) { "cap for role '${key.take(80)}' must be 1 or more, was $cap" }
            if (cap == 1) merged.remove(key) else merged[key] = cap
        }
        val updated = existing.copy(laneCaps = merged.toSortedMap())
        projects[existing.id] = updated
        persist(updated)
        return updated
    }

    /** BASICS-KILL-1 (contract 1.116.0): turn project [id]'s auto-removal of done tickets' worker sessions on or off. */
    @Synchronized
    fun setAutoRemoveDoneWorkers(id: String, on: Boolean): Project {
        val existing = get(id)
        if (existing.autoRemoveDoneWorkers == on) return existing
        val updated = existing.copy(autoRemoveDoneWorkers = on)
        projects[existing.id] = updated
        persist(updated)
        return updated
    }

    /** BASICS-KILL-1: whether project [projectId] removes a done ticket's worker session (false when unset or unknown). */
    @Synchronized
    fun autoRemovesDoneWorkers(projectId: String?): Boolean = projects[projectId?.trim().orEmpty()]?.autoRemoveDoneWorkers == true

    /** LANE-CAPS: the most agents of [role] that may run at once in project [projectId] (1 when unset or unknown). */
    @Synchronized
    fun laneCapOf(projectId: String?, role: String): Int = projects[projectId?.trim().orEmpty()]?.laneCaps?.get(role) ?: 1

    /** BASE-1a: project [id]'s base branch, or null when it has none (or there is no such project). */
    @Synchronized
    fun baseBranchOf(id: String): String? = projects[id.trim()]?.baseBranch?.takeIf { it.isNotEmpty() }

    /** Every project, in the order they were created. Empty before the first create. */
    @Synchronized
    fun list(): List<Project> = projects.values.toList()

    /**
     * Write-through: the store's map is the answer for this process, the persistence surface is
     * the answer for the next one. Only ever called with an already-accepted project, so a refusal
     * (which throws before this) never leaves a half-written row behind.
     */
    private fun persist(project: Project) {
        persistence.upsert(project)
    }

    private fun mintId(): String {
        repeat(10) {
            val candidate = idFactory()
            if (candidate !in projects) return candidate
        }
        error("could not find a free project id")
    }

    companion object {
        const val MAX_NAME_LENGTH = 200
        const val MAX_GOAL_LENGTH = 100_000

        private fun checkName(raw: String): String {
            val name = raw.trim()
            require(name.isNotEmpty()) { "name is blank" }
            require(name.length <= MAX_NAME_LENGTH) { "name is longer than $MAX_NAME_LENGTH characters" }
            require(name.none { it.isISOControl() }) { "name contains a control character" }
            return name
        }

        const val MAX_DIRECTORY_LENGTH = 4_096

        /**
         * CP-2: an empty directory CLEARS the setting. Anything else must be an ABSOLUTE path to a
         * directory that exists NOW: a relative path would resolve against whatever the daemon's
         * working directory happened to be, and a missing one would make every git read answer
         * "unknown" for a reason the user could have been told at save time.
         */
        private fun checkDirectory(raw: String): String {
            val directory = raw.trim()
            if (directory.isEmpty()) return ""
            require(directory.length <= MAX_DIRECTORY_LENGTH) { "directory is longer than $MAX_DIRECTORY_LENGTH characters" }
            require(directory.none { it.isISOControl() }) { "directory contains a control character" }
            val file = java.io.File(directory)
            require(file.isAbsolute) { "directory must be an absolute path: '${directory.take(80)}'" }
            require(file.isDirectory) { "directory is not a directory that exists on the daemon's machine: '${directory.take(80)}'" }
            return directory
        }

        private fun checkGoal(raw: String): String {
            val goal = raw.trim()
            require(goal.length <= MAX_GOAL_LENGTH) { "goal is longer than $MAX_GOAL_LENGTH characters" }
            return goal
        }
    }
}
