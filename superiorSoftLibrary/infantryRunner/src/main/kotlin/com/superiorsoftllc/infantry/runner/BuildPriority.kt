package com.superiorsoftllc.infantry.runner

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.platform.win32.BaseTSD
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.win32.StdCallLibrary
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/*
 * Ticket 66153313: builds that workers start (Gradle, its Kotlin compiler, test forks) ran at Normal priority and used
 * all 16 cores of the owner's PC. The daemon now enforces a lower priority on every worker session: it sets the session's
 * ROOT process (the harness it spawns) to the configured class, and every descendant inherits that class. This does not
 * depend on the worker remembering anything. Windows: SetPriorityClass, plus a per-session Job object whose priority-class
 * limit stops a child from raising itself back to Normal. POSIX: `nice -n`. The session env also caps Gradle's worker count
 * and test forks, and keeps the Kotlin compiler inside the (lowered) Gradle JVM instead of a shared Normal-priority daemon.
 */

/** The CPU priority a worker session (and so every build it starts) runs at: setting `limits.build.priority`. */
enum class BuildPriority(val key: String, val nice: Int, val windowsClass: Int) {
    NORMAL("normal", 0, 0x0000_0020),
    BELOW_NORMAL("belownormal", 10, 0x0000_4000),
    IDLE("idle", 19, 0x0000_0040),
    ;

    companion object {
        val DEFAULT = BELOW_NORMAL
        val KEYS: List<String> = entries.map { it.key }

        /** `belownormal`, `below_normal`, `below-normal` and `BelowNormal` all name [BELOW_NORMAL]; null for anything else. */
        fun parse(text: String?): BuildPriority? {
            val k = text?.trim()?.lowercase()?.replace("_", "")?.replace("-", "") ?: return null
            return entries.firstOrNull { it.key == k }
        }
    }
}

/**
 * What a worker session's builds may use (settings `limits.build.*`). [maxWorkers] and [maxParallelForks] are 0 for
 * unlimited (no cap is passed; Gradle and the build scripts use their own defaults).
 */
data class BuildLimits(
    val priority: BuildPriority = BuildPriority.DEFAULT,
    val maxWorkers: Int = defaultMaxWorkers(),
    val maxParallelForks: Int = 0,
) {
    /**
     * The env every worker session gets: the generic priority and caps ([PRIORITY_ENV], [MAX_WORKERS_ENV], [MAX_FORKS_ENV]),
     * plus what each tool profile's `env` templates make of them (STACK-NEUTRAL-1: the Gradle `-D` caps are data in the
     * gradle profile, not code). [inherited] is the daemon's own environment: a variable a template names is appended to.
     */
    fun sessionEnv(inherited: (String) -> String? = { null }, profiles: List<ToolProfile> = ToolProfiles.active): Map<String, String> =
        buildMap {
            put(PRIORITY_ENV, priority.key)
            if (maxWorkers > 0) put(MAX_WORKERS_ENV, maxWorkers.toString())
            if (maxParallelForks > 0) put(MAX_FORKS_ENV, maxParallelForks.toString())
            putAll(ToolProfiles.sessionEnv(maxWorkers, maxParallelForks, inherited, profiles))
        }

    companion object {
        /** Read by the runner (the priority to apply) and by scripts/infantry-gate.sh (its own fallback outside a session). */
        const val PRIORITY_ENV = "INFANTRY_BUILD_PRIORITY"
        const val MAX_WORKERS_ENV = "INFANTRY_BUILD_MAX_WORKERS"
        const val MAX_FORKS_ENV = "INFANTRY_BUILD_MAX_FORKS"

        /** Logical cores minus 2 (at least 1): the owner keeps two cores for the desktop. */
        fun defaultMaxWorkers(cores: Int = Runtime.getRuntime().availableProcessors()): Int = (cores - 2).coerceAtLeast(1)
    }
}

/** Applies a [BuildPriority] to a spawned process. Every failure is logged ONCE per kind and never fails the launch. */
object ProcessPriority {
    private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
    private val logged = ConcurrentHashMap.newKeySet<String>()

    /** The priority a spec asks for (the [BuildLimits.PRIORITY_ENV] the session manager put in its env); null = untouched. */
    fun requested(spec: ProcessSpec): BuildPriority? =
        spec.env[BuildLimits.PRIORITY_ENV]?.let(BuildPriority::parse)?.takeIf { it != BuildPriority.NORMAL }

    /** POSIX: the argv run under `nice -n`, so the child starts lowered (no window between start and renice). */
    fun wrap(command: List<String>, priority: BuildPriority?, windows: Boolean = isWindows, nice: String? = niceBinary()): List<String> {
        if (priority == null || priority == BuildPriority.NORMAL || windows) return command
        if (nice == null) {
            logOnce("no-nice", "no `nice` binary found; worker sessions run at normal priority")
            return command
        }
        return listOf(nice, "-n", priority.nice.toString()) + command
    }

    /** Windows: lower the started [pid] and bind it to a Job object that holds the class. No-op elsewhere. */
    fun applyAfterStart(pid: Long, priority: BuildPriority?) {
        if (priority == null || priority == BuildPriority.NORMAL || !isWindows) return
        runCatching { WindowsJob.apply(pid, priority) }
            .onFailure { logOnce("jna:${it::class.simpleName}", "could not lower pid $pid to ${priority.key}: ${it.message}") }
            .getOrNull()
            ?.forEach { logOnce(it.substringBefore(':'), "pid $pid: $it") }
    }

    /** The class a Windows process runs at, for tests and diagnostics; null when unknown or not Windows. */
    fun windowsPriorityClass(pid: Long): Int? = if (!isWindows) null else runCatching { WindowsJob.priorityClass(pid) }.getOrNull()

    fun logOnce(kind: String, message: String) {
        if (logged.add(kind)) System.err.println("[infantry] build priority: $message (logged once)")
    }

    private fun niceBinary(): String? = listOf("/usr/bin/nice", "/bin/nice").firstOrNull { File(it).canExecute() }
}

/** The Kernel32 calls jna-platform does not map (Job objects). */
@Suppress("FunctionName")
private interface JobApi : StdCallLibrary {
    fun CreateJobObjectW(attributes: Pointer?, name: WString?): WinNT.HANDLE?
    fun SetInformationJobObject(job: WinNT.HANDLE, infoClass: Int, info: Structure, length: Int): Boolean
    fun AssignProcessToJobObject(job: WinNT.HANDLE, process: WinNT.HANDLE): Boolean
}

/** JOBOBJECT_BASIC_LIMIT_INFORMATION. */
@Suppress("PropertyName")
@Structure.FieldOrder(
    "PerProcessUserTimeLimit", "PerJobUserTimeLimit", "LimitFlags", "MinimumWorkingSetSize", "MaximumWorkingSetSize",
    "ActiveProcessLimit", "Affinity", "PriorityClass", "SchedulingClass",
)
class JobBasicLimit : Structure() {
    @JvmField var PerProcessUserTimeLimit: Long = 0
    @JvmField var PerJobUserTimeLimit: Long = 0
    @JvmField var LimitFlags: Int = 0
    @JvmField var MinimumWorkingSetSize: BaseTSD.SIZE_T = BaseTSD.SIZE_T(0)
    @JvmField var MaximumWorkingSetSize: BaseTSD.SIZE_T = BaseTSD.SIZE_T(0)
    @JvmField var ActiveProcessLimit: Int = 0
    @JvmField var Affinity: BaseTSD.ULONG_PTR = BaseTSD.ULONG_PTR(0)
    @JvmField var PriorityClass: Int = 0
    @JvmField var SchedulingClass: Int = 0
}

/** JOBOBJECT_EXTENDED_LIMIT_INFORMATION: BREAKAWAY_OK is an extended flag, refused (error 87) in the basic structure. */
@Suppress("PropertyName")
@Structure.FieldOrder(
    "BasicLimitInformation", "IoInfo", "ProcessMemoryLimit", "JobMemoryLimit", "PeakProcessMemoryUsed", "PeakJobMemoryUsed",
)
class JobExtendedLimit : Structure() {
    @JvmField var BasicLimitInformation: JobBasicLimit = JobBasicLimit()

    /** IO_COUNTERS: six ULONGLONGs, unused. */
    @JvmField var IoInfo: LongArray = LongArray(6)
    @JvmField var ProcessMemoryLimit: BaseTSD.SIZE_T = BaseTSD.SIZE_T(0)
    @JvmField var JobMemoryLimit: BaseTSD.SIZE_T = BaseTSD.SIZE_T(0)
    @JvmField var PeakProcessMemoryUsed: BaseTSD.SIZE_T = BaseTSD.SIZE_T(0)
    @JvmField var PeakJobMemoryUsed: BaseTSD.SIZE_T = BaseTSD.SIZE_T(0)
}

private object WindowsJob {
    private const val JOB_OBJECT_EXTENDED_LIMIT_INFORMATION = 9
    private const val JOB_OBJECT_LIMIT_PRIORITY_CLASS = 0x0000_0020

    /** A child that asks for CREATE_BREAKAWAY_FROM_JOB would otherwise FAIL to start; with this it starts outside the job. */
    private const val JOB_OBJECT_LIMIT_BREAKAWAY_OK = 0x0000_0800

    private val api: JobApi by lazy { Native.load("kernel32", JobApi::class.java) }
    private val k32 get() = Kernel32.INSTANCE

    /** Returns the problems met (empty = lowered and held by a job); each is `kind: detail`. */
    fun apply(pid: Long, priority: BuildPriority): List<String> {
        val access = WinNT.PROCESS_SET_INFORMATION or WinNT.PROCESS_SET_QUOTA or WinNT.PROCESS_TERMINATE or
            WinNT.PROCESS_QUERY_LIMITED_INFORMATION
        val process = k32.OpenProcess(access, false, pid.toInt()) ?: return listOf("open: OpenProcess failed (error ${k32.GetLastError()})")
        val problems = ArrayList<String>()
        try {
            if (!k32.SetPriorityClass(process, WinDef.DWORD(priority.windowsClass.toLong()))) {
                problems += "class: SetPriorityClass failed (error ${k32.GetLastError()})"
            }
            val job = api.CreateJobObjectW(null, null)
            if (job == null) {
                problems += "job: CreateJobObject failed (error ${k32.GetLastError()})"
            } else {
                try {
                    val limit = JobExtendedLimit().apply {
                        BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_PRIORITY_CLASS or JOB_OBJECT_LIMIT_BREAKAWAY_OK
                        BasicLimitInformation.PriorityClass = priority.windowsClass
                        write()
                    }
                    when {
                        !api.SetInformationJobObject(job, JOB_OBJECT_EXTENDED_LIMIT_INFORMATION, limit, limit.size()) ->
                            problems += "job: SetInformationJobObject failed (error ${k32.GetLastError()})"
                        !api.AssignProcessToJobObject(job, process) ->
                            problems += "job: AssignProcessToJobObject failed (error ${k32.GetLastError()})"
                    }
                } finally {
                    // The job lives on while the process is in it; no kill-on-close, so closing our handle changes nothing.
                    k32.CloseHandle(job)
                }
            }
        } finally {
            k32.CloseHandle(process)
        }
        return problems
    }

    fun priorityClass(pid: Long): Int? {
        val process = k32.OpenProcess(WinNT.PROCESS_QUERY_LIMITED_INFORMATION, false, pid.toInt()) ?: return null
        return try {
            k32.GetPriorityClass(process)?.toInt()?.takeIf { it != 0 }
        } finally {
            k32.CloseHandle(process)
        }
    }
}
