package com.superiorsoftllc.agenthq.mvp

import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.protocol.Story
import com.superiorsoftllc.agenthq.protocol.StoryMove
import com.superiorsoftllc.agenthq.protocol.StorySave
import com.superiorsoftllc.agenthq.protocol.StoryStatus
import com.superiorsoftllc.agenthq.stories.InMemoryStoryStore
import com.superiorsoftllc.agenthq.stories.JsonStoryStore
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * MVP Sprint-Zero, slice B leaf B3 -- the BOARD LISTING over the wire: `story_list` -> `stories`.
 *
 * The seam (slice 0, corrected by `0822d8f`) froze `story_list` as the one query of the board:
 * CONTRACT.md §2.2.5 spells it "`story_list` (empty data) with `stories`, grouped by status in the
 * frozen order", and the frozen order is [StoryStatus.ALL] -- `todo`, `doing`, `done`, and nothing
 * else. This suite is what that ONE query does.
 *
 * DELIBERATELY MINIMAL, like the seam it sits on. There is no story body, no priority, no assignee,
 * no comment, no history and no UI here: a listing answers "which stories are in which column", and
 * the order inside a column is NOT this leaf's to invent (see [Story] and [BoardHandler]).
 *
 * ## WHAT THIS SUITE DOES NOT PROVE, AND WHO OWNS IT
 *
 * Every frame here goes through [BoardHandler.answer] exactly as the daemon's router will call it --
 * the same [Envelope], the same strict/lax JSON, the same refusal convention -- but the router line
 * that dispatches `story_list` to the handler is NOT in this slice. `AgentHqDaemon`'s
 * `handleConnection` ends in `else -> Envelope(Envelope.ERROR, "unknown frame type '...'")`
 * (`daemon/DaemonMain.kt`), and registering the branch there is integration I0's, which is why the
 * frozen `StoryBoardDaemonTest` stays RED with `unknown frame type 'story_save'` and why
 * `story_list` answers that same refusal today. **The missing-route receipt for this leaf is that
 * refusal, and the exact recipe is [BoardHandler]'s documentation.** Nothing here edits the shared
 * router, `CONTRACT.md`, `EventVocabulary.VERSION` or another slice's file.
 *
 * The project id is carried as a plain string, exactly as slice B's frozen daemon tests do it: no
 * test needs slice A's `project_save` to work, so the board listing is built without the project
 * object existing. The two halves meet at integration.
 */
class BoardDaemonTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Both projects of every test, so a listing that mixes them up cannot pass by accident. */
    private val alpha = "project-alpha"
    private val beta = "project-beta"

    /**
     * The read side slice B2's store did not need.
     *
     * [InMemoryStoryStore] holds its rows in a `protected` map and the frozen [StoryStore] interface
     * offers `save` / `get` / `move` and no listing, so a subclass is the only way to hand real rows
     * to a listing without adding a method to an interface two slices already compile against.
     * THIS IS A TEST DOUBLE, NOT A SECOND STORE: every rule of the board still runs in the parent,
     * and only the read is added.
     */
    private class BoardRows(
        idFactory: () -> String = { "story-" + UUID.randomUUID().toString().take(8) },
    ) : InMemoryStoryStore(idFactory) {
        fun rows(): List<Story> = stories.values.toList()
    }

    /** A real store holding [titles] in one project, each story landing in `todo`. */
    private fun store(vararg titles: String, projectId: String = alpha): BoardRows =
        BoardRows().also { store -> titles.forEach { store.save(StorySave(projectId = projectId, title = it)) } }

    /** A handler over the rows a real store actually wrote. */
    private fun handler(rows: () -> List<Story>) = BoardHandler(rows)

    /**
     * The answer for a frame this handler DOES own, unwrapped. A null here would mean the handler
     * declined its own frame, which is a test failure and not something to paper over with `?.`.
     */
    private fun BoardHandler.answered(envelope: Envelope): Envelope =
        answer(envelope) ?: error("the board handler declined '${envelope.type}', which it owns")

    /** The `stories` answer, decoded. */
    private fun BoardHandler.board(projectId: String? = null): List<Story> =
        json.decodeFromString(ListSerializer(Story.serializer()), answered(envelope(projectId)).data)

    private fun envelope(projectId: String? = null) =
        Envelope(Envelope.STORY_LIST, projectId ?: "")

    // ---- the listing rule -------------------------------------------------------------

    /**
     * The whole board, grouped by status in the frozen order: every `todo` story, then every
     * `doing` one, then every `done` one. Creation order is NOT the listing order, and the listing
     * never interleaves two columns.
     */
    @Test
    fun theWholeBoardListsInTheFrozenColumnOrder() {
        val rows = store("One", "Two", "Three")
        rows.move(StoryMove(rows.rows()[2].id, StoryStatus.DOING))
        rows.move(StoryMove(rows.rows()[0].id, StoryStatus.DONE))
        val handler = handler { rows.rows() }

        val board = handler.board()

        assertEquals(
            mapOf(
                StoryStatus.TODO to listOf("Two"),
                StoryStatus.DOING to listOf("Three"),
                StoryStatus.DONE to listOf("One"),
            ),
            board.groupBy({ it.status }, { it.title }),
        )

        val rank = StoryStatus.ALL.withIndex().associate { it.value to it.index }
        board.map { rank.getValue(it.status) }.zipWithNext().forEach { (left, right) ->
            assertTrue(left <= right, "columns never interleave: ${board.map { it.status }}")
        }
    }

    /**
     * The frozen form: `story_list` with EMPTY data lists every project's board, which is what
     * CONTRACT.md §2.2.5 froze. A filter is the additive form on top of it, never a replacement.
     */
    @Test
    fun theFrozenEmptyDataFormListsEveryProject() {
        val rows = store("Alpha one", projectId = alpha)
            .also { it.save(StorySave(projectId = beta, title = "Beta one")) }
        val handler = handler { rows.rows() }

        val board = handler.board(null)

        assertEquals(setOf(alpha, beta), board.map { it.projectId }.toSet())
        assertEquals(2, board.size, "every project's story is listed exactly once")
    }

    /**
     * Listing BY PROJECT: only that project's stories come back, still grouped by the frozen
     * column order. The other project's board is a different board and never leaks in.
     */
    @Test
    fun aProjectsBoardListsOnlyItsOwnStories() {
        val rows = store("Alpha one", "Alpha two", projectId = alpha)
        rows.save(StorySave(projectId = beta, title = "Beta one"))
        rows.save(StorySave(projectId = beta, title = "Beta two"))
        rows.move(StoryMove(rows.rows().first { it.projectId == beta }.id, StoryStatus.DOING))
        rows.move(StoryMove(rows.rows().first { it.projectId == alpha }.id, StoryStatus.DONE))
        val handler = handler { rows.rows() }

        val board = handler.board(beta)

        // todo first, then doing: the frozen order, NOT the order the stories were created in
        // (Beta one was created before Beta two but sits in the later column).
        assertEquals(
            listOf(StoryStatus.TODO to "Beta two", StoryStatus.DOING to "Beta one"),
            board.map { it.status to it.title },
        )
        assertTrue(board.none { it.projectId != beta }, "another project's story never leaks into a filtered board")
    }

    /** A project with no stories lists an empty board. It is a read, not a lookup: there is no
     * project store here to refuse an unknown id against, and slice B must not depend on slice A. */
    @Test
    fun aProjectWithNoStoriesListsAnEmptyBoardRatherThanARefusal() {
        val handler = handler { store("One").rows() }

        val answer = handler.answered(envelope("project-no-stories"))

        assertEquals(Envelope.STORIES, answer.type)
        assertEquals(emptyList(), json.decodeFromString(ListSerializer(Story.serializer()), answer.data))
    }

    /** An empty board is an empty listing, never an error and never a missing frame. */
    @Test
    fun anEmptyBoardListsAnEmptyBoard() {
        val handler = handler { emptyList() }

        val answer = handler.answered(envelope())

        assertEquals(Envelope.STORIES, answer.type)
        assertEquals(
            emptyList<Story>(),
            json.decodeFromString(ListSerializer(Story.serializer()), answer.data),
        )
    }

    /**
     * A row the frozen vocabulary does not spell never reaches the wire. The store already refuses
     * one, so this is the listing's own half of the guarantee the frozen
     * `anUnknownStatusIsRefusedNotSilentlyNormalised` makes on the `story` frames: a board listing
     * can never be what puts an unknown column on the wire.
     */
    @Test
    fun aStoryOutsideTheFrozenVocabularyNeverReachesTheWire() {
        val good = store("One", "Two")
        val rows = good.rows() + Story(id = "story-bogus", projectId = alpha, title = "Bogus", status = "wontdo")
        val handler = handler { rows }

        val board = handler.board()

        assertEquals(listOf("One", "Two"), board.map { it.title })
        assertTrue(board.all { it.status in StoryStatus.ALL }, "no listed story carries an unknown column")
    }

    // ---- the wire, exactly as a client sees it ----------------------------------------

    /** The pinned wire keys, per entry: the seam's four and nothing else. */
    @Test
    fun theListingCarriesThePinnedWireKeys() {
        val handler = handler { store("One", "Two").rows() }

        val listed = handler.answered(envelope())

        assertEquals(Envelope.STORIES, listed.type)
        val parsed = json.parseToJsonElement(listed.data).jsonArray
        assertEquals(2, parsed.size, "both stories of the board are listed")
        assertEquals(
            setOf(setOf("id", "projectId", "title", "status")),
            parsed.map { entry -> entry.jsonObject.keys }.toSet(),
            "every listed story carries exactly the four pinned keys",
        )
    }

    /** A real encode/decode round-trip: what the handler encodes, a client decodes. */
    @Test
    fun theListingSurvivesARealEnvelopeRoundTrip() {
        val rows = store("One")
        rows.move(StoryMove(rows.rows()[0].id, StoryStatus.DONE))
        val handler = handler { rows.rows() }

        val wire = json.encodeToString(Envelope.serializer(), handler.answered(envelope()))

        assertEquals(Envelope.STORIES, json.decodeFromString(Envelope.serializer(), wire).type)
        assertEquals(
            listOf("One"),
            json.decodeFromString(
                ListSerializer(Story.serializer()),
                json.decodeFromString(Envelope.serializer(), wire).data,
            ).map { it.title },
        )
    }

    // ---- refusals and foreign frames ---------------------------------------------------

    /**
     * A filter that is not a usable project id is REFUSED, never silently ignored: an id full of
     * control characters is a client bug, and answering it with "no stories" would look exactly
     * like an empty board.
     */
    @Test
    fun aProjectFilterThatIsNotAUsableIdIsRefusedNotIgnored() {
        val handler = handler { store("One").rows() }

        val refused = handler.answered(envelope("project\u0000bogus"))

        assertEquals(Envelope.ERROR, refused.type)
        assertTrue(
            refused.data.startsWith("${Envelope.STORY_LIST} refused:"),
            "expected the standing refusal convention, got '${refused.data}'",
        )

        val tooLong = handler.answered(envelope("p".repeat(BoardHandler.MAX_PROJECT_ID_LENGTH + 1)))
        assertEquals(Envelope.ERROR, tooLong.type)
        assertTrue(tooLong.data.startsWith("${Envelope.STORY_LIST} refused:"))
    }

    /**
     * Foreign frames are NOT this handler's. Answering null (rather than an `error`, and rather
     * than throwing) is what lets I0 register one line and keeps the daemon's own
     * `unknown frame type` answer for every frame it does not know.
     */
    @Test
    fun aForeignFrameIsNotAnsweredByTheBoardHandler() {
        val handler = handler { store("One").rows() }

        listOf(
            Envelope.STORY_SAVE,
            Envelope.STORY_GET,
            Envelope.STORY_MOVE,
            Envelope.STORY,
            Envelope.STORIES,
            Envelope.PROMPT,
            Envelope.TICKET_LIST,
            Envelope.READY,
        ).forEach { type ->
            assertNull(handler.answer(Envelope(type, """{"title":"nope"}""")), "'$type' is not the board listing")
        }
    }

    // ---- the board a restart reads back ------------------------------------------------

    /**
     * A board a restart reads back lists in the frozen order.
     *
     * B2 made stories survive a restart behind `JsonStoryStore`; this is the listing's half of that
     * promise, proven against the file the durable store ACTUALLY wrote -- read back the way a
     * second daemon reads it, never out of a mirror the test keeps itself.
     */
    @Test
    fun aBoardRestoredFromDiskListsInTheFrozenOrder() {
        val dir = Files.createTempDirectory("agenthq-board-listing").toFile()
        try {
            val file = File(dir, "stories.json")
            val durable = JsonStoryStore(file)
            val one = durable.save(StorySave(projectId = alpha, title = "One"))
            durable.save(StorySave(projectId = alpha, title = "Two"))
            durable.save(StorySave(projectId = alpha, title = "Three"))
            durable.move(StoryMove(one.id, StoryStatus.DONE))

            val restored = json.parseToJsonElement(file.readText()).jsonObject["stories"]!!
            val handler = handler { json.decodeFromJsonElement(ListSerializer(Story.serializer()), restored) }

            val board = handler.board()

            assertEquals(
                mapOf(
                    StoryStatus.TODO to listOf("Two", "Three"),
                    StoryStatus.DONE to listOf("One"),
                ),
                board.groupBy({ it.status }, { it.title }),
            )
        } finally {
            dir.deleteRecursively()
        }
    }
}
