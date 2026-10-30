package com.superiorsoftllc.agenthq.runner

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.win32.StdCallLibrary

/**
 * STALL-2 (ticket a46744ec) item 2: the WHOLE tree of a command the runner started, so "done" means every process it
 * attached has ended, not only the one the runner waits on. STALL-1 named the survivors; this waits for them.
 *
 *  - Windows: a Job Object the command is assigned to right after it starts. Every process it creates from then on is in
 *    the job (also the ones whose parent already exited, which a parent-pid walk loses), so the job's process-id list is
 *    the tree. A process started in the few milliseconds before the assignment is not in it; the snapshot below covers it.
 *    No limits are set (no kill-on-close): the job only counts.
 *  - Linux: the runner makes itself a child subreaper (`prctl(PR_SET_CHILD_SUBREAPER)`), so an orphan of the tree is
 *    re-parented to the runner instead of to init, and the runner's descendants ARE the tree. Stronger than a process
 *    group: a member that calls `setsid` (a daemonising server) still counts.
 *  - anywhere else, or when the call fails: the descendants the runner can still see (snapshot), kind `snapshot`.
 *
 * Build daemons a command leaves behind on purpose ([daemon]: Gradle, Kotlin) and every process under them (their worker
 * processes) are never waited on.
 */
interface TreeWatch : AutoCloseable {
    /** `job object`, `subreaper` or `snapshot`: what [alive] reads. */
    val kind: String

    /** The tree's live members, build daemons and their own children excluded. */
    fun alive(): List<ProcessHandle>

    override fun close() {}
}

object ProcessTree {
    private val isWindows = System.getProperty("os.name").lowercase().contains("win")
    private val isLinux = System.getProperty("os.name").lowercase().contains("linux")

    /** Whether the Linux subreaper is on for this JVM (set once, before the first command starts). */
    @Volatile private var subreaper: Boolean? = null

    /** Call before starting the command: on Linux this is what makes orphans come back to the runner. */
    fun prepare() {
        if (!isLinux || subreaper != null) return
        subreaper = runCatching { LibC.INSTANCE.prctl(PR_SET_CHILD_SUBREAPER, 1L, 0L, 0L, 0L) == 0 }.getOrDefault(false)
    }

    /** The tree watch for [child] (started after [prepare]); [daemon] matches the command lines never waited on. */
    fun attach(child: Process, daemon: Regex): TreeWatch {
        val pid = child.pid()
        if (isWindows) {
            runCatching { WindowsTreeJob.create(pid, daemon) }.getOrNull()?.let { return it }
        }
        // The runner's other children (none in `agenthq bg run`, but any in a host JVM) are not this command's tree.
        if (isLinux && subreaper == true) {
            val before = runCatching { ProcessHandle.current().descendants().map { it.pid() }.toList().toSet() - pid }.getOrDefault(emptySet())
            return Descendants("subreaper", ProcessHandle.current(), daemon, keepOut = pid, notOurs = before, since = child.toHandle().info().startInstant().orElse(null))
        }
        return Descendants("snapshot", child.toHandle(), daemon, keepOut = pid)
    }

    /**
     * Linux: a re-parented orphan that exited is a zombie child of the runner until it is waited on, and a zombie still
     * reads as alive. Reap it by ITS pid (never `waitpid(-1)`, which could take the exit status of the JDK's own child
     * [keepOut]) and count it as ended.
     */
    private fun zombieReaped(h: ProcessHandle, keepOut: Long): Boolean {
        if (!isLinux) return false
        val stat = runCatching { java.io.File("/proc/${h.pid()}/stat").readText() }.getOrNull() ?: return false
        val state = stat.substringAfterLast(')').trim().firstOrNull()
        if (state != 'Z') return false
        if (h.pid() != keepOut && subreaper == true) runCatching { LibC.INSTANCE.waitpid(h.pid().toInt(), null, WNOHANG) }
        return true
    }

    /**
     * [h]'s full command line. The JDK gives none for another process on Windows (`Info.commandLine()` is empty there, so
     * a `GradleDaemon` match on it never fired), so Windows asks the kernel (`NtQueryInformationProcess`,
     * ProcessCommandLineInformation, Windows 8.1+). Falls back to the executable path.
     */
    fun commandLine(h: ProcessHandle): String {
        h.info().commandLine().orElse(null)?.let { return it }
        if (isWindows) runCatching { WindowsCommandLine.of(h.pid()) }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
        return h.info().command().orElse("")
    }

    /** [members] without [daemon]-matching processes and anything whose parent chain (within [members]) reaches one. */
    fun withoutDaemons(members: List<ProcessHandle>, daemon: Regex): List<ProcessHandle> {
        val byPid = members.associateBy { it.pid() }
        val excluded = HashSet<Long>()
        members.filter { daemon.containsMatchIn(commandLine(it)) }.forEach { excluded += it.pid() }
        var grew = true
        while (grew) {
            grew = false
            for (m in members) {
                if (m.pid() in excluded) continue
                val parent = m.parent().map { it.pid() }.orElse(null) ?: continue
                if (parent in excluded && parent in byPid) { excluded += m.pid(); grew = true }
            }
        }
        return members.filter { it.pid() !in excluded && it.isAlive && !consoleHost(it) }
    }

    /** Windows gives a console process a `conhost.exe`, which ends with its client: never a member of its own. */
    private fun consoleHost(h: ProcessHandle) =
        isWindows && h.info().command().map { it.substringAfterLast('\\').equals("conhost.exe", true) }.orElse(false)

    private class Descendants(
        override val kind: String,
        private val root: ProcessHandle,
        private val daemon: Regex,
        private val keepOut: Long,
        private val notOurs: Set<Long> = emptySet(),
        /** Members started before the command (less a second of clock slack) are someone else's. */
        private val since: java.time.Instant? = null,
    ) : TreeWatch {
        // The snapshot form keeps every pid it saw, since an orphan drops out of the root's descendants.
        private val seen = LinkedHashMap<Long, ProcessHandle>()

        @Synchronized
        override fun alive(): List<ProcessHandle> {
            runCatching { root.descendants().forEach { seen.putIfAbsent(it.pid(), it) } }
            val live = (if (kind == "subreaper") runCatching { root.descendants().toList() }.getOrDefault(emptyList()) else seen.values.toList())
                .filter { it.pid() !in notOurs && !startedBefore(it) && it.isAlive && !zombieReaped(it, keepOut) }
            return withoutDaemons(live, daemon)
        }

        private fun startedBefore(h: ProcessHandle): Boolean {
            val start = since ?: return false
            return h.info().startInstant().map { it.isBefore(start.minusSeconds(1)) }.orElse(false)
        }
    }

    private const val PR_SET_CHILD_SUBREAPER = 36
    private const val WNOHANG = 1

    @Suppress("FunctionName")
    private interface LibC : Library {
        fun prctl(option: Int, arg2: Long, arg3: Long, arg4: Long, arg5: Long): Int
        fun waitpid(pid: Int, status: Pointer?, options: Int): Int

        companion object {
            val INSTANCE: LibC by lazy { Native.load("c", LibC::class.java) }
        }
    }

    @Suppress("FunctionName")
    private interface NtDll : StdCallLibrary {
        fun NtQueryInformationProcess(process: WinNT.HANDLE, infoClass: Int, info: Pointer, length: Int, returned: com.sun.jna.ptr.IntByReference): Int
    }

    private object WindowsCommandLine {
        private const val PROCESS_COMMAND_LINE_INFORMATION = 60
        private val nt: NtDll by lazy { Native.load("ntdll", NtDll::class.java) }

        /** UNICODE_STRING { USHORT Length; USHORT MaximumLength; PWSTR Buffer (pointer-aligned) } + the text after it. */
        fun of(pid: Long): String? {
            val k32 = Kernel32.INSTANCE
            val h = k32.OpenProcess(WinNT.PROCESS_QUERY_LIMITED_INFORMATION, false, pid.toInt()) ?: return null
            try {
                var size = 4096
                repeat(3) {
                    val mem = Memory(size.toLong()).apply { clear() }
                    val ret = com.sun.jna.ptr.IntByReference()
                    val status = nt.NtQueryInformationProcess(h, PROCESS_COMMAND_LINE_INFORMATION, mem, size, ret)
                    if (status == 0) {
                        val bytes = mem.getShort(0).toInt() and 0xffff
                        val buffer = mem.getPointer(Native.POINTER_SIZE.toLong()) ?: return null
                        return String(buffer.getCharArray(0, bytes / 2))
                    }
                    if (ret.value <= size) return null
                    size = ret.value
                }
                return null
            } finally {
                k32.CloseHandle(h)
            }
        }
    }

    @Suppress("FunctionName")
    private interface JobQueryApi : StdCallLibrary {
        fun CreateJobObjectW(attributes: Pointer?, name: WString?): WinNT.HANDLE?
        fun AssignProcessToJobObject(job: WinNT.HANDLE, process: WinNT.HANDLE): Boolean
        fun QueryInformationJobObject(job: WinNT.HANDLE, infoClass: Int, info: Pointer, length: Int, returned: Pointer?): Boolean
    }

    private class WindowsTreeJob(private val job: WinNT.HANDLE, private val daemon: Regex) : TreeWatch {
        override val kind = "job object"

        override fun alive(): List<ProcessHandle> {
            val pids = ids() ?: return emptyList()
            val me = ProcessHandle.current().pid()
            return withoutDaemons(pids.filter { it != me }.mapNotNull { ProcessHandle.of(it).orElse(null) }, daemon)
        }

        /** JOBOBJECT_BASIC_PROCESS_ID_LIST: two DWORD counts, then ULONG_PTR ids (pointer-aligned). */
        private fun ids(): List<Long>? {
            val max = 4096
            val ptr = Native.POINTER_SIZE
            val mem = Memory((8L + ptr.toLong() * max))
            mem.clear()
            if (!api.QueryInformationJobObject(job, JOB_OBJECT_BASIC_PROCESS_ID_LIST, mem, mem.size().toInt(), null)) return null
            val n = mem.getInt(4).coerceIn(0, max)
            return (0 until n).map { i -> if (ptr == 8) mem.getLong(8L + 8L * i) else mem.getInt(8L + 4L * i).toLong() and 0xffffffffL }
        }

        override fun close() { Kernel32.INSTANCE.CloseHandle(job) }

        companion object {
            private const val JOB_OBJECT_BASIC_PROCESS_ID_LIST = 3
            private val api: JobQueryApi by lazy { Native.load("kernel32", JobQueryApi::class.java) }

            fun create(pid: Long, daemon: Regex): WindowsTreeJob? {
                val k32 = Kernel32.INSTANCE
                val process = k32.OpenProcess(WinNT.PROCESS_SET_QUOTA or WinNT.PROCESS_TERMINATE or WinNT.PROCESS_QUERY_LIMITED_INFORMATION, false, pid.toInt())
                    ?: return null
                try {
                    val job = api.CreateJobObjectW(null, null) ?: return null
                    if (!api.AssignProcessToJobObject(job, process)) {
                        k32.CloseHandle(job)
                        return null
                    }
                    return WindowsTreeJob(job, daemon)
                } finally {
                    k32.CloseHandle(process)
                }
            }
        }
    }
}
