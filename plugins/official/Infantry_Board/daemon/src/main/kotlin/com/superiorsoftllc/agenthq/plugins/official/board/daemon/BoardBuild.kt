package com.superiorsoftllc.agenthq.plugins.official.board.daemon

import com.superiorsoftllc.agenthq.backlog.TicketBoard
import com.superiorsoftllc.agenthq.daemon.api.board.BoardRuntime
import com.superiorsoftllc.agenthq.epics.Epic
import com.superiorsoftllc.agenthq.epics.EpicHandler
import com.superiorsoftllc.agenthq.epics.EpicPersistence
import com.superiorsoftllc.agenthq.epics.EpicStore
import com.superiorsoftllc.agenthq.epics.ProjectDirectory
import com.superiorsoftllc.agenthq.mvp.BoardHandler
import com.superiorsoftllc.agenthq.mvp.StoryHandler
import com.superiorsoftllc.agenthq.mvp.StoryHandler.Companion.asStory
import com.superiorsoftllc.agenthq.mvp.StoryTickets
import com.superiorsoftllc.agenthq.protocol.Ticket
import com.superiorsoftllc.agenthq.protocol.TicketMove
import com.superiorsoftllc.agenthq.protocol.TicketSave
import kotlinx.serialization.json.Json

/** DP-5h-3a2: the story shim, the board-summary handler and the epic handler over the board `BoardFromRuntime` built, from the runtime's inputs. */
class BoardHandlers(val stories: StoryHandler, val storyBoard: BoardHandler, val epics: EpicHandler, val epicStore: EpicStore, val epicInputs: com.superiorsoftllc.agenthq.daemon.api.board.BoardEpicInputs)

object BoardBuild {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun handlers(runtime: BoardRuntime, board: TicketBoard): BoardHandlers {
        val storyInputs = runtime.stories
        val stories = StoryHandler(
            board = object : StoryTickets {
                override fun get(id: String): Ticket = board.get(id)
                override fun save(request: TicketSave): Ticket = board.save(request).ticket
                override fun moveWithoutStart(request: TicketMove): Ticket = board.move(request, startOnInProgress = false).ticket
            },
            resolve = storyInputs::ticketFor,
            onEpicChanged = storyInputs::epicChanged,
        )
        val storyBoard = BoardHandler(stories = { board.list().map { it.asStory() } })
        val e = runtime.epics
        val store = EpicStore(
            persistence = object : EpicPersistence {
                override fun loadAll(): List<Epic> = e.loadAll().map { json.decodeFromString(Epic.serializer(), it) }
                override fun upsert(epic: Epic) = e.upsert(json.encodeToString(Epic.serializer(), epic))
            },
            projects = ProjectDirectory { id -> if (e.projectKnown(id)) id else throw IllegalArgumentException("unknown project: $id") },
            ticketIdsByEpic = { board.list().filter { it.epicId != null }.groupBy({ it.epicId!! }, { it.id }) },
        )
        val epics = EpicHandler(store, baseCheck = { epic, branch -> e.checkBase(epic.projectId, branch) }, branchName = e::branchName)
        return BoardHandlers(stories, storyBoard, epics, store, e)
    }

    /**
     * DP-5h-3e-b: what core's `BoardWiring.attachTicketServices` ran at start, now the plugin's: fold the legacy `stories.json` into the board once (a
     * failure is logged and the next start retries), then clear the queue marks (nothing starts on boot).
     */
    fun startup(runtime: BoardRuntime, board: TicketBoard) {
        val stories = runtime.stories
        stories.migrationFile()?.let { path ->
            runCatching {
                com.superiorsoftllc.agenthq.stories.StoryMigration(
                    file = java.io.File(path),
                    board = com.superiorsoftllc.agenthq.stories.StoryMigration.StoryImport { title, status, projectId, epicId, description, inTx ->
                        board.import(title, status, projectId, epicId, description, inTx)
                    },
                    mappings = object : com.superiorsoftllc.agenthq.stories.StoryMigration.StoryMappings {
                        override fun ticketFor(storyId: String): String? = stories.ticketFor(storyId)
                        override fun insert(storyId: String, ticketId: String, at: Long) = stories.recordMapping(storyId, ticketId, at)
                    },
                    epics = { runtime.epics.loadAll().map { json.decodeFromString(Epic.serializer(), it) } },
                ).run()
            }.onFailure { System.err.println("[agentHq] story migration failed, will retry next start: $it") }
        }
        println("[agentHq] backlog queue marks cleared=${board.clearQueueMarks()} (nothing starts on boot)")
    }
}
