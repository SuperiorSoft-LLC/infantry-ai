// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.sound

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JvmSoundPlayerTest {
    private val bundled = File("src/commonMain/composeResources/files/incoming-comms.mp3")

    @Test fun `the bundled default decodes to pcm`() {
        val pcm = JvmSoundPlayer.decode(bundled.readBytes())
        assertTrue(pcm.bytes.size > 1000 && pcm.sampleRate > 0 && pcm.channels in 1..2)
    }

    @Test fun `garbage is not decodable and play returns false`() {
        assertFailsWith<Throwable> { JvmSoundPlayer.decode(ByteArray(300) { (it * 7).toByte() }) }
        assertFalse(JvmSoundPlayer().play(ByteArray(300) { (it * 7).toByte() }, 80))
    }

    @Test fun `volume scales the samples`() {
        val pcm = byteArrayOf(0x00, 0x40, 0x00, 0xC0.toByte()) // 16384, -16384
        assertContentEquals(pcm, JvmSoundPlayer.scale(pcm, 100))
        assertContentEquals(byteArrayOf(0x00, 0x20, 0x00, 0xE0.toByte()), JvmSoundPlayer.scale(pcm, 50))
        assertContentEquals(ByteArray(4), JvmSoundPlayer.scale(pcm, 0))
    }

    @Test fun `the custom store keeps a copy, survives a missing file and clears`() {
        val dir = kotlin.io.path.createTempDirectory("snd").toFile()
        val store = FileCustomSoundStore(File(dir, "sub/custom.mp3"))
        assertNull(store.load())
        assertTrue(store.save(byteArrayOf(1, 2, 3)))
        assertEquals(listOf<Byte>(1, 2, 3), store.load()!!.toList())
        store.clear()
        assertNull(store.load())
        dir.deleteRecursively()
    }
}
