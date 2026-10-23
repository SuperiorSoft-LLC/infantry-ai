package com.superiorsoftllc.agenthq.protocol

import kotlinx.serialization.Serializable

/*
 * MVP Sprint-Zero (slice 0 freeze): the PROJECT wire types.
 *
 * IMPLEMENTED BY: MVP slice A (branch `feature-agenthq-mvp-scope`, worktree ticket f96655b0).
 * This file is the frozen SEAM only: it names the types and carries NO behaviour -- no store,
 * no daemon dispatch, no validation. Slice A builds that half behind these names; slice B builds
 * its half without talking to A. Neither may rename or reshape a type here: the RED tests of
 * slice 0 pin them, and both implementation slices compile against them.
 *
 * Plain Kotlin and kotlinx.serialization only, so a non-JVM client can share the types.
 */

/**
 * One project: a stable id, a name and its goal.
 *
 * [id] is chosen by the daemon and is stable for the life of the project, across restarts and
 * across edits: a ticket or a story names its project by this id, so the project id is the join
 * key of the MVP. [name] is what a client shows and [goal] says in words what the project is
 * for (empty when the project carries no goal text).
 *
 * RETIRED (contract 1.29.0): this type used to also carry `storyIds`, the ordered list of the
 * project's stories, maintained by I0 as stories were created. A story's order now lives entirely
 * on [com.superiorsoftllc.agenthq.epics.Epic.storyIds] -- an epic is where a story is actually
 * ordered, and the project-level list was a duplicate nothing read: `BoardHandler.board` orders a
 * listing from the story store's own creation order, and [com.superiorsoftllc.agenthq.brief.LeadBriefComposer]
 * deliberately never read it. See CONTRACT.md's "What changed in 1.29.0" for the wire-level note.
 */
@Serializable
data class Project(
    val id: String,
    val name: String,
    val goal: String = "",
    /**
     * Contract 1.36.0 (CP-2): the absolute path of the directory the project lives in, which the
     * daemon's git service is rooted at. Empty = no directory configured (never null, so a client
     * never has to tell "unset" from "blank"); an older daemon omits the key, which a client reads
     * the same way.
     */
    val directory: String = "",
    /**
     * Contract 1.81.0 (BASE-1a): the branch a ticket start in this project cuts its `ticket/` branch from. Empty =
     * none set, and a start falls back to the daemon's `AGENTHQ_BASE_BRANCH`, else `HEAD`. Set and cleared ONLY with
     * `project_base` (never `project_save`), which checks it resolves first. An older daemon omits the key.
     */
    val baseBranch: String = "",
)

/**
 * Contract 1.81.0 (BASE-1a): `project_base`, answered by the whole [Project] (`project` frame).
 *
 * - [branch] set: the project's base branch becomes [branch], refused with the reason unless it resolves to a commit
 *   in the repository ticket starts cut in (and the project's directory, when set, is that same repository).
 * - [clear] true: the setting is removed; starts fall back to `AGENTHQ_BASE_BRANCH`, else `HEAD`.
 * - neither: a read.
 *
 * Tickets already started keep the base commit they recorded; a change affects new cuts only.
 */
@Serializable
data class ProjectBaseRequest(
    val projectId: String,
    val branch: String? = null,
    val clear: Boolean = false,
) {
    companion object {
        fun decode(text: String): ProjectBaseRequest = StrictJson.decode(serializer(), text, "project_base payload")
    }
}

/**
 * `project_save`: create a project (no [id]) or edit one (the [id] of an existing project).
 *
 * Shaped after `ticket_save`: on a create the daemon chooses the id and answers with the whole
 * [Project]; on an edit a null field is left as it is. An unknown [id] is refused, so an edit
 * racing a delete never brings a project back.
 */
@Serializable
data class ProjectSave(
    val id: String? = null,
    val name: String? = null,
    val goal: String? = null,
    /** Contract 1.36.0: a null [directory] leaves it as it is; an empty one clears it. */
    val directory: String? = null,
) {
    companion object {
        fun decode(text: String): ProjectSave = StrictJson.decode(serializer(), text, "project_save payload")
    }
}
