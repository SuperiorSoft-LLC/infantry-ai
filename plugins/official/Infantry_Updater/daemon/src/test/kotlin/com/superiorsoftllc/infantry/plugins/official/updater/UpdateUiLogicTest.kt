// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.updater

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** UPDATE-3/4: footer states, the toast and dialog flags, the running-agents guard, and channel/source selection. */
class UpdateUiLogicTest {
    private val v = SemVer.parse("1.0.3")!!

    private fun update(latest: String) = AvailableUpdate(v, SemVer.parse(latest)!!, GitHubRelease(tagName = "v$latest", body = "n", assets = emptyList()), ReleaseAsset("AgentHQ-$latest.msi", 1, url = "x"), null)

    @Test
    fun `every state has a footer label`() {
        assertEquals("Up to date (1.0.3)", UpdateState.Idle.footerLabel("1.0.3"))
        assertEquals("Checking for updates…", UpdateState.Checking(false).footerLabel(null))
        assertEquals("Update available v1.0.4", UpdateState.Available(update("1.0.4")).footerLabel(null))
        // UPDATE-UX-1: a download in the background is the bottom-right card's, not the footer's ("Downloading v1.0.7…" is gone).
        assertNull(UpdateState.Downloading(update("1.0.4")).footerLabel(null))
        assertNull(UpdateState.Restarting(update("1.0.4")).footerLabel(null))
        assertNull(UpdateState.DownloadFailed(update("1.0.4"), "x").footerLabel(null))
        val ready = UpdateState.Ready(update("1.0.4"), VerifiedInstaller(update("1.0.4"), File("x.msi"), "s", true))
        assertNull(ready.footerLabel(null, indicatorVisible = true))
        assertEquals("Ready to install v1.0.4", ready.footerLabel(null, indicatorVisible = false))
        assertTrue(UpdateState.Message("offline").footerLabel(null)!!.startsWith("Update error"))
    }

    @Test
    fun `the bottom-right card says what the download is doing`() {
        val u = update("1.0.7")
        assertEquals("Downloading 1.0.7 - 45%", UpdateState.Downloading(u, 45, 100).indicatorLabel())
        assertEquals("Downloading 1.0.7…", UpdateState.Downloading(u, 0, 0).indicatorLabel())
        assertEquals(100, UpdateState.Downloading(u, 150, 100).percent)
        assertEquals("Ready to install 1.0.7", UpdateState.Ready(u, VerifiedInstaller(u, File("x.msi"), "s", true)).indicatorLabel())
        assertEquals("Update 1.0.7 failed. offline", UpdateState.DownloadFailed(u, "offline").indicatorLabel())
        assertNull(UpdateState.Available(u).indicatorLabel())
        assertNull(UpdateState.Idle.indicatorLabel())
    }

    /** A releases folder holding AgentHQ-1.0.4.msi ([body]) and a SHA256SUMS that names [sumOf]'s hash. */
    private fun releases(body: String = "m", sumOf: String = body): File {
        val dir = Files.createTempDirectory("rel").toFile()
        File(dir, "AgentHQ-1.0.4.msi").writeText(body)
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(sumOf.toByteArray()).joinToString("") { "%02x".format(it) }
        File(dir, "SHA256SUMS").writeText("$sha  AgentHQ-1.0.4.msi\n")
        return dir
    }

    @Test
    fun `Update closes the prompt, downloads in the background, then Ready waits for Restart now`() = runBlocking<Unit> {
        val dir = releases()
        val scope = CoroutineScope(SupervisorJob())
        val handed = mutableListOf<VerifiedInstaller>()
        var exits = 0
        val logged = mutableListOf<String>()
        val s = UpdateService(UpdateSettings.Enabled(UpdateConfig("local/releases", v, localDir = dir)), scope,
            handoff = { handed += it }, exit = { exits++ }, log = { logged += it })
        s.check(manual = false)!!.join()
        s.openDialog()
        assertTrue(s.dialogOpen.value)
        val job = s.update()!!
        // The window is never held: the prompt closes at once and the card shows the download.
        assertFalse(s.dialogOpen.value)
        assertFalse(s.toastVisible.value)
        assertTrue(s.indicatorVisible.value)
        job.join()
        val ready = assertIs<UpdateState.Ready>(s.state.value)
        assertTrue(ready.installer.checksumVerified)
        assertEquals(0, handed.size, "nothing installs before Restart now")
        assertTrue(logged.any { it.startsWith("download start") } && logged.any { it.startsWith("verify ok") }, logged.joinToString(" | "))
        // Later hides the card, the update stays ready, the footer brings the card back; no new check while ready.
        s.dismiss()
        assertFalse(s.indicatorVisible.value)
        assertIs<UpdateState.Ready>(s.state.value)
        assertEquals("Ready to install v1.0.4", s.state.value.footerLabel(null, s.indicatorVisible.value))
        assertNull(s.checkIfDue())
        s.showIndicator()
        assertTrue(s.indicatorVisible.value)
        s.install()!!.join()
        assertIs<UpdateState.Restarting>(s.state.value)
        assertEquals(1, handed.size)
        assertEquals(1, exits)
        assertTrue(logged.any { it.startsWith("install: launching msiexec") })
        scope.cancel()
        dir.deleteRecursively()
    }

    @Test
    fun `a failed download shows the reason with Retry, and Retry downloads again`() = runBlocking<Unit> {
        val dir = releases(body = "tampered", sumOf = "good")
        val scope = CoroutineScope(SupervisorJob())
        val s = UpdateService(UpdateSettings.Enabled(UpdateConfig("local/releases", v, localDir = dir)), scope, handoff = {}, exit = {}, log = {})
        s.check(manual = false)!!.join()
        s.update()!!.join()
        val failed = assertIs<UpdateState.DownloadFailed>(s.state.value)
        assertTrue("SHA-256 mismatch" in failed.text, failed.text)
        assertTrue(s.indicatorVisible.value)
        // The release is fixed; Retry gets it.
        File(dir, "AgentHQ-1.0.4.msi").writeText("good")
        s.retry()!!.join()
        assertIs<UpdateState.Ready>(s.state.value)
        scope.cancel()
        dir.deleteRecursively()
    }

    @Test
    fun `Dismiss on a failed download goes back to update available`() = runBlocking<Unit> {
        val dir = releases(body = "tampered", sumOf = "good")
        val scope = CoroutineScope(SupervisorJob())
        val s = UpdateService(UpdateSettings.Enabled(UpdateConfig("local/releases", v, localDir = dir)), scope, handoff = {}, exit = {}, log = {})
        s.check(manual = false)!!.join()
        s.update()!!.join()
        assertIs<UpdateState.DownloadFailed>(s.state.value)
        s.dismiss()
        assertIs<UpdateState.Available>(s.state.value)
        scope.cancel()
        dir.deleteRecursively()
    }

    @Test
    fun `an installer that cannot start is a failure with Retry, and the app does not exit`() = runBlocking<Unit> {
        val dir = releases()
        val scope = CoroutineScope(SupervisorJob())
        var exits = 0
        val s = UpdateService(UpdateSettings.Enabled(UpdateConfig("local/releases", v, localDir = dir)), scope,
            handoff = { error("msiexec missing") }, exit = { exits++ }, log = {})
        s.check(manual = false)!!.join()
        s.update()!!.join()
        s.install()!!.join()
        val failed = assertIs<UpdateState.DownloadFailed>(s.state.value)
        assertTrue("msiexec missing" in failed.text, failed.text)
        assertEquals(0, exits)
        scope.cancel()
        dir.deleteRecursively()
    }

    @Test
    fun `the install prompt names running agents and defaults to Cancel, and is silent with none`() {
        assertEquals("3 agents running, update anyway?", InstallPrompt(3).message)
        assertEquals("1 agent running, update anyway?", InstallPrompt(1).message)
        assertTrue(InstallPrompt(1).defaultsToCancel)
        assertNull(InstallPrompt(0).message)
        assertFalse(InstallPrompt(0).defaultsToCancel)
    }

    @Test
    fun `a found update raises the toast, Later keeps the footer state, the dialog opens from the footer`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("rel").toFile()
        File(dir, "AgentHQ-1.0.4.msi").writeText("m")
        val bytes = "m".toByteArray()
        File(dir, "SHA256SUMS").writeText("${java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }}  AgentHQ-1.0.4.msi\n")
        val scope = CoroutineScope(SupervisorJob())
        val s = UpdateService(UpdateSettings.Enabled(UpdateConfig("local/releases", v, localDir = dir)), scope, handoff = {}, exit = {}, runningAgents = { 2 }, log = {})
        s.check(manual = false)!!.join()
        assertIs<UpdateState.Available>(s.state.value)
        assertTrue(s.toastVisible.value)
        assertEquals("2 agents running, update anyway?", s.installPrompt().message)
        s.openDialog()
        assertTrue(s.dialogOpen.value)
        assertFalse(s.toastVisible.value)
        s.dismiss()
        assertFalse(s.dialogOpen.value)
        assertIs<UpdateState.Available>(s.state.value)
        scope.cancel()
        dir.deleteRecursively()
    }

    private fun resolve(props: String, env: Map<String, String> = emptyMap()): UpdateSettings {
        val state = Files.createTempDirectory("st").toFile()
        File(state, UpdateSettings.FILE_NAME).writeText(props)
        // The overrides alone: no build manifest (UPDATER-DEFAULT-1 has its own tests).
        return UpdateSettings.resolve(true, "1.0.3", state, setting = { null }, env = { env[it] }, credentialStore = { null }, manifest = { null })
    }

    @Test
    fun `source and channel pick local or GitHub`() {
        assertEquals(File("C:/rel"), (resolve("channel=unstable\ndir=C:/rel\nrepo=a/b") as UpdateSettings.Enabled).config.localDir)
        assertNull((resolve("channel=production\ndir=C:/rel\nrepo=a/b") as UpdateSettings.Enabled).config.localDir)
        assertNull((resolve("source=github\ndir=C:/rel\nrepo=a/b") as UpdateSettings.Enabled).config.localDir)
        assertEquals(File("C:/rel"), (resolve("source=local\ndir=C:/rel\nrepo=a/b") as UpdateSettings.Enabled).config.localDir)
        assertEquals(File("C:/rel"), (resolve("dir=C:/rel") as UpdateSettings.Enabled).config.localDir)
        assertIs<UpdateSettings.Disabled>(resolve("source=local"))
        // UPDATER-DEFAULT-1 (owner): an unknown channel or source is ignored with a warning, never "updates off".
        assertEquals("a/b", assertIs<UpdateSettings.Enabled>(resolve("channel=nightly\nrepo=a/b")).config.repo)
        assertEquals("a/b", assertIs<UpdateSettings.Enabled>(resolve("source=ftp\nrepo=a/b")).config.repo)
        assertIs<UpdateSettings.Disabled>(resolve("channel=production"))
    }

    @Test
    fun `the factory gives a LocalDirSource or the GitHub Updater`() {
        assertIs<LocalDirSource>(UpdateSources.forConfig(UpdateConfig("x/y", v, localDir = File("d"))))
        assertIs<Updater>(UpdateSources.forConfig(UpdateConfig("x/y", v)))
    }
}
