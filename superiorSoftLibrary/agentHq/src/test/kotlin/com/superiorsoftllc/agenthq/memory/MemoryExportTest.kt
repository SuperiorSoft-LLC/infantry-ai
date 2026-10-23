package com.superiorsoftllc.agenthq.memory

import com.superiorsoftllc.agenthq.db.AgentHQDatabaseFactory
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * MEM-4: the daemon writes a READ-ONLY markdown mirror of one project's memory -- an INDEX.md listing
 * every note by name + description, and one `<name>.md` per note carrying its body.
 *
 * Agents (Claude Code, OpenCode) read files for free, so the mirror is how the database's notes reach
 * a reader without a round-trip: the database stays the source of truth, the folder is a generated copy
 * it can rebuild at will. This test runs at the daemon level, over the same SQLite file the daemon
 * keeps its state in (via [SqlMemoryPersistence]), so it exercises the real store and not the in-memory
 * default.
 *
 * The one claim: exporting project P writes the index and one file per note, a second export after an
 * edit overwrites the stale file with the new body, and a note no longer in the store disappears from
 * the folder (the mirror matches the store exactly).
 *
 * MEM-7 adds the second claim: a note name that would escape the mirror folder (a path separator or a
 * `..` traversal) or shadow its index (`INDEX`, case-insensitive) is refused at SAVE time; and for a
 * row that reached the store another way (an existing durable row, loaded by `init`), the export's
 * canonical-path guard still skips it rather than writing it outside the mirror folder.
 */
class MemoryExportTest {

    /** A temp directory holding the SQLite file a daemon would keep its state in. */
    private fun stateDir() = Files.createTempDirectory("agenthq-mem4-").toFile()

    @Test
    fun memoryExportWritesIndexAndNoteFiles() {
        val state = stateDir()
        try {
            val dbPath = File(state, "agenthq.db").absolutePath
            val handle = AgentHQDatabaseFactory.open(dbPath)
            try {
                val store = MemoryStore(
                    persistence = SqlMemoryPersistence(handle.database),
                    idFactory = { "note-" + System.nanoTime() },
                )
                store.save(
                    projectId = "project-1",
                    name = "auth-flow",
                    description = "Contains the login steps",
                    body = "The login flow body.",
                )
                store.save(
                    projectId = "project-1",
                    name = "billing-flow",
                    description = "Contains the invoice steps",
                    body = "The invoice flow body.",
                )
                // A note of another project must never appear in project-1's mirror.
                store.save(
                    projectId = "project-2",
                    name = "other-flow",
                    description = "Contains unrelated steps",
                    body = "Nothing here belongs to project-1.",
                )

                val dir = File(state, "mirror")
                dir.mkdirs()
                // A file left by an earlier export whose note is no longer in the store: the mirror
                // must drop it, so the folder always matches the store exactly.
                File(dir, "removed-note.md").writeText("stale body of a deleted note")
                store.export("project-1", dir)

                val index = File(dir, "INDEX.md")
                assertTrue(index.isFile, "export writes INDEX.md")
                val indexText = index.readText()
                assertTrue(
                    indexText.contains("auth-flow") && indexText.contains("Contains the login steps"),
                    "the index lists the note name and its description: '$indexText'",
                )
                assertTrue(
                    indexText.contains("billing-flow") && indexText.contains("Contains the invoice steps"),
                    "the index lists every note of the project: '$indexText'",
                )
                assertTrue(
                    !indexText.contains("other-flow"),
                    "the index never leaks another project's note: '$indexText'",
                )

                val noteFile = File(dir, "auth-flow.md")
                assertTrue(noteFile.isFile, "export writes one <name>.md per note")
                assertTrue(
                    noteFile.readText().contains("The login flow body."),
                    "the note file carries the body: '${noteFile.readText()}'",
                )

                // A second export after an edit overwrites the stale file with the new body.
                store.save(
                    projectId = "project-1",
                    name = "auth-flow",
                    description = "Contains the login steps",
                    body = "The login flow body, revised.",
                )
                store.export("project-1", dir)
                val revised = File(dir, "auth-flow.md").readText()
                assertTrue(
                    revised.contains("The login flow body, revised."),
                    "a re-export overwrites the note file with the edited body: '$revised'",
                )
                assertTrue(
                    !revised.contains("The login flow body."),
                    "the stale body is gone after a re-export: '$revised'",
                )

                assertEquals(
                    2,
                    dir.listFiles { f -> f.isFile && f.name.endsWith(".md") && f.name != "INDEX.md" }?.size,
                    "the mirror holds exactly one file per note of the project",
                )
                assertTrue(
                    !File(dir, "removed-note.md").exists(),
                    "a note no longer in the store disappears from the folder on the next export",
                )
            } finally {
                handle.close()
            }
        } finally {
            runCatching { state.deleteRecursively() }
        }
    }

    /**
     * MEM-7: the mirror is a folder of files named after notes, so a note name is a file name and must
     * be safe as one. `../x` and `a/b` would write OUTSIDE [dir]; `INDEX` (any case) would overwrite the
     * index the export generates. Each is refused at SAVE time with [IllegalArgumentException] and the
     * store keeps nothing.
     *
     * A second claim covers a row that never passed the save-time check: a note already in the durable
     * store (written before MEM-7, or by another writer) is loaded by the store's `init`. The export's
     * canonical-path guard must still skip such a name rather than writing it outside [dir], so no
     * existing row can escape or shadow the mirror.
     */
    @Test
    fun memoryExportRefusesPathAndIndexNames() {
        val state = stateDir()
        try {
            val dbPath = File(state, "agenthq.db").absolutePath
            val handle = AgentHQDatabaseFactory.open(dbPath)
            try {
                val store = MemoryStore(
                    persistence = SqlMemoryPersistence(handle.database),
                    idFactory = { "note-" + System.nanoTime() },
                )

                val unsafe = listOf("../x", "..", "a/b", "a\\b", "INDEX", "index", "InDeX")
                unsafe.forEach { name ->
                    assertFailsWith<IllegalArgumentException>("a note named '$name' must be refused at save time") {
                        store.save(
                            projectId = "project-1",
                            name = name,
                            description = "would escape or shadow the mirror",
                            body = "the body of '$name'",
                        )
                    }
                    assertTrue(
                        store.index("project-1").isEmpty(),
                        "a refused save of '$name' must keep nothing: ${store.index("project-1")}",
                    )
                }

                // A safe name still saves, and the export writes exactly one note file -- never a
                // second file that could shadow INDEX.md.
                store.save(
                    projectId = "project-1",
                    name = "auth-flow",
                    description = "Contains the login steps",
                    body = "The login flow body.",
                )
                assertEquals(1, store.index("project-1").size, "the safe note is the only note stored")

                val dir = File(state, "mirror")
                store.export("project-1", dir)
                assertTrue(
                    File(dir, "auth-flow.md").isFile,
                    "the safe note is mirrored",
                )
                assertTrue(
                    File(dir, "INDEX.md").readText().contains("auth-flow"),
                    "the index lists the safe note",
                )
                assertEquals(
                    1,
                    dir.listFiles { f -> f.isFile && f.name.endsWith(".md") && f.name != "INDEX.md" }?.size,
                    "the mirror holds exactly one note file, nothing shadowing INDEX.md",
                )

                // MEM-7 (review): save-time validation stops NEW unsafe names, but a row already in
                // the durable store (written before MEM-7, or by another writer) is loaded by the
                // store's init without passing checkName. Export must still refuse to let such a name
                // escape the mirror folder -- the canonical-path guard is kept as defence in depth.
                SqlMemoryPersistence(handle.database).upsert(
                    MemoryNote(
                        id = "note-legacy-escape",
                        projectId = "project-1",
                        name = "../escape",
                        folder = "",
                        description = "an unsafe row that predates MEM-7",
                        body = "must never be written outside the mirror",
                        updatedAt = "2026-10-11T00:00:00Z",
                    ),
                )
                val reopened = MemoryStore(persistence = SqlMemoryPersistence(handle.database))
                val guardDir = File(state, "guard-mirror")
                guardDir.mkdirs()
                reopened.export("project-1", guardDir)
                assertTrue(
                    !File(state, "escape.md").exists(),
                    "an unsafe existing row must not escape the mirror folder",
                )
                assertTrue(
                    !File(guardDir, "INDEX.md").readText().contains("escape"),
                    "the index never links a name the export guard skipped",
                )
                assertEquals(
                    listOf("auth-flow"),
                    guardDir.listFiles { f -> f.isFile && f.name.endsWith(".md") && f.name != "INDEX.md" }
                        ?.map { it.name.removeSuffix(".md") }
                        ?.sorted(),
                    "only the safe note is mirrored; the unsafe existing row is skipped",
                )
            } finally {
                handle.close()
            }
        } finally {
            runCatching { state.deleteRecursively() }
        }
    }
}
