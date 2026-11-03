package com.superiorsoftllc.infantry.mvp

import com.superiorsoftllc.infantry.daemon.DaemonFixture
import com.superiorsoftllc.infantry.daemon.WsTestClient
import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.protocol.Project
import com.superiorsoftllc.infantry.protocol.Story
import com.superiorsoftllc.infantry.protocol.StoryStatus
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * MVP Sprint-Zero, I0 -- the INTEGRATION lifecycle, RED until I0 lands it.
 *
 * Slice A built the project half (`project_save` / `project_get` / `project_list`) and slice B built
 * the board half (`story_save` / `story_get` / `story_move` / `story_list` over its own stores).
 * Each half was proven against a daemon that knew nothing about the other, which is what kept them
 * independently buildable. This suite is the JOIN: the two halves sharing one daemon, one project
 * holding its stories in order, and that whole state surviving a restart.
 *
 * WHAT I0 OWNS HERE, and what this suite therefore pins:
 *
 *  1. `story_*` / `story_list` are ANSWERED -- the router branch slice B could not register itself,
 *     so until it exists the daemon answers `error: unknown frame type 'story_save'`.
 *  2. Creating a story writes nothing onto its project (contract 1.29.0 retired `Project.storyIds`;
 *     a story's order lives on `Epic.storyIds`). A move is a status change and nothing more.
 *  3. The board a restart reads back is the board that was written -- the project and the stories'
 *     columns both come back, because both stores persist.
 *
 * Every test drives the REAL daemon over a REAL WebSocket, the way `ProjectDaemonTest` and
 * `StoryBoardDaemonTest` do: nothing is faked but the provider stub. The project id travels as a
 * plain string in a `story_save` payload, so a story can be created without any project existing --
 * exactly as slice B's own suite does -- and the integration tests here create the project first.
 */
class MvpLifecycleDaemonTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** RED waits are short on purpose: the missing behaviour is "no answer frame at all", not a slow one. */
    private val timeoutMs = 5_000L

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

    /** The first `project` frame from [client] whose decoded project satisfies [match]. */
    private fun WsTestClient.awaitProject(description: String, match: (Project) -> Boolean): Project {
        val frame = awaitWhere(description, timeoutMs) { envelope ->
            envelope.type == Envelope.PROJECT && runCatching {
                json.decodeFromString(Project.serializer(), envelope.data)
            }.getOrNull()?.let(match) == true
        }
        return json.decodeFromString(Project.serializer(), frame.data)
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

    /** Create one project and answer with it. */
    private fun WsTestClient.createProject(name: String, goal: String? = null): Project {
        val payload = if (goal == null) """{"name":"$name"}""" else """{"name":"$name","goal":"$goal"}"""
        send(Envelope.PROJECT_SAVE, payload)
        return awaitProject("project_save answered with '$name'") { it.name == name }
    }

    /** Create one story inside [projectId] and answer with it. */
    private fun WsTestClient.createStory(projectId: String, title: String): Story {
        send(Envelope.STORY_SAVE, """{"projectId":"$projectId","title":"$title"}""")
        return awaitStory("story_save answered with '$title'") { it.title == title }
    }

    /**
     * Contract 1.29.0: creating stories no longer writes anything onto their project -- the
     * project-level `storyIds` is retired (a story's order lives on `Epic.storyIds`), so the
     * `project` frame read back after stories exist carries no `storyIds` key at all.
     */
    @Test
    fun creatingStoriesLeavesTheProjectFrameWithoutStoryIds() = withDaemon { fixture ->
        val client = fixture.client
        val project = client.createProject("Sprint-Zero MVP", "one story, three ways")
        client.createStory(project.id, "Run one story three ways")
        client.createStory(project.id, "Run it a second way")

        client.send(Envelope.PROJECT_GET, project.id)
        val frame = client.awaitWhere("project_get answered after the creates", timeoutMs) {
            it.type == Envelope.PROJECT && it.data.contains(project.id)
        }
        assertTrue(!frame.data.contains("storyIds"), "the project frame no longer carries storyIds: ${frame.data}")
        assertEquals(project, json.decodeFromString(Project.serializer(), frame.data), "a story create left the project as it was")
    }

    /**
     * A MOVE is a status change and nothing more: the story that moved is in its new column and the
     * other is exactly where it was -- a story carries no place of its own.
     */
    @Test
    fun movingOneStoryMovesOnlyThatStory() = withDaemon { fixture ->
        val client = fixture.client
        val project = client.createProject("Sprint-Zero MVP")
        val first = client.createStory(project.id, "Stays in todo")
        val second = client.createStory(project.id, "Moves to doing")

        client.send(Envelope.STORY_MOVE, """{"id":"${second.id}","status":"${StoryStatus.DOING}"}""")
        client.awaitStory("story_move answered with the story in doing") {
            it.id == second.id && it.status == StoryStatus.DOING
        }

        client.send(Envelope.STORY_GET, first.id)
        val untouched = client.awaitStory("story_get answered the unmoved story") { it.id == first.id }
        assertEquals(StoryStatus.TODO, untouched.status, "a move never touched the other story")
    }

    /**
     * `story_list` answers by project and groups by the frozen column order, over BOTH stores:
     * every project's board when no filter is given, one project's board when its id is given, and
     * an empty board for a project that holds no stories -- a read, never a lookup.
     */
    @Test
    fun storyListAnswersByProjectAndGroupsByStatus() = withDaemon { fixture ->
        val client = fixture.client
        val alpha = client.createProject("Alpha")
        val beta = client.createProject("Beta")
        val alphaOne = client.createStory(alpha.id, "Alpha one")
        val alphaTwo = client.createStory(alpha.id, "Alpha two")
        val betaOne = client.createStory(beta.id, "Beta one")

        client.send(Envelope.STORY_MOVE, """{"id":"${alphaTwo.id}","status":"${StoryStatus.DONE}"}""")
        client.awaitStory("story_move answered with 'Alpha two' in done") {
            it.id == alphaTwo.id && it.status == StoryStatus.DONE
        }

        // No filter: every project's board, partitioned by column in the frozen order.
        client.send(Envelope.STORY_LIST, "")
        val whole = client.awaitStories("story_list answered with every project's board") { stories ->
            stories.map { it.id }.toSet() == setOf(alphaOne.id, alphaTwo.id, betaOne.id)
        }
        val rank = StoryStatus.ALL.withIndex().associate { it.value to it.index }
        whole.map { rank.getValue(it.status) }.zipWithNext().forEach { (left, right) ->
            assertTrue(left <= right, "columns never interleave: ${whole.map { it.status }}")
        }

        // One project's id is the additive filter: only that project's stories, still grouped.
        client.send(Envelope.STORY_LIST, alpha.id)
        val alphas = client.awaitStories("story_list answered Alpha's board") { stories ->
            stories.map { it.id }.toSet() == setOf(alphaOne.id, alphaTwo.id)
        }
        assertEquals(listOf(alphaOne.id, alphaTwo.id), alphas.map { it.id }, "Alpha's board, in column order")
        assertEquals(listOf(StoryStatus.TODO, StoryStatus.DONE), alphas.map { it.status })

        client.send(Envelope.STORY_LIST, beta.id)
        val betas = client.awaitStories("story_list answered Beta's board") { stories ->
            stories.size == 1 && stories.first().id == betaOne.id
        }
        assertEquals(listOf(betaOne.id), betas.map { it.id })

        // A project that holds no stories lists an empty board -- a read, not a lookup, so an
        // unknown-but-well-formed id is NOT a refusal.
        client.send(Envelope.STORY_LIST, "project-nobody-holds")
        val none = client.awaitStories("story_list answered an empty board") { it.isEmpty() }
        assertTrue(none.isEmpty(), "an empty board, not a refusal: $none")
    }

    /**
     * The whole state survives a restart: the project's ordered join AND the stories' columns are
     * read back by a SECOND daemon booted over the same state, because both stores persist.
     *
     * This is what makes the MVP usable at all -- a daemon restarted mid-sprint must come back with
     * the board as it was left, not with an empty one.
     */
    @Test
    fun aRestartReadsBackTheSameBoardAndStoryIds() {
        val state = Files.createTempDirectory("infantry-mvp-lifecycle-").toFile()
        val dbPath = File(state, "infantry.db").absolutePath
        val first = DaemonFixture.launch(stateDir = state, storePath = dbPath)
        var projectId: String? = null
        var firstStoryId: String? = null
        var secondStoryId: String? = null
        try {
            first.client.await(Envelope.READY)
            val project = first.client.createProject("Sprint-Zero MVP", "one story, three ways")
            val one = first.client.createStory(project.id, "Run one story three ways")
            val two = first.client.createStory(project.id, "Run it a second way")
            projectId = project.id
            firstStoryId = one.id
            secondStoryId = two.id
            first.client.send(Envelope.STORY_MOVE, """{"id":"${two.id}","status":"${StoryStatus.DOING}"}""")
            first.client.awaitStory("story_move answered before the restart") {
                it.id == two.id && it.status == StoryStatus.DOING
            }
            first.stop()

            val second = DaemonFixture.launch(stateDir = state, storePath = dbPath)
            try {
                second.client.await(Envelope.READY)

                // The project came back.
                second.client.send(Envelope.PROJECT_GET, projectId!!)
                second.client.awaitProject("project_get after the restart") { it.id == projectId }

                // And the board came back with it, both stories in the columns they were left in.
                second.client.send(Envelope.STORY_LIST, projectId!!)
                val board = second.client.awaitStories("story_list after the restart") { stories ->
                    stories.map { it.id }.toSet() == setOf(firstStoryId, secondStoryId)
                }
                assertEquals(
                    mapOf(StoryStatus.TODO to listOf(firstStoryId), StoryStatus.DOING to listOf(secondStoryId)),
                    board.groupBy({ it.status }, { it.id }),
                )

                second.client.send(Envelope.STORY_GET, firstStoryId!!)
                val after = second.client.awaitStory("story_get after the restart") { it.id == firstStoryId }
                assertEquals("Run one story three ways", after.title, "the story itself came back whole")
            } finally {
                second.stop()
            }
        } finally {
            runCatching { state.deleteRecursively() }
        }
    }
}
