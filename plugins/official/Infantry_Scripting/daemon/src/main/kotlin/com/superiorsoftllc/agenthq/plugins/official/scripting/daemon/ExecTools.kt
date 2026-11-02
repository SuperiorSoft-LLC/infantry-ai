package com.superiorsoftllc.agenthq.plugins.official.scripting.daemon

import com.superiorsoftllc.agenthq.daemon.api.DaemonTool
import com.superiorsoftllc.agenthq.daemon.api.ToolCall
import com.superiorsoftllc.agenthq.daemon.api.ToolResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** SCRIPT-CMD-1: the guard and limits of the process bridge. All data (plugin settings `scripting.exec.*`); a bad value falls back to the default. */
data class ExecSettings(
    val enabled: Boolean, val allow: List<String>, val roles: List<String>,
    val defaultTimeoutSec: Long, val maxTimeoutSec: Long, val tailChars: Int,
) {
    companion object {
        fun read(config: (String) -> String?): ExecSettings {
            fun num(key: String, range: LongRange, d: Long) = config(key)?.trim()?.toLongOrNull()?.takeIf { it in range } ?: d
            val max = num("scripting.exec.maxTimeoutSec", 1L..3600L, 600L)
            return ExecSettings(
                config("scripting.exec.enabled")?.trim()?.lowercase() == "true",
                list(config("scripting.exec.allow")), list(config("scripting.exec.roles")),
                num("scripting.exec.defaultTimeoutSec", 1L..3600L, 60L).coerceAtMost(max), max,
                num("scripting.exec.tailChars", 100L..1_000_000L, 8000L).toInt(),
            )
        }

        /** A JSON array of strings, or one entry per line / comma-separated. */
        fun list(text: String?): List<String> {
            val t = text?.trim().orEmpty()
            if (t.isEmpty()) return emptyList()
            if (t.startsWith("[")) runCatching { return (Json.parseToJsonElement(t) as JsonArray).mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) } }
            return t.split('\n', ',').map { it.trim() }.filter { it.isNotEmpty() }
        }
    }
}

/** Splits a command line into arguments: whitespace separates, single or double quotes group. No shell expansion of any kind. */
object CommandSplit {
    fun split(line: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var quote: Char? = null
        var has = false
        for (c in line) when {
            quote != null -> if (c == quote) quote = null else cur.append(c)
            c == '"' || c == '\'' -> { quote = c; has = true }
            c.isWhitespace() -> if (has || cur.isNotEmpty()) { out += cur.toString(); cur.setLength(0); has = false }
            else -> cur.append(c)
        }
        if (has || cur.isNotEmpty()) out += cur.toString()
        return out
    }
}

/**
 * SCRIPT-CMD-1: the Lua process bridge. `agenthq_exec` runs a command line as the caller and answers {exitCode, durationMs, stdoutTail,
 * stderrTail, timedOut}; `background=true` answers a run id at once, `agenthq_exec_status` reads it and the named event `exec.done`
 * announces its end. Never through a shell (argv runs directly, a string is split); a timeout kills the whole process tree.
 */
class ExecTools(
    private val config: (String) -> String?,
    /** The project's root directory (the parent of `.agenthq/`), or null for an unknown project. */
    private val rootOf: (String) -> File?,
    private val publish: (name: String, payload: String) -> Unit,
    private val log: (String) -> Unit,
    /** SCRIPT-ACTIVE-1: lights the calling session's card while a command runs. */
    private val marks: AutomationMarks = AutomationMarks({ _, _ -> }),
) {
    private class Run(val id: String, val command: String, val script: String, val session: String, val project: String? = null) {
        val started = System.currentTimeMillis()
        @Volatile var lastTickBytes = 0L
        @Volatile var out: Tail? = null
        @Volatile var err: Tail? = null
        @Volatile var state = "running"
        @Volatile var result: JsonObject? = null
    }

    private class Refusal(msg: String) : Exception(msg)

    private val runs = ConcurrentHashMap<String, Run>()
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "exec-bridge").apply { isDaemon = true } }

    val exec: DaemonTool = object : DaemonTool {
        override val name = EXEC
        override val description = "Run a command line as you and get {exitCode, durationMs, stdoutTail, stderrTail, timedOut}. command = argv list (runs directly) or one string (split on spaces/quotes, no shell, no expansion). " +
            "cwd (default: the project root), env, timeoutSec, stdin. background=true returns a run id at once (read it with $STATUS; event exec.done fires on its end). " +
            "Off by default: setting scripting.exec.enabled; allow-list scripting.exec.allow, roles scripting.exec.roles. Open to every role unless those say otherwise."
        override val inputSchema: JsonObject = Json.parseToJsonElement(
            """{"type":"object","properties":{"command":{"description":"argv array, or one command-line string"},"cwd":{"type":"string"},"env":{"type":"object"},"timeoutSec":{"type":"integer"},"stdin":{"type":"string"},"background":{"type":"boolean"},"script":{"type":"string","description":"label carried in exec.done (the calling script's name)"},"project":{"type":"string"}},"required":["command"]}""",
        ).jsonObject
        override val timeoutMs: Long = 620_000
        override fun call(call: ToolCall): ToolResult = try { runExec(call) } catch (e: Refusal) { ToolResult("$EXEC refused: ${e.message}", true) }
    }

    val status: DaemonTool = object : DaemonTool {
        override val name = STATUS
        override val description = "State of a background $EXEC run: running|done|timeout, exitCode and the output tails once it ended."
        override val inputSchema: JsonObject = Json.parseToJsonElement("""{"type":"object","properties":{"id":{"type":"string"}},"required":["id"]}""").jsonObject
        override val annotations: JsonObject = Json.parseToJsonElement("""{"readOnlyHint":true}""").jsonObject
        override fun call(call: ToolCall): ToolResult {
            val id = (call.arguments["id"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val r = runs[id] ?: return ToolResult("$STATUS: no run '$id'", true)
            return ToolResult(buildJsonObject {
                put("id", r.id)
                put("state", r.state)
                r.result?.forEach { (k, v) -> put(k, v) }
            }.toString())
        }
    }

    private fun runExec(call: ToolCall): ToolResult {
        val s = ExecSettings.read(config)
        if (!s.enabled) throw Refusal("the process bridge is off. Enable it with the setting scripting.exec.enabled = true (optionally limit it with scripting.exec.allow and scripting.exec.roles)")
        val a = call.arguments
        val role = call.clientRole.orEmpty()
        if (s.roles.isNotEmpty() && s.roles.none { it.equals(role, true) }) throw Refusal("role '${role.ifBlank { "none" }}' may not exec (scripting.exec.roles = ${s.roles.joinToString()})")
        val argv: List<String> = when (val c = a["command"]) {
            is JsonArray -> c.map { (it as? JsonPrimitive)?.contentOrNull ?: throw Refusal("command list entries must be strings") }
            is JsonPrimitive -> CommandSplit.split(c.content)
            else -> throw Refusal("command is required (a list or a string)")
        }
        if (argv.isEmpty() || argv[0].isBlank()) throw Refusal("command is empty")
        val line = argv.joinToString(" ")
        if (s.allow.isNotEmpty() && s.allow.none { matches(it, line) }) throw Refusal("command line is not in the allow-list scripting.exec.allow")
        val project = (a["project"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: call.clientProjectId
        val root = project?.let(rootOf)
        val cwdArg = (a["cwd"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        val cwd = when {
            cwdArg == null -> root ?: throw Refusal("no project to take a working directory from (pass cwd or project)")
            File(cwdArg).isAbsolute -> File(cwdArg)
            else -> File(root ?: throw Refusal("a relative cwd needs a project"), cwdArg)
        }
        if (!cwd.isDirectory) throw Refusal("cwd is not a directory: $cwd")
        val env = (a["env"] as? JsonObject)?.mapValues { (it.value as? JsonPrimitive)?.contentOrNull.orEmpty() }.orEmpty()
        val asked = (a["timeoutSec"] as? JsonPrimitive)?.doubleOrNull?.toLong()
        val timeout = (asked ?: s.defaultTimeoutSec).coerceIn(1L, s.maxTimeoutSec)
        val stdin = (a["stdin"] as? JsonPrimitive)?.contentOrNull
        val script = (a["script"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val background = (a["background"] as? JsonPrimitive)?.booleanOrNull == true
        val run = Run(UUID.randomUUID().toString().take(8), line, script, call.clientSessionId.orEmpty(), project)
        val work = { marks.begin(run.session, run.script.ifBlank { "exec" }, "exec").use { execute(run, argv, cwd, env, timeout, stdin, s.tailChars, role, background) } }
        if (!background) return ToolResult(work().toString())
        pruneRuns()
        runs[run.id] = run
        pool.execute { runCatching(work) }
        return ToolResult(buildJsonObject { put("id", run.id); put("state", "running") }.toString())
    }

    private fun execute(run: Run, argv: List<String>, cwd: File, env: Map<String, String>, timeoutSec: Long, stdin: String?, tail: Int, role: String, background: Boolean): JsonObject {
        val started = System.nanoTime()
        val out = Tail(tail)
        val err = Tail(tail)
        run.out = out; run.err = err
        var exit = -1
        var timedOut = false
        var failure: String? = null
        try {
            val pb = ProcessBuilder(argv).directory(cwd)
            pb.environment().putAll(env)
            val p = pb.start()
            val readers = listOf(
                Thread { p.inputStream.bufferedReader().use { r -> out.pump(r) } },
                Thread { p.errorStream.bufferedReader().use { r -> err.pump(r) } },
            ).onEach { it.isDaemon = true; it.start() }
            Thread { runCatching { p.outputStream.use { o -> if (stdin != null) o.write(stdin.toByteArray()) } } }.apply { isDaemon = true }.start()
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                timedOut = true
                p.descendants().forEach { it.destroyForcibly() }
                p.destroyForcibly()
                p.waitFor(5, TimeUnit.SECONDS)
            }
            exit = if (timedOut) -1 else p.exitValue()
            readers.forEach { it.join(2000) }
        } catch (e: Exception) {
            failure = (e.message ?: e.javaClass.simpleName).take(200)
        }
        val ms = (System.nanoTime() - started) / 1_000_000
        val result = buildJsonObject {
            put("exitCode", exit)
            put("durationMs", ms)
            put("stdoutTail", out.text())
            put("stderrTail", if (failure != null) "could not start: $failure" else err.text())
            put("timedOut", timedOut)
        }
        log("exec by session=${run.session.ifBlank { "-" }} role=${role.ifBlank { "-" }} script=${run.script.ifBlank { "-" }} cmd=[${run.command.take(200)}] exit=$exit timedOut=$timedOut ms=$ms")
        if (background) {
            run.result = result
            run.state = if (timedOut) "timeout" else "done"
            publish("exec.done", buildJsonObject { put("id", run.id); put("exitCode", exit); put("script", run.script); put("session", run.session); put("timedOut", timedOut); run.project?.let { put("projectId", it) } }.toString())
        }
        return result
    }

    /**
     * SCRIPT-TICK-1: publishes the named event `exec.tick` for each live background run: {id, script, session, projectId, elapsedMs,
     * stdoutTail, stderrTail, bytesSinceLast}. Called about every `scripting.exec.tickSec` (default 10) by the plugin; a run that
     * ended is not ticked (exec.done announced it).
     */
    fun tick() {
        val now = System.currentTimeMillis()
        for (r in runs.values) {
            if (r.state != "running") continue
            val o = r.out; val e = r.err
            val total = (o?.total() ?: 0L) + (e?.total() ?: 0L)
            val since = total - r.lastTickBytes
            r.lastTickBytes = total
            runCatching {
                publish("exec.tick", buildJsonObject {
                    put("id", r.id); put("script", r.script); put("session", r.session); r.project?.let { put("projectId", it) }
                    put("elapsedMs", now - r.started); put("stdoutTail", o?.text().orEmpty()); put("stderrTail", e?.text().orEmpty()); put("bytesSinceLast", since)
                }.toString())
            }
        }
    }

    private fun pruneRuns() {
        if (runs.size < MAX_RUNS) return
        runs.values.filter { it.state != "running" }.take(runs.size - MAX_RUNS + 1).forEach { runs.remove(it.id) }
    }

    private class Tail(private val cap: Int) {
        private val sb = StringBuilder()
        @Volatile private var count = 0L
        fun total(): Long = count
        fun pump(r: java.io.Reader) {
            val buf = CharArray(4096)
            while (true) {
                val n = r.read(buf)
                if (n < 0) break
                synchronized(sb) { count += n; sb.append(buf, 0, n); if (sb.length > cap * 2) sb.delete(0, sb.length - cap) }
            }
        }
        fun text(): String = synchronized(sb) { if (sb.length > cap) sb.substring(sb.length - cap) else sb.toString() }
    }

    companion object {
        const val EXEC = "agenthq_exec"
        const val STATUS = "agenthq_exec_status"
        private const val MAX_RUNS = 100

        /** Seconds between exec.tick events: setting scripting.exec.tickSec, default 10, 1..3600. */
        fun tickSec(config: (String) -> String?): Long = config("scripting.exec.tickSec")?.trim()?.toLongOrNull()?.takeIf { it in 1L..3600L } ?: 10L

        /** `re:<regex>` must match the whole command line; anything else is a prefix. */
        fun matches(pattern: String, line: String): Boolean =
            if (pattern.startsWith("re:")) runCatching { Regex(pattern.removePrefix("re:")).matches(line) }.getOrDefault(false) else line.startsWith(pattern)
    }
}
