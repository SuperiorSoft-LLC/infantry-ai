// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** NS4: `--app <dir>` reads app.json; the appId names the plugins root, the state dir and the upgrade code. */
class ShellAppTest {
    @Test
    fun `an app dir resolves to its manifest, plugins root, state dir and upgrade code`() {
        val app = Files.createTempDirectory("ns4-app").toFile()
        val home = Files.createTempDirectory("ns4-home").toFile()
        try {
            File(app, "app.json").writeText(
                """{ "appId": "com.example.board", "name": "Board", "icon": "icon.ico", "windowTitle": "Board window",
                   "daemon": { "mode": "bundle", "url": null, "seedProject": "Board" } }""",
            )
            assertEquals(app.absoluteFile, ShellApp.appDir(arrayOf("--safe-mode", "--app", app.path)) { null })

            val launch = ShellApp.resolve(app, home.path)
            assertEquals(AppManifest("com.example.board", "Board", "icon.ico", "Board window", AppManifest.Daemon("bundle", null, "Board")), launch.manifest)
            assertEquals(File(app, "plugins"), launch.pluginsRoot)
            assertEquals(File(home, ".infantry/apps/com.example.board"), launch.stateDir)
            assertEquals(File(launch.stateDir, "plugins"), ShellApp.resolve(app, home.path, installed = true).pluginsRoot)
            assertEquals(AppManifest.upgradeUuid("com.example.board"), launch.manifest.upgradeUuid)
            assertNotEquals("6f1d3c2a-8b47-4e5a-9c1e-2a7b0d4f8e61", launch.manifest.upgradeUuid) // never Infantry AI's
        } finally {
            app.deleteRecursively(); home.deleteRecursively()
        }
    }
}
