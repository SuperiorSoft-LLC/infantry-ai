package com.superiorsoftllc.infantry.ui.perf

import com.superiorsoftllc.infantry.ui.net.CommandResult
import com.superiorsoftllc.infantry.ui.net.Controller
import com.superiorsoftllc.infantry.ui.protocol.AgentProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.lang.management.ManagementFactory
import java.net.ServerSocket
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * PERF-1: `./gradlew :superiorSoftLibrary:infantry:perfProfile` (alias of `:superiorSoftLibrary:infantryUi:perfProfile`).
 *
 * Boots, in a fresh temp INFANTRY home on a free port (never `~/.infantry`, never 8787/8788, see [PerfGuard]):
 *  - the DAEMON JVM exactly as `runDaemon` runs it (`DaemonMainKt` on infantry's installDist classpath), and
 *  - the UI JVM exactly as infantryUi's `run` runs it (`MainKt`, the dev window) pointed at it via `INFANTRY_WS_URL`.
 * Both with the shipped JVM caps (PERF-2, `infantry/jvm-caps.txt`, passed as `perf.jvmCaps`), plus NMT=summary unless
 * `-PperfNmt=false`.
 *
 * Measures startup to READY (daemon: its `listening on` line; UI: the daemon's next `ws connected`), then RSS/working
 * set + heap at: idle; under load (4 stub agents each streaming 2,000 lines); after `perfIdleMinutes` (default 10) of
 * idle following the load; and once after a forced full GC. Idle CPU is sampled before and after the load.
 *
 * Each run writes `docs/perf/runs/infantry-profile-<date>-<time>.json` and regenerates `docs/perf/infantry-profile-<date>.md`
 * from every run of that date (so two runs show their spread).
 */
fun main() {
    val code = try {
        PerfRun(PerfConfig.fromSystem()).execute()
        0
    } catch (e: Throwable) {
        System.err.println("[perf] FAILED: $e")
        e.printStackTrace()
        1
    }
    exitProcess(code)
}

data class PerfConfig(
    val install: File,
    val uiClasspath: List<File>,
    val testClasspath: List<File>,
    val docsDir: File,
    val workDir: File,
    val idleMinutes: Double,
    val settleSeconds: Long,
    val cpuSeconds: Long,
    val agents: Int,
    val lines: Int,
    val paceMs: Long,
    val nmt: Boolean,
    val startupTrials: Int,
    /** PERF-2: the shipped JVM heap caps, the same list the packaged launcher gets. */
    val jvmCaps: List<String> = emptyList(),
) {
    companion object {
        private fun prop(name: String) = System.getProperty(name)?.takeIf { it.isNotBlank() }
        private fun req(name: String) = prop(name) ?: error("system property $name is not set: run ./gradlew :superiorSoftLibrary:infantry:perfProfile")
        private fun cp(name: String) = File(req(name)).readLines().map { it.trim() }.filter { it.isNotEmpty() }.map(::File)

        fun fromSystem() = PerfConfig(
            install = File(req("perf.install")),
            uiClasspath = cp("perf.uiClasspathFile"),
            testClasspath = cp("perf.testClasspathFile"),
            docsDir = File(req("perf.docsDir")),
            workDir = File(req("perf.workDir")),
            idleMinutes = prop("perf.idleMinutes")?.toDouble() ?: 10.0,
            settleSeconds = prop("perf.settleSeconds")?.toLong() ?: 60,
            cpuSeconds = prop("perf.cpuSeconds")?.toLong() ?: 60,
            agents = prop("perf.agents")?.toInt() ?: 4,
            lines = prop("perf.lines")?.toInt() ?: 2_000,
            paceMs = prop("perf.paceMs")?.toLong() ?: 5,
            nmt = prop("perf.nmt")?.toBoolean() ?: true,
            startupTrials = (prop("perf.startupTrials")?.toInt() ?: 5).coerceAtLeast(1),
            jvmCaps = prop("perf.jvmCaps")?.split('|')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
        )
    }
}

class PerfRun(private val cfg: PerfConfig) {
    private val windows = PerfProbe.windows
    private val java = File(System.getProperty("java.home"), if (windows) "bin/java.exe" else "bin/java")
    private val ownerHome = File(System.getProperty("user.home"))
    private val guard = PerfGuard(ownerHome)

    private val root = Files.createTempDirectory("infantry-perf").toFile().canonicalFile
    private val home = File(root, "home")
    private val state = File(root, "state")
    private val records = File(root, "records")
    private val logs = File(root, "logs")
    private val repo = File(root, "repo")

    private val r = linkedMapOf<String, Any?>()
    private val procs = mutableListOf<Process>()
    private val notes = mutableListOf<String>()

    private val jvmFlags: List<String> = buildList {
        add("-Dfile.encoding=UTF-8")
        addAll(cfg.jvmCaps)
        if (cfg.nmt) add("-XX:NativeMemoryTracking=summary")
    }

    fun execute() {
        val started = LocalDateTime.now()
        listOf(home, state, records, logs, repo).forEach { it.mkdirs() }
        listOf(home, state, records, logs, repo).forEach { guard.requireIsolated(it, root) }
        val ownerBefore = ownerFingerprint()
        log("temp root $root; owner before: $ownerBefore")
        machine(started)
        layOutRepo()
        writeHarness()

        try {
            // Startup is the median of [PerfConfig.startupTrials] cold starts: one start alone swings 4x on this machine
            // (disk cache, Defender). Trial daemons each get their own fresh temp state dir; trial windows are closed.
            val daemonMs = (1 until cfg.startupTrials).map { k ->
                startDaemon(File(root, "state-trial-$k").apply { mkdirs(); guard.requireIsolated(this, root) }, "daemon-trial-$k.log").let { d ->
                    stop(d.process)
                    d.startupMs
                }
            }
            val daemon = startDaemon(state, "daemon.log")
            daemonLog = daemon.log
            val uiMs = (1 until cfg.startupTrials).map { k -> startUi(daemon.wsUrl, "ui-trial-$k.log").let { (p, ms) -> stop(p); ms } }
            val (ui, uiLast) = startUi(daemon.wsUrl, "ui.log")
            startup("daemon", daemonMs + daemon.startupMs)
            startup("ui", uiMs + uiLast)
            val pids = mapOf("daemon" to daemon.process.pid(), "ui" to ui.pid())
            pids.forEach { (who, pid) ->
                guard.requireChildHome(who, PerfProbe.systemProperties(pid)?.getProperty("user.home"), home)
                r["$who.vmVersion"] = PerfProbe.vmVersion(pid)
                r["$who.flags"] = PerfProbe.flags(pid)
            }
            r["guard.childHomes"] = "both JVMs report user.home=$home"

            log("settling ${cfg.settleSeconds}s before the idle snapshot")
            Thread.sleep(cfg.settleSeconds * 1000)
            snapshot("idle", pids)
            cpu("cpuIdlePct", pids)

            load(daemon.wsUrl, pids)
            snapshot("load", pids)

            log("idle for ${cfg.idleMinutes} min after the load")
            Thread.sleep((cfg.idleMinutes * 60_000).toLong())
            snapshot("afterIdle", pids)
            cpu("cpuAfterIdlePct", pids)

            pids.values.forEach(PerfProbe::fullGc)
            Thread.sleep(5_000)
            snapshot("afterGc", pids)
        } finally {
            stopAll()
        }

        verifyStubEnvs()
        val violations = guard.diff(ownerBefore, ownerFingerprint())
        r["guard.ownerUntouched"] = violations.isEmpty()
        r["guard.violations"] = violations.joinToString("; ")
        r["notes"] = notes.joinToString(" | ")
        val json = PerfReport.writeRun(cfg.docsDir, started, r)
        val doc = PerfReport.writeDoc(cfg.docsDir, started.toLocalDate())
        log("run json: $json")
        log("report:   $doc")
        keepLogs()
        check(violations.isEmpty()) { "PERF-1: the owner's state changed during the run: $violations" }
        root.deleteRecursively()
    }

    // ---- setup ------------------------------------------------------------------------------------------------------

    private fun machine(started: LocalDateTime) {
        val os = ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
        r["date"] = started.toLocalDate().toString()
        r["startedAt"] = started.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        r["machine.os"] = PerfProbe.osVersion()
        r["machine.cpu"] = PerfProbe.cpuName()
        r["machine.cores"] = Runtime.getRuntime().availableProcessors()
        r["machine.ramGb"] = "%.1f".format(os.totalMemorySize / (1024.0 * 1024 * 1024))
        r["machine.freeRamGbAtStart"] = "%.1f".format(os.freeMemorySize / (1024.0 * 1024 * 1024))
        r["profiler.java"] = "${System.getProperty("java.vendor")} ${System.getProperty("java.version")}"
        r["config.idleMinutes"] = cfg.idleMinutes
        r["config.settleSeconds"] = cfg.settleSeconds
        r["config.cpuSeconds"] = cfg.cpuSeconds
        r["config.agents"] = cfg.agents
        r["config.lines"] = cfg.lines
        r["config.paceMs"] = cfg.paceMs
        r["config.jvmFlags"] = jvmFlags.joinToString(" ")
        r["config.startupTrials"] = cfg.startupTrials
    }

    private fun layOutRepo() {
        git("init", "-b", "main")
        File(repo, "README.md").writeText("PERF-1 profile repo\n")
        git("add", "README.md")
        git("-c", "user.name=Perf", "-c", "user.email=perf@example.invalid", "-c", "commit.gpgsign=false", "commit", "-m", "perf base")
    }

    private fun writeHarness() {
        val argfile = File(root, "stub.args")
        // The stub JVM gets the temp home too: Java on Windows takes user.home from the OS profile, not the env.
        argfile.writeText("\"-Duser.home=${home.path.replace('\\', '/')}\"\n-cp\n${argQuote(cfg.testClasspath)}\n${PerfStubHarness::class.java.name}\n")
        val dir = File(state, "harnesses").apply { mkdirs() }
        fun rule(kind: String, event: String, field: String) = buildJsonObject {
            put("match", buildJsonObject { put("kind", kind) })
            put("event", event)
            put("fields", buildJsonObject { put(field, field) })
        }
        val descriptor = buildJsonObject {
            put("id", STUB)
            put("name", "PERF-1 streaming stub")
            put("command", java.path)
            put("args", buildJsonArray {
                add("@${argfile.path}")
                listOf("--records", records.path, "--lines", cfg.lines.toString(), "--pace-ms", cfg.paceMs.toString()).forEach { add(it) }
            })
            put("capabilities", buildJsonArray { add("MODEL_SELECTION"); add("SESSION_RESUME") })
            put("output", buildJsonObject {
                put("format", "jsonl")
                put("map", buildJsonArray {
                    add(rule("session", "session", "id"))
                    add(rule("text", "text", "text"))
                })
            })
            put("models", buildJsonObject {
                put("list", buildJsonArray { add(buildJsonObject { put("provider", "stub"); put("id", "perf-model") }) })
            })
        }
        File(dir, "$STUB.json").writeText(descriptor.toString())
    }

    // ---- the two JVMs -----------------------------------------------------------------------------------------------

    private class Daemon(val process: Process, val wsUrl: String, val log: File, val startupMs: Long)

    private lateinit var daemonLog: File

    private fun startup(who: String, samples: List<Long>) {
        val sorted = samples.sorted()
        r["$who.startupMs"] = sorted[sorted.size / 2]
        r["$who.startupSamplesMs"] = samples.joinToString(", ")
        log("$who startup samples $samples ms -> median ${r["$who.startupMs"]}")
    }

    private fun startDaemon(stateDir: File, logName: String): Daemon {
        val requested = freePort()
        guard.requirePort(requested, "daemon (requested)")
        val daemonLog = File(logs, logName)
        val cp = File(cfg.install, "lib").listFiles { f -> f.extension == "jar" }.orEmpty().sortedBy { it.name }
        check(cp.isNotEmpty()) { "no jars in ${cfg.install}/lib: run :superiorSoftLibrary:infantry:installDist" }
        val argfile = File(root, "daemon.args")
        val args = jvmFlags + listOf("\"-Duser.home=${home.path.replace('\\', '/')}\"", "-cp", argQuote(cp), DAEMON_MAIN)
        argfile.writeText(args.joinToString("\n"))
        r["daemon.cmdline"] = (jvmFlags + listOf("-Duser.home=<temp>", "-cp <infantry install/lib/*>", DAEMON_MAIN)).joinToString(" ")
        val b = ProcessBuilder(java.path, "@${argfile.path}").directory(repo).redirectErrorStream(true).redirectOutput(daemonLog)
        b.environment().apply {
            scrub(this)
            put("INFANTRY_PORT", requested.toString())
            put("INFANTRY_PORT_STRICT", "true")
            put("INFANTRY_PROVIDER", "stub")
            put("INFANTRY_STATE_DIR", stateDir.path)
            put("INFANTRY_HOME", stateDir.path)
            put("INFANTRY_LIMITS_LIVE", "false")
            put("INFANTRY_BIN", File(cfg.install, "bin").path)
        }
        val t0 = System.nanoTime()
        val p = b.start().also { procs += it }
        val boot = awaitLog(daemonLog, p, BOOT_LINE, "the daemon's READY line")
        val ms = (System.nanoTime() - t0) / 1_000_000
        val port = boot.groupValues[2].toInt()
        guard.requirePort(port, "daemon (bound)")
        val portFile = File(stateDir, "daemon.port")
        check(portFile.isFile && portFile.readText().trim() == port.toString()) { "PERF-1: the daemon did not record port $port in the temp $portFile" }
        r["guard.ports"] = "daemon bound $port (requested $requested); owner ports ${PerfGuard.OWNER_PORTS} untouched"
        log("daemon READY on $port in $ms ms ($logName)")
        return Daemon(p, "ws://${boot.groupValues[1]}:$port/ws", daemonLog, ms)
    }

    private fun startUi(wsUrl: String, logName: String): Pair<Process, Long> {
        val argfile = File(root, "ui.args")
        val args = jvmFlags + listOf("\"-Duser.home=${home.path.replace('\\', '/')}\"", "-cp", argQuote(cfg.uiClasspath), UI_MAIN)
        argfile.writeText(args.joinToString("\n"))
        r["ui.cmdline"] = (jvmFlags + listOf("-Duser.home=<temp>", "-cp <infantryUi main runtime classpath>", UI_MAIN)).joinToString(" ")
        val log = File(logs, logName)
        val b = ProcessBuilder(java.path, "@${argfile.path}").directory(repo).redirectErrorStream(true).redirectOutput(log)
        b.environment().apply {
            scrub(this)
            put("INFANTRY_WS_URL", wsUrl)
            put("INFANTRY_LIMITS_LIVE", "false")
        }
        val connectsBefore = CONNECTED.findAll(daemonLog.readText()).count()
        val t0 = System.nanoTime()
        val p = b.start().also { procs += it }
        val end = System.currentTimeMillis() + 120_000
        while (CONNECTED.findAll(daemonLog.readText()).count() <= connectsBefore) {
            check(p.isAlive) { "the UI exited (${p.exitValue()}) before connecting: ${log.readLines().takeLast(20)}" }
            check(System.currentTimeMillis() < end) { "the UI did not connect within 120 s: ${log.readLines().takeLast(20)}" }
            Thread.sleep(20)
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        log("UI READY (connected) in $ms ms ($logName)")
        return p to ms
    }

    // ---- the load ---------------------------------------------------------------------------------------------------

    private fun load(wsUrl: String, pids: Map<String, Long>) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val driver = Controller(scope)
        try {
            driver.connect(wsUrl, null)
            until("the driver connected") { driver.state.value.connection.isLive }
            repeat(cfg.agents) { i ->
                sent("save perf agent $i", runBlocking { driver.saveProfile(AgentProfile(id = "perf-$i", name = "Perf $i", role = "backend", provider = STUB)) })
            }
            until("the ${cfg.agents} perf profiles are stored") { driver.state.value.profiles.count { it.id?.startsWith("perf-") == true } >= cfg.agents }

            val peak = ConcurrentHashMap<String, Double>()
            val sampling = AtomicBoolean(true)
            val sampler = thread(isDaemon = true, name = "perf-peak") {
                while (sampling.get()) {
                    PerfProbe.osMem(pids.values.toList()).forEach { (pid, m) ->
                        val who = pids.entries.first { it.value == pid }.key
                        peak[who] = maxOf(peak[who] ?: 0.0, m.workingSetMb)
                    }
                    Thread.sleep(1_000)
                }
            }
            val t0 = System.nanoTime()
            repeat(cfg.agents) { i -> sent("start perf agent $i", runBlocking { driver.spawnAs("perf-$i", "PERF-1: stream your transcript.") }) }
            val end = System.currentTimeMillis() + 300_000
            while (doneCount() < cfg.agents) {
                check(System.currentTimeMillis() < end) { "only ${doneCount()} of ${cfg.agents} stub agents finished in 5 min; daemon log: ${daemonLog.readLines().takeLast(15)}" }
                Thread.sleep(200)
            }
            r["load.streamMs"] = (System.nanoTime() - t0) / 1_000_000
            Thread.sleep(15_000) // let both JVMs drain the frames before the snapshot
            sampling.set(false)
            sampler.join(5_000)
            peak.forEach { (who, mb) -> r["$who.loadPeak.wsMb"] = mb }
            val entries = driver.state.value.agents.values.sumOf { it.entries.size }
            r["load.driverTranscriptEntries"] = entries
            log("load done in ${r["load.streamMs"]} ms; the driver window holds $entries transcript entries")
        } finally {
            runCatching { driver.disconnect() }
            scope.cancel()
        }
    }

    private fun doneCount() = records.listFiles { f -> f.name.endsWith(".done") }?.size ?: 0

    // ---- measurements -----------------------------------------------------------------------------------------------

    private fun snapshot(point: String, pids: Map<String, Long>) {
        val mem = PerfProbe.osMem(pids.values.toList())
        pids.forEach { (who, pid) ->
            val m = mem[pid] ?: error("$who (pid $pid) is gone at $point")
            r["$who.$point.wsMb"] = m.workingSetMb
            r["$who.$point.privateMb"] = m.privateMb
            r["$who.$point.threads"] = m.threads
            PerfProbe.heap(pid)?.let { h ->
                r["$who.$point.heapUsedMb"] = h.usedMb
                r["$who.$point.heapCommittedMb"] = h.committedMb
            }
            if (cfg.nmt) PerfProbe.nmt(pid).forEach { (cat, mb) -> r["$who.$point.nmt.$cat"] = mb }
        }
        val ar = PerfProbe.processesNamed("ReferenceApp")
        r["reference.$point.wsMb"] = ar.values.sum()
        r["reference.$point.processes"] = ar.size
        r["reference.$point.each"] = ar.values.sortedDescending().joinToString(" + ") { "%.0f".format(it) }
        log("$point: " + pids.keys.joinToString { who -> "$who ws=%.0f MB heap=%s MB".format(r["$who.$point.wsMb"], r["$who.$point.heapUsedMb"]?.let { "%.0f".format(it) }) } +
            "; reference app %.0f MB / ${ar.size} procs".format(ar.values.sum()))
    }

    private fun cpu(key: String, pids: Map<String, Long>) {
        PerfProbe.cpuPercent(pids.values.toList(), cfg.cpuSeconds).forEach { (pid, pct) ->
            r["${pids.entries.first { it.value == pid }.key}.$key"] = pct
        }
        log("$key: " + pids.keys.joinToString { "$it=%.2f%%".format(r["$it.$key"]) })
    }

    // ---- isolation ----------------------------------------------------------------------------------------------------

    private fun ownerFingerprint() = guard.ownerFingerprint(PerfGuard.OWNER_PORTS.associateWith { PerfProbe.listener(it) })

    /** Every stub agent was told to call THIS daemon, and ran with the temp home. */
    private fun verifyStubEnvs() {
        val envs = records.listFiles { f -> f.name.endsWith(".env.properties") }.orEmpty()
        check(envs.size >= cfg.agents) { "only ${envs.size} stub env records" }
        envs.forEach { f ->
            val p = Properties().apply { f.inputStream().use { load(it) } }
            val url = p.getProperty("INFANTRY_URL").orEmpty()
            val port = Regex(""":(\d+)/""").find(url)?.groupValues?.get(1)?.toInt()
            check(port != null) { "PERF-1: stub ${f.name} got no INFANTRY_URL ($url)" }
            guard.requirePort(port, "stub agent ${f.name}")
            guard.requireIsolated(File(p.getProperty("user.home")), root)
        }
        r["guard.stubs"] = "${envs.size} stub agents called the temp daemon with a temp user.home"
    }

    // ---- plumbing ---------------------------------------------------------------------------------------------------

    private fun stopAll() {
        procs.toList().asReversed().forEach(::stop)
    }

    private fun stop(p: Process) {
        runCatching { p.descendants().forEach { it.destroyForcibly() } }
        p.destroyForcibly()
        p.waitFor(20, TimeUnit.SECONDS)
        procs.remove(p)
    }

    private fun keepLogs() {
        val target = File(cfg.workDir, "last").apply { deleteRecursively() }
        runCatching { logs.copyRecursively(File(target, "logs"), overwrite = true) }
        runCatching { records.copyRecursively(File(target, "records"), overwrite = true) }
    }

    private fun until(what: String, timeoutMs: Long = 60_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "timed out waiting for $what" }
            Thread.sleep(25)
        }
    }

    private fun sent(what: String, result: CommandResult) {
        check(result is CommandResult.Sent) { "could not $what: $result" }
    }

    private fun git(vararg args: String) {
        val b = ProcessBuilder(listOf("git") + args).directory(repo).redirectErrorStream(true)
        scrub(b.environment())
        val p = b.start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0) { "git ${args.joinToString(" ")} failed: $out" }
    }

    private fun log(msg: String) {
        println("[perf] $msg")
        System.out.flush()
    }

    companion object {
        const val STUB = "perf-stub"
        const val DAEMON_MAIN = "com.superiorsoftllc.infantry.daemon.DaemonMainKt"
        const val UI_MAIN = "com.superiorsoftllc.infantry.ui.MainKt"
        private val BOOT_LINE = Regex("""\[infantry] listening on ws://([^:\s]+):(\d+)/ws""")
        private val CONNECTED = Regex("""\[infantry] ws connected""")

        /** No inherited GIT_* or INFANTRY_* reaches a child: an INFANTRY_URL from this shell would name the owner's daemon. */
        fun scrub(env: MutableMap<String, String>) {
            env.keys.filter { k -> k.uppercase().let { it.startsWith("GIT_") || it.startsWith("INFANTRY_") } }.forEach { env.remove(it) }
        }

        private fun freePort(): Int {
            repeat(20) {
                val port = ServerSocket(0).use { it.localPort }
                if (port !in PerfGuard.OWNER_PORTS) return port
            }
            error("no free port outside ${PerfGuard.OWNER_PORTS}")
        }

        private fun argQuote(classpath: List<File>): String =
            "\"" + classpath.joinToString(File.pathSeparator) { it.absolutePath.replace('\\', '/') } + "\""

        private fun awaitLog(log: File, p: Process, line: Regex, what: String): MatchResult {
            val end = System.currentTimeMillis() + 120_000
            while (System.currentTimeMillis() < end) {
                val text = runCatching { log.readText() }.getOrDefault("")
                line.find(text)?.let { return it }
                check(p.isAlive) { "exited (${p.exitValue()}) before $what: ${text.lines().takeLast(20)}" }
                Thread.sleep(10)
            }
            error("no $what within 120 s")
        }
    }
}
