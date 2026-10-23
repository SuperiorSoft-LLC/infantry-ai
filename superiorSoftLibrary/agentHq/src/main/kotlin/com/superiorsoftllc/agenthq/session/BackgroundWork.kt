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
}

/** Windows and POSIX alike: [ProcessHandle.descendants], no PowerShell polling. */
object OsProcessSource : ProcessSource {
    override fun descendants(rootPid: Long): List<ProcInfo> {
        val root = ProcessHandle.of(rootPid).orElse(null) ?: return emptyList()
        return root.descendants().filter { it.isAlive }.map { h ->
            val info = h.info()
            ProcInfo(
                h.pid(), h.parent().map { it.pid() }.orElse(null),
                info.commandLine().orElse(null) ?: info.command().orElse("") ,
                info.startInstant().map { it.toEpochMilli() }.orElse(null),
            )
        }.toList()
    }
}

object BackgroundWorkScanner {
    /** A process the harness itself keeps (console hosts, its MCP servers) is never "work". */
    private val HELPER = Regex("""(?i)conhost|werfault|openconsole|\bmcp\b|\bagenthq\b""")

    /** Processes that started within this long of the harness process are its launch helpers. */
    const val LAUNCH_GRACE_MS = 20_000L

    const val LABEL_MAX = 80

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
)

/** One `bg_job` request. [op] is `beat` (register or heartbeat: an upsert), `done`, or `list`. */
@Serializable
data class BgJobRequest(val op: String, val job: BgJob? = null, val jobId: String? = null)
