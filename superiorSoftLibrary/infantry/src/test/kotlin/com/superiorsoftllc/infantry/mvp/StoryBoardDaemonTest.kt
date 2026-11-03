package com.superiorsoftllc.infantry.mvp

import com.superiorsoftllc.infantry.daemon.DaemonFixture
import com.superiorsoftllc.infantry.daemon.WsTestClient
import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.protocol.Story
import com.superiorsoftllc.infantry.protocol.StoryStatus
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * MVP Sprint-Zero, slice B (the STORY + STATUS board) -- RED by design.
 *
 * SLICE 0 of ticket b2fbb94d froze the seam: the `Story` / `StorySave` / `StoryMove` types, the
 * `StoryStatus` vocabulary (`todo`, `doing`, `done`, in that frozen order), and the `story_save` /
 * `story_get` / `story_move` / `story_list` frames. This test suite is what the implementing slice
 * (branch `feature-agenthq-mvp-board`) must turn GREEN.
 *
 * Every test here touches ONLY the story half of the MVP. The project id is carried as a plain
 * string: no test needs slice A's `project_save` to work, so slice B can be built without the
 * project object existing. The two halves meet at integration.
 *
 * Each test drives the REAL daemon over a REAL WebSocket. While the behaviour is missing the
 * daemon answers `error: unknown frame type 'story_save'` and every assertion below fails naming
 * that gap.
 */
class StoryBoardDaemonTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** RED waits are short on purpose: the missing behaviour is "no answer frame at all", not a slow one. */
    private val timeoutMs = 4_000L

    private fun withDaemon(block: (DaemonFixture) -> Unit) {
        val fixture = DaemonFixture.launch()
        try {
            fixture.client.await(Envelope.READY)
            block(fixture)
        } finally {
            fixture.stop()
            fixture.deleteState()
        }
    }

    /** The first `story` frame from [client] whose decoded story satisfies [match]. */
    private fun WsTestClient.awaitStory(description: String, match: (Story) -> Boolean): Story {
        val frame = awaitWhere(description, timeoutMs) { envelope ->
            envelope.type == Envelope.STORY && runCatching {
                json.decodeFromString(Story.serializer(), envelope.data)
            }.getOrNull()?.let(match) == true
        }
        return json.decodeFromString(Story.serializer(), frame.data)
    }

    /** The first `stories` frame from [client] whose decoded board satisfies [match]. */
    private fun WsTestClient.awaitStories(description: String, match: (List<Story>) -> Boolean): List<Story> {
        val frame = awaitWhere(description, timeoutMs) { envelope ->
            envelope.type == Envelope.STORIES && runCatching {
                json.decodeFromString(ListSerializer(Story.serializer()), envelope.data)
            }.getOrNull()?.let(match) == true
        }
        return json.decodeFromString(ListSerializer(Story.serializer()), frame.data)
    }

    /**
     * A story created with `story_save` comes back whole: the project it belongs to, the title, a
     * fresh `todo` place, and the pinned wire keys. The shape is deliberately minimal -- there is
     * no description, no position and no timestamp: order lives on the project, not the story.
     */
    @Test
    fun aStoryIsCreatedInAProject() = withDaemon { fixture ->
        fixture.send(Envelope.STORY_SAVE, """{"projectId":"project-sprint-zero","title":"Run one story three ways"}""")
        val created = fixture.client.awaitStory("story_save answered with a story") { it.title == "Run one story three ways" }

        assertTrue(created.id.isNotBlank(), "the daemon chose the id: '${created.id}'")
        assertEquals("project-sprint-zero", created.projectId)
        assertEquals(StoryStatus.TODO, created.status, "a new story starts in the first column")
        assertEquals(
            setOf("id", "projectId", "title", "status"),
            fixture.client.of(Envelope.STORY).first().let { json.parseToJsonElement(it.data).jsonObject.keys },
        )

        // story_get answers with the very same story (occurrence 1: frame 0 is the save's answer).
        fixture.send(Envelope.STORY_GET, created.id)
        val fetched = json.decodeFromString(
            Story.serializer(),
            fixture.client.await(Envelope.STORY, occurrence = 1, timeoutMs = timeoutMs).data,
        )
        assertEquals(created, fetched)
    }

    /**
     * A story's status moves through the frozen vocabulary and nothing else: `todo` -> `doing` ->
     * `done`, each move answered with the story in its new column. A move is a status change only:
     * a story carries no position of its own.
     */
    @Test
    fun storyStatusMovesThroughTheFrozenVocabulary() = withDaemon { fixture ->
        fixture.send(Envelope.STORY_SAVE, """{"projectId":"project-sprint-zero","title":"Ledger the tokens"}""")
        val created = fixture.client.awaitStory("story_save answered with a story") { it.title == "Ledger the tokens" }
        assertEquals(StoryStatus.TODO, created.status)

        fixture.send(Envelope.STORY_MOVE, """{"id":"${created.id}","status":"doing"}""")
        val doing = fixture.client.awaitStory("story_move answered with the story in doing") {
            it.id == created.id && it.status == StoryStatus.DOING
        }
        assertEquals(StoryStatus.DOING, doing.status)

        fixture.send(Envelope.STORY_MOVE, """{"id":"${created.id}","status":"done"}""")
        val done = fixture.client.awaitStory("story_move answered with the story in done") {
            it.id == created.id && it.status == StoryStatus.DONE
        }
        assertEquals(StoryStatus.DONE, done.status)
    }

    /**
     * A status outside the frozen vocabulary is REFUSED, never silently normalised to the nearest
     * column: the answer is an `error` naming the move, the story keeps its column, and no frame
     * ever carries a status the vocabulary does not spell.
     */
    @Test
    fun anUnknownStatusIsRefusedNotSilentlyNormalised() = withDaemon { fixture ->
        fixture.send(Envelope.STORY_SAVE, """{"projectId":"project-sprint-zero","title":"Refuse the unknown"}""")
        val created = fixture.client.awaitStory("story_save answered with a story") { it.title == "Refuse the unknown" }

        fixture.send(Envelope.STORY_MOVE, """{"id":"${created.id}","status":"wontdo"}""")
        fixture.client.awaitWhere("story_move refusal", timeoutMs) { frame ->
            frame.type == Envelope.ERROR && frame.data.contains("story_move refused")
        }

        // The story is exactly where it was (occurrence 1: frame 0 is the save's answer; a refused
        // move pushes nothing).
        fixture.send(Envelope.STORY_GET, created.id)
        val after = json.decodeFromString(
            Story.serializer(),
            fixture.client.await(Envelope.STORY, occurrence = 1, timeoutMs = timeoutMs).data,
        )
        assertEquals(StoryStatus.TODO, after.status, "a refused move never changed the column")

        // And nothing that arrived on the wire carried a status outside the frozen vocabulary.
        val offVocabulary = fixture.client.frames
            .filter { it.type == Envelope.STORY }
            .mapNotNull { envelope -> runCatching { json.decodeFromString(Story.serializer(), envelope.data) }.getOrNull() }
            .filter { it.status !in StoryStatus.ALL }
        assertTrue(offVocabulary.isEmpty(), "no story ever carried '${StoryStatus.ALL}' outside it: $offVocabulary")
    }

    /**
     * The board listing groups by status in the frozen order: every `todo` story, then every
     * `doing` one, then every `done` one. Order WITHIN a column is not the story's own -- it comes
     * from the project's ordered `storyIds` -- so this asserts the column grouping and that the
     * listing is a partition of the board, and never that a story carries its own place.
     */
    @Test
    fun theBoardListingGroupsByStatusInFrozenOrder() = withDaemon { fixture ->
        fixture.send(Envelope.STORY_SAVE, """{"projectId":"project-sprint-zero","title":"One"}""")
        val one = fixture.client.awaitStory("story_save answered with 'One'") { it.title == "One" }
        fixture.send(Envelope.STORY_SAVE, """{"projectId":"project-sprint-zero","title":"Two"}""")
        val two = fixture.client.awaitStory("story_save answered with 'Two'") { it.title == "Two" }
        fixture.send(Envelope.STORY_SAVE, """{"projectId":"project-sprint-zero","title":"Three"}""")
        val three = fixture.client.awaitStory("story_save answered with 'Three'") { it.title == "Three" }

        fixture.send(Envelope.STORY_MOVE, """{"id":"${three.id}","status":"doing"}""")
        fixture.client.awaitStory("story_move answered with 'Three' in doing") {
            it.id == three.id && it.status == StoryStatus.DOING
        }
        fixture.send(Envelope.STORY_MOVE, """{"id":"${one.id}","status":"done"}""")
        fixture.client.awaitStory("story_move answered with 'One' in done") {
            it.id == one.id && it.status == StoryStatus.DONE
        }

        fixture.send(Envelope.STORY_LIST, "")
        val board = fixture.client.awaitStories("story_list answered with the whole board") { stories ->
            stories.map { it.id }.toSet() == setOf(one.id, two.id, three.id)
        }

        // The listing is a partition of the board: every story exactly once, no repeats, no extras.
        assertEquals(
            listOf(one.id, two.id, three.id).sorted(),
            board.map { it.id }.sorted(),
            "the board lists every story exactly once",
        )

        // todo, then doing, then done: the frozen column order, not creation order.
        val rank = StoryStatus.ALL.withIndex().associate { it.value to it.index }
        board.map { rank.getValue(it.status) }.zipWithNext().forEach { (left, right) ->
            assertTrue(left <= right, "columns never interleave: ${board.map { it.status }}")
        }

        // Every column holds exactly the story that was moved into it.
        assertEquals(
            mapOf(
                StoryStatus.TODO to listOf(two.id),
                StoryStatus.DOING to listOf(three.id),
                StoryStatus.DONE to listOf(one.id),
            ),
            board.groupBy({ it.status }, { it.id }),
        )
    }
}
