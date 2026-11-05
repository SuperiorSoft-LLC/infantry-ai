// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.updater

import java.io.File

/**
 * UPDATE-1: hand a verified installer to Windows and restart the app on the new version.
 *
 * The running app holds its own jars and runtime open, so the MSI cannot replace them while this JVM lives. The
 * handoff writes a small `apply.cmd` next to the installer and starts it DETACHED; the script waits for this process
 * to exit, runs `msiexec /i` (a major upgrade over the fixed upgradeUuid, into the same INSTALLDIR), then starts the
 * app again. The caller exits right after [start] returns true.
 *
 * The data survives because it is not in the install dir: the installed profile keeps it in
 * `%LOCALAPPDATA%\SuperiorSoft\Infantry AI` (INSTALL-1), which no uninstall or upgrade touches.
 */
object MsiHandoff {
    const val SCRIPT_NAME = "apply.cmd"
    const val LOG_NAME = "msiexec.log"

    /** How long the script waits for the old app to exit before giving up (seconds, about one per loop). */
    const val WAIT_SECONDS = 120

    /**
     * The script's text. [pid] is the process to wait for; [installDir] keeps the upgrade in the owner's chosen folder
     * (null lets the MSI use its default); [relaunch] is the app launcher to start afterwards (null = do not restart).
     */
    fun script(pid: Long, msi: File, installDir: File?, relaunch: File?, log: File): String = buildString {
        fun q(f: File) = "\"" + f.absolutePath + "\""
        appendLine("@echo off")
        appendLine("rem Infantry AI UPDATE-1: wait for Infantry AI (pid $pid) to exit, install the new version, start it again.")
        appendLine("set /a tries=0")
        appendLine(":wait")
        appendLine("tasklist /FI \"PID eq $pid\" /NH 2>nul | find \" $pid \" >nul")
        appendLine("if errorlevel 1 goto install")
        appendLine("set /a tries+=1")
        appendLine("if %tries% geq $WAIT_SECONDS goto install")
        // `timeout` refuses to run without a console stdin; ping is the portable one-second sleep.
        appendLine("ping -n 2 127.0.0.1 >nul")
        appendLine("goto wait")
        appendLine(":install")
        append("msiexec /i ").append(q(msi))
        if (installDir != null) append(" INSTALLDIR=").append(q(installDir))
        append(" /passive /norestart /l*v ").appendLine(q(log))
        appendLine("echo msiexec exit %errorlevel% >> ${q(File(log.parentFile, "apply.result"))}")
        // Even when msiexec failed, Windows Installer rolled back to the old version: restart whichever is installed.
        if (relaunch != null) appendLine("start \"\" ${q(relaunch)}")
        appendLine("exit")
    }

    /** Write the script next to [installer] and start it detached; the script's file. Throws when it could not start. */
    @JvmStatic
    @JvmOverloads
    fun start(installer: VerifiedInstaller, installDir: File?, relaunch: File?, pid: Long = ProcessHandle.current().pid()): File {
        check(System.getProperty("os.name").startsWith("Windows")) { "the updater installs .msi packages on Windows only" }
        val dir = installer.file.parentFile
        val script = File(dir, SCRIPT_NAME)
        script.writeText(script(pid, installer.file, installDir, relaunch, File(dir, LOG_NAME)).replace("\n", "\r\n"))
        // `start` gives the script its own console process, so it outlives this JVM. `cmd.exe /c` explicitly: `start` on
        // a bare .cmd runs it under `cmd /K`, which would leave the console window open after the update.
        ProcessBuilder("cmd.exe", "/c", "start", "\"Infantry AI update\"", "/min", "cmd.exe", "/c", script.absolutePath)
            .directory(dir)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        return script
    }
}
