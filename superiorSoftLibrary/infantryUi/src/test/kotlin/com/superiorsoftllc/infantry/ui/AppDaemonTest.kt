// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.daemon.RemoteAccess
import com.superiorsoftllc.infantry.daemon.StateDirLock
import com.superiorsoftllc.infantry.mvp.ProjectStore
import com.superiorsoftllc.infantry.mvp.SqlProjectPersistence
import com.superiorsoftllc.infantry.persistence.SqlInfantryStore
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
            assertEquals(File(home, ".infantry/apps/com.example.a").path, pa["INFANTRY_STATE_DIR"])
            assertNotEquals(pa["INFANTRY_STATE_DIR"], pb["INFANTRY_STATE_DIR"])
            assertEquals(File(a.stateDir, "infantry.db").path, pa["INFANTRY_DB"])
            assertEquals("127.0.0.1", pa["INFANTRY_HOST"])
            assertEquals("0", pa["INFANTRY_PORT"])

            // One daemon per app: both locks are held at once, each in its own dir.
            val la = StateDirLock.acquire(a.stateDir.apply { mkdirs() })
            val lb = StateDirLock.acquire(b.stateDir.apply { mkdirs() })
            la.release(); lb.release()

            // seedProject: created on the first start only.
            val db = File(pa.getValue("INFANTRY_DB"))
            assertTrue(SeedProject.onFirstStart(db, "Board"))
            assertFalse(SeedProject.onFirstStart(db, "Board"))
            val store = SqlInfantryStore.open(db.path)
            try {
                assertEquals(listOf("Board"), ProjectStore(persistence = SqlProjectPersistence(store.database)).list().map { it.name })
            } finally {
                store.close()
            }
            assertFalse(File(pb.getValue("INFANTRY_DB")).exists())

            // The Infantry install's and the dev state dirs are untouched.
            assertFalse(InstallProfile.stateDir({ null }, home.path).exists())
            assertFalse(File(home, ".infantry/infantry.db").exists())
            assertFalse(File(home, ".agenthq").exists())
            // The full build carries the bundled daemon's launcher.
            assertNotNull(DaemonLaunchers.find())
        } finally {
            home.deleteRecursively(); apps.deleteRecursively()
        }
    }

    @Test
    fun `an app daemon started with INFANTRY_TOKEN in the parent env neither uses nor forwards it`() {
        val home = Files.createTempDirectory("sec-home").toFile()
        val apps = Files.createTempDirectory("sec-apps").toFile()
        try {
            val app = File(apps, "com.example.sec").apply {
                mkdirs()
                File(this, "app.json").writeText("""{ "appId": "com.example.sec", "daemon": { "mode": "bundle" } }""")
            }.let { ShellApp.resolve(it, home.path) }
            val props = AppDaemonProfile.properties(app)
            val isolated = RemoteAccess.credentialsIsolated { props[it] }
            assertTrue(isolated)
            val ownerEnv = mapOf("INFANTRY_TOKEN" to "owner-secret", "INFANTRY_TOKEN" to "owner-secret")
            val dir = app.stateDir.apply { mkdirs() }
            assertEquals(null, RemoteAccess.resolveToken(dir, { ownerEnv[it] }, isolated))
            assertEquals(null, RemoteAccess.clientToken({ ownerEnv[it] ?: props[it] }, isolated))
            // Its agents get every inherited credential name blanked.
            val scrub = RemoteAccess.inheritedCredentialScrub()
            for (name in listOf("INFANTRY_TOKEN", "INFANTRY_TOKEN_FILE", "INFANTRY_TOKEN", "INFANTRY_TOKEN_FILE")) assertEquals("", scrub[name])
            // The Infantry install (not isolated) still honours the environment token.
            assertEquals("owner-secret", RemoteAccess.resolveToken(dir, { ownerEnv[it] }, isolated = false))
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
