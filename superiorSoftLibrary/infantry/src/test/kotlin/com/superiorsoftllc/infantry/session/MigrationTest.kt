package com.superiorsoftllc.infantry.session

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** RENAME-2 MigrationTest: old dir only / both / new only, and the old file names inside an explicit dir. Temp dirs only. */
class MigrationTest {
    private val quiet: (String) -> Unit = {}
    private fun tmp(): File = Files.createTempDirectory("rename2-mig").toFile()

    private fun seedOld(old: File) {
        File(old, "sub").mkdirs()
        File(old, "agenthq.db").writeText("db")
        File(old, "agenthq.db-wal").writeText("wal")
        File(old, "sub/settings.json").writeText("{\"a\":1}")
        File(old, "daemon.lock").writeText("held")
    }

    @Test fun `old dir only is copied with new names and left untouched`() {
        val root = tmp(); val old = File(root, ".agenthq"); val new = File(root, ".infantry")
        seedOld(old)
        assertEquals(StateDirMigration.Outcome.MIGRATED, StateDirMigration.migrate(old, new, quiet))
        assertEquals("db", File(new, "infantry.db").readText())
        assertEquals("wal", File(new, "infantry.db-wal").readText())
        assertEquals("{\"a\":1}", File(new, "sub/settings.json").readText())
        assertFalse(File(new, "daemon.lock").exists(), "a running daemon's lock is not copied")
        assertTrue(File(old, "agenthq.db").isFile && File(old, "daemon.lock").isFile, "the old dir is untouched")
        assertFalse(File(root, ".infantry.migrating").exists())
        root.deleteRecursively()
    }

    @Test fun `both dirs exist uses the new one and changes nothing`() {
        val root = tmp(); val old = File(root, ".agenthq"); val new = File(root, ".infantry")
        seedOld(old); new.mkdirs(); File(new, "infantry.db").writeText("new")
        assertEquals(StateDirMigration.Outcome.USED_NEW, StateDirMigration.migrate(old, new, quiet))
        assertEquals("new", File(new, "infantry.db").readText())
        assertFalse(File(new, "sub").exists())
        root.deleteRecursively()
    }

    @Test fun `new dir only and neither are no-ops`() {
        val root = tmp(); val new = File(root, ".infantry"); new.mkdirs()
        assertEquals(StateDirMigration.Outcome.USED_NEW, StateDirMigration.migrate(File(root, ".agenthq"), new, quiet))
        assertEquals(StateDirMigration.Outcome.NOTHING_TO_DO, StateDirMigration.migrate(File(root, "x"), File(root, "y"), quiet))
        assertFalse(File(root, "y").exists())
        root.deleteRecursively()
    }

    @Test fun `an explicit dir in the old layout gets the new file names beside the old`() {
        val dir = tmp(); seedOld(dir)
        assertEquals(StateDirMigration.Outcome.MIGRATED, StateDirMigration.upgradeLayout(dir, quiet))
        assertEquals("db", File(dir, "infantry.db").readText())
        assertTrue(File(dir, "agenthq.db").isFile)
        assertEquals(StateDirMigration.Outcome.NOTHING_TO_DO, StateDirMigration.upgradeLayout(dir, quiet))
        dir.deleteRecursively()
    }
}
