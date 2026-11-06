// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.updater

import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.plugin.host.PluginJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * UPDATER-DEFAULT-1: the update source is manifest data (module.json `appUpdate`, baked by the build) and the lowest layer of
 * [UpdateSettings.resolve]; bad overrides are ignored, never "updates off"; the window re-reads settings and checks on focus.
 */
class UpdateManifestTest {
    private val temp: File = Files.createTempDirectory("update-manifest").toFile()
    private val scope = CoroutineScope(SupervisorJob())

    @AfterTest
    fun cleanup() {
        scope.cancel()
        temp.deleteRecursively()
    }

    private val official = mapOf(
        UpdateManifest.KEY_TYPE to "github", UpdateManifest.KEY_REPO to "SuperiorSoft-LLC/infantry-ai",
        UpdateManifest.KEY_VISIBILITY to "public", UpdateManifest.KEY_CHANNEL to "production", UpdateManifest.KEY_INTERVAL to "24",
    )

    private fun resolve(
        setting: (String) -> String? = { null },
        config: (String) -> String? = { null },
        manifest: (String) -> String? = { official[it] },
    ) = UpdateSettings.resolve(
        installed = true, version = "1.0.6", stateDir = temp, setting = setting, env = { null },
        credentialStore = { null }, config = config, manifest = manifest,
    )

    private fun props(text: String) = File(temp, UpdateSettings.FILE_NAME).writeText(text)

    @Test
    fun `the baked resource is this plugin's module json appUpdate block`() {
        // The test class path carries the jar's resource as daemon/build.gradle.kts wrote it from ../module.json.
        assertEquals("SuperiorSoft-LLC/infantry-ai", UpdateManifest.get(UpdateManifest.KEY_REPO))
        assertEquals("public", UpdateManifest.get(UpdateManifest.KEY_VISIBILITY))
        assertEquals("o/r", UpdateManifest.load("# c\nrepo=o/r\n").getProperty("repo"))
        assertNull(UpdateManifest.load(null).getProperty("repo"))
    }

    @Test
    fun `this plugin's module json still parses as a module manifest`() {
        // QA-FIX-I: the app's release block once sat under `update`, the engine's per-module UpdateDeclaration key; every
        // ModuleManifest parse of this file then threw MissingFieldException (source), so the host dropped the plugin.
        val text = File("../module.json").readText()
        val manifest = PluginJson.decodeFromString(ModuleManifest.serializer(), text)
        assertEquals("Infantry_Updater", manifest.name)
        assertNull(manifest.update, "the app's release source is `appUpdate`; `update` is this module's own update source")
    }

    @Test
    fun `an official build checks its manifest repo with zero setup`() {
        val on = assertIs<UpdateSettings.Enabled>(resolve())
        assertEquals("SuperiorSoft-LLC/infantry-ai", on.config.repo)
        assertEquals(UpdateVisibility.PUBLIC, on.config.visibility)
        assertNull(on.config.localDir)
        assertEquals(24, on.config.checkIntervalHours)
        assertTrue(on.config.autoCheck)
        assertTrue(on.warnings.isEmpty(), on.warnings.toString())
    }

    @Test
    fun `a build whose manifest names no repo stays off`() {
        val off = assertIs<UpdateSettings.Disabled>(resolve(manifest = { null }))
        assertTrue("no update repo" in off.reason, off.reason)
    }

    @Test
    fun `developer overrides win over the manifest`() {
        assertEquals("me/fork", assertIs<UpdateSettings.Enabled>(resolve(config = { if (it == UpdateSettings.KEY_REPO) "me/fork" else null })).config.repo)
        assertEquals("env/repo", assertIs<UpdateSettings.Enabled>(resolve(setting = { if (it == UpdateSettings.REPO) "env/repo" else null })).config.repo)
        props("repo=file/repo\n")
        assertEquals("file/repo", assertIs<UpdateSettings.Enabled>(resolve()).config.repo)
        // A developer's dir= still switches to the local folder: the manifest's channel is only the build's default.
        props("dir=C:/rel\n")
        assertEquals(File("C:/rel"), assertIs<UpdateSettings.Enabled>(resolve()).config.localDir)
    }

    @Test
    fun `update properties source=public is the visibility, not the old local or github switch`() {
        props("source=public\n")
        val pub = assertIs<UpdateSettings.Enabled>(resolve())
        assertEquals("SuperiorSoft-LLC/infantry-ai", pub.config.repo)
        assertEquals(UpdateVisibility.PUBLIC, pub.config.visibility)
        props("source=private\n")
        assertEquals(UpdateVisibility.PRIVATE, assertIs<UpdateSettings.Enabled>(resolve()).config.visibility)
        props("source=github\n")
        assertNull(assertIs<UpdateSettings.Enabled>(resolve()).config.localDir)
    }

    @Test
    fun `a bad value is ignored with a warning and never turns updates off`() {
        // The owner typed the repo into Source; a URL into Repo; garbage into the channel and the interval.
        val on = assertIs<UpdateSettings.Enabled>(resolve(config = {
            when (it) {
                UpdateSettings.KEY_SOURCE -> "SuperiorSoft-LLC/infantry-ai"
                UpdateSettings.KEY_REPO -> "https://github.com/SuperiorSoft-LLC/infantry-ai"
                UpdateSettings.KEY_CHANNEL -> "nightly"
                UpdateSettings.KEY_INTERVAL -> "daily"
                else -> null
            }
        }))
        assertEquals("SuperiorSoft-LLC/infantry-ai", on.config.repo)
        assertEquals(UpdateVisibility.PUBLIC, on.config.visibility)
        assertNull(on.config.localDir)
        assertEquals(24, on.config.checkIntervalHours)
        assertEquals(4, on.warnings.size, on.warnings.toString())
        props("source=ftp\n")
        assertIs<UpdateSettings.Enabled>(resolve())
    }

    @Test
    fun `auto check off is the one user setting`() {
        assertFalse(assertIs<UpdateSettings.Enabled>(resolve(config = { if (it == UpdateSettings.KEY_AUTO) "false" else null })).config.autoCheck)
        assertTrue(assertIs<UpdateSettings.Enabled>(resolve(config = { if (it == UpdateSettings.KEY_AUTO) "true" else null })).config.autoCheck)
    }

    @Test
    fun `a dev build never updates even with a manifest repo`() {
        assertIs<UpdateSettings.Disabled>(UpdateSettings.resolve(installed = false, version = "1.0.6", stateDir = temp, setting = { null }, manifest = { official[it] }))
    }

    private var checks = 0
    private val source = object : UpdateSource {
        override fun check(): UpdateCheck { checks++; return UpdateCheck.UpToDate(SemVer.parse("1.0.6")!!, SemVer.parse("1.0.6")!!) }
        override fun download(update: AvailableUpdate, into: File): Fetched<VerifiedInstaller> = error("not used")
        override fun expectedSha256(update: AvailableUpdate): Fetched<String> = error("not used")
    }

    private fun enabled(hours: Int = 24, auto: Boolean = true) =
        UpdateSettings.Enabled(UpdateConfig("o/r", SemVer.parse("1.0.6")!!, checkIntervalHours = hours, autoCheck = auto))

    @Test
    fun `focus checks again only once the interval has passed`() = runBlocking {
        var now = 1_000_000L
        fun service(settings: UpdateSettings) = UpdateService(settings, scope, updaterFor = { source }, handoff = {}, exit = {}, log = {}, clock = { now })
        val s = service(enabled())
        assertNotNull(s.check(manual = false)).join() // the start-up check
        assertEquals(1, checks)
        assertNull(s.checkIfDue(), "focus before 24 h is no check")
        now += 23 * 3_600_000L
        assertNull(s.checkIfDue())
        now += 3_600_000L
        assertNotNull(s.checkIfDue()).join()
        assertEquals(2, checks)
        assertNull(s.checkIfDue(), "the clock restarts at each check")

        now += 1_000 * 3_600_000L
        assertNull(service(enabled(hours = 0)).checkIfDue(), "0 hours = only at start and by hand")
        assertNull(service(UpdateSettings.Disabled("off")).checkIfDue())
    }

    @Test
    fun `auto check off skips start-up and focus checks but a manual check still runs`() = runBlocking {
        val s = UpdateService(enabled(auto = false), scope, updaterFor = { source }, handoff = {}, exit = {}, log = {})
        assertNull(s.check(manual = false))
        assertNull(s.checkIfDue())
        assertNotNull(s.check(manual = true)).join()
        assertEquals(1, checks)
    }

    @Test
    fun `the window re-reads its settings before each check, so a fix applies without a restart`() = runBlocking {
        var current: UpdateSettings = UpdateSettings.Disabled("no update repo")
        val s = UpdateService(current, scope, updaterFor = { source }, handoff = {}, exit = {}, log = {}, reload = { current })
        assertNull(s.check(manual = true))
        assertIs<UpdateState.Message>(s.state.value)
        current = enabled()
        assertNotNull(s.check(manual = true)).join()
        assertEquals(1, checks)
        assertIs<UpdateState.UpToDate>(s.state.value)
    }
}
