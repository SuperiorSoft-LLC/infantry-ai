// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.voice

import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * VOICE-1: desktop text-to-speech with no library. Windows runs SAPI through `wscript` (a GUI-subsystem host, so no
 * console window flashes); macOS uses `say`; Linux `spd-say` or `espeak`. When nothing can be started [speak] is false
 * and the caller falls back to [beep]. Only the text it is given is ever passed on.
 *
 * READALOUD-TRUNC-1: speech is a queue. `interrupt = false` plays after what is being said, and [speak] never blocks the
 * caller (it is called from the UI thread). Text is passed on the command line, so text longer than [MAX_PIECE_CHARS]
 * is split at sentence or word ends into pieces that play one after another: nothing is cut off.
 */
class JvmSpeaker(
    private val osName: String = System.getProperty("os.name").orEmpty(),
    private val start: (List<String>) -> Process = { cmd ->
        ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
    },
) : Speaker {
    private val windows = osName.startsWith("Windows", ignoreCase = true)
    private val mac = osName.startsWith("Mac", ignoreCase = true)
    @Volatile private var current: Process? = null
    /** Bumped by an interrupt or [stop]: queued pieces of an older generation are dropped. */
    @Volatile private var generation = 0
    /** Queued pieces not finished yet (started or waiting). */
    private val pending = AtomicInteger(0)
    private val worker = ThreadPoolExecutor(0, 1, 2, TimeUnit.SECONDS, LinkedBlockingQueue()) { r ->
        Thread(r, "os-voice").apply { isDaemon = true }
    }
    private val script: File? by lazy {
        if (!windows) null else runCatching {
            File.createTempFile("ahq-say", ".vbs").also {
                it.deleteOnExit()
                it.writeText(
                    "Set v = CreateObject(\"SAPI.SpVoice\")\r\n" +
                        "If WScript.Arguments.Count > 2 Then\r\n" +
                        "  For Each t In v.GetVoices\r\n" +
                        "    If t.GetDescription = WScript.Arguments(2) Then Set v.Voice = t\r\n" +
                        "  Next\r\n" +
                        "End If\r\n" +
                        "v.Volume = CInt(WScript.Arguments(1))\r\n" +
                        "v.Speak WScript.Arguments(0)\r\n",
                )
            }
        }.getOrNull()
    }

    @Synchronized
    override fun speak(text: String, volumePercent: Int, voiceName: String?, interrupt: Boolean): Boolean {
        val safe = text.replace("\"", "'").replace(Regex("[\r\n]+"), " ").trim()
        if (safe.isEmpty()) return false
        val vol = volumePercent.coerceIn(0, 100)
        val voice = voiceName?.replace("\"", "")?.takeIf { it.isNotBlank() }
        if (windows && script == null) return false
        val pieces = speechPieces(safe, MAX_PIECE_CHARS)
        if (interrupt) { generation++; current?.takeIf { it.isAlive }?.destroy() }
        val mine = generation
        // Nothing playing (or an interrupt): the first piece starts now, so a voice that cannot start answers false.
        val rest = if (interrupt || (pending.get() == 0 && current?.isAlive != true)) {
            current = runCatching { start(command(pieces.first(), vol, voice)) }.getOrNull() ?: return false
            pieces.drop(1)
        } else pieces
        for (piece in rest) {
            pending.incrementAndGet()
            worker.execute {
                try {
                    if (mine != generation) return@execute
                    current?.waitFor()
                    synchronized(this) {
                        if (mine != generation) return@execute
                        current = runCatching { start(command(piece, vol, voice)) }.getOrNull()
                    }
                } finally { pending.decrementAndGet() }
            }
        }
        return true
    }

    private fun command(text: String, vol: Int, voice: String?): List<String> = when {
        windows -> listOf("wscript.exe", "//B", "//Nologo", script!!.absolutePath, text, vol.toString()) + listOfNotNull(voice)
        mac -> listOf("say") + listOfNotNull(voice?.let { "-v" }, voice) + text
        else -> listOf(if (onPath("spd-say")) "spd-say" else "espeak", "-v".takeIf { voice != null }, voice, text).filterNotNull()
    }

    /** Stops what is being said and drops what is queued. */
    @Synchronized
    override fun stop() {
        generation++
        current?.takeIf { it.isAlive }?.destroy()
    }

    override fun beep() {
        runCatching { java.awt.Toolkit.getDefaultToolkit().beep() }
    }

    private fun onPath(exe: String): Boolean =
        System.getenv("PATH").orEmpty().split(File.pathSeparator).any { it.isNotBlank() && File(it, exe).canExecute() }

    /** Waits until nothing is queued or speaking (tests). */
    internal fun awaitIdle(timeoutMs: Long): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (pending.get() == 0 && current?.isAlive != true) return true
            Thread.sleep(20)
        }
        return false
    }

    companion object {
        /**
         * The longest text passed to one voice process. A platform bound, not a preference: Windows caps a whole command
         * line at 32,767 characters (wscript and the script path included), so a piece stays well under it.
         */
        const val MAX_PIECE_CHARS = 8_000
    }
}

/**
 * [text] in pieces of at most [max] characters, split after a sentence end (`.`, `!`, `?`) when one falls in the second
 * half of the piece, else at the last space, else hard. Only the whitespace at a cut is dropped; no word is lost.
 */
internal fun speechPieces(text: String, max: Int): List<String> {
    val limit = max.coerceAtLeast(1)
    val out = mutableListOf<String>()
    var rest = text.trim()
    while (rest.length > limit) {
        val window = rest.substring(0, limit + 1)
        val sentence = Regex("[.!?…][\"')\\]]*\\s").findAll(window).lastOrNull()?.let { it.range.last }
        val cut = sentence?.takeIf { it > limit / 2 } ?: window.lastIndexOf(' ').takeIf { it > 0 } ?: limit
        out += rest.substring(0, cut).trim()
        rest = rest.substring(cut).trim()
    }
    if (rest.isNotEmpty()) out += rest
    return out
}
