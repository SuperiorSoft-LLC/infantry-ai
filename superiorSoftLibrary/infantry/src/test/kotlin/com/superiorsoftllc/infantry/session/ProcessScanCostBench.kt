// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.session

import java.lang.management.ManagementFactory
import kotlin.test.Test

/**
 * PROC-WATCH-CPU-1: the CPU one simulated minute of the daemon's process scans costs on THIS machine, with real OS processes.
 * Off by default (it spawns processes and takes ~a minute): `INFANTRY_PROC_BENCH=1`. Per minute the daemon runs 30 background
 * scans (every 2 s: [BackgroundWorkScanner.tops] + [BackgroundWorkScanner.scan] per session with a kept process) and 6
 * process-watch passes (every 10 s: `info` + `descendants` per session root). Prints CPU seconds per minute.
 */
class ProcessScanCostBench {
    @Test
    fun `cpu seconds per minute of the session and process-watch scans`() {
        if (System.getenv("INFANTRY_PROC_BENCH") != "1") return
        val sessions = System.getenv("INFANTRY_PROC_BENCH_SESSIONS")?.toIntOrNull() ?: 8
        val windows = System.getProperty("os.name").lowercase().contains("win")
        val argv = if (windows) listOf("cmd.exe", "/c", "ping -n 600 127.0.0.1 >nul") else listOf("sh", "-c", "sleep 600")
        val roots = (1..sessions).map { ProcessBuilder(argv).redirectErrorStream(true).start() }
        try {
            Thread.sleep(1500)
            val source: ProcessSource = OsProcessSource
            val bean = ManagementFactory.getThreadMXBean()
            fun minute(): Long {
                val t0 = bean.currentThreadCpuTime
                repeat(30) { tick ->
                    ProcessSnapshots.newTick()
                    for (r in roots) {
                        BackgroundWorkScanner.tops(source, r.pid(), null)
                        BackgroundWorkScanner.scan(source, r.pid(), null)
                    }
                    if (tick % 5 == 0) for (r in roots) { source.info(r.pid()); source.descendants(r.pid()) }
                }
                return bean.currentThreadCpuTime - t0
            }
            minute() // warm-up (class loading, JNA)
            val runs = (1..3).map { minute() }
            val perMin = runs.sorted()[1] / 1e9
            val kids = roots.sumOf { source.descendants(it.pid()).size }
            println("PROC-BENCH sessions=$sessions descendants=$kids cpuSecondsPerMinute=${"%.3f".format(perMin)} runs=${runs.map { "%.3f".format(it / 1e9) }}")
        } finally {
            roots.forEach { p -> p.toHandle().descendants().forEach { it.destroyForcibly() }; p.destroyForcibly() }
        }
    }
}
