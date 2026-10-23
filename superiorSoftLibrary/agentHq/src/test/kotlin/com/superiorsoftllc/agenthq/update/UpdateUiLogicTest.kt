package com.superiorsoftllc.agenthq.update

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
        assertEquals("Downloading v1.0.4…", UpdateState.Downloading(update("1.0.4")).footerLabel(null))
        assertEquals("Ready to install v1.0.4", UpdateState.Restarting(update("1.0.4")).footerLabel(null))
        assertTrue(UpdateState.Message("offline").footerLabel(null).startsWith("Update error"))
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
        return UpdateSettings.resolve(true, "1.0.3", state, setting = { null }, env = { env[it] }, credentialStore = { null })
    }

    @Test
    fun `source and channel pick local or GitHub`() {
        assertEquals(File("C:/rel"), (resolve("channel=unstable\ndir=C:/rel\nrepo=a/b") as UpdateSettings.Enabled).config.localDir)
        assertNull((resolve("channel=production\ndir=C:/rel\nrepo=a/b") as UpdateSettings.Enabled).config.localDir)
        assertNull((resolve("source=github\ndir=C:/rel\nrepo=a/b") as UpdateSettings.Enabled).config.localDir)
        assertEquals(File("C:/rel"), (resolve("source=local\ndir=C:/rel\nrepo=a/b") as UpdateSettings.Enabled).config.localDir)
        assertEquals(File("C:/rel"), (resolve("dir=C:/rel") as UpdateSettings.Enabled).config.localDir)
        assertIs<UpdateSettings.Disabled>(resolve("source=local"))
        assertIs<UpdateSettings.Disabled>(resolve("channel=nightly\nrepo=a/b"))
        assertIs<UpdateSettings.Disabled>(resolve("source=ftp\nrepo=a/b"))
        assertIs<UpdateSettings.Disabled>(resolve("channel=production"))
    }

    @Test
    fun `the factory gives a LocalDirSource or the GitHub Updater`() {
        assertIs<LocalDirSource>(UpdateSources.forConfig(UpdateConfig("x/y", v, localDir = File("d"))))
        assertIs<Updater>(UpdateSources.forConfig(UpdateConfig("x/y", v)))
    }
}
