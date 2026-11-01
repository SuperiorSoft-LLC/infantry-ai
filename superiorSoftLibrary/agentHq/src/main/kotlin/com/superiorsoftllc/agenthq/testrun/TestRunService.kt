package com.superiorsoftllc.agenthq.testrun

import com.superiorsoftllc.agenthq.daemon.api.storage.PluginStorage
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/** FULLSUITE-2: the ledger of failures seen per project, kept in the daemon store so dedupe survives a restart. */
interface LedgerStore {
    fun load(projectId: String): Ledger
    fun save(projectId: String, ledger: Ledger)
}

class MemoryLedgerStore : LedgerStore {
    private val map = ConcurrentHashMap<String, Ledger>()
    override fun load(projectId: String) = map[projectId] ?: Ledger()
    override fun save(projectId: String, ledger: Ledger) { map[projectId] = ledger }
}

/** The ledger as one JSON row per project in the daemon database (table `testrun_ledger`, module id `testrun`). */
class SqlLedgerStore(private val storage: PluginStorage) : LedgerStore {
    private val json = Json { ignoreUnknownKeys = true }
    private val ready by lazy {
        storage.migrate(1, listOf("CREATE TABLE IF NOT EXISTS testrun_ledger (project_id TEXT PRIMARY KEY, body TEXT NOT NULL)"))
    }

    override fun load(projectId: String): Ledger {
        ready
        val body = storage.query("SELECT body FROM testrun_ledger WHERE project_id = ?", listOf(projectId)).firstOrNull()?.get("body") as? String
        return body?.let { runCatching { json.decodeFromString(Ledger.serializer(), it) }.getOrNull() } ?: Ledger()
    }

    override fun save(projectId: String, ledger: Ledger) {
        ready
        storage.execute(
            "INSERT OR REPLACE INTO testrun_ledger (project_id, body) VALUES (?, ?)",
            listOf(projectId, json.encodeToString(Ledger.serializer(), ledger)),
        )
    }
}

/** Files the failures: [create] a ticket, [comment] on one, [notify] the PM. All three are the daemon's own board and mail. */
class ServiceFailureSink(
    private val createTicket: (title: String, description: String) -> String,
    private val commentTicket: (ticketId: String, text: String) -> Unit,
    private val notifyPm: (text: String) -> Unit,
) : FailureSink {
    override fun create(title: String, description: String) = createTicket(title, description)
    override fun comment(ticketId: String, text: String) = commentTicket(ticketId, text)
    override fun notifyNew(text: String) = notifyPm(text)
}

/**
 * FULLSUITE-2: the background full-suite runner connected to a daemon. One run per project at a time; `run` returns at once and
 * the suite goes on in [async] (never blocks, never gates). Config is project data (`tests.*`); unset command / branch = refused
 * with the runner's own message. `tests.scheduleMinutes` (project data, unset/0 = off) is the optional schedule, driven by [tick].
 */
class TestRunService(
    private val ledgers: LedgerStore,
    private val data: (projectId: String) -> Map<String, String>,
    private val envFor: (projectId: String) -> SuiteEnv?,
    private val exec: SuiteExec,
    private val sinkFor: (projectId: String) -> FailureSink,
    private val async: (Runnable) -> Unit = { Thread(it, "full-suite-run").apply { isDaemon = true }.start() },
    /** Told (projectId, neutral status line) on start and end of a run: the status bar. */
    private val onStatus: (String, String) -> Unit = { _, _ -> },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val active = ConcurrentHashMap.newKeySet<String>()

    private fun config(projectId: String) = data(projectId).let { d -> TestRunConfig.from { d[it] } }

    /** Starts a run; returns the one-line answer. Refuses (and says why) when the command or branch is unset. */
    fun run(projectId: String): String {
        val cfg = config(projectId)
        val env = envFor(projectId)
        val ledger = ledgers.load(projectId).copy(running = false)
        if (cfg.fullCommand == null || cfg.workingBranch == null || env == null) {
            val refused = FullSuiteRunner(env ?: NoEnv, exec, sinkFor(projectId)).run(cfg, ledger)
            ledgers.save(projectId, refused)
            return refused.last?.message?.ifBlank { null } ?: "project has no directory; nothing was run"
        }
        if (!active.add(projectId)) return "a run is already in progress"
        ledgers.save(projectId, ledger.copy(running = true))
        onStatus(projectId, FullSuiteRunner.status(ledger.copy(running = true)))
        async(Runnable {
            val after = try {
                FullSuiteRunner(env, exec, sinkFor(projectId)).run(cfg, ledger)
            } catch (e: Exception) {
                ledger.copy(last = RunResult("", now(), now(), 0, 0, 0, outcome = "skipped", message = "run failed: ${e.message}"), running = false)
            }
            ledgers.save(projectId, after.copy(running = false))
            active.remove(projectId)
            onStatus(projectId, FullSuiteRunner.status(after.copy(running = false)))
        })
        return "started: full suite on ${cfg.workingBranch} (runs in the background)"
    }

    /** SCRIPT-HOOK-3 (TST-02): stops the run in flight for [projectId]; says so when none is. */
    fun stop(projectId: String): String {
        if (projectId !in active) return "no run in progress"
        exec.stop()
        return "stopping the running full suite"
    }

    fun status(projectId: String): String {
        val l = ledgers.load(projectId)
        val shown = l.copy(running = projectId in active)
        val open = shown.failures.values.filter { it.ticketId != null && !it.flaky }
        val flaky = shown.failures.values.count { it.flaky }
        return FullSuiteRunner.status(shown) +
            (shown.last?.takeIf { it.outcome == "ran" }?.let { " (sha ${it.sha.take(8)}, passed ${it.passed}, failed ${it.failed}, flaky ${it.flaky.size})" } ?: "") +
            (if (open.isNotEmpty()) "\ntickets: " + open.joinToString(", ") { "${it.ticketId} (${it.identity})" } else "") +
            (if (flaky > 0) "\nflaky seen: $flaky" else "")
    }

    /** The optional schedule: start a run for every project whose `tests.scheduleMinutes` is due. Off when unset or 0. */
    fun tick(projectIds: List<String>) {
        for (id in projectIds) {
            val minutes = data(id)["tests.scheduleMinutes"]?.trim()?.toLongOrNull() ?: continue
            if (minutes <= 0 || id in active) continue
            val last = ledgers.load(id).last
            if (last == null || now() - last.endedAt >= minutes * 60_000L) run(id)
        }
    }

    private object NoEnv : SuiteEnv {
        override fun headSha(dir: java.io.File) = ""
        override fun prepareWorktree(branch: String) = java.io.File(".")
        override fun results(dir: java.io.File, glob: String?) = emptyList<java.io.File>()
        override fun suspectedArea(className: String) = emptyList<String>()
        override fun freeMemoryPercent() = 100
        override fun now() = System.currentTimeMillis()
    }
}
