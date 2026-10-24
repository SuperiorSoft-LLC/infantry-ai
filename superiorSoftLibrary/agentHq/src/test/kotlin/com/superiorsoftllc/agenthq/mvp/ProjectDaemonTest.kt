package com.superiorsoftllc.agenthq.mvp

import com.superiorsoftllc.agenthq.daemon.DaemonFixture
import com.superiorsoftllc.agenthq.daemon.WsTestClient
import com.superiorsoftllc.agenthq.db.AgentHQDatabaseFactory
import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.protocol.Project
import com.superiorsoftllc.agenthq.protocol.Ticket
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * MVP Sprint-Zero, slice A (the PROJECT object) -- RED by design.
 *
 * SLICE 0 of ticket b2fbb94d froze the seam: the `Project` / `ProjectSave` types, the
 * `project_save` / `project_get` / `project_list` frames, the `project` / `projects` answers and
 * the `Ticket.projectId` key. This test suite is what the implementing slice (branch
 * `feature-agenthq-mvp-scope`) must turn GREEN. Every test here touches ONLY the project half of
 * the MVP: none of them needs a story, so it can be built without the slice-B board existing.
 *
 * Each test drives the REAL daemon over a REAL WebSocket, the way `TicketDaemonTest` does: nothing
 * is faked except the provider stub. While the behaviour is missing the daemon answers
 * `error: unknown frame type 'project_save'` and every assertion below fails naming that gap.
 */
class ProjectDaemonTest {

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

    /** The first `project` frame from [client] whose decoded project satisfies [match]. */
    private fun WsTestClient.awaitProject(description: String, match: (Project) -> Boolean): Project {
        val frame = awaitWhere(description, timeoutMs) { envelope ->
            envelope.type == Envelope.PROJECT && runCatching {
                json.decodeFromString(Project.serializer(), envelope.data)
            }.getOrNull()?.let(match) == true
        }
        return json.decodeFromString(Project.serializer(), frame.data)
    }

    /** The first `projects` frame from [client] whose decoded list satisfies [match]. */
    private fun WsTestClient.awaitProjects(description: String, match: (List<Project>) -> Boolean): List<Project> {
        val frame = awaitWhere(description, timeoutMs) { envelope ->
            envelope.type == Envelope.PROJECTS && runCatching {
                json.decodeFromString(ListSerializer(Project.serializer()), envelope.data)
            }.getOrNull()?.let(match) == true
        }
        return json.decodeFromString(ListSerializer(Project.serializer()), frame.data)
    }

    /** The first `ticket` frame from [client] whose decoded ticket satisfies [match]. */
    private fun WsTestClient.awaitTicket(description: String, match: (Ticket) -> Boolean): Ticket {
        val frame = awaitWhere(description, timeoutMs) { envelope ->
            envelope.type == Envelope.TICKET && runCatching {
                json.decodeFromString(Ticket.serializer(), envelope.data)
            }.getOrNull()?.let(match) == true
        }
        return json.decodeFromString(Ticket.serializer(), frame.data)
    }

    /**
     * A project created with `project_save` comes back whole, is readable by id with `project_get`,
     * and lists with `project_list`. The wire keys are pinned: a client renders exactly this shape.
     */
    @Test
    fun aProjectIsCreatedAndReadBack() = withDaemon { fixture ->
        fixture.send(Envelope.PROJECT_SAVE, """{"name":"Sprint-Zero MVP","goal":"one story, three ways"}""")
        val created = fixture.client.awaitProject("project_save answered with a project") { true }

        assertTrue(created.id.isNotBlank(), "the daemon chose the id: '${created.id}'")
        assertEquals("Sprint-Zero MVP", created.name)
        assertEquals("one story, three ways", created.goal)
        assertEquals(
            setOf("id", "name", "goal", "directory", "baseBranch", "laneCaps", "autoRemoveDoneWorkers"), // + autoRemoveDoneWorkers: 1.116.0 (BASICS-KILL-1); + laneCaps: 1.111.0 (LANE-CAPS); + directory: 1.36.0 (CP-2); + baseBranch: 1.81.0 (BASE-1a); additive
            fixture.client.of(Envelope.PROJECT).first().let { json.parseToJsonElement(it.data).jsonObject.keys },
        )

        // project_get answers with the very same project (the id is the raw payload, not JSON).
        // Occurrence 1: frame 0 is the save's own answer; this must be a second, separate answer.
        fixture.send(Envelope.PROJECT_GET, created.id)
        val fetched = json.decodeFromString(
            Project.serializer(),
            fixture.client.await(Envelope.PROJECT, occurrence = 1, timeoutMs = timeoutMs).data,
        )
        assertEquals(created, fetched)

        // project_list answers with the list that holds exactly this project.
        fixture.send(Envelope.PROJECT_LIST, "")
        val listed = fixture.client.awaitProjects("project_list answered with the project") { projects ->
            projects.any { it.id == created.id }
        }
        assertEquals(listOf(created), listed)
    }

    /**
     * A ticket names the project it belongs to with `projectId`, and the key survives a read: the
     * join between the MVP's project and the existing daemon-owned backlog is this one nullable
     * field. The same key spells the join on both sides of the MVP.
     */
    @Test
    fun aTicketResolvesToItsProject() = withDaemon { fixture ->
        fixture.send(Envelope.PROJECT_SAVE, """{"name":"Sprint-Zero MVP"}""")
        val project = fixture.client.awaitProject("project_save answered with a project") { true }

        fixture.send(Envelope.TICKET_SAVE, """{"title":"Run one story three ways","projectId":"${project.id}"}""")
        val saved = fixture.client.awaitTicket("ticket_save answered with the project's ticket") { ticket ->
            ticket.title == "Run one story three ways"
        }
        assertEquals(project.id, saved.projectId, "the ticket carries the project it was saved with")
        assertTrue(
            "projectId" in json.parseToJsonElement(fixture.client.of(Envelope.TICKET).first().data).jsonObject.keys,
            "projectId is part of the ticket's wire shape, nulls included",
        )

        // A read by id resolves the same project (occurrence 1: frame 0 is the save's answer).
        fixture.send(Envelope.TICKET_GET, saved.id)
        val fetched = json.decodeFromString(
            Ticket.serializer(),
            fixture.client.await(Envelope.TICKET, occurrence = 1, timeoutMs = timeoutMs).data,
        )
        assertEquals(project.id, fetched.projectId)

        // An edit that names no project leaves the ticket's project as it is. The match is on the
        // edited title, so this cannot be satisfied by the create answer that came before it.
        fixture.send(Envelope.TICKET_SAVE, """{"id":"${saved.id}","title":"Run one story three ways, measured"}""")
        val edited = fixture.client.awaitTicket("ticket_save edit answered") { it.id == saved.id && it.title == "Run one story three ways, measured" }
        assertEquals(project.id, edited.projectId, "a field the request left out is left as it was")
    }

    /**
     * A project with no tickets is a first-class citizen: the board loads empty, a project can be
     * created and read back with nothing referencing it, and an unknown project id is refused
     * rather than answered with an empty project.
     */
    @Test
    fun aProjectWithNoTicketsBehaves() = withDaemon { fixture ->
        fixture.send(Envelope.PROJECT_LIST, "")
        val empty = fixture.client.awaitProjects("project_list answered an empty board") { it.isEmpty() }
        assertTrue(empty.isEmpty(), "no projects yet: $empty")

        fixture.send(Envelope.PROJECT_SAVE, """{"name":"Empty by design"}""")
        val project = fixture.client.awaitProject("project_save answered with a project") { it.name == "Empty by design" }
        fixture.send(Envelope.PROJECT_LIST, "")
        val listed = fixture.client.awaitProjects("project_list answered with the one project") { it.size == 1 }
        assertEquals(listOf(project), listed)

        fixture.send(Envelope.PROJECT_GET, "no-such-project")
        fixture.client.awaitWhere("unknown project refused", timeoutMs) { frame ->
            frame.type == Envelope.ERROR && frame.data.contains("project_get refused")
        }
    }

    /**
     * Project ids are stable across a restart: a project saved before the daemon stopped reads back
     * identically after it boots again over the same store. The id is the MVP's join key, so it
     * must outlive the process that minted it.
     */
    @Test
    fun projectIdsAreStableAcrossARestart() {
        val state = Files.createTempDirectory("agenthq-mvp-project-").toFile()
        val dbPath = File(state, "agenthq.db").absolutePath
        val first = DaemonFixture.launch(stateDir = state, storePath = dbPath)
        try {
            first.client.await(Envelope.READY)
            first.send(Envelope.PROJECT_SAVE, """{"name":"Sprint-Zero MVP","goal":"one story, three ways"}""")
            val created = first.client.awaitProject("project_save answered with a project") { true }
            first.stop()

            val second = DaemonFixture.launch(stateDir = state, storePath = dbPath)
            try {
                second.client.await(Envelope.READY)
                second.send(Envelope.PROJECT_GET, created.id)
                val afterRestart = second.client.awaitProject("project_get after restart") { it.id == created.id }
                assertEquals(created, afterRestart, "the same project, with the same stable id, after a restart")
            } finally {
                second.stop()
            }
        } finally {
            runCatching { state.deleteRecursively() }
        }
    }
}

/**
 * MVP Sprint-Zero, slice A3: the `project_*` handler itself, proven at the wire level.
 *
 * The daemon-level suite above is the ACCEPTANCE GATE and stays red until I0 registers the handler
 * in the router (PM's ownership split: slice A owns the handler, I0 owns the registration, the
 * `ProjectStore(persistence = SqlProjectPersistence(...))` wiring and the DB-handle pass-through).
 * This suite is what slice A can prove on its own, and it is not a weaker proof: every request
 * crosses the SAME encode/decode path the socket uses -- a frame is serialized, parsed back into an
 * [Envelope] and answered exactly as `handleConnection` does, and the answer is then serialized and
 * parsed once more before it is asserted on. Only the socket is left out, so once I0 registers the
 * handler these tests describe precisely what a connected client will receive.
 *
 * The refusal wording is the frozen contract's: `project_save refused: ...` / `project_get refused:
 * ...` (CONTRACT.md §2.2.5, "Refusals follow the standing convention"), and an unknown id is
 * REFUSED rather than answered with an empty project -- the same rule `ticket_get` follows.
 */
class ProjectHandlerTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private var minted = 0

    /** A store with deterministic, non-colliding ids, so assertions can name them. */
    private fun store() = ProjectStore(idFactory = { "project-" + (minted++).toString() })

    /**
     * One request over the real codec: the frame is serialized the way a client sends it, decoded
     * the way the daemon reads it, answered, and the answer is serialized and decoded back -- the
     * full round trip with the socket omitted.
     */
    private fun ProjectHandler.ask(type: String, data: String): Envelope {
        val requestWire: String = json.encodeToString(Envelope.serializer(), Envelope(type, data))
        val request: Envelope = json.decodeFromString(Envelope.serializer(), requestWire)
        val answer: Envelope = answer(request)
            ?: error("the handler answered nothing for '$type' (data='${data.take(40)}')")
        val answerWire: String = json.encodeToString(Envelope.serializer(), answer)
        return json.decodeFromString(Envelope.serializer(), answerWire)
    }

    /** The answer frame decoded as the project it carries. */
    private fun Envelope.project(): Project = json.decodeFromString(Project.serializer(), data)

    /**
     * The wire keys a client renders, pinned exactly as the daemon-level suite pins them: a
     * `project` frame carries `id`, `name` and `goal` and nothing else (no `storyIds` since 1.29.0).
     */
    private fun Envelope.assertProjectKeys() {
        assertEquals(Envelope.PROJECT, type, "a project request is answered by a `project` frame")
        assertEquals(
            setOf("id", "name", "goal", "directory", "baseBranch", "laneCaps", "autoRemoveDoneWorkers"), // + autoRemoveDoneWorkers: 1.116.0 (BASICS-KILL-1); + laneCaps: 1.111.0 (LANE-CAPS); + directory: 1.36.0 (CP-2); + baseBranch: 1.81.0 (BASE-1a); additive
            json.parseToJsonElement(data).jsonObject.keys,
            "the `project` frame's wire keys are the frozen ones",
        )
    }

    /**
     * Save, then read the same project back by id and list it: the whole query path of the frame,
     * with the id chosen by the handler and the list holding exactly that one project.
     */
    @Test
    fun aProjectIsSavedReadBackAndListed() {
        val handler = ProjectHandler(store())

        val created = handler.ask(
            Envelope.PROJECT_SAVE,
            """{"name":"Sprint-Zero MVP","goal":"one story, three ways"}""",
        )
        created.assertProjectKeys()
        val project = created.project()
        assertTrue(project.id.isNotBlank(), "the daemon chooses the id: '${project.id}'")
        assertEquals("Sprint-Zero MVP", project.name)
        assertEquals("one story, three ways", project.goal)

        // The id travels as the raw payload, not as JSON -- `project_get` answers with the same project.
        val fetched = handler.ask(Envelope.PROJECT_GET, project.id)
        fetched.assertProjectKeys()
        assertEquals(project, fetched.project())

        val listed = handler.ask(Envelope.PROJECT_LIST, "")
        assertEquals(Envelope.PROJECTS, listed.type)
        assertEquals(listOf(project), listed.projectList())
    }

    /** The list answers in creation order, which is the order the store inserted. */
    @Test
    fun projectsAreListedInCreationOrder() {
        val handler = ProjectHandler(store())
        val first = handler.ask(Envelope.PROJECT_SAVE, """{"name":"MVP 2/11"}""").project()
        val second = handler.ask(Envelope.PROJECT_SAVE, """{"name":"MVP 3/11"}""").project()

        val listed = handler.ask(Envelope.PROJECT_LIST, "")
        assertEquals(listOf(first, second), listed.projectList())
    }

    /**
     * An edit names the project's id and leaves out what it does not change, Shaped after
     * `ticket_save`: a null field is left exactly as it was, so a rename cannot wipe a goal.
     */
    @Test
    fun anEditLeavesOutTheFieldTheRequestLeftOut() {
        val handler = ProjectHandler(store())
        val created = handler.ask(
            Envelope.PROJECT_SAVE,
            """{"name":"Sprint-Zero MVP","goal":"one story, three ways"}""",
        ).project()

        val renamed = handler.ask(
            Envelope.PROJECT_SAVE,
            """{"id":"${created.id}","name":"Sprint-Zero MVP, measured"}""",
        ).project()
        assertEquals("Sprint-Zero MVP, measured", renamed.name)
        assertEquals("one story, three ways", renamed.goal, "a field the edit left out stays as it was")

        val withGoal = handler.ask(
            Envelope.PROJECT_SAVE,
            """{"id":"${created.id}","goal":"two stories, measured"}""",
        ).project()
        assertEquals("Sprint-Zero MVP, measured", withGoal.name, "a field the edit left out stays as it was")
        assertEquals("two stories, measured", withGoal.goal)
    }

    /**
     * An unknown project id is REFUSED with `<frame> refused: ...`, never answered with an empty
     * project and never created on the spot: an edit racing a delete must not resurrect a row.
     */
    @Test
    fun anUnknownProjectIsRefusedNamingTheFrame() {
        val handler = ProjectHandler(store())

        val refusal = handler.ask(Envelope.PROJECT_GET, "no-such-project")
        assertEquals(Envelope.ERROR, refusal.type)
        assertTrue(
            refusal.data.contains("project_get refused"),
            "the refusal names its frame, the standing convention: '${refusal.data}'",
        )

        val editRefusal = handler.ask(Envelope.PROJECT_SAVE, """{"id":"no-such-project","name":"Back from the dead"}""")
        assertEquals(Envelope.ERROR, editRefusal.type)
        assertTrue(
            editRefusal.data.contains("project_save refused"),
            "an edit of an unknown project is refused the same way: '${editRefusal.data}'",
        )
        assertTrue(
            handler.ask(Envelope.PROJECT_LIST, "").projectList().isEmpty(),
            "a refused create writes nothing",
        )
    }

    /** A create's own refusal: a blank name names the frame it came from. */
    @Test
    fun aCreateThatTheStoreRefusesIsRefusedNamingTheFrame() {
        val handler = ProjectHandler(store())

        val refusal = handler.ask(Envelope.PROJECT_SAVE, """{"name":"   "}""")
        assertEquals(Envelope.ERROR, refusal.type)
        assertTrue(
            refusal.data.startsWith("project_save refused:"),
            "the refusal names its frame, the standing convention: '${refusal.data}'",
        )
        assertTrue(
            handler.ask(Envelope.PROJECT_LIST, "").projectList().isEmpty(),
            "a refused create writes nothing",
        )
    }

    /**
     * A payload the daemon does not fully understand is refused, never half-applied: `ProjectSave`
     * decodes STRICTLY (see `StrictJson`), so a key from a newer contract cannot quietly fall back
     * to a default the client did not ask for.
     */
    @Test
    fun aMalformedPayloadIsRefused() {
        val handler = ProjectHandler(store())

        val refusal = handler.ask(Envelope.PROJECT_SAVE, """{"name":"Sprint-Zero MVP","goal":"x","nope":true}""")
        assertEquals(Envelope.ERROR, refusal.type)
        assertTrue(
            refusal.data.startsWith("project_save refused: malformed project_save payload"),
            "a command the daemon does not fully understand is refused: '${refusal.data}'",
        )
        assertTrue(
            handler.ask(Envelope.PROJECT_LIST, "").projectList().isEmpty(),
            "a malformed request writes nothing",
        )
    }

    /**
     * The durable path the daemon will use: a handler over a [ProjectStore] whose persistence is
     * SQL, on a real database file. Closing the handle is the daemon stopping, opening it again is
     * the restart, and the project -- with the id the first handler minted, the MVP's join key --
     * reads back identically.
     */
    @Test
    fun aProjectSurvivesTheHandlerBeingRebuiltOverTheSameDatabase() {
        val state = Files.createTempDirectory("agenthq-mvp-a3-").toFile()
        try {
            val dbPath = File(state, "agenthq.db").absolutePath

            val before = AgentHQDatabaseFactory.open(dbPath)
            val created = ProjectHandler(
                ProjectStore(persistence = SqlProjectPersistence(before.database)),
            ).ask(Envelope.PROJECT_SAVE, """{"name":"Sprint-Zero MVP","goal":"one story, three ways"}""").project()
            before.close()

            val after = AgentHQDatabaseFactory.open(dbPath)
            try {
                val handler = ProjectHandler(
                    ProjectStore(persistence = SqlProjectPersistence(after.database)),
                )
                val fetched = handler.ask(Envelope.PROJECT_GET, created.id).project()
                assertEquals(created, fetched, "the same project, with the same stable id, after a restart")
                assertEquals(listOf(created), handler.ask(Envelope.PROJECT_LIST, "").projectList())
            } finally {
                after.close()
            }
        } finally {
            runCatching { state.deleteRecursively() }
        }
    }

    /**
     * A frame that is not this handler's is answered with null, so the router keeps its own
     * `unknown frame type` answer for everything the MVP does not own -- the fallback registration
     * pattern is safe, and no other slice's frame can be swallowed here.
     */
    @Test
    fun aFrameThatIsNotAProjectIsNotAnswered() {
        val handler = ProjectHandler(store())
        assertNull(handler.answer(Envelope(Envelope.TICKET_LIST, "")))
        assertNull(handler.answer(Envelope(Envelope.STORY_SAVE, """{"title":"x","projectId":"y"}""")))
        assertNull(handler.answer(Envelope(Envelope.PROMPT, "hi")))
    }

    /** The `projects` answer frame decoded as the list it carries. */
    private fun Envelope.projectList(): List<Project> =
        json.decodeFromString(ListSerializer(Project.serializer()), data)
}
