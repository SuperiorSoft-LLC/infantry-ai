// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.runner

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.win32.StdCallLibrary

/**
 * RUN-TAB-1: one Windows Job object per run, so Stop ends the whole tree even after the started process has exited.
 * `taskkill /T` walks parent links, which break when a middle process exits while a grandchild lives on (and keeps the
 * run "running" by holding its pipes). A job remembers every descendant. No limits are set: the job only groups and
 * terminates, and it nests inside the daemon's own kill-on-close job. Other platforms: [attach] returns null.
 */
class RunJob private constructor(private val handle: WinNT.HANDLE) {
    @Suppress("FunctionName")
    private interface Api : StdCallLibrary {
        fun CreateJobObjectW(attributes: Pointer?, name: WString?): WinNT.HANDLE?
        fun AssignProcessToJobObject(job: WinNT.HANDLE, process: WinNT.HANDLE): Boolean
        fun TerminateJobObject(job: WinNT.HANDLE, exitCode: Int): Boolean
    }

    @Volatile private var closed = false

    /** Kills every process still in the job. */
    @Synchronized fun terminate() {
        if (closed) return
        runCatching { api.TerminateJobObject(handle, 1) }
    }

    @Synchronized fun close() {
        if (closed) return
        closed = true
        runCatching { Kernel32.INSTANCE.CloseHandle(handle) }
    }

    companion object {
        private val windows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
        private val api: Api by lazy { Native.load("kernel32", Api::class.java) }

        /** Puts [pid] in a new job; null when not Windows or the OS refuses (the caller falls back to taskkill). */
        fun attach(pid: Long): RunJob? {
            if (!windows) return null
            return runCatching {
                val k32 = Kernel32.INSTANCE
                val process = k32.OpenProcess(WinNT.PROCESS_SET_QUOTA or WinNT.PROCESS_TERMINATE, false, pid.toInt()) ?: return null
                try {
                    val job = api.CreateJobObjectW(null, null) ?: return null
                    if (api.AssignProcessToJobObject(job, process)) RunJob(job) else { k32.CloseHandle(job); null }
                } finally {
                    k32.CloseHandle(process)
                }
            }.getOrNull()
        }
    }
}
