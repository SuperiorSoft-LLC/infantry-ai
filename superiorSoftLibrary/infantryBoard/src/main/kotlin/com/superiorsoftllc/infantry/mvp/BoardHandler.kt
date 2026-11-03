// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.mvp

import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.protocol.Story
import com.superiorsoftllc.infantry.protocol.StoryStatus
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * E1-c (contract 1.62.0): `story_list` is a DEPRECATED SHIM. The daemon feeds [stories] from the ticket board
 * (every ticket through [StoryHandler.Companion.asStory], in board order), so the listing below is the ticket
 * board seen in the old three-column shape. Use `ticket_list` (with `epicId`, contract 1.66.0) instead.
 *
 * MVP Sprint-Zero, slice B leaf B3: the `story_list` WIRE SURFACE -- the board listing, and the ONLY
 * query of the MVP board.
 *
 * The seam (slice 0, corrected by `0822d8f`) froze `story_list` -> `stories`, and CONTRACT.md
 * §2.2.5 froze what the answer means: "grouped by status in the frozen order", the order being
 * [StoryStatus.ALL] -- `todo`, `doing`, `done`, and nothing else. B1 built the rules behind the
 * board and B2 made them survive a restart; this class is the thin layer that answers a client's
 * one question, exactly as `ProjectHandler` answers slice A's three. It decodes a request, applies
 * the frozen order, and encodes the answer -- nothing more, so every rule of the board (what a
 * story is, what a status may be) stays the store's own.
 *
 * ## WHAT A LISTING IS NOT
 *
 * DELIBERATELY MINIMAL, like the seam it sits on. There is no story body, no priority, no estimate,
 * no assignee, no comment, no history and no UI here. **And there is no ordering beyond the frozen
 * rule:** a listing partitions the board by column in [StoryStatus.ALL]'s order, and within a
 * column it returns the rows in the order the store holds them (creation order), because a story
 * carries no place of its own here -- a story's order, when it has an epic, lives on
 * [com.superiorsoftllc.infantry.epics.Epic.storyIds] instead (RETIRED, contract 1.29.0: this used to
 * be `Project.storyIds`, maintained by I0; this handler never read either).
 *
 * ## HOW A LISTING READS THE BOARD
 *
 * [StoryStore] is the WRITE path -- `save` / `get` / `move` -- and the frozen interface carries no
 * listing, so this handler is built over `() -> List<Story>` and never over a store type. That keeps
 * the read at the layer that needs it (here) instead of widening an interface two slices already
 * compile against, and it keeps slice B free of slice A: a listing never asks a project store
 * whether a project id exists.
 *
 * **I0 WIRING, one line, plus the router branch I0 owns:**
 *
 *     // in InfantryDaemon.build, next to the story store:
 *     val board = BoardHandler { storyStore.stories() }
 *     // in handleConnection's `when`, one branch (or the final else's fallthrough):
 *     Envelope.STORY_LIST -> board.answer(envelope)
 *
 * `storyStore.stories()` is a one-line read on the integrated store, which I0 owns as a whole; it
 * answers the rows the store actually holds, so a board written before a restart is listed after
 * one. Nothing else in this leaf needs the router, and nothing in it edits one.
 *
 * A refusal answers the frozen `error` frame naming its own request (`story_list refused: ...`),
 * the standing convention `ticket_get` and the backlog follow.
 */
class BoardHandler(
    /**
     * Every story the daemon holds, read at ANSWER time rather than at construction time, so a
     * listing always answers the board as it is right now -- a story saved after this handler was
     * built is listed by the next `story_list` (see `theBoardIsReadAtAnswerTime…` in
     * [BoardDaemonTest]).
     */
    private val stories: () -> List<Story>,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) {
    /**
     * Answer one frame, or null when the frame is not this handler's.
     *
     * `story_list` carries the project to list in its data: EMPTY data (the form CONTRACT.md
     * §2.2.5 froze) lists every project's board, and a project id lists that one project's. The
     * filter is additive to the frozen form, never a replacement for it, so a client built against
     * the frozen contract keeps working untouched.
     *
     * Returning null for a foreign frame (rather than throwing, or answering an `error`) is what
     * makes the single-line registration below safe, exactly as in `ProjectHandler`:
     *
     *     else -> board?.answer(envelope)
     *         ?: Envelope(Envelope.ERROR, "unknown frame type '${envelope.type}'")
     *
     * Either way the MVP's board query is answered here and EVERY other frame keeps the daemon's
     * own answer untouched -- no story, ticket, session or mail frame can be swallowed by this layer.
     */
    fun answer(envelope: Envelope): Envelope? = when (envelope.type) {
        Envelope.STORY_LIST -> storyRequest(envelope.type) {
            val projectId = envelope.data.trim().takeIf { it.isNotEmpty() }?.let { checkProjectId(it) }
            storiesEnvelope(board(projectId))
        }

        else -> null
    }

    /**
     * The listing rule, and the whole of it: keep the frozen vocabulary, keep one project when one
     * is asked for, then partition by column in [StoryStatus.ALL]'s order.
     *
     * A row whose status the frozen vocabulary does not spell is dropped rather than listed: the
     * store already refuses one, so this is the listing's own half of the guarantee the frozen
     * `anUnknownStatusIsRefusedNotSilentlyNormalised` makes on the `story` frames -- a board
     * listing can never be what puts an unknown column on the wire.
     *
     * Within a column the order is the store's own (creation order), because a story carries no
     * place of its own: see the class documentation.
     */
    fun board(projectId: String? = null): List<Story> {
        val rows = stories().filter { it.status in StoryStatus.ALL }
        val wanted = if (projectId == null) rows else rows.filter { it.projectId == projectId }
        return StoryStatus.ALL.flatMap { column -> wanted.filter { it.status == column } }
    }

    /** The board as a `stories` frame, every field present on every entry. */
    fun storiesEnvelope(stories: List<Story>): Envelope =
        Envelope(Envelope.STORIES, json.encodeToString(ListSerializer(Story.serializer()), stories))

    /**
     * Run one board request for frame [frame]: a refusal ([IllegalArgumentException], a filter that
     * is not a usable project id included) answers `<frame> refused: <why>`; anything else
     * unexpected answers `<frame> failed: <what>` rather than reaching the client as an exception.
     */
    private inline fun storyRequest(frame: String, block: () -> Envelope): Envelope = try {
        block()
    } catch (e: IllegalArgumentException) {
        Envelope(Envelope.ERROR, "$frame refused: ${e.message ?: "invalid request"}")
    } catch (e: Exception) {
        Envelope(Envelope.ERROR, "$frame failed: ${e.message ?: e::class.simpleName}")
    }

    companion object {
        const val MAX_PROJECT_ID_LENGTH = 200

        /**
         * A project filter this handler can act on, or a refusal naming what was wrong with it.
         *
         * A filter that is blank is NOT a refusal -- it is the frozen "list every project's board"
         * form, handled by [answer] before it reaches here. An id that is too long, or that carries
         * a control character, IS refused: those are client bugs, and answering them with "no
         * stories" would be indistinguishable from a genuinely empty board.
         */
        fun checkProjectId(raw: String): String {
            val projectId = raw.trim()
            require(projectId.isNotEmpty()) { "expects a project id, or empty data to list every project's board" }
            require(projectId.length <= MAX_PROJECT_ID_LENGTH) {
                "project id is longer than $MAX_PROJECT_ID_LENGTH characters"
            }
            require(projectId.none { it.isISOControl() }) { "project id contains a control character" }
            return projectId
        }
    }
}
