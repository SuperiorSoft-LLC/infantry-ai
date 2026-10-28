package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.daemon.StateDirLock
import com.superiorsoftllc.agenthq.mvp.ProjectStore
import com.superiorsoftllc.agenthq.mvp.SqlProjectPersistence
import com.superiorsoftllc.agenthq.persistence.SqlAgentHqStore
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** NS5 + NS6a: each shell app's bundled daemon is isolated; DaemonChoice resolves in the design's order. */
class AppDaemonTest {
    @Test
    fun `two app ids get two isolated loopback daemons with their own state dirs, locks and seed`() {
        val home = Files.createTempDirectory("ns5-home").toFile()
        val apps = Files.createTempDirectory("ns5-apps").toFile()
        try {
            fun launch(id: String) = File(apps, id).apply {
                mkdirs()
                File(this, "app.json").writeText("""{ "appId": "$id", "daemon": { "mode": "bundle", "seedProject": "Board" } }""")
            }.let { ShellApp.resolve(it, home.path) }
            val a = launch("com.example.a")
            val b = launch("com.example.b")
            val pa = AppDaemonProfile.properties(a)
            val pb = AppDaemonProfile.properties(b)
            assertEquals(File(home, ".infantry/apps/com.example.a").path, pa["AGENTHQ_STATE_DIR"])
            assertNotEquals(pa["AGENTHQ_STATE_DIR"], pb["AGENTHQ_STATE_DIR"])
            assertEquals(File(a.stateDir, "agenthq.db").path, pa["AGENTHQ_DB"])
            assertEquals("127.0.0.1", pa["AGENTHQ_HOST"])
            assertEquals("0", pa["AGENTHQ_PORT"])

            // One daemon per app: both locks are held at once, each in its own dir.
            val la = StateDirLock.acquire(a.stateDir.apply { mkdirs() })
            val lb = StateDirLock.acquire(b.stateDir.apply { mkdirs() })
            la.release(); lb.release()

            // seedProject: created on the first start only.
            val db = File(pa.getValue("AGENTHQ_DB"))
            assertTrue(SeedProject.onFirstStart(db, "Board"))
            assertFalse(SeedProject.onFirstStart(db, "Board"))
            val store = SqlAgentHqStore.open(db.path)
            try {
                assertEquals(listOf("Board"), ProjectStore(persistence = SqlProjectPersistence(store.database)).list().map { it.name })
            } finally {
                store.close()
            }
            assertFalse(File(pb.getValue("AGENTHQ_DB")).exists())

            // The Infantry install's and the dev state dirs are untouched.
            assertFalse(InstallProfile.stateDir({ null }, home.path).exists())
            assertFalse(File(home, ".agenthq").exists())
            // The full build carries the bundled daemon's launcher.
            assertNotNull(DaemonLaunchers.find())
        } finally {
            home.deleteRecursively(); apps.deleteRecursively()
        }
    }

    @Test
    fun `DaemonChoice resolves grant, then saved server, then bundle, else the connect dialog`() {
        val app = AppManifest.Daemon(url = "ws://board.example/ws")
        assertEquals(DaemonChoice.Connect("ws://board.example/ws", DaemonChoice.Source.APP_GRANT), DaemonChoice.resolve(app, "ws://saved/ws", true) { true })
        assertEquals(DaemonChoice.Connect("ws://saved/ws", DaemonChoice.Source.SAVED_SERVER), DaemonChoice.resolve(app, "ws://saved/ws", true))
        assertEquals(DaemonChoice.Bundle, DaemonChoice.resolve(app, null, buildHasDaemon = true))
        assertEquals(DaemonChoice.ConnectDialog, DaemonChoice.resolve(app.copy(mode = "connect"), null, buildHasDaemon = true))
        assertEquals(DaemonChoice.ConnectDialog, DaemonChoice.resolve(app, null, buildHasDaemon = false))
    }
}
