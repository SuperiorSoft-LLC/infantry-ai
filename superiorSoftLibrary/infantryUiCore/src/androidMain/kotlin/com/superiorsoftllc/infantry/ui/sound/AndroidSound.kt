// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.sound

import android.content.Context
import android.media.MediaPlayer
import java.io.File

/** Android mp3 playback with the platform MediaPlayer: no dependency. A bad file fails at the synchronous prepare, so it returns false. */
class AndroidSoundPlayer(context: Context) : SoundPlayer {
    private val cache = context.applicationContext.cacheDir

    override fun play(mp3: ByteArray, volumePercent: Int): Boolean = runCatching {
        val temp = File.createTempFile("incoming-comms", ".mp3", cache).apply { writeBytes(mp3) }
        val player = MediaPlayer()
        try {
            player.setDataSource(temp.absolutePath)
            player.prepare()
            val v = volumePercent.coerceIn(0, 100) / 100f
            player.setVolume(v, v)
            player.setOnCompletionListener { it.release(); temp.delete() }
            player.setOnErrorListener { mp, _, _ -> mp.release(); temp.delete(); true }
            player.start()
        } catch (t: Throwable) {
            runCatching { player.release() }
            temp.delete()
            throw t
        }
        true
    }.getOrDefault(false)
}

/** The owner's own sound as a copy in the app's private files dir; every failure is a false / null. */
class AndroidCustomSoundStore(context: Context) : CustomSoundStore {
    private val file = File(context.applicationContext.filesDir, "sounds/incoming-comms-custom.mp3")

    override fun save(bytes: ByteArray): Boolean = runCatching {
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        true
    }.getOrDefault(false)

    override fun load(): ByteArray? = runCatching { file.takeIf { it.isFile }?.readBytes() }.getOrNull()

    override fun clear() { runCatching { file.delete() } }
}
