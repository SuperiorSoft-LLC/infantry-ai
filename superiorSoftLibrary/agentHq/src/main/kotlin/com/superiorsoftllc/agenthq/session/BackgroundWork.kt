package com.superiorsoftllc.agenthq.session

import kotlinx.serialization.Serializable

/**
 * What a session is still running after its own turn ended: processes its shell launched that are alive. Derived by the
 * daemon from the OS process tree under the session's kept harness process ([BackgroundWorkScanner]), never from the
 * worker's word. While present the session counts as busy everywhere ([isBusy], and the wire state reads BUSY).
 */
@Serializable
data class BackgroundWork(
    /** The pid of the oldest top-level background process. */
    val pid: Long,
    /** Its command, cut short, e.g. `gradlew agentHqUi:test`; `N processes` is appended for more than one. */
    val label: String,
    /** Epoch millis the oldest process started (the elapsed time on the card counts from here). */
    val startedAt: Long,
    /** Live descendant processes that are not launch helpers. */
    val count: Int = 1,
)

/** One live process as the scanner sees it. */
data class ProcInfo(val pid: Long, val parentPid: Long?, val command: String, val startedAt: Long?)

/** The OS process tree under a root. Injected so a test can fake it. */
interface ProcessSource {
    fun descendants(rootPid: Long): List<ProcInfo>

    /** The live process [pid] itself; null when it is gone or unknown. The process ledger uses it to record a session root. */
    fun info(pid: Long): ProcInfo? = null

    /** Every live process, parent pid known where the OS says. Empty when unsupported. */
    fun all(): List<ProcInfo> = emptyList()
}

/** Windows and POSIX alike: [ProcessHandle.descendants], no PowerShell polling. */
object OsProcessSource : ProcessSource {
    override fun descendants(rootPid: Long): List<ProcInfo> {
        val root = ProcessHandle.of(rootPid).orElse(null) ?: return emptyList()
        return root.descendants().filter { it.isAlive }.map { h -> describe(h, parentKnown = true, full = true) }.toList()
    }

    override fun all(): List<ProcInfo> =
        ProcessHandle.allProcesses().filter { it.isAlive }.map { describe(it, parentKnown = true) }.toList()

    override fun info(pid: Long): ProcInfo? =
        ProcessHandle.of(pid).filter { it.isAlive }.map { describe(it, parentKnown = false, full = true) }.orElse(null)

    /** STALL-2: [full] = the real command line on Windows too (the JDK gives none there, so the daemon/helper filters missed). */
    private fun describe(h: ProcessHandle, parentKnown: Boolean, full: Boolean = false): ProcInfo {
        val info = h.info()
        return ProcInfo(
            h.pid(), if (parentKnown) h.parent().map { it.pid() }.orElse(null) else null,
            if (full) com.superiorsoftllc.agenthq.runner.ProcessTree.commandLine(h) else info.commandLine().orElse(null) ?: info.command().orElse(""),
            info.startInstant().map { it.toEpochMilli() }.orElse(null),
        )
    }
}

object BackgroundWorkScanner {
    /** A process the harness itself keeps (console hosts, its MCP servers) is never "work". */
    private val HELPER = Regex("""(?i)conhost|werfault|openconsole|\bmcp\b|\bagenthq\b""")

    /** Processes that started within this long of the harness process are its launch helpers. */
    const val LAUNCH_GRACE_MS = 20_000L

    const val LABEL_MAX = 80

    /** STALL-1: processes a command leaves running on purpose (build daemons); never a job whose end is awaited. */
    private val DAEMON: Regex get() = com.superiorsoftllc.agenthq.runner.ToolProfiles.daemonRegex()

    /**
     * STALL-1: the top-level WORK processes under [rootPid] (the same helper and launch-grace rules as [scan]), build
     * daemons excluded: the processes whose end the daemon tells the session about.
     */
    fun tops(source: ProcessSource, rootPid: Long, rootStartedAt: Long?): List<ProcInfo> {
        val (work, ignored) = classify(source.descendants(rootPid), rootStartedAt)
        return work.filter { (it.parentPid == rootPid || it.parentPid in ignored) && !DAEMON.containsMatchIn(it.command) }
    }

    private fun classify(all: List<ProcInfo>, rootStartedAt: Long?): Pair<List<ProcInfo>, Set<Long>> {
        val ignored = HashSet<Long>()
        for (p in all.sortedBy { it.startedAt ?: 0L }) {
            val launchHelper = rootStartedAt != null && p.startedAt != null && p.startedAt - rootStartedAt <= LAUNCH_GRACE_MS
            if (launchHelper || HELPER.containsMatchIn(p.command) || p.parentPid in ignored) ignored += p.pid
        }
        return all.filter { it.pid !in ignored } to ignored
    }

    fun scan(source: ProcessSource, rootPid: Long, rootStartedAt: Long?): BackgroundWork? {
        val all = source.descendants(rootPid)
        val ignored = HashSet<Long>()
        // Parents first by start time, so a helper's whole subtree is ignored with it.
        for (p in all.sortedBy { it.startedAt ?: 0L }) {
            val launchHelper = rootStartedAt != null && p.startedAt != null && p.startedAt - rootStartedAt <= LAUNCH_GRACE_MS
            if (launchHelper || HELPER.containsMatchIn(p.command) || p.parentPid in ignored) ignored += p.pid
        }
        val work = all.filter { it.pid !in ignored }
        val top = work.filter { it.parentPid == rootPid || it.parentPid in ignored }.ifEmpty { work }
            .minByOrNull { it.startedAt ?: Long.MAX_VALUE } ?: return null
        val shortCmd = top.command.trim().replace(Regex("""\s+"""), " ").let {
            if (it.length <= LABEL_MAX) it else it.take(LABEL_MAX - 1) + "…"
        }
        val label = if (work.size > 1) "$shortCmd (+${work.size - 1} more)" else shortCmd
        return BackgroundWork(top.pid, label, top.startedAt ?: 0L, work.size)
    }
}

/**
 * A background job an agent registered through `agenthq bg run`. The wrapper process ([pid]) heartbeats while it lives;
 * the daemon drops the job when it says it is done, when [pid] is gone, or when the heartbeat goes quiet.
 */
@Serializable
data class BgJob(
    val id: String,
    val label: String,
    /** The wrapper's pid (the process `bg stop` ends, and the one the daemon probes for liveness). */
    val pid: Long,
    val startedAt: Long,
    /** Epoch millis of the last register/heartbeat, stamped by the daemon. */
    val lastBeat: Long = 0L,
    /** Where the wrapper tees the command's output; null when none. */
    val logPath: String? = null,
    /** `bg run --keep`: the job (and everything under it) survives its session closing (ticket b785f383). */
    val keep: Boolean = false,
    /** BG-NOTIFY (ticket 245a7d31): `bg run --notify`: the daemon mails the session when the job ends (or a trigger fires); null = no notice. */
    val notify: BgNotify? = null,
    /**
     * STALL-1 (ticket 802c64d5): the command runs in the caller's foreground (a PATH shim, plain `bg run`): its end is
     * noticed by mail only when the session is at rest by then; a turn still in flight saw the end on its own console.
     */
    val attached: Boolean = false,
    /** STALL-1: `agenthq unwatched --reason <text>`: the output is not watched (no log); heartbeat and stall checks still apply. */
    val optOut: String? = null,
    /** STALL-1: the pid of the command itself (the wrapper's child), null when not known. */
    val cmdPid: Long? = null,
)

/**
 * BG-NOTIFY: what the daemon watches for on a `--notify` job and tells the owning session by COMMS mail (which wakes an
 * idle agent). Exit is always a trigger. A null number means the daemon's setting ([com.superiorsoftllc.agenthq.settings.LimitRegistry]
 * `limits.bg.*`) applies, read when the trigger fires.
 */
@Serializable
data class BgNotify(
    /** How many last output lines the notice carries; null = `limits.bg.notifyTailLines`. */
    val tailLines: Int? = null,
    /** Regexes matched against each new log line; a match is noticed while the job still runs (tools that never exit, end markers). */
    val patterns: List<String> = emptyList(),
    /** Minutes after which a still-running job is noticed (again every such period); null = `limits.bg.notifyTimeoutMinutes`, 0 = never. */
    val timeoutMinutes: Int? = null,
)

/**
 * One `bg_job` request. [op] is `beat` (register or heartbeat: an upsert), `done`, `eof` (STALL-1: the output closed while the command still runs), or `list`. On `done` the wrapper adds
 * the command's [exitCode] and its last output lines ([tail]) for a `--notify` job's notice.
 */
@Serializable
data class BgJobRequest(
    val op: String,
    val job: BgJob? = null,
    val jobId: String? = null,
    val exitCode: Int? = null,
    val tail: List<String>? = null,
    /** STALL-1: on `done` and `eof`, the bytes the command wrote to its output pipe (0 = it printed nothing). */
    val bytes: Long? = null,
    /** STALL-1: on `done`, whether the output pipe reached EOF; false = a child the command left behind still holds it. */
    val eof: Boolean? = null,
    /** STALL-1: on `done`, child processes still alive after the command ended (`pid command`), detached daemons excluded. */
    val survivors: List<String>? = null,
)

/** BG-NOTIFY: what [SessionManager] tells about registered jobs beyond the record change. */
interface BgJobListener {
    /** One background scan pass ran (every few seconds). */
    fun scanned() {}

    /** [job] of [sessionId] was dropped without a `done`: its wrapper died or went quiet. */
    fun expired(sessionId: String, job: BgJob) {}

    /**
     * STALL-1 layer 5: a long-lived process under [sessionId]'s harness (one the daemon did not launch, e.g. the
     * harness's own background shell) is gone. Its exit code is unknown: it is not the daemon's child.
     */
    fun treeJobEnded(sessionId: String, job: ProcInfo, endedAt: Long) {}
}
