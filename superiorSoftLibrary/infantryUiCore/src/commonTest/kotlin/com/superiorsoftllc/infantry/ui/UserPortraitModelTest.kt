// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.model.UserPortrait
import com.superiorsoftllc.infantry.ui.model.reduce
import com.superiorsoftllc.infantry.ui.protocol.Envelope
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** USER-PORTRAIT (contract 1.107.0): what a picked file may be, and how the daemon's `settings` frames set and clear it. */
class UserPortraitModelTest {
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2)
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1)
    private val webp = "RIFF....WEBPVP8 ".encodeToByteArray()
    private fun feed(s: AppState, data: String) = reduce(s, Envelope(Envelope.SETTINGS, data), 100)

    @Test fun `png jpeg and webp are accepted by their bytes, not their name`() {
        for (ok in listOf(png, jpeg, webp)) assertNull(UserPortrait.refusal(ok))
        assertContains(UserPortrait.refusal("GIF89a....".encodeToByteArray())!!, "PNG, JPEG and WebP")
        assertContains(UserPortrait.refusal(null)!!, "could not be read")
    }

    @Test fun `a file over 5 MB is refused with its size and the limit`() {
        val message = UserPortrait.refusal(null, size = 6L * 1024 * 1024)!!
        assertContains(message, "6 MB")
        assertContains(message, "limit is 5 MB")
    }

    @Test fun `nothing is set until the daemon answers, a set frame shows it, a full frame without the key clears it`() {
        assertNull(AppState().userPortrait)
        val set = feed(AppState(), """{"session.keepAliveMinutes":30,"user.portrait":"AAEC"}""")
        assertEquals("AAEC", set.userPortrait)
        assertEquals("AAEC", feed(set, """{"context.skillIds":[]}""").userPortrait, "an unrelated partial frame leaves it")
        assertNull(feed(set, """{"session.keepAliveMinutes":30,"context.skillIds":[]}""").userPortrait, "reset: the daemon's full frame no longer has the key")
    }

    @Test fun `base64 round trips`() {
        assertContentEquals(png, UserPortrait.decode(UserPortrait.encode(png)))
        assertNull(UserPortrait.decode("%%%"))
    }
}
