package com.superiorsoftllc.infantry.protocol

import kotlinx.serialization.Serializable

/*
 * MVP Sprint-Zero (slice 0 freeze): the STORY + STATUS wire types -- the minimal scrum board.
 *
 * IMPLEMENTED BY: MVP slice B (branch `feature-agenthq-mvp-board`, worktree ticket d273f528).
 * This file is the frozen SEAM only: it names the types and carries NO behaviour. Slice B builds
 * that half behind these names; slice A builds its half without talking to B. Neither may rename
 * or reshape a type here: the RED tests of slice 0 pin them.
 *
 * Plain Kotlin and kotlinx.serialization only, so a non-JVM client can share the types.
 */

/**
 * The story board's columns, as the wire spells them.
 *
 * MINIMAL ON PURPOSE: Sprint-Zero wants a board, not a workflow engine. Three columns are the
 * whole vocabulary -- a story is either not started (`todo`), being worked (`doing`) or finished
 * (`done`). This is deliberately NOT [TicketStatus]: the daemon's own backlog columns are a
 * different board with a different life, and an MVP story never moves itself between them.
 */
object StoryStatus {
    /** Not started. A new story lands here, at the bottom. */
    const val TODO = "todo"

    /** Being worked. */
    const val DOING = "doing"

    /** Finished. */
    const val DONE = "done"

    /** Frozen board order: a board listing goes column by column in this order, each column top first. */
    val ALL: List<String> = listOf(TODO, DOING, DONE)
}

/**
 * One story: a unit of work inside one project, with a status on the board.
 *
 * [projectId] is the id of the [Project] the story belongs to -- the join key of the MVP, named
 * consistently with [Ticket.projectId]. [status] is one of [StoryStatus.ALL].
 *
 * DELIBERATELY MINIMAL: Sprint-Zero pins `{ id, projectId, title, status }` and nothing else.
 * There is no `description`, no `position`, and no `createdAt` / `updatedAt` -- a story is a
 * lightweight work item, not the daemon's own backlog ticket. Order inside a column, when a story
 * has one, is carried by [com.superiorsoftllc.infantry.epics.Epic.storyIds] (RETIRED, contract
 * 1.29.0: this used to be `Project.storyIds`, maintained by I0); a story never carries its own
 * place, so a move is a status change and nothing more.
 */
@Serializable
data class Story(
    val id: String,
    val projectId: String,
    val title: String,
    val status: String,
)

/**
 * `story_save`: create a story (no [id]) or edit one (the [id] of an existing story).
 *
 * A create needs a [title] and the [projectId] it belongs to, and lands the story in
 * [StoryStatus.TODO]. An edit leaves a null field as it is; an unknown [id] is refused, so an
 * edit racing a delete never brings the story back.
 *
 * [epicId] (contract 1.22.0) is an OPTIONAL epic the new story also joins. It is read on a
 * CREATE and ignored on an edit: the story's project is the join key it is created under, and
 * an epic is the second home it hangs off. The epic's ordered list is written at integration,
 * exactly as the project's is; an epic id that names no epic is a no-op, never a refusal.
 */
@Serializable
data class StorySave(
    val id: String? = null,
    val projectId: String? = null,
    val title: String? = null,
    val epicId: String? = null,
) {
    companion object {
        fun decode(text: String): StorySave = StrictJson.decode(serializer(), text, "story_save payload")
    }
}

/**
 * `story_move`: put story [id] in column [status] (null = stay in its column).
 *
 * [status] must be one of [StoryStatus.ALL]: an unknown status is REFUSED, never silently
 * normalised to the nearest column. There is no `position`: a story's place inside its column
 * comes from [com.superiorsoftllc.infantry.epics.Epic.storyIds], not from the story itself.
 */
@Serializable
data class StoryMove(
    val id: String,
    val status: String? = null,
) {
    companion object {
        fun decode(text: String): StoryMove = StrictJson.decode(serializer(), text, "story_move payload")
    }
}
