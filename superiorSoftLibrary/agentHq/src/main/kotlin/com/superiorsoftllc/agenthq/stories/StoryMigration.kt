package com.superiorsoftllc.agenthq.stories

import com.superiorsoftllc.agenthq.backlog.TicketBoard
import com.superiorsoftllc.agenthq.db.AgentHQDatabase
import com.superiorsoftllc.agenthq.epics.SqlEpicPersistence
import com.superiorsoftllc.agenthq.protocol.Story
import com.superiorsoftllc.agenthq.protocol.StoryStatus
import com.superiorsoftllc.agenthq.protocol.TicketStatus
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * E1-a (docs/EPIC-1-ONE-WORK-MODEL-PLAN.md, "Migration"): the one-time startup step that folds the story board
 * (`stories.json`, [JsonStoryStore]) into the ticket board, one ticket per story.
 *
 * - Stories go in file (creation) order; a story already in `story_migration` is skipped, so a second start adds
 *   nothing and a start after a crash finishes the rest without duplicates.
 * - Each ticket is created through [TicketBoard.import], and its `story_migration` row is written inside the SAME
 *   transaction: a ticket without its mapping (or the reverse) cannot be left behind.
 * - `title` and `projectId` are copied, the status is mapped ([ticketStatus]), `epicId` is the first epic (in the
 *   epics table's creation order) whose `storyIds` names the story; any further epic is named in the description
 *   so nothing is dropped silently.
 * - When every story is mapped, [file] is renamed to `stories.json.migrated` (a backup, never deleted).
 * - A file that cannot be read as a story board, or that stores a status outside [StoryStatus.ALL], is CORRUPT:
 *   it is left untouched, logged, and migrates nothing. It is never treated as "no stories".
 *
 * Existing tickets and epics are not modified. [afterStory] runs after each story's commit; it is a test seam
 * (a crash after N of M) and does nothing in the daemon.
 */
class StoryMigration(
    private val file: File,
    private val board: TicketBoard,
    private val database: AgentHQDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    private val afterStory: (storyId: String) -> Unit = {},
) {
    /** What one [run] did. */
    data class Result(val migrated: Int, val skipped: Int, val renamed: Boolean, val corrupt: Boolean = false)

    fun run(): Result {
        if (!file.isFile) return Result(migrated = 0, skipped = 0, renamed = false)
        val stories = try {
            decode()
        } catch (e: Exception) {
            System.err.println("[agentHq] story migration: ${file.path} is unreadable, left untouched: ${e.message}")
            return Result(migrated = 0, skipped = 0, renamed = false, corrupt = true)
        }
        val epics = SqlEpicPersistence(database).loadAll()
        val queries = database.story_migrationQueries
        var migrated = 0
        var skipped = 0
        for (story in stories) {
            if (queries.ticketFor(story.id).executeAsOneOrNull() != null) {
                skipped++
                continue
            }
            val holders = epics.filter { story.id in it.storyIds }.map { it.id }
            val description = buildString {
                append("Migrated from story ").append(story.id)
                if (holders.size > 1) append("\nAlso listed under epic ").append(holders.drop(1).joinToString(", "))
            }
            board.import(
                title = story.title,
                status = ticketStatus(story.status),
                projectId = story.projectId,
                epicId = holders.firstOrNull(),
                description = description,
            ) { ticketId -> queries.insertMapping(story.id, ticketId, clock()) }
            migrated++
            afterStory(story.id)
        }
        val renamed = rename()
        println("[agentHq] story migration: migrated=$migrated skipped=$skipped renamed=$renamed")
        return Result(migrated, skipped, renamed)
    }

    private fun decode(): List<Story> {
        val snapshot = json.decodeFromString(Snapshot.serializer(), file.readText())
        val broken = snapshot.stories.firstOrNull { it.status !in StoryStatus.ALL }
        require(broken == null) {
            "story '${broken?.id?.take(80)}' carries status '${broken?.status?.take(40)}', which is not a column"
        }
        return snapshot.stories
    }

    private fun rename(): Boolean {
        val target = File(file.parentFile, file.name + MIGRATED_SUFFIX)
        return try {
            try {
                Files.move(file.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Exception) {
                // Some filesystems (and OneDrive-synced folders) refuse an atomic move; a plain replace still keeps the backup.
                Files.move(file.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            true
        } catch (e: Exception) {
            // Every story is mapped, so the next start skips them all and retries only this rename.
            System.err.println("[agentHq] story migration: could not rename ${file.path}: ${e.message}")
            false
        }
    }

    /** The same shape [JsonStoryStore] writes. */
    @Serializable
    private data class Snapshot(
        val version: Int = 1,
        val stories: List<Story> = emptyList(),
    )

    companion object {
        const val MIGRATED_SUFFIX = ".migrated"

        private val json = Json { ignoreUnknownKeys = true }

        /** Story column -> ticket column. Only the three frozen story statuses reach here (see [decode]). */
        fun ticketStatus(storyStatus: String): String = when (storyStatus) {
            StoryStatus.TODO -> TicketStatus.TODO
            StoryStatus.DOING -> TicketStatus.IN_PROGRESS
            StoryStatus.DONE -> TicketStatus.DONE
            else -> throw IllegalArgumentException("unknown story status '${storyStatus.take(40)}'")
        }
    }
}
