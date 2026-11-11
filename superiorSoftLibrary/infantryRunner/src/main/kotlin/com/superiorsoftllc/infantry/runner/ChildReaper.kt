// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.runner

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.win32.StdCallLibrary

/**
 * QUIT-ORPHAN-1: when the daemon process ends (window closed, crash, kill), every process it spawned dies with it.
 *
 * Windows has no parent-death tie, so the daemon puts ITSELF into a Job object with KILL_ON_JOB_CLOSE. Every child
 * (a harness, its MCP bridge, a shell) is born inside that job and so are its descendants; the OS closes the job
 * handle when the JVM exits, however it exits, and kills the whole tree. Generic: no harness is named. The handle is
 * deliberately never closed by us. A child that asks for CREATE_BREAKAWAY_FROM_JOB still starts (BREAKAWAY_OK).
 * Other platforms: no-op (a process group / PDEATHSIG tie is a separate seam).
 */
object ChildReaper {
    private const val EXTENDED_LIMIT_INFORMATION = 9
    private const val KILL_ON_JOB_CLOSE = 0x0000_2000
    private const val BREAKAWAY_OK = 0x0000_0800

    @Suppress("FunctionName")
    private interface Api : StdCallLibrary {
        fun CreateJobObjectW(attributes: Pointer?, name: WString?): WinNT.HANDLE?
        fun SetInformationJobObject(job: WinNT.HANDLE, infoClass: Int, info: Structure, length: Int): Boolean
        fun AssignProcessToJobObject(job: WinNT.HANDLE, process: WinNT.HANDLE): Boolean
    }

    private val windows = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    /** Null = armed (or not Windows); otherwise why the job could not be set up. Armed at most once. */
    val problem: String? by lazy { if (windows) runCatching { arm() }.getOrElse { "${it::class.simpleName}: ${it.message}" } else null }

    /** Call before the first spawn; cheap afterwards. A failure is logged once and never blocks the spawn. */
    fun ensure() {
        problem?.let { ProcessPriority.logOnce("child-reaper", "children may outlive the app: $it") }
    }

    private fun arm(): String? {
        val k32 = Kernel32.INSTANCE
        val api = Native.load("kernel32", Api::class.java)
        val job = api.CreateJobObjectW(null, null) ?: return "CreateJobObject failed (error ${k32.GetLastError()})"
        val limit = JobExtendedLimit().apply {
            BasicLimitInformation.LimitFlags = KILL_ON_JOB_CLOSE or BREAKAWAY_OK
            write()
        }
        if (!api.SetInformationJobObject(job, EXTENDED_LIMIT_INFORMATION, limit, limit.size())) {
            return "SetInformationJobObject failed (error ${k32.GetLastError()})"
        }
        if (!api.AssignProcessToJobObject(job, k32.GetCurrentProcess())) {
            return "AssignProcessToJobObject failed (error ${k32.GetLastError()})"
        }
        return null // the job handle stays open for the life of the JVM
    }
}
