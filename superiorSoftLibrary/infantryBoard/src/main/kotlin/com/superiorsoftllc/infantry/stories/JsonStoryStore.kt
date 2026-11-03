// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.stories

import com.superiorsoftllc.infantry.protocol.Story
import com.superiorsoftllc.infantry.protocol.StoryMove
import com.superiorsoftllc.infantry.protocol.StorySave
import com.superiorsoftllc.infantry.protocol.StoryStatus
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * MVP Sprint-Zero, slice B leaf B2 -- the DURABLE half of the story board.
 *
 * Every rule of the board lives in [InMemoryStoryStore]; this class adds one thing and nothing
 * else: after a row really changed, it rewrites the whole board to [file]. It is the
 * [com.superiorsoftllc.infantry.session.JsonSessionStore] pattern applied to stories -- an interface
 * with an on-disk implementation -- and it is deliberately the SAME deal for the SAME reason:
 *
 * - **No database.** The MVP need is "a restart keeps the board", not "query stories". The `stories`
 *   table belongs to the shared `InfantryDatabase` schema, and taking `Schema.version` from 7 to 8
 *   is I0's, because slice A's `projects` migration needs the very same number and SQLDelight names
 *   a migration for the version it leaves -- one bump per integration, never one per slice. The
 *   scoped draft of that table is in [StoryStore]'s documentation.
 * - **A whole file, written atomically.** A `.tmp` sibling is written and moved into place, so a
 *   crash mid-write cannot truncate a live board. Some filesystems (and OneDrive-synced folders)
 *   refuse an atomic move, so a plain replace is the fallback, exactly as in
 *   [com.superiorsoftllc.infantry.session.JsonSessionStore].
 * - **A corrupt file is "no stories", never a daemon that will not start.** An unreadable file, or
 *   a story stored with a status the frozen [StoryStatus.ALL] does not spell, leaves the store empty
 *   with one line on stderr. Losing a board must never stop the daemon from booting; the same rule
 *   the session store holds sessions to, for the same reason.
 *
 * CONTRACT: implementations must be safe to call from several threads, so every mutating operation
 * is `@Synchronized` and its flush happens inside the same lock -- a reader never sees half a write,
 * and two writers never interleave two files.
 */
class JsonStoryStore(
    private val file: File,
    idFactory: () -> String = { "story-" + UUID.randomUUID().toString().take(8) },
) : InMemoryStoryStore(idFactory) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Read the board the moment the store is built: that read IS what a restart is. */
    init {
        restore()
    }

    @Synchronized
    override fun save(request: StorySave): Story = super.save(request).also { flush() }

    @Synchronized
    override fun move(request: StoryMove): Story = super.move(request).also { flush() }

    /**
     * Only a row that really changed reaches here, so writing the board back is always a change and
     * never a no-op: [InMemoryStoryStore.move] already returns early for the column a story is in,
     * and [InMemoryStoryStore.save] reports an edit that says nothing new.
     */
    override fun afterWrite() = flush()

    /**
     * Load [file] into the in-memory board. A missing file is an empty board -- a first boot. A file
     * that cannot be read as a snapshot, or that stores a status outside the frozen vocabulary, is
     * corrupt: it leaves the board empty and says so on stderr rather than letting an unknown column
     * come back out of a restart.
     */
    private fun restore() {
        stories.clear()
        if (!file.isFile) return
        val failure = runCatching { decode() }.exceptionOrNull()
        if (failure != null) {
            System.err.println("[infantry] could not read story store ${file.path}: ${failure.message}")
        }
    }

    private fun decode(): Snapshot {
        val snapshot = json.decodeFromString(Snapshot.serializer(), file.readText())
        val broken = snapshot.stories.firstOrNull { it.status !in StoryStatus.ALL }
        require(broken == null) {
            "story '${broken?.id?.take(80)}' carries status '${broken?.status?.take(40)}', " +
                "which is not a column: ${StoryStatus.ALL.joinToString(", ")}"
        }
        snapshot.stories.forEach { stories[it.id] = it }
        return snapshot
    }

    /** Write the whole board to [file], atomically: a crash mid-write cannot truncate a live board. */
    private fun flush() {
        val text = json.encodeToString(
            Snapshot.serializer(),
            Snapshot(stories = stories.values.toList()),
        )
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        try {
            Files.move(
                tmp.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: Exception) {
            // Some filesystems (and OneDrive-synced folders) refuse an atomic move; a plain replace
            // is still better than leaving only the .tmp behind.
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    @Serializable
    private data class Snapshot(
        val version: Int = 1,
        val stories: List<Story> = emptyList(),
    )

    companion object {
        /** The default store: `stories.json` inside [stateDir] (the caller passes the daemon's state dir). */
        fun default(stateDir: File): JsonStoryStore = JsonStoryStore(File(stateDir, "stories.json"))
    }
}
