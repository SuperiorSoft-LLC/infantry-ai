package com.superiorsoftllc.agenthq.ui.perf

import java.io.File
import java.util.Properties
import java.util.concurrent.TimeUnit

/**
 * PERF-1: read-only measurements of a process from the outside. Nothing here changes the measured process except
 * [fullGc], which the profiler calls once, at the very end.
 *
 * - OS memory: Windows `Get-Process` (WorkingSet64, PrivateMemorySize64, threads, handles); Linux `/proc/<pid>/status`.
 * - JVM heap: `jcmd <pid> GC.heap_info` (total = committed, used), NMT via `jcmd <pid> VM.native_memory summary`.
 * - CPU: [ProcessHandle.Info.totalCpuDuration] sampled over a window.
 */
object PerfProbe {
    val windows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
    private val javaHome = File(System.getProperty("java.home"))
    private val jcmd = File(javaHome, if (windows) "bin/jcmd.exe" else "bin/jcmd")

    data class OsMem(val workingSetMb: Double, val privateMb: Double?, val threads: Int?, val handles: Int?)

    data class Heap(val committedMb: Double, val usedMb: Double)

    fun osMem(pids: List<Long>): Map<Long, OsMem> = if (windows) {
        val ids = pids.joinToString(",")
        powershell("Get-Process -Id $ids -ErrorAction SilentlyContinue | ForEach-Object { \"\$(\$_.Id) \$(\$_.WorkingSet64) \$(\$_.PrivateMemorySize64) \$(\$_.Threads.Count) \$(\$_.HandleCount)\" }")
            .lines().mapNotNull { line ->
                val f = line.trim().split(' ').takeIf { it.size == 5 }?.map { it.toLongOrNull() ?: return@mapNotNull null } ?: return@mapNotNull null
                f[0] to OsMem(mb(f[1]), mb(f[2]), f[3].toInt(), f[4].toInt())
            }.toMap()
    } else {
        pids.associateWith { pid ->
            val status = File("/proc/$pid/status").readLines().associate { it.substringBefore(':') to it.substringAfter(':').trim() }
            OsMem(
                (status["VmRSS"]?.substringBefore(' ')?.toLong() ?: 0) / 1024.0,
                status["VmData"]?.substringBefore(' ')?.toLong()?.div(1024.0),
                status["Threads"]?.toInt(),
                null,
            )
        }
    }

    /** Every process whose name matches [name] (Windows only): pid -> working set MB. */
    fun processesNamed(name: String): Map<Long, Double> = if (!windows) emptyMap() else
        powershell("Get-Process -Name '$name' -ErrorAction SilentlyContinue | ForEach-Object { \"\$(\$_.Id) \$(\$_.WorkingSet64)\" }")
            .lines().mapNotNull { l -> l.trim().split(' ').takeIf { it.size == 2 }?.let { (id, ws) -> (id.toLongOrNull() ?: return@mapNotNull null) to mb(ws.toLongOrNull() ?: return@mapNotNull null) } }.toMap()

    /** Who listens on [port] (Windows only; null = nobody or not measurable). */
    fun listener(port: Int): Long? = if (!windows) null else
        powershell("Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty OwningProcess")
            .trim().toLongOrNull()

    private val HEAP = Regex("""total (\d+)K, used (\d+)K""")

    fun heap(pid: Long): Heap? = HEAP.find(jcmd(pid, "GC.heap_info"))?.let { m ->
        Heap(m.groupValues[1].toLong() / 1024.0, m.groupValues[2].toLong() / 1024.0)
    }

    private val NMT_TOTAL = Regex("""Total: reserved=(\d+)KB, committed=(\d+)KB""")
    private val NMT_ROW = Regex("""^-\s+(.+?) \(reserved=(\d+)KB, committed=(\d+)KB""", RegexOption.MULTILINE)

    /** NMT committed MB per category, plus "Total"; empty when the JVM was started without NMT. */
    fun nmt(pid: Long): Map<String, Double> {
        val out = jcmd(pid, "VM.native_memory", "summary")
        val total = NMT_TOTAL.find(out) ?: return emptyMap()
        return buildMap {
            put("Total", total.groupValues[2].toLong() / 1024.0)
            NMT_ROW.findAll(out).forEach { put(it.groupValues[1].trim(), it.groupValues[3].toLong() / 1024.0) }
        }
    }

    fun systemProperties(pid: Long): Properties? {
        val out = jcmd(pid, "VM.system_properties")
        if ("user.home" !in out) return null
        return Properties().apply { load(out.lines().drop(1).joinToString("\n").reader()) }
    }

    fun flags(pid: Long): String = jcmd(pid, "VM.flags").lines().drop(1).joinToString(" ").trim()

    fun vmVersion(pid: Long): String = jcmd(pid, "VM.version").lines().drop(1).firstOrNull { it.isNotBlank() }.orEmpty().trim()

    fun fullGc(pid: Long) {
        jcmd(pid, "GC.run")
    }

    fun cpuMillis(pid: Long): Long? = ProcessHandle.of(pid).flatMap { it.info().totalCpuDuration() }.map { it.toMillis() }.orElse(null)

    /** CPU of each pid over [seconds], as % of ONE core (100 = a full core busy). */
    fun cpuPercent(pids: List<Long>, seconds: Long): Map<Long, Double?> {
        val start = pids.associateWith { cpuMillis(it) }
        val t0 = System.nanoTime()
        Thread.sleep(seconds * 1000)
        val wallMs = (System.nanoTime() - t0) / 1_000_000.0
        return pids.associateWith { pid ->
            val a = start[pid]
            val b = cpuMillis(pid)
            if (a == null || b == null) null else (b - a) * 100.0 / wallMs
        }
    }

    fun cpuName(): String = if (windows) {
        powershell("(Get-CimInstance Win32_Processor | Select-Object -First 1).Name").trim()
    } else {
        runCatching { File("/proc/cpuinfo").readLines().first { it.startsWith("model name") }.substringAfter(':').trim() }.getOrDefault("unknown")
    }

    fun osVersion(): String = if (windows) {
        powershell("\$o = Get-CimInstance Win32_OperatingSystem; \"\$(\$o.Caption) \$(\$o.Version)\"").trim()
    } else {
        "${System.getProperty("os.name")} ${System.getProperty("os.version")}"
    }

    private fun mb(bytes: Long) = bytes / (1024.0 * 1024.0)

    private fun jcmd(pid: Long, vararg command: String): String = run(listOf(jcmd.path, pid.toString()) + command)

    /** `-EncodedCommand` (UTF-16LE base64): Java's Windows argument quoting would mangle the script's own quotes. */
    private fun powershell(script: String): String = run(
        listOf("powershell", "-NoProfile", "-NonInteractive", "-EncodedCommand", java.util.Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))),
    )

    private fun run(argv: List<String>): String {
        val p = ProcessBuilder(argv).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(60, TimeUnit.SECONDS)) p.destroyForcibly()
        return out
    }
}
