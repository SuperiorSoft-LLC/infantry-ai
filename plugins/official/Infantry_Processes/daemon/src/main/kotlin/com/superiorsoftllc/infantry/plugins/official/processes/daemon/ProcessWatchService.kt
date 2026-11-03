package com.superiorsoftllc.infantry.plugins.official.processes.daemon

import com.superiorsoftllc.infantry.daemon.api.DaemonApi
import com.superiorsoftllc.infantry.daemon.api.processes.CpuAlertConfig
import com.superiorsoftllc.infantry.daemon.api.processes.PluginSettings
import com.superiorsoftllc.infantry.daemon.api.processes.ProcessReport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * STACK-NEUTRAL-2: the running watch of Infantry_Processes. [start] wires the detector to the host (`api.processes`): the slow tick,
 * the close-time reap after its grace, the start-up and shutdown sweeps, the `processes` frame handler and the launch env contributor.
 * [close] stops the timer and everything registered; what the watch recorded stays in its ledger file.
 */
internal class ProcessWatchService private constructor(private val exec: ScheduledExecutorService, private val handles: List<AutoCloseable>, val watch: ProcessWatch) : AutoCloseable {
    override fun close() {
        handles.forEach { runCatching { it.close() } }
        exec.shutdownNow()
        runCatching { exec.awaitTermination(5, TimeUnit.SECONDS) }
    }

    companion object {
        /** How long a closed session's own exit handlers get before its leftover processes are killed. */
        const val SESSION_CLOSE_GRACE_MS = 3_000L
        const val INTERVAL_KEY = "limits.processes.watchIntervalSeconds"
        const val LEDGER_FILE = "process-ledger.json"
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun start(api: DaemonApi): ProcessWatchService {
            val os = api.processes
            val settings = api.settings
            val ledger = ProcessLedger(os, File(api.stateDir, LEDGER_FILE), os.ownerId)
            val watch = ProcessWatch(
                ledger, os,
                config = { config(settings) },
                cpuAlert = { cpuAlert(settings) },
                enabled = { WatchLaunchEnv.enabled(settings) },
                onPressure = { report ->
                    os.publishPressure(json.encodeToString(ProcessReport.serializer(), report))
                    api.log("memory pressure: ${report.freeMb} MB free (floor ${report.floorMb}), ${report.rogue.size} rogue process(es)")
                },
                log = api::log,
            )
            // Ticket 66153313: agent processes held the CPU above `limits.cpu.alertPercent` for `limits.cpu.alertMinutes`.
            watch.onCpuSustained = { report ->
                val top = report.tracked.filter { it.cpuPercent != null }.sortedByDescending { it.cpuPercent }.take(3)
                    .joinToString("; ") { "pid ${it.pid} ${it.cpuPercent}% ${it.command.take(60)}" }
                os.raiseAlert(
                    "cpu_sustained",
                    "Agent processes have used ${report.agentCpuPercent}% of all CPU cores for ${report.cpuAlertMinutes}+ min " +
                        "(threshold ${report.cpuAlertPercent}%; settings limits.cpu.alertPercent / limits.cpu.alertMinutes, 0 min = off). " +
                        "Top: ${top.ifEmpty { "none sampled" }}",
                )
            }
            val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "infantry-process-watch").also { it.isDaemon = true } }
            val intervalMs = { (settings.limit(INTERVAL_KEY) ?: 10L).coerceAtLeast(1) * 1000L }
            fun scheduleWatchPass() {
                runCatching {
                    exec.schedule({
                        runCatching { watch.tick() }.onFailure { api.log("process watch failed: ${it.message}") }
                        if (!exec.isShutdown) scheduleWatchPass()
                    }, intervalMs(), TimeUnit.MILLISECONDS)
                } // RejectedExecutionException once close() shut it down: nothing more to schedule.
            }
            scheduleWatchPass()
            val handles = ArrayList<AutoCloseable>()
            // Ticket b785f383: a closing session's background processes die with it, after a grace for its own exit handlers.
            handles += os.onSessionClosed { c ->
                runCatching {
                    exec.schedule({
                        runCatching { watch.reapSession(c.sessionId, c.at, c.keepPids, c.reason) }
                            .onFailure { api.log("close cleanup of ${c.sessionId} failed: ${it.message}") }
                    }, SESSION_CLOSE_GRACE_MS, TimeUnit.MILLISECONDS)
                }
            }
            // The first pass catches up after a crash (a session that ended while the daemon was down).
            runCatching {
                exec.schedule({
                    runCatching { watch.reapEnded("daemon start") }.onFailure { api.log("start-up cleanup failed: ${it.message}") }
                }, intervalMs(), TimeUnit.MILLISECONDS)
            }
            // A clean stop leaves nothing of an ended or removed session behind; live sessions' bg jobs stay.
            handles += os.onDaemonShutdown {
                runCatching { watch.reapEnded("daemon shutdown") }.onFailure { api.log("shutdown cleanup failed: ${it.message}") }
            }
            handles += os.serve { request -> ProcessFrames.answer(watch, request) }
            handles += api.launchEnv.contribute("watch-build") { WatchLaunchEnv.env(os, settings) }
            return ProcessWatchService(exec, handles, watch)
        }

        /** The `processes.*` settings, defaults where unset or out of range. */
        fun config(settings: PluginSettings): ProcessWatchConfig {
            val d = ProcessWatchConfig()
            fun int(key: String, range: IntRange, default: Int): Int {
                val stored = settings.stored(key) ?: return default
                val parsed = runCatching { (Json.parseToJsonElement(stored) as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull }.getOrNull()
                return parsed?.takeIf { it in range } ?: default
            }
            val autoReap = settings.stored("processes.autoReap")
                ?.let { runCatching { (Json.parseToJsonElement(it) as? JsonPrimitive)?.booleanOrNull }.getOrNull() } ?: d.autoReap
            return ProcessWatchConfig(
                idleMinutes = int("processes.idleMinutes", 1..1440, d.idleMinutes),
                heavyMb = int("processes.heavyMb", 0..1_048_576, d.heavyMb.toInt()).toLong(),
                floorMb = int("processes.floorMb", 0..1_048_576, d.floorMb.toInt()).toLong(),
                autoReap = autoReap,
            )
        }

        fun cpuAlert(settings: PluginSettings): CpuAlertConfig {
            val d = CpuAlertConfig()
            return CpuAlertConfig(
                percent = settings.limit("limits.cpu.alertPercent")?.toInt() ?: d.percent,
                minutes = settings.limit("limits.cpu.alertMinutes")?.toInt() ?: d.minutes,
            )
        }
    }
}
