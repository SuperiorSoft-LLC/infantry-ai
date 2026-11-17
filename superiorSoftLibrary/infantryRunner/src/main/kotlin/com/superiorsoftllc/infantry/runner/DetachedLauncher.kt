// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.runner

import com.sun.jna.Memory
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinDef
import java.io.File

/**
 * RUN-TAB-1: starts a process that OUTLIVES its caller and the daemon: no pipes (nothing to drain, nothing to hold open),
 * and on Windows outside the daemon's kill-on-close job ([ChildReaper]) through CREATE_BREAKAWAY_FROM_JOB, with a hidden console (a console tool such as powershell needs one; a GUI app is unaffected)
 * of its own. Opt-in per saved command; every other start is unchanged. Returns the new pid, or throws with the OS error.
 */
object DetachedLauncher {
    private const val CREATE_UNICODE_ENVIRONMENT = 0x0000_0400
    private const val CREATE_NEW_PROCESS_GROUP = 0x0000_0200
    private const val CREATE_NO_WINDOW = 0x0800_0000
    private const val CREATE_BREAKAWAY_FROM_JOB = 0x0100_0000
    private const val ERROR_ACCESS_DENIED = 5

    private val windows = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    fun launch(argv: List<String>, dir: File?, env: Map<String, String>): Long =
        if (windows) launchWindows(argv, dir, env) else launchPosix(argv, dir, env)

    private fun launchPosix(argv: List<String>, dir: File?, env: Map<String, String>): Long {
        val b = ProcessBuilder(argv)
        dir?.let { b.directory(it) }
        b.environment().putAll(env)
        b.redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
        b.redirectOutput(ProcessBuilder.Redirect.DISCARD)
        b.redirectError(ProcessBuilder.Redirect.DISCARD)
        return b.start().pid()
    }

    private fun launchWindows(argv: List<String>, dir: File?, env: Map<String, String>): Long {
        val k32 = Kernel32.INSTANCE
        val cmd = (windowsCommandLine(argv) + "\u0000").toCharArray()
        val block = if (env.isEmpty()) null else environmentBlock(env)
        var flags = CREATE_UNICODE_ENVIRONMENT or CREATE_NEW_PROCESS_GROUP or CREATE_NO_WINDOW
        for (breakaway in listOf(true, false)) {
            val si = WinBase.STARTUPINFO()
            val pi = WinBase.PROCESS_INFORMATION()
            val ok = k32.CreateProcessW(
                null, cmd.copyOf(), null, null, false,
                WinDef.DWORD((flags or if (breakaway) CREATE_BREAKAWAY_FROM_JOB else 0).toLong()),
                block, dir?.path, si, pi,
            )
            if (ok) {
                val pid = pi.dwProcessId.toLong()
                k32.CloseHandle(pi.hProcess)
                k32.CloseHandle(pi.hThread)
                return pid
            }
            val err = k32.GetLastError()
            // A job that forbids breakaway refuses the flag: start inside it rather than not at all.
            if (!(breakaway && err == ERROR_ACCESS_DENIED)) throw IllegalStateException("CreateProcess failed (error $err)")
        }
        throw IllegalStateException("CreateProcess failed")
    }

    /** The current environment plus [extra], as a UTF-16 block (name=value NUL ... NUL NUL), sorted as Windows wants. */
    private fun environmentBlock(extra: Map<String, String>): Memory {
        val all = (System.getenv() + extra).entries.sortedBy { it.key.uppercase() }
        val text = all.joinToString("") { "${it.key}=${it.value}\u0000" } + "\u0000"
        val bytes = text.toByteArray(Charsets.UTF_16LE)
        return Memory(bytes.size.toLong() + 2).also { it.write(0, bytes, 0, bytes.size); it.setShort(bytes.size.toLong(), 0) }
    }
}
