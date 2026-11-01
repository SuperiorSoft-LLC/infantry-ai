package com.superiorsoftllc.agenthq.testrun

import java.io.File

/** Runs a command at low priority in [dir]; the daemon implements it over the bg job runner. */
interface SuiteExec {
    data class Out(val exitCode: Int, val output: String)

    fun run(command: String, dir: File, timeoutMinutes: Long): Out

    /** SCRIPT-HOOK-3 (TST-02): ends the command in flight, if any (its exit code then reports non-zero). */
    fun stop() {}
}

/** Where failing tests go: tickets in todo/backlog, deduplicated by the runner. */
interface FailureSink {
    /** @return the new ticket id */
    fun create(title: String, description: String): String

    fun comment(ticketId: String, text: String)

    /** Mail the PM, only for a NEW failing test. */
    fun notifyNew(text: String)
}

interface SuiteEnv {
    fun headSha(dir: File): String

    /** Check out / reuse the configured branch in the runner's own worktree. */
    fun prepareWorktree(branch: String): File

    /** Result files matching [glob] under [dir]. */
    fun results(dir: File, glob: String?): List<File>

    /** Suspected area: the tickets that last changed [className]'s file (ticket-files index); empty when unknown. */
    fun suspectedArea(className: String): List<String>

    /** Free memory percent (0-100). */
    fun freeMemoryPercent(): Int

    fun now(): Long
}

/**
 * Background full-suite runner. Never gates: callers invoke it on a schedule or by hand; nothing waits on it.
 * Refuses (does nothing) when tests.fullCommand or tests.workingBranch is unset.
 */
class FullSuiteRunner(
    private val env: SuiteEnv,
    private val exec: SuiteExec,
    private val sink: FailureSink,
    private val parsers: Map<String, ResultParser> = listOf(JunitXmlParser, ExitCodeParser).associateBy { it.format },
) {
    fun run(cfg: TestRunConfig, ledger: Ledger): Ledger {
        val t0 = env.now()
        val command0 = cfg.fullCommand ?: return refuse(ledger, t0, "tests.fullCommand is not set; set it in the project data, nothing was run")
        val branch = cfg.workingBranch ?: return refuse(ledger, t0, "tests.workingBranch is not set; set it in the project data, nothing was run")
        val parser = parsers[cfg.resultFormat] ?: return refuse(ledger, t0, "unknown tests.resultFormat '${cfg.resultFormat}'")
        if (ledger.running) return skipped(ledger, t0, "a run is already in progress")
        if (cfg.memoryPressureThreshold > 0 && env.freeMemoryPercent() < cfg.memoryPressureThreshold) {
            return skipped(ledger, t0, "memory pressure: free ${env.freeMemoryPercent()}% < ${cfg.memoryPressureThreshold}%")
        }

        val dir = env.prepareWorktree(branch)
        val sha = env.headSha(dir)
        val command = listOf(command0, cfg.envOrArgs).filter { it.isNotBlank() }.joinToString(" ")
        val first = exec.run(command, dir, cfg.timeoutMinutes)
        val cases = parser.parse(env.results(dir, cfg.resultGlob), first.exitCode, first.output)
        val failing = cases.filter { it.status == TestStatus.FAIL }

        // A failure that passes on rerun is flaky, not a bug.
        val flakyIds = mutableSetOf<String>()
        if (failing.isNotEmpty() && cfg.rerunCommand != null) {
            val again = exec.run(cfg.rerunCommand, dir, cfg.timeoutMinutes)
            val passedAgain = parser.parse(env.results(dir, cfg.resultGlob), again.exitCode, again.output)
                .filter { it.status == TestStatus.PASS }.map { it.identity }.toSet()
            failing.filter { it.identity in passedAgain }.forEach { flakyIds += it.identity }
        }

        val records = ledger.failures.toMutableMap()
        for (t in failing) {
            val id = t.identity
            val old = records[id]
            val ticket = old?.ticketId
            if (id in flakyIds) {
                records[id] = (old ?: FailureRecord(id, null, sha, sha)).copy(lastSha = sha, flaky = true)
            } else if (ticket == null) {
                val area = env.suspectedArea(t.className).joinToString(", ").ifEmpty { "unknown" }
                val tid = sink.create("Test failing: ${t.className}.${t.name}".trim(), describe(t, sha, area))
                records[id] = (old ?: FailureRecord(id, null, sha, sha)).copy(ticketId = tid, lastSha = sha, flaky = false)
                sink.notifyNew("New failing test on $branch at ${sha.take(8)}: ${t.className}.${t.name} (ticket $tid)")
            } else {
                val n = old.failCount + 1
                records[id] = old.copy(lastSha = sha, failCount = n, flaky = false)
                sink.comment(ticket, "Still failing at ${sha.take(8)} (run $n).\n${t.output}")
            }
        }
        val real = failing.filter { it.identity !in flakyIds }.map { it.identity }
        val res = RunResult(
            sha, t0, env.now(), cases.count { it.status == TestStatus.PASS }, real.size, cases.count { it.status == TestStatus.SKIP },
            real, flakyIds.toList(), "ran",
        )
        return Ledger(records, res, false)
    }

    private fun describe(t: TestCase, sha: String, area: String) =
        "Found by the background full-suite run (never gates).\n\nTest: ${t.identity}\nCommit: $sha\n" +
            "Suspected area (tickets that last changed the file): $area\n\nOutput:\n${t.output}"

    private fun refuse(l: Ledger, t0: Long, msg: String) =
        l.copy(last = RunResult("", t0, env.now(), 0, 0, 0, outcome = "refused", message = msg), running = false)

    private fun skipped(l: Ledger, t0: Long, msg: String) =
        l.copy(last = RunResult("", t0, env.now(), 0, 0, 0, outcome = "skipped", message = msg))

    companion object {
        /** Status text; a run in progress is neutral wording. */
        fun status(l: Ledger): String {
            val last = l.last
            return when {
                l.running -> "tests: running"
                last == null -> "tests: no run yet"
                last.outcome != "ran" -> "tests: ${last.outcome} (${last.message})"
                last.failed == 0 -> "tests: green"
                else -> "tests: ${last.failing.size} failing"
            }
        }
    }
}
