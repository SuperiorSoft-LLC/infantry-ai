// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.plugins.official.chat.*
import com.superiorsoftllc.infantry.plugins.official.chatfeed.*

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.infantry.ui.model.LineKind
import com.superiorsoftllc.infantry.ui.model.OutputLine
import com.superiorsoftllc.infantry.ui.model.UiFlags
import com.superiorsoftllc.infantry.ui.model.UserPortrait
import com.superiorsoftllc.infantry.ui.shell.LocalPlatformHost
import com.superiorsoftllc.infantry.ui.shell.PlatformHost
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** USER-PORTRAIT: the human's chat avatar is the bundled portrait, an uploaded one replaces it, reset restores it. */
@OptIn(ExperimentalTestApi::class)
class UserPortraitUiTest {
    private fun source(node: SemanticsNodeInteraction): String? =
        node.fetchSemanticsNode().config.getOrNull(UserPortraitSource)

    private fun pngBase64(): String {
        val surface = Surface.makeRasterN32Premul(16, 16)
        surface.canvas.clear(0xFF3366CC.toInt())
        return UserPortrait.encode(surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes)
    }

    @Test
    fun defaultShowsThenUploadReplacesItThenResetRestoresIt() {
        runDesktopComposeUiTest(900, 400) {
            var portrait by mutableStateOf<String?>(null)
            setContent {
                CompositionLocalProvider(LocalUserPortrait provides portrait) {
                    ChatLine(OutputLine(LineKind.USER, "hi there", 0), agentId = "agent-1", agentName = "Scrum Master")
                }
            }
            waitUntil(timeoutMillis = 5_000) { source(onNodeWithContentDescription("your portrait")) == USER_PORTRAIT_DEFAULT }
            onNodeWithText("Y").assertDoesNotExist() // the bundled picture decoded: no letter placeholder

            portrait = pngBase64()
            waitUntil(timeoutMillis = 5_000) { source(onNodeWithContentDescription("your portrait")) == USER_PORTRAIT_CUSTOM }

            portrait = null // reset to default
            waitUntil(timeoutMillis = 5_000) { source(onNodeWithContentDescription("your portrait")) == USER_PORTRAIT_DEFAULT }
        }
    }

    private class FakeHost(val size: Long, val file: ByteArray?) : PlatformHost {
        override val defaultDaemonUrl = "ws://x"
        override fun setting(key: String): String? = null
        override fun readTextFile(path: String): String? = null
        override val canPickFiles = true
        override fun pickFile(title: String): String = "picked.png"
        override fun fileSize(path: String): Long = size
        override fun readBytes(path: String): ByteArray? = file
        override fun portraitPng(bytes: ByteArray, side: Int): ByteArray? = bytes
    }

    private fun config(host: PlatformHost, saved: MutableList<ByteArray?>, portrait: String? = null, body: ComposeUiTest.() -> Unit) =
        runDesktopComposeUiTest(900, 900) {
            setContent {
                CompositionLocalProvider(LocalPlatformHost provides host) {
                    Column { ConfigDialog(UiFlags(), onFlags = {}, onDismiss = {}, userPortrait = portrait to { png -> saved += png }) }
                }
            }
            body()
        }

    @Test
    fun anOversizeFileIsRefusedWithAMessageAndNothingIsSaved() {
        val saved = mutableListOf<ByteArray?>()
        config(FakeHost(6L * 1024 * 1024, null), saved) {
            onNodeWithTag(PORTRAIT_CHANGE_TAG).performScrollTo().performClick()
            waitForIdle()
            onNodeWithTag(PORTRAIT_ERROR_TAG).assertTextContains("limit is 5 MB", substring = true)
        }
        assertEquals(0, saved.size)
    }

    @Test
    fun aNonImageIsRefusedAndAValidOneIsSavedAndResetSendsNull() {
        val saved = mutableListOf<ByteArray?>()
        config(FakeHost(5, "hello".encodeToByteArray()), saved) {
            onNodeWithTag(PORTRAIT_CHANGE_TAG).performScrollTo().performClick()
            waitForIdle()
            onNodeWithTag(PORTRAIT_ERROR_TAG).assertTextContains("PNG, JPEG and WebP", substring = true)
        }
        assertEquals(0, saved.size)

        val png = UserPortrait.decode(pngBase64())!!
        config(FakeHost(png.size.toLong(), png), saved, portrait = pngBase64()) {
            onNodeWithTag(PORTRAIT_CHANGE_TAG).performScrollTo().performClick()
            waitForIdle()
            onNodeWithTag(PORTRAIT_ERROR_TAG).assertDoesNotExist()
            onNodeWithTag(PORTRAIT_RESET_TAG).performScrollTo().performClick()
            waitForIdle()
        }
        assertEquals(2, saved.size)
        assertNotNull(saved[0])
        assertNull(saved[1], "Reset to default saves null")
    }
}
