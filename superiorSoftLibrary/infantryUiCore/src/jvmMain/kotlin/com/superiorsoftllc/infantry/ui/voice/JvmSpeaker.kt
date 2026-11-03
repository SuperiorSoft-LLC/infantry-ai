// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.voice

import java.io.File

/**
 * VOICE-1: desktop text-to-speech with no library. Windows runs SAPI through `wscript` (a GUI-subsystem host, so no
 * console window flashes); macOS uses `say`; Linux `spd-say` or `espeak`. When nothing can be started [speak] is false
 * and the caller falls back to [beep]. Only the text it is given is ever passed on.
 */
class JvmSpeaker(private val osName: String = System.getProperty("os.name").orEmpty()) : Speaker {
    private val windows = osName.startsWith("Windows", ignoreCase = true)
    private val mac = osName.startsWith("Mac", ignoreCase = true)
    @Volatile private var current: Process? = null
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
        val previous = current
        if (previous != null && previous.isAlive) {
            if (interrupt) previous.destroy()
            else runCatching { previous.waitFor(15, java.util.concurrent.TimeUnit.SECONDS) }
        }
        val vol = volumePercent.coerceIn(0, 100)
        val voice = voiceName?.replace("\"", "")?.takeIf { it.isNotBlank() }
        val cmd: List<String> = when {
            windows -> listOf("wscript.exe", "//B", "//Nologo", (script ?: return false).absolutePath, safe, vol.toString()) + listOfNotNull(voice)
            mac -> listOf("say") + listOfNotNull(voice?.let { "-v" }, voice) + safe
            else -> listOf(if (onPath("spd-say")) "spd-say" else "espeak", "-v".takeIf { voice != null }, voice, safe).filterNotNull()
        }
        return runCatching {
            current = ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            true
        }.getOrDefault(false)
    }

    override fun beep() {
        runCatching { java.awt.Toolkit.getDefaultToolkit().beep() }
    }

    private fun onPath(exe: String): Boolean =
        System.getenv("PATH").orEmpty().split(File.pathSeparator).any { it.isNotBlank() && File(it, exe).canExecute() }
}
