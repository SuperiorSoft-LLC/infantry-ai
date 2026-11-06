// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.infantry.plugins.official.updater.AvailableUpdate
import com.superiorsoftllc.infantry.plugins.official.updater.DownloadProgress
import com.superiorsoftllc.infantry.plugins.official.updater.Fetched
import com.superiorsoftllc.infantry.plugins.official.updater.GitHubRelease
import com.superiorsoftllc.infantry.plugins.official.updater.ReleaseAsset
import com.superiorsoftllc.infantry.plugins.official.updater.SemVer
import com.superiorsoftllc.infantry.plugins.official.updater.UpdateCheck
import com.superiorsoftllc.infantry.plugins.official.updater.UpdateConfig
import com.superiorsoftllc.infantry.plugins.official.updater.UpdateFailure
import com.superiorsoftllc.infantry.plugins.official.updater.UpdateService
import com.superiorsoftllc.infantry.plugins.official.updater.UpdateSettings
import com.superiorsoftllc.infantry.plugins.official.updater.UpdateSource
import com.superiorsoftllc.infantry.plugins.official.updater.UpdateState
import com.superiorsoftllc.infantry.plugins.official.updater.VerifiedInstaller
import com.superiorsoftllc.infantry.ui.model.ThemeSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertIs

/**
 * UPDATE-UX-1: the update UI never holds the window. Update closes the prompt and the download shows in a bottom-right
 * card ("Downloading 1.0.7 - 45%" with a bar), then "Ready to install" (Restart now / Later) or a failure with Retry.
 * Writes each state to build/update-shots/<name>.png so the colours can be looked at.
 */
@OptIn(ExperimentalTestApi::class)
class UpdatePromptUiTest {
    private val scope = CoroutineScope(SupervisorJob())
    private val gate = CountDownLatch(1)
    @Volatile private var fail: Boolean = false
    private val v5 = SemVer.parse("1.0.5")!!
    private val v7 = SemVer.parse("1.0.7")!!

    @AfterTest
    fun stop() { gate.countDown(); scope.cancel() }

    /** A source that offers 1.0.7, reports 45% and then waits on [gate]; ends Ok, or failed when [fail]. */
    private inner class HeldSource : UpdateSource {
        val installer = ReleaseAsset("InfantryAI-1.0.7.msi", 100, url = "x")
        val update = AvailableUpdate(v5, v7, GitHubRelease("v1.0.7", body = "**Full Changelog**: v1.0.6...v1.0.7"), installer, null)
        override fun check(): UpdateCheck = UpdateCheck.Available(update)
        override fun expectedSha256(update: AvailableUpdate): Fetched<String> = Fetched.Ok("0")
        override fun download(update: AvailableUpdate, into: File): Fetched<VerifiedInstaller> = download(update, into, DownloadProgress.NONE)
        override fun download(update: AvailableUpdate, into: File, progress: DownloadProgress): Fetched<VerifiedInstaller> {
            progress.bytes(45, 100)
            gate.await(30, TimeUnit.SECONDS)
            return if (fail) Fetched.Failed(UpdateFailure.Offline("download stalled: no data for 60 s after 45 of 100 bytes"))
            else Fetched.Ok(VerifiedInstaller(update, File(into, installer.name), "ab12", checksumVerified = true))
        }
    }

    private fun service(): UpdateService {
        val source = HeldSource()
        return UpdateService(UpdateSettings.Enabled(UpdateConfig("o/r", v5)), scope, updaterFor = { source }, handoff = {}, exit = {}, log = {})
    }

    private fun androidx.compose.ui.test.ComposeUiTest.shot(name: String) {
        waitForIdle()
        val dir = File("build/update-shots").apply { mkdirs() }
        ImageIO.write(onAllNodes(isRoot())[0].captureToImage().toAwtImage(), "png", File(dir, "$name.png"))
    }

    @Test
    fun `Update closes the prompt and the bottom-right card shows progress, then Ready with Restart now`() {
        Palette.theme = ThemeSettings(dark = true)
        val s = service()
        runBlocking { s.check(manual = false)!!.join() }
        runDesktopComposeUiTest(900, 500) {
            setContent { Box(Modifier.fillMaxSize().background(Palette.Bg)) { UpdatePrompt(s) } }
            onNodeWithText("Infantry AI 1.0.7 is available.").assertExists()
            shot("1-available-toast")
            onNodeWithText("View").performClick()
            onNodeWithTag(UPDATE_DIALOG_TAG).assertExists()
            onNodeWithTag(UPDATE_NOW_TAG).performClick()
            waitUntil(timeoutMillis = 5_000) { (s.state.value as? UpdateState.Downloading)?.percent == 45 }
            waitForIdle()
            // Not modal: the dialog is gone while the download runs; the card carries the progress.
            onNodeWithTag(UPDATE_DIALOG_TAG).assertDoesNotExist()
            onNodeWithText("Downloading 1.0.7 - 45%").assertExists()
            onNodeWithTag(UPDATE_FOOTER_TAG).assertDoesNotExist()
            shot("2-downloading")
            gate.countDown()
            waitUntil(timeoutMillis = 5_000) { s.state.value is UpdateState.Ready }
            waitForIdle()
            onNodeWithText("Ready to install 1.0.7").assertExists()
            onNodeWithTag(UPDATE_RESTART_TAG).assertExists()
            shot("3-ready")
            onNodeWithTag(UPDATE_LATER_CARD_TAG).performClick()
            waitForIdle()
            onNodeWithText("Ready to install 1.0.7").assertDoesNotExist()
            onNodeWithText("Ready to install v1.0.7").assertExists()
        }
        Palette.theme = ThemeSettings()
    }

    @Test
    fun `a failed download shows the reason and Retry`() {
        Palette.theme = ThemeSettings(dark = true)
        fail = true
        val s = service()
        runBlocking { s.check(manual = false)!!.join() }
        gate.countDown()
        runBlocking { s.update()!!.join() }
        assertIs<UpdateState.DownloadFailed>(s.state.value)
        runDesktopComposeUiTest(900, 500) {
            setContent { Box(Modifier.fillMaxSize().background(Palette.Bg)) { UpdatePrompt(s) } }
            onNodeWithTag(UPDATE_RETRY_TAG).assertExists()
            shot("4-failed")
        }
        Palette.theme = ThemeSettings()
    }
}
