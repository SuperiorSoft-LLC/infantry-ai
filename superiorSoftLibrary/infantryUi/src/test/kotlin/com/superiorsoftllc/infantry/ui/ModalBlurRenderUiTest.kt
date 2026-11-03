// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.superiorsoftllc.infantry.ui.shell.JvmPlatformHost
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TEST-SPEED-4: the UI test JVMs run with `-Dinfantry.ui.blur=off` (the CPU renderer pays ~150 ms a frame for the blur).
 * This class is the one that renders the blur for real: it turns the property back ON for itself, opens a modal over a
 * sharp white stripe on black, and checks the stripe is smeared into its neighbours. The OFF case checks the switch.
 */
@OptIn(ExperimentalTestApi::class)
class ModalBlurRenderUiTest {
    private val property = "infantry.ui.blur"

    private fun <T> withBlurProperty(value: String, body: () -> T): T {
        val before = System.getProperty(property)
        System.setProperty(property, value)
        try { return body() } finally { if (before == null) System.clearProperty(property) else System.setProperty(property, before) }
    }

    /** Brightness (0..255) of the pixel [dx] columns left of the stripe, before and after a modal opens. */
    private fun besideTheStripe(supported: Boolean, dx: Int): Pair<Int, Int> {
        var result = 0 to 0
        runDesktopComposeUiTest(240, 120) {
            val state = ModalBlurState(supported = supported)
            var open by mutableStateOf(false)
            setContent {
                CompositionLocalProvider(LocalModalBlur provides state) {
                    Box(Modifier.fillMaxSize().modalBlur(state).background(Color.Black)) {
                        Box(Modifier.offset(x = 120.dp).width(4.dp).fillMaxHeight().background(Color.White))
                    }
                    if (open) RegisterModal()
                }
            }
            waitForIdle()
            val sharp = onRoot().captureToImage().toAwtImage()
            val stripe = (0 until sharp.width).first { red(sharp, it) > 200 }
            open = true
            mainClock.advanceTimeBy(1_000) // past the 180 ms fade-in
            waitForIdle()
            assertTrue(state.anyModalOpen, "the modal registered itself")
            val after = onRoot().captureToImage().toAwtImage()
            result = red(sharp, stripe - dx) to red(after, stripe - dx)
        }
        return result
    }

    private fun red(img: BufferedImage, x: Int): Int = (img.getRGB(x, img.height / 2) shr 16) and 0xFF

    @Test fun `with blur on, an open modal smears the window behind it`() = withBlurProperty("on") {
        val host = JvmPlatformHost(env = { null })
        assertTrue(host.supportsBlur, "-Dinfantry.ui.blur=on: the desktop host blurs")
        val (before, after) = besideTheStripe(host.supportsBlur, dx = 4)
        assertEquals(0, before, "no modal: the pixel beside the stripe is black")
        assertTrue(after > 10, "modal open with blur: the stripe bleeds into its neighbour (got $after)")
    }

    @Test fun `with blur off, the window behind a modal stays sharp`() = withBlurProperty("off") {
        val host = JvmPlatformHost(env = { null })
        assertFalse(host.supportsBlur, "-Dinfantry.ui.blur=off: the desktop host does not blur")
        val (before, after) = besideTheStripe(host.supportsBlur, dx = 4)
        assertEquals(0, before)
        assertEquals(0, after, "no blur: nothing bleeds")
    }
}
