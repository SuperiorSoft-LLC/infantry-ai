// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.runner

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.win32.StdCallLibrary
import java.util.concurrent.ConcurrentHashMap

/**
 * PROC-LEAK-1 (ticket 55312934): one OS container per session, so every process a session's harness starts stays attributable
 * to it, also once its parent is gone (the `gradlew` client exits, its daemon stays; a shell launches a detached child and exits).
 * A parent-pid walk loses such an orphan unless it saw it while the parent lived; a container never does.
 *
 *  - Windows: a NAMED Job Object per key. The harness pid is assigned right after it starts; every process it creates from
 *    then on is in the job (job membership is inherited, nested jobs included), so the job's process-id list IS the session's
 *    tree. No kill-on-close: the job only counts, a daemon restart kills nothing. Breakaway is allowed, so a child that asks for
 *    `CREATE_BREAKAWAY_FROM_JOB` still starts (outside the job, where the tree walk is the fallback).
 *  - anywhere else: [None] (assign answers false, members are empty); the tree walk of the process watch stays the attribution.
 *
 * A container is a mechanism: it never kills. What is reaped, and when, is the process-watch plugin's.
 */
interface SessionContainers {
    /** `job object`, or `none`. */
    val kind: String

    /** Put [pid] (and so everything it starts from now on) in [key]'s container; false when the OS refused or there is none. */
    fun assign(key: String, pid: Long): Boolean

    /** The pids alive in [key]'s container now; empty when it has none (or none is known). */
    fun members(key: String): List<Long>

    /** Let go of [key]'s container (its processes keep running). */
    fun release(key: String)

    object None : SessionContainers {
        override val kind = "none"
        override fun assign(key: String, pid: Long) = false
        override fun members(key: String) = emptyList<Long>()
        override fun release(key: String) {}
    }

    companion object {
        private val isWindows = System.getProperty("os.name").orEmpty().lowercase().contains("win")

        /** The containers of this OS; [prefix] (the daemon's instance id) keeps two daemons' sessions apart. */
        fun forHost(prefix: String?): SessionContainers =
            if (isWindows) runCatching { WindowsJobContainers(prefix) }.getOrElse { None } else None

        /** A key or prefix as an object-name part: letters, digits and `-` only, at most [NAME_PART_MAX] characters. */
        internal fun safe(text: String): String = text.replace(Regex("[^A-Za-z0-9-]"), "-").take(NAME_PART_MAX)

        internal const val NAME_PART_MAX = 64
    }
}

/**
 * The Windows containers: `Local\infantry-<prefix>-<key>` Job Objects. A handle is kept per key while the job may still hold
 * a process; [members] closes it once the job is empty (the name then goes, and the next [assign] starts a fresh job).
 * After a daemon restart the old job's name is gone with its handle: what the process watch recorded before stays in its
 * ledger, so nothing already seen is lost.
 */
class WindowsJobContainers(prefix: String?) : SessionContainers {
    override val kind = "job object"
    private val base = "Local\\infantry-" + (prefix?.let { SessionContainers.safe(it) + "-" } ?: "")
    private val jobs = ConcurrentHashMap<String, WinNT.HANDLE>()

    private fun name(key: String) = base + SessionContainers.safe(key)

    @Synchronized
    private fun job(key: String, create: Boolean): WinNT.HANDLE? {
        jobs[key]?.let { return it }
        if (!create) return null
        val h = api.CreateJobObjectW(null, WString(name(key))) ?: return null
        allowBreakaway(h)
        jobs[key] = h
        return h
    }

    /** JOBOBJECT_BASIC_LIMIT_INFORMATION with only LimitFlags = BREAKAWAY_OK (offset 16 after two LARGE_INTEGERs). */
    private fun allowBreakaway(job: WinNT.HANDLE) {
        val size = if (Native.POINTER_SIZE == 8) 64 else 48
        val info = Memory(size.toLong()).apply { clear() }
        info.setInt(16, JOB_OBJECT_LIMIT_BREAKAWAY_OK)
        runCatching { api.SetInformationJobObject(job, JOB_OBJECT_BASIC_LIMIT_INFORMATION, info, size) }
    }

    override fun assign(key: String, pid: Long): Boolean = runCatching {
        val job = job(key, create = true) ?: return false
        val k32 = Kernel32.INSTANCE
        val process = k32.OpenProcess(WinNT.PROCESS_SET_QUOTA or WinNT.PROCESS_TERMINATE or WinNT.PROCESS_QUERY_LIMITED_INFORMATION, false, pid.toInt())
            ?: return false
        try {
            api.AssignProcessToJobObject(job, process)
        } finally {
            k32.CloseHandle(process)
        }
    }.getOrDefault(false)

    override fun members(key: String): List<Long> {
        val job = job(key, create = false) ?: return emptyList()
        val ids = runCatching { ids(job) }.getOrNull() ?: return emptyList()
        val me = ProcessHandle.current().pid()
        val live = ids.filter { it != me }
        if (live.isEmpty()) release(key)
        return live
    }

    @Synchronized
    override fun release(key: String) {
        jobs.remove(key)?.let { Kernel32.INSTANCE.CloseHandle(it) }
    }

    /** JOBOBJECT_BASIC_PROCESS_ID_LIST: two DWORD counts, then ULONG_PTR ids; grown once when the job holds more than fit. */
    private fun ids(job: WinNT.HANDLE): List<Long>? {
        var max = INITIAL_IDS
        repeat(2) {
            val ptr = Native.POINTER_SIZE
            val mem = Memory(8L + ptr.toLong() * max).apply { clear() }
            if (!api.QueryInformationJobObject(job, JOB_OBJECT_BASIC_PROCESS_ID_LIST, mem, mem.size().toInt(), null)) {
                val assigned = mem.getInt(0)
                if (assigned > max) { max = assigned + INITIAL_IDS; return@repeat }
                return null
            }
            val n = mem.getInt(4).coerceIn(0, max)
            return (0 until n).map { i -> if (ptr == 8) mem.getLong(8L + 8L * i) else mem.getInt(8L + 4L * i).toLong() and 0xffffffffL }
        }
        return null
    }

    @Suppress("FunctionName")
    private interface JobApi : StdCallLibrary {
        fun CreateJobObjectW(attributes: Pointer?, name: WString?): WinNT.HANDLE?
        fun AssignProcessToJobObject(job: WinNT.HANDLE, process: WinNT.HANDLE): Boolean
        fun SetInformationJobObject(job: WinNT.HANDLE, infoClass: Int, info: Pointer, length: Int): Boolean
        fun QueryInformationJobObject(job: WinNT.HANDLE, infoClass: Int, info: Pointer, length: Int, returned: Pointer?): Boolean
    }

    private companion object {
        const val JOB_OBJECT_BASIC_LIMIT_INFORMATION = 2
        const val JOB_OBJECT_BASIC_PROCESS_ID_LIST = 3
        const val JOB_OBJECT_LIMIT_BREAKAWAY_OK = 0x00000800
        const val INITIAL_IDS = 1024
        val api: JobApi by lazy { Native.load("kernel32", JobApi::class.java) }
    }
}
