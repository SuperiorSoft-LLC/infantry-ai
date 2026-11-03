package com.superiorsoftllc.infantry.plugins.official.run.daemon

import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.daemon.api.processes.PluginSettings
import com.superiorsoftllc.infantry.daemon.api.runs.RunHandle
import com.superiorsoftllc.infantry.daemon.api.runs.RunListener
import com.superiorsoftllc.infantry.daemon.api.runs.RunSpec
import com.superiorsoftllc.infantry.daemon.api.runs.Runs
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import java.util.Base64
import java.util.UUID

/**
 * Contract 1.33.0 `run` frame. `state` is STARTING, RUNNING or FINISHED.
 * 1.134.0 (RUN-CONSOLE): [name] (the command's name at start, for the pill), [terminal] (the run has a pty and streams
 * `run_bytes`) and [interactive] (the run takes `run_input`).
 */
@Serializable
data class RunRecord(
    val runId: String,
    val commandId: String,
    val state: String,
    val startedAt: Long,
    val pid: Long? = null,
    val lastSequence: Long = 0,
    val name: String? = null,
    val terminal: Boolean = false,
    val interactive: Boolean = false,
)

/**
 * 1.134.0 `run_bytes`: raw terminal output, base64 in [data]. [sequence] counts chunks 1..n per run, apart from the
 * `run_output` line sequence. A [replay] chunk is the answer to `run_attach`: the kept scrollback up to and including
 * chunk [sequence] ([droppedBytes] fell off the front); a console resets, writes it, then applies live chunks after it.
 */
@Serializable
data class RunBytes(
    val runId: String,
    val sequence: Long,
    val data: String,
    val replay: Boolean = false,
    val droppedBytes: Long = 0,
)

/** 1.134.0 `runs`: the daemon's recent runs, newest first, the answer to `run_list` (the Run tab's pills). */
@Serializable
data class RunSummary(
    val runId: String,
    val commandId: String,
    val name: String,
    val state: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val exitCode: Int? = null,
    val outcome: String? = null,
    val terminal: Boolean = false,
    val interactive: Boolean = false,
)

@Serializable
data class RunOutput(val runId: String, val sequence: Long, val stream: String, val line: String, val truncated: Boolean = false)

@Serializable
data class RunFinished(val runId: String, val exitCode: Int? = null, val outcome: String, val endedAt: Long, val detail: String? = null)

/**
 * IN-APP BUILD T3: runs ONE saved command at a time through a [ProcessRunner] (argv list, never a shell string).
 *
 * `run_output` sequences are 1..n per run with no gap or repeat; exactly one `run_finished` follows per run.
 * Frames go out through [emit] (broadcast); the `run` answer is handed back to the caller so it can be sent
 * BEFORE [Started.launch] lets any output flow.
 */
class RunManager(
    /** Read on every `run_start`, so a `command_save` / `command_delete` (RUN-CMD-1) is seen by the next run. */
    private val registry: () -> CommandRegistry,
    private val runs: Runs,
    private val emit: (Envelope) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    /** RUN-CONSOLE: a run asks for a pty and streams run_bytes as well as run_output lines. False, or a host with no pty, = pipes. */
    private val usePty: Boolean = true,
    private val limits: RunLimits = RunLimits(),
    /** A restricted policy (its `restricted`): no interactive run, no keyboard into a run (no raw shell), and no run gets a secret (SECRETS-2). */
    private val restricted: () -> Boolean = { false },
    /**
     * SECRETS-2: a keystore secret's fields by name (null = no such secret). Read on the daemon's side at `run_start`;
     * the values go only into the child's environment and are masked in everything it prints.
     */
    private val secrets: (String) -> Map<String, String>? = { null },
) {
    /** A fixed set of commands (the tests, and any caller with no store behind it). */
    constructor(registry: CommandRegistry, runs: Runs, emit: (Envelope) -> Unit, now: () -> Long = System::currentTimeMillis) :
        this({ registry }, runs, emit, now)

    /** The run limits, read at every use (settings `limits.run.*`; the defaults when no settings are behind). [hint] ends a refusal with where to change it. */
    class RunLimits(
        val scrollbackBytes: () -> Int = { 2 * 1024 * 1024 },
        val history: () -> Int = { 20 },
        /** SEC-FIX-1 M2: `limits.run.inputBytesPerSecond` (0 = unlimited), the per-run keyboard rate. */
        val inputBytesPerSecond: () -> Int = { 64 * 1024 },
        /** SEC-FIX-1 M2: `limits.run.inputMaxFrameBytes`, the largest single `run_input`. */
        val inputMaxFrameBytes: () -> Int = { 16 * 1024 },
        /** SEC-FIX-1 M2: `limits.run.inputQueueBytes`, input waiting for the pty writer. */
        val inputQueueBytes: () -> Int = { 256 * 1024 },
        /** SEC-FIX-1 M2: `limits.run.maxCols` / `limits.run.maxRows`, the clamp on a pty size. */
        val maxCols: () -> Int = { 1000 },
        val maxRows: () -> Int = { 1000 },
        val hint: (key: String) -> String = { "" },
    ) {
        companion object {
            /** The limits as the daemon settings hold them (`api.settings`). */
            fun from(settings: PluginSettings): RunLimits {
                val d = RunLimits()
                fun cap(key: String, dflt: () -> Int, zeroIsUnlimited: Boolean = false): () -> Int = {
                    val v = settings.limit(key)
                    if (v == null) dflt() else if (zeroIsUnlimited && v == 0L) Int.MAX_VALUE else v.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                }
                return RunLimits(
                    cap("limits.run.scrollbackBytes", d.scrollbackBytes), cap("limits.run.history", d.history),
                    cap("limits.run.inputBytesPerSecond", d.inputBytesPerSecond, true), cap("limits.run.inputMaxFrameBytes", d.inputMaxFrameBytes),
                    cap("limits.run.inputQueueBytes", d.inputQueueBytes), cap("limits.run.maxCols", d.maxCols), cap("limits.run.maxRows", d.maxRows),
                    settings::hint,
                )
            }
        }
    }

    /** The answer to a `run_start`, and the thunk that actually starts the child. */
    class Started internal constructor(val reply: Envelope, private val launcher: (() -> Unit)?) {
        fun launch() { launcher?.invoke() }
    }

    private val lock = Any()
    private var inFlight: Run? = null
    /** Newest last; finished runs past [RunLimits.history] are forgotten, their scrollback with them. */
    private val history = ArrayDeque<Run>()

    /**
     * `run_start {"commandId", "interactive"?, "cols"?, "rows"?}`. [interactive] (1.134.0) lets a console type
     * into the run and is refused under a restricted policy; cols / rows size the pty before its first byte (clamped, 1.136.0).
     * The run gets its saved command's secrets (1.135.0) and no others: a `secrets` override is refused (1.136.0, SEC-FIX-1).
     */
    fun start(data: String): Started {
        val obj = runCatching { wire.parseToJsonElement(data) }.getOrNull() as? JsonObject
        val commandId = (obj?.get("commandId") as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return refuse("run_start expects {\"commandId\":\"<id>\"}")
        val interactive = (obj["interactive"] as? JsonPrimitive)?.booleanOrNull ?: false
        if (interactive && restricted()) return refuse("run_start refused: interactive runs are off under a restricted policy")
        val cols = clampCols((obj["cols"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 } ?: DEFAULT_COLS)
        val rows = clampRows((obj["rows"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 } ?: DEFAULT_ROWS)
        // SEC-FIX-1 H1(a): a run gets its SAVED command's secrets only; a request naming more is refused, never merged.
        if (obj.containsKey("secrets")) return refuse("run_start refused: a run gets only its saved command's secrets; \"secrets\" is not accepted here")
        val run: Run
        val command: SavedCommand
        // The lookup is under [lock] too, so a save or delete running in [unlessRunning] cannot slip between them.
        synchronized(lock) {
            command = registry().find(commandId) ?: return refuse("run_start refused: unknown command '$commandId'")
            if (inFlight != null) return refuse("run_start refused: a run is already in flight")
            val env = secretEnv(command.secrets.distinct()).getOrElse { return refuse("run_start refused: ${it.message}") }
            run = Run(UUID.randomUUID().toString(), command, now(), interactive, cols, rows, env)
            inFlight = run
            history.addLast(run)
            prune()
        }
        return Started(Envelope(Envelope.RUN, wire.encodeToString(RunRecord.serializer(), run.record("STARTING")))) { run.launch() }
    }

    /** `run_list` -> `runs`: the kept runs, newest first. */
    fun list(): Envelope {
        val all = synchronized(lock) { history.toList() }.asReversed().map { it.summary() }
        return Envelope(Envelope.RUNS, wire.encodeToString(ListSerializer(RunSummary.serializer()), all))
    }

    /** `run_attach {"runId"}` -> one replay `run_bytes` (the kept scrollback), finished runs too; else an ERROR. */
    fun attach(data: String): Envelope {
        val runId = field(data, "runId") ?: return Envelope(Envelope.ERROR, "run_attach expects {\"runId\":\"<id>\"}")
        val run = synchronized(lock) { history.lastOrNull { it.runId == runId } }
            ?: return Envelope(Envelope.ERROR, "run_attach refused: no such run")
        return run.replay()
    }

    /** `run_resize {"runId", "cols", "rows"}`: resizes the run's pty. Null when done, else an ERROR. */
    fun resize(data: String): Envelope? {
        val obj = runCatching { wire.parseToJsonElement(data) }.getOrNull() as? JsonObject
        val runId = (obj?.get("runId") as? JsonPrimitive)?.takeIf { it.isString }?.content
        val cols = (obj?.get("cols") as? JsonPrimitive)?.intOrNull
        val rows = (obj?.get("rows") as? JsonPrimitive)?.intOrNull
        if (runId == null || cols == null || rows == null || cols <= 0 || rows <= 0)
            return Envelope(Envelope.ERROR, "run_resize expects {\"runId\", \"cols\" > 0, \"rows\" > 0}")
        val run = synchronized(lock) { inFlight }?.takeIf { it.runId == runId }
            ?: return Envelope(Envelope.ERROR, "run_resize refused: no such run in flight")
        run.resize(clampCols(cols), clampRows(rows))
        return null
    }

    /** SEC-FIX-1 M2: a pty size is clamped to 1..`limits.run.maxCols` / `maxRows`, never refused. */
    private fun clampCols(cols: Int): Int = cols.coerceIn(1, limits.maxCols().coerceAtLeast(1))
    private fun clampRows(rows: Int): Int = rows.coerceIn(1, limits.maxRows().coerceAtLeast(1))

    /**
     * `run_input {"runId", "data": base64}`: keyboard bytes into the run's pty. Only for a run started interactive and
     * never under a restricted policy. SEC-FIX-1 M2: at most `limits.run.inputMaxFrameBytes` per frame and
     * `limits.run.inputBytesPerSecond` per run; the bytes are queued (at most `limits.run.inputQueueBytes`) for the
     * run's own writer thread, so a slow pty never holds the WebSocket loop. Null when queued, else an ERROR.
     */
    fun input(data: String): Envelope? {
        val runId = field(data, "runId")
        val encoded = field(data, "data")
        if (runId == null || encoded == null) return Envelope(Envelope.ERROR, "run_input expects {\"runId\", \"data\": base64}")
        // Measured before decoding, so a huge frame is never decoded at all (base64 is 4 characters per 3 bytes).
        val maxFrame = limits.inputMaxFrameBytes()
        if (encoded.length / 4L * 3 > maxFrame + 2L) return inputTooBig(maxFrame)
        val bytes = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull()
            ?: return Envelope(Envelope.ERROR, "run_input expects {\"runId\", \"data\": base64}")
        if (bytes.size > maxFrame) return inputTooBig(maxFrame)
        if (restricted()) return Envelope(Envelope.ERROR, "run_input refused: typing into a run is off under a restricted policy")
        val run = synchronized(lock) { inFlight }?.takeIf { it.runId == runId }
            ?: return Envelope(Envelope.ERROR, "run_input refused: no such run in flight")
        if (!run.interactive) return Envelope(Envelope.ERROR, "run_input refused: the run was not started interactive")
        return run.write(bytes)?.let { Envelope(Envelope.ERROR, "run_input refused: $it") }
    }

    private fun inputTooBig(max: Int) =
        Envelope(Envelope.ERROR, "run_input refused: one frame may carry at most $max bytes. ${limits.hint("limits.run.inputMaxFrameBytes")}")

    private fun field(data: String, name: String): String? =
        ((runCatching { wire.parseToJsonElement(data) }.getOrNull() as? JsonObject)?.get(name) as? JsonPrimitive)
            ?.takeIf { it.isString }?.content

    /** Under [lock]: forget the oldest runs past the history cap; the run in flight is never dropped. */
    private fun prune() {
        val cap = limits.history().coerceAtLeast(1)
        while (history.size > cap) {
            val oldest = history.firstOrNull { it !== inFlight } ?: return
            history.remove(oldest)
        }
    }

    /**
     * `run_stop "<runId> [force]"`: returns an ERROR frame on bad input, or null when a stop was begun.
     * The answer to a stop is the run's single `run_finished` (KILLED_GRACEFUL or KILLED_FORCED), sent from
     * a background thread so the socket handler never waits out the grace window.
     */
    fun stop(data: String): Envelope? {
        val parts = data.trim().split(Regex("[ \t]+"))
        if (parts.isEmpty() || parts[0].isEmpty() || parts.size > 2 || (parts.size == 2 && parts[1] != "force"))
            return Envelope(Envelope.ERROR, "run_stop expects '<runId> [force]'")
        val run = synchronized(lock) { inFlight }?.takeIf { it.runId == parts[0] }
            ?: return Envelope(Envelope.ERROR, "run_stop refused: no such run in flight")
        run.stop(force = parts.size == 2)
        return null
    }

    /**
     * RUN-CMD-1: runs [change] (a save or delete of [commandId]) only while that command has no run in flight, and
     * under the same lock `run_start` takes, so no run of it can start halfway through. Returns the refusal reason
     * when it is running, else whatever [change] returns (null = done, a string = its own refusal).
     */
    fun unlessRunning(commandId: String, change: () -> String?): String? = synchronized(lock) {
        if (inFlight?.command?.id == commandId) "command '$commandId' is running; stop it first" else change()
    }

    private fun refuse(why: String) = Started(Envelope(Envelope.ERROR, why), null)

    /**
     * The environment [names] give a run: every field of each secret, under its field name (a later secret's field of the
     * same name wins). A refusal names the secret or the field, never a value.
     */
    private fun secretEnv(names: List<String>): Result<Map<String, String>> {
        fun no(why: String) = Result.failure<Map<String, String>>(IllegalArgumentException(why))
        if (names.isEmpty()) return Result.success(emptyMap())
        if (names.size > maxRunSecrets) return no("a run takes at most $maxRunSecrets secrets")
        if (restricted()) return no("secrets are locked under a restricted policy")
        val env = LinkedHashMap<String, String>()
        for (name in names) {
            if (!CommandRegistry.isSecretName(name)) return no("invalid secret name")
            // SEC-FIX-1 H1(b): the lookup refuses a built-in (the update token) with its own reason, kept here.
            val fields = try { secrets(name) } catch (e: IllegalArgumentException) { return no(e.message ?: "secret '$name' is refused") } catch (e: Exception) { null }
                ?: return no("secret '$name' is not in the keystore")
            for ((field, value) in fields) {
                if (field.uppercase().startsWith("INFANTRY_")) return no("secret '$name' field '$field' would override a daemon setting")
                env[field] = value
            }
        }
        return Result.success(env)
    }

    private inner class Run(
        val runId: String,
        val command: SavedCommand,
        val startedAt: Long,
        val interactive: Boolean,
        private val cols: Int,
        private val rows: Int,
        private val env: Map<String, String> = emptyMap(),
    ) {
        /** SECRETS-2: the injected values never reach a frame, the scrollback or a line. */
        private val scrubber = SecretScrubber(env.values)
        private val outLock = Any()
        private var sequence = 0L
        private var capped = false
        private var finished = false
        private var ended: RunFinished? = null
        @Volatile private var handle: RunHandle? = null
        @Volatile private var stopMode: String? = null // null, KILLED_GRACEFUL or KILLED_FORCED
        @Volatile private var hasTerminal = false
        private var byteSequence = 0L
        private val scrollback = ByteScrollback(limits.scrollbackBytes)
        private val decoder = AnsiLineDecoder { output("stdout", it) }

        fun record(state: String, pid: Long? = null) =
            RunRecord(runId, command.id, state, startedAt, pid, name = command.name, terminal = hasTerminal, interactive = interactive)

        fun summary(): RunSummary = synchronized(outLock) {
            val state = when { finished -> "FINISHED"; handle != null -> "RUNNING"; else -> "STARTING" }
            RunSummary(runId, command.id, command.name, state, startedAt, ended?.endedAt, ended?.exitCode, ended?.outcome, hasTerminal, interactive)
        }

        /** Under [outLock], so no live chunk lands between the snapshot and its sequence. */
        fun replay(): Envelope = synchronized(outLock) {
            val bytes = RunBytes(runId, byteSequence, b64(scrollback.snapshot()), replay = true, droppedBytes = scrollback.droppedBytes)
            Envelope(Envelope.RUN_BYTES, wire.encodeToString(RunBytes.serializer(), bytes))
        }

        fun resize(cols: Int, rows: Int) { runCatching { handle?.takeIf { it.terminal }?.resize(cols, rows) } }

        // SEC-FIX-1 M2: the keyboard rate (a token bucket of [RunLimits.inputBytesPerSecond]) and the queue the
        // writer thread drains. Guarded by [inputLock]; the writer starts with the first input.
        private val inputLock = Any()
        private val inputQueue = java.util.concurrent.LinkedBlockingQueue<ByteArray>()
        private var queuedBytes = 0L
        private var allowance = -1.0 // bytes the bucket holds; -1 = not filled yet
        private var allowanceAt = 0L
        private var writer: Thread? = null

        /** Queues [bytes] for the pty: null when queued, else why not (no terminal, over the rate, queue full). */
        fun write(bytes: ByteArray): String? {
            val p = handle?.takeIf { it.terminal } ?: return "the run has no terminal"
            synchronized(inputLock) {
                val rate = limits.inputBytesPerSecond()
                if (rate > 0) {
                    val t = now()
                    allowance = if (allowance < 0) rate.toDouble()
                    else (allowance + (t - allowanceAt).coerceAtLeast(0) * rate / 1000.0).coerceAtMost(rate.toDouble())
                    allowanceAt = t
                    if (bytes.size > allowance) {
                        return "over ${rate} input bytes per second. ${limits.hint("limits.run.inputBytesPerSecond")}"
                    }
                    allowance -= bytes.size
                }
                val cap = limits.inputQueueBytes()
                if (queuedBytes + bytes.size > cap) {
                    return "the terminal is not keeping up ($queuedBytes bytes waiting). ${limits.hint("limits.run.inputQueueBytes")}"
                }
                queuedBytes += bytes.size
                inputQueue.add(bytes)
                if (writer == null) writer = Thread({ drain(p) }, "infantry-run-input").apply { isDaemon = true; start() }
            }
            return null
        }

        /** The writer thread: one pty write at a time, in arrival order, until the run is finished. */
        private fun drain(p: RunHandle) {
            while (true) {
                val next = inputQueue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (next == null) { if (isFinished()) return else continue }
                runCatching { p.write(next) }
                synchronized(inputLock) { queuedBytes -= next.size }
            }
        }

        fun stop(force: Boolean) {
            // Not started yet, or the spawn failed: finish() already owns the outcome.
            val p = handle?.let { Stoppable({ it.isAlive }, it::terminate, it::killTree) } ?: return
            synchronized(outLock) { if (finished || stopMode != null) return; stopMode = if (force) "KILLED_FORCED" else "KILLED_GRACEFUL" }
            Thread({
                if (!force) {
                    runCatching { p.terminate() }
                    val end = System.nanoTime() + command.graceMillis * 1_000_000
                    while (!isFinished() && p.alive() && System.nanoTime() < end) Thread.sleep(10)
                    if (!p.alive()) { settle(); return@Thread }
                    synchronized(outLock) { if (!finished) stopMode = "KILLED_FORCED" }
                }
                runCatching { p.killTree() }
                settle()
            }, "infantry-run-stop").apply { isDaemon = true }.start()
        }

        private fun isFinished() = synchronized(outLock) { finished }

        /** Give onExit a moment to report the real code; otherwise finish with a null one. Never a plain FAILED. */
        private fun settle() {
            val end = System.nanoTime() + 2_000_000_000L
            while (!isFinished() && System.nanoTime() < end) Thread.sleep(10)
            finish(null, stopMode ?: "KILLED_FORCED", null)
        }

        fun launch() {
            val listener = object : RunListener {
                override fun onBytes(bytes: ByteArray) = terminalBytes(bytes)
                override fun onLine(stream: String, line: String) = lines(stream, line)
                override fun onExit(exitCode: Int) {
                    synchronized(outLock) { cleanBytes(scrubber.flush()); decoder.flush() }
                    finish(exitCode, stopMode ?: if (exitCode == 0) "SUCCEEDED" else "FAILED", null)
                }
            }
            val started = try {
                runs.start(RunSpec(command.argv, relativeCwd(runs, command.workingDir), env, terminal = usePty, cols = cols, rows = rows), listener)
            } catch (e: Exception) {
                // Never the argv or the message (it can quote it): the class name only.
                finish(null, "FAILED", "spawn failed (${e::class.simpleName})")
                return
            }
            hasTerminal = started.terminal
            handle = started
            // 1.134.0: every client learns the run is up (the phone's pill too), not only the one that started it.
            if (started.terminal) emit(Envelope(Envelope.RUN, wire.encodeToString(RunRecord.serializer(), record("RUNNING", started.pid))))
        }

        private fun terminalBytes(raw: ByteArray) = synchronized(outLock) {
            if (finished) return@synchronized
            cleanBytes(scrubber.bytes(raw))
        }

        /** Under [outLock]: bytes already scrubbed, into the scrollback, a `run_bytes` frame and the line decoder. */
        private fun cleanBytes(bytes: ByteArray) {
            if (bytes.isEmpty() || finished) return
            scrollback.append(bytes)
            byteSequence += 1
            emit(Envelope(Envelope.RUN_BYTES, wire.encodeToString(RunBytes.serializer(), RunBytes(runId, byteSequence, b64(bytes)))))
            decoder.feed(bytes)
        }

        /** Split on \n, \r and \r\n; a trailing break does not make an empty extra line. */
        private fun lines(stream: String, text: String) {
            val parts = scrubber.line(text).split(BREAK)
            val usable = if (parts.size > 1 && parts.last().isEmpty()) parts.dropLast(1) else parts
            for (p in usable) output(stream, p)
        }

        private fun output(stream: String, raw: String) = synchronized(outLock) {
            if (finished || capped) return@synchronized
            if (sequence >= MAX_LINES) {
                capped = true
                sendOutput(stream, "[output truncated]", false)
                return@synchronized
            }
            val cut = raw.length > MAX_LINE_CHARS
            sendOutput(stream, if (cut) raw.substring(0, MAX_LINE_CHARS) else raw, cut)
        }

        private fun sendOutput(stream: String, line: String, truncated: Boolean) {
            sequence += 1
            emit(Envelope(Envelope.RUN_OUTPUT, wire.encodeToString(RunOutput.serializer(), RunOutput(runId, sequence, stream, line, truncated))))
        }

        fun finish(exitCode: Int?, outcome: String, detail: String?) {
            synchronized(outLock) {
                if (finished) return
                finished = true
                val end = RunFinished(runId, exitCode, outcome, now(), detail)
                ended = end
                synchronized(lock) { if (inFlight === this) inFlight = null; prune() }
                emit(Envelope(Envelope.RUN_FINISHED, wire.encodeToString(RunFinished.serializer(), end)))
            }
        }
    }

    /** What the stop sequence needs of a piped or a terminal child. */
    private class Stoppable(val alive: () -> Boolean, val terminate: () -> Unit, val killTree: () -> Unit)

    companion object {
        const val MAX_LINES = 10_000
        const val MAX_LINE_CHARS = 8 * 1024
        /** The pty size until a console says otherwise (layout, not a limit: any size can be asked for). */
        const val DEFAULT_COLS = 120
        const val DEFAULT_ROWS = 32
        private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
        private val BREAK = Regex("\r\n|\r|\n")
        private val wire = Json { encodeDefaults = true }
    }
}

/** The cwd of a run relative to the surface's root (the command's working directory is canonical and inside the repo); null = the root. */
private fun relativeCwd(runs: Runs, dir: java.io.File): String? {
    val root = runs.workRoot?.let { runCatching { it.canonicalFile }.getOrNull() } ?: return null
    return dir.relativeToOrNull(root)?.path?.takeIf { it.isNotEmpty() }?.replace('\\', '/')
}
