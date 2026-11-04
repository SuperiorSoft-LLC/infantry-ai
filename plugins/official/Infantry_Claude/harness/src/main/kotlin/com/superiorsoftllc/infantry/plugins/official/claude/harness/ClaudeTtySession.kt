// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.claude.harness

import com.superiorsoftllc.infantry.harness.api.HarnessProcess
import com.superiorsoftllc.infantry.harness.api.HarnessRequest
import com.superiorsoftllc.infantry.harness.api.HarnessSession
import com.superiorsoftllc.infantry.harness.api.MCP_PID_FILE_ENV
import com.superiorsoftllc.infantry.harness.api.McpState
import com.superiorsoftllc.infantry.harness.api.SessionConnection
import com.superiorsoftllc.infantry.harness.api.SessionOutput
import com.superiorsoftllc.infantry.harness.api.SessionTurnEnd
import com.superiorsoftllc.infantry.harness.api.ToolApproval
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.ArrayDeque
import java.util.IdentityHashMap
import java.util.UUID

/**
 * CLI-PTY-1 (experimental, `harness.claude.mode = interactive`): Claude Code run as a person runs it: the interactive `claude` (no `-p`) in a
 * pseudo-terminal the host gives it ([terminal], API 2.13), so its requests are the CLI's own (entrypoint `cli`). Nothing is spoofed: no
 * entrypoint variable, no edited header, no patched CLI; the daemon only types into the terminal and reads the files the CLI writes.
 *
 * - **Start:** the same flags as headless where interactive mode has them (model, effort, permission mode, allowed tools, MCP config, resume),
 *   plus `--session-id` for a new session so its transcript is known before it exists, and `--settings` with the daemon's hooks (below).
 *   The CLI's folder trust question, which a new worktree gets, is answered "trust" with the keys a person would press (Enter only once
 *   the screen shows the accept option selected); the bypass-permissions warning is turned off by the documented
 *   `skipDangerousModePermissionPrompt` setting in the same file, only when that mode is asked for. `--strict-mcp-config` is always
 *   passed, so the approval dialog for a project `.mcp.json` (which `-p` never shows) cannot appear.
 * - **Input:** each turn is pasted (bracketed paste) and submitted with Enter once the CLI is up; a turn that arrives while another runs waits.
 * - **Output:** the session transcript, read as it grows and mapped to stream-json records ([TranscriptMapper]). The screen is kept only as
 *   debug scrollback (and for the trust question).
 * - **Turn end:** Claude Code's `Stop` hook (a command hook that appends to a per-session events file); an API error the CLI records ends it
 *   as FAILED. `SessionStart` marks the CLI as up, `UserPromptSubmit` that the paste was taken.
 * - **Interrupt:** Esc into the terminal; the process lives on.
 * - **MCP link (CLI-PTY-3):** the daemon's MCP server gets [MCP_PID_FILE_ENV] (a per-session file it writes its pid to). Every
 *   [MCP_CHECK_MS] the pid is checked; a change (up, gone, a new pid up again) is reported with [SessionOutput.mcpState] and nothing else:
 *   no reconnect is made here. One live CLI starts its servers once, so a server that dies stays dead unless a script asks for
 *   `session_act mcp_reconnect`.
 */
class ClaudeTtySession(
    private val executable: () -> String,
    private val mcp: (Map<String, String>) -> Pair<List<String>?, Map<String, String>>,
    private val watchSettings: (Map<String, String>) -> String? = { null },
    private val stateDir: () -> File,
    private val configDir: () -> File = { ClaudeTranscripts.configDir() },
    private val newSessionId: () -> String = { UUID.randomUUID().toString() },
    private val idleMinutes: () -> Int = { 0 },
    private val clock: () -> Long = System::currentTimeMillis,
    /** false = no poller thread; a test drives [TtyConnection.tick]. */
    private val poll: Boolean = true,
    /** Whether a process id still runs (the MCP server check); a test fakes it. */
    private val pidAlive: (Long) -> Boolean = ::processAlive,
) : HarnessSession {
    override val label: String = "claude"

    override fun idleMinutes(): Int = idleMinutes.invoke()

    /** What [launch] decided for a process, until [connect] takes it. Keyed by identity: the host hands back the very object. */
    data class Launch(val sessionId: String, val cwd: String, val events: File, val settings: File, val mcpPid: File? = null)

    private val launches = IdentityHashMap<HarnessProcess, Launch>()

    fun owns(launched: HarnessProcess): Boolean = synchronized(launches) { launched in launches }

    override fun terminal(launched: HarnessProcess): Boolean = owns(launched)

    private fun argv(
        exe: String, request: HarnessRequest, sessionId: String?, resume: String?, settings: String?, env: Map<String, String>,
        extraMcpEnv: Map<String, String> = emptyMap(),
    ): List<String> {
        val (mcpCommand, baseMcpEnv) = mcp(env)
        val mcpEnv = baseMcpEnv + extraMcpEnv
        return ClaudeCommand.interactiveCommand(
            executable = exe,
            model = ClaudeCommand.effectiveModel(request.model),
            permissionMode = ClaudeCommand.permissionMode(request.toolApproval),
            mcpConfigs = ClaudeCommand.mcpConfigs(ClaudeCommand.userMcpConfigs(), mcpCommand, mcpEnv),
            // Always strict here: without it the interactive CLI asks to approve any project .mcp.json it finds (a home folder's
            // counts), a dialog `-p` never shows; strict loads exactly the servers listed (the user's configs and the daemon's).
            strictMcp = true,
            sessionId = sessionId,
            resumeSessionId = resume,
            effort = request.effort,
            settings = settings,
        )
    }

    override fun fingerprint(request: HarnessRequest, env: Map<String, String>): String =
        argv("claude", request, null, null, null, env).joinToString("\u0000") + "\u0000" + request.project + "\u0000" +
            (env + ClaudeCommand.contextLimitEnv(request.contextLimit)).toSortedMap()

    override fun launch(request: HarnessRequest, env: Map<String, String>): HarnessProcess {
        val resume = request.sessionId?.takeIf { it.isNotBlank() }
        val id = resume ?: newSessionId()
        val dir = File(stateDir(), TTY_DIR).apply { mkdirs() }
        val events = File(dir, "$id.events").apply { delete() }
        val settings = File(dir, "$id.settings.json")
        val mcpPid = File(dir, "$id.mcp.pid").apply { delete() }
        val watch = watchSettings(env).takeIf { request.toolApproval == ToolApproval.ALLOW_ALL }
        settings.writeText(ClaudeCommand.ttySettings(events.absolutePath, watch, bypass = ClaudeCommand.permissionMode(request.toolApproval) == "bypassPermissions"))
        val process = HarnessProcess(
            command = argv(executable(), request, if (resume == null) id else null, resume, settings.absolutePath, env, mapOf(MCP_PID_FILE_ENV to mcpPid.absolutePath)),
            env = mapOf("CLAUDE_CODE_ENABLE_TODO_TOOLS" to "1") + ClaudeCommand.contextLimitEnv(request.contextLimit),
        )
        synchronized(launches) { launches[process] = Launch(id, request.project, events, settings, mcpPid) }
        return process
    }

    override fun connect(out: SessionOutput): SessionConnection = error("an interactive claude connects with its launched process")

    override fun connect(out: SessionOutput, launched: HarnessProcess): SessionConnection {
        val launch = synchronized(launches) { launches.remove(launched) } ?: error("this process was not launched by the interactive claude session")
        return TtyConnection(out, launch, configDir(), clock, bootTimeoutMs, pidAlive).also { if (poll) it.startPolling() }
    }

    /** One interactive claude in its terminal. Every state change happens in [tick] (the poller thread) or under [lock]. */
    class TtyConnection(
        private val out: SessionOutput,
        private val launch: Launch,
        private val configDir: File,
        private val clock: () -> Long,
        private val bootTimeoutMs: Long,
        private val pidAlive: (Long) -> Boolean = ::processAlive,
    ) : SessionConnection {
        private class Turn(val prompt: String) {
            var abortAt: Long? = null
            var pastedAt: Long? = null
            var enteredAt: Long? = null
            var enterRetried = false
            var submitted = false
            var stopAt: Long? = null
        }

        private val lock = Any()
        private val mapper = TranscriptMapper(launch.sessionId)
        private val events = AppendedLines { launch.events }
        private var transcriptFile: File? = null
        private val transcript = AppendedLines { transcriptFile ?: ClaudeTranscripts.find(configDir, launch.cwd, launch.sessionId)?.also { transcriptFile = it } }
        private val queue = ArrayDeque<Turn>()
        private var active: Turn? = null
        private val launchedAt = clock()
        private var firstScreenAt: Long? = null
        private var startedAt: Long? = null
        private var trustDownAt: Long? = null
        private var trustDownMark = 0L
        private var trustEnterDone = false
        private var interruptedSeen = false
        private var initSent = false
        private val screen = StringBuilder()
        /** Characters appended to the screen so far (the scrollback is trimmed; this is not). */
        private var screenTotal = 0L
        @Volatile private var running = true
        @Volatile private var exited = false
        private var mcpCheckedAt: Long? = null
        private var mcpSeen: Pair<Long, Boolean>? = null
        private var thread: Thread? = null

        fun startPolling() {
            thread = Thread({
                while (running) {
                    try { tick() } catch (t: Throwable) { out.detail("interactive claude: ${t.javaClass.simpleName}: ${t.message.orEmpty().take(200)}") }
                    try { Thread.sleep(TICK_MS) } catch (_: InterruptedException) { break }
                }
            }, "infantry-claude-tty").apply { isDaemon = true; start() }
        }

        /** The debug scrollback: the screen as plain text, the last [SCREEN_KEEP_CHARS] characters. */
        fun screenText(): String = synchronized(lock) { screen.toString() }

        override fun onStdout(line: String) {
            val text = ScreenText.strip(line)
            synchronized(lock) {
                if (firstScreenAt == null) firstScreenAt = clock()
                screen.append(text)
                screenTotal += text.length
                if (screen.length > SCREEN_KEEP_CHARS) screen.delete(0, screen.length - SCREEN_KEEP_CHARS)
            }
        }

        override fun sendTurn(request: HarnessRequest) {
            val turn = Turn(request.prompt)
            val failed = synchronized(lock) { if (exited) true else { queue.add(turn); false } }
            if (failed) out.turnEnded(SessionTurnEnd.FAILED, null, "the interactive claude has exited")
        }

        override fun abortTurn(): Boolean = synchronized(lock) {
            val turn = active ?: return true
            if (turn.abortAt == null) {
                turn.abortAt = clock()
                interruptedSeen = false
                key(ESC)
            }
            true
        }

        override fun close(): Boolean {
            synchronized(lock) { key(ESC) }
            Thread.sleep(KEY_GAP_MS)
            synchronized(lock) { paste("/exit") }
            Thread.sleep(KEY_GAP_MS)
            synchronized(lock) { key(ENTER) }
            return true
        }

        override fun onExit(exitCode: Int) {
            running = false
            // The screen is the only account of why a terminal program quit: show its tail when it did not exit cleanly.
            if (exitCode != 0) out.detail("the interactive claude exited (code $exitCode); screen: " + synchronized(lock) { screenTail() })
            // The host ends its turn in flight itself (sent or still queued here), so nothing is ended twice.
            synchronized(lock) {
                exited = true
                active = null
                queue.clear()
            }
            thread?.interrupt()
        }

        /** One step of the state machine; the poller calls it every [TICK_MS]. */
        fun tick() {
            val now = clock()
            for (e in events.read()) {
                when (e.trim().lowercase()) {
                    EV_START -> synchronized(lock) { if (startedAt == null) startedAt = now }
                    EV_SUBMIT -> synchronized(lock) { active?.submitted = true }
                    EV_STOP -> synchronized(lock) { active?.let { if (it.submitted || it.enteredAt != null) it.stopAt = it.stopAt ?: now } }
                }
            }
            if (!initSent) {
                initSent = true
                out.providerSession(launch.sessionId)
                out.output(mapper.initLine())
            }
            answerTrust(now)
            checkMcp(now)
            var apiError: String? = null
            for (line in transcript.read()) {
                for (signal in mapper.map(line)) when (signal) {
                    is TranscriptSignal.Line -> out.output(signal.json)
                    TranscriptSignal.PromptRecorded -> synchronized(lock) { active?.submitted = true }
                    TranscriptSignal.Interrupted -> synchronized(lock) { interruptedSeen = true }
                    is TranscriptSignal.ApiError -> apiError = signal.text
                }
            }
            val ends = ArrayList<Triple<SessionTurnEnd, Int?, String?>>()
            synchronized(lock) {
                val turn = active
                when {
                    turn == null -> {
                        if (queue.isNotEmpty() && ready(now)) {
                            val next = queue.poll()
                            active = next
                            paste(next.prompt)
                            next.pastedAt = now
                        } else if (queue.isNotEmpty() && now - launchedAt > bootTimeoutMs) {
                            val reason = "the interactive claude did not come up in ${bootTimeoutMs / 1000} s; screen: " + screenTail()
                            repeat(queue.size) { ends += Triple(SessionTurnEnd.FAILED, null, reason) }
                            queue.clear()
                        }
                    }
                    turn.abortAt != null && (interruptedSeen || now - turn.abortAt!! >= ABORT_GRACE_MS) -> {
                        mapper.resultLine() // drop the interrupted turn's usage
                        ends += Triple(SessionTurnEnd.INTERRUPTED, null, null)
                        active = null
                    }
                    apiError != null -> {
                        out.output(mapper.resultLine(isError = true, error = apiError))
                        ends += Triple(SessionTurnEnd.FAILED, null, apiError)
                        active = null
                    }
                    turn.stopAt != null && now - turn.stopAt!! >= STOP_SETTLE_MS -> {
                        // The hook can fire just before the last record is flushed: one more read, then the turn's result.
                        for (line in transcript.read()) for (signal in mapper.map(line)) if (signal is TranscriptSignal.Line) out.output(signal.json)
                        out.output(mapper.resultLine())
                        ends += Triple(SessionTurnEnd.COMPLETED, 0, null)
                        active = null
                    }
                    turn.pastedAt != null && turn.enteredAt == null && now - turn.pastedAt!! >= PASTE_SETTLE_MS -> {
                        key(ENTER)
                        turn.enteredAt = now
                    }
                    turn.enteredAt != null && !turn.submitted && !turn.enterRetried && now - turn.enteredAt!! >= SUBMIT_RETRY_MS -> {
                        // A paste still being taken can swallow the first Enter.
                        key(ENTER)
                        turn.enterRetried = true
                    }
                }
            }
            for ((end, code, reason) in ends) out.turnEnded(end, code, reason)
        }

        /**
         * CLI-PTY-3: the MCP server's pid file, read every [MCP_CHECK_MS]. No file yet = the server has not started: nothing to say. A pid
         * that runs = connected; the same pid gone = disconnected; a new pid (the CLI started it again) that runs = connected again.
         */
        private fun checkMcp(now: Long) {
            val file = launch.mcpPid ?: return
            mcpCheckedAt?.let { if (now - it < MCP_CHECK_MS) return }
            mcpCheckedAt = now
            val pid = runCatching { file.takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull() }.getOrNull() ?: return
            val alive = pidAlive(pid)
            val seen = pid to alive
            if (seen == mcpSeen) return
            val wasUp = mcpSeen?.second
            mcpSeen = seen
            if (alive && wasUp != true) out.mcpState(McpState.CONNECTED, "MCP server pid $pid runs")
            else if (!alive && wasUp != false) out.mcpState(McpState.DISCONNECTED, "MCP server pid $pid exited")
        }

        /** Up = the CLI ran its SessionStart hook a moment ago; without hooks, a while after its first screen. */
        private fun ready(now: Long): Boolean {
            if (trustDownAt != null && !trustEnterDone) return false
            startedAt?.let { return now - it >= READY_AFTER_START_MS }
            return firstScreenAt?.let { now - it >= READY_FALLBACK_MS } ?: false
        }

        /**
         * The folder trust question: "No, exit" is preselected, so Down, then Enter once the screen shows the pointer on the
         * accept option (a key sent before the menu takes input is lost, and Enter on "No, exit" quits the CLI). Down is sent
         * again every [TRUST_RETRY_MS] until the selection shows.
         */
        private fun answerTrust(now: Long) = synchronized(lock) {
            if (trustEnterDone) return@synchronized
            val down = trustDownAt
            if (down == null) {
                if (ScreenText.squeeze(screen.takeLast(TRUST_WINDOW_CHARS).toString()).contains(TRUST_OPTION)) pressDown(now)
                return@synchronized
            }
            val since = (screenTotal - trustDownMark).coerceIn(0L, screen.length.toLong()).toInt()
            when {
                ScreenText.squeeze(screen.takeLast(since).toString()).contains(TRUST_SELECTED) && now - down >= KEY_GAP_MS -> {
                    key(ENTER)
                    trustEnterDone = true
                }
                now - down >= TRUST_RETRY_MS -> pressDown(now)
            }
        }

        private fun pressDown(now: Long) {
            key(DOWN)
            trustDownAt = now
            trustDownMark = screenTotal
        }

        private fun screenTail(): String = ScreenText.squeeze(screen.takeLast(SCREEN_TAIL_CHARS).toString()).ifBlank { "(blank)" }

        private fun key(k: String) { out.write(k) }

        private fun paste(text: String) { out.write(PASTE_START + text + PASTE_END) }
    }

    companion object {
        const val TTY_DIR = "claude-tty"
        const val EV_START = "start"
        const val EV_SUBMIT = "submit"
        const val EV_STOP = "stop"

        const val ESC = "\u001b"
        const val ENTER = "\r"
        const val DOWN = "\u001b[B"
        const val PASTE_START = "\u001b[200~"
        const val PASTE_END = "\u001b[201~"

        /** What the trust question's accept option reads as once spaces are squeezed out. */
        const val TRUST_OPTION = "itrustthisfolder"

        /** The accept option with the menu pointer on it, spaces squeezed out. */
        const val TRUST_SELECTED = "❯yes,itrustthisfolder"

        const val TICK_MS = 150L
        /** How often the MCP server's pid is checked (CLI-PTY-3). */
        const val MCP_CHECK_MS = 2_000L
        const val KEY_GAP_MS = 400L
        const val TRUST_RETRY_MS = 1_500L
        const val PASTE_SETTLE_MS = 400L
        const val SUBMIT_RETRY_MS = 4_000L
        const val STOP_SETTLE_MS = 300L
        const val ABORT_GRACE_MS = 3_000L
        const val READY_AFTER_START_MS = 1_500L
        const val READY_FALLBACK_MS = 8_000L
        const val SCREEN_KEEP_CHARS = 32 * 1024
        const val SCREEN_TAIL_CHARS = 600
        const val TRUST_WINDOW_CHARS = 4096
    }
}

/** headless (default) or interactive, read at every launch; a change restarts the process (the mode is part of the fingerprint). */
class ClaudeModeSession(
    private val headless: HarnessSession,
    private val interactive: ClaudeTtySession,
    private val mode: () -> String?,
) : HarnessSession {
    private fun tty(): Boolean = ClaudeCommand.isInteractive(mode())

    override val label: String get() = headless.label
    override val bootTimeoutMs: Long get() = headless.bootTimeoutMs
    override fun idleMinutes(): Int? = headless.idleMinutes()

    override fun fingerprint(request: HarnessRequest, env: Map<String, String>): String =
        if (tty()) "interactive\u0000" + interactive.fingerprint(request, env) else headless.fingerprint(request, env)

    override fun launch(request: HarnessRequest, env: Map<String, String>): HarnessProcess =
        if (tty()) interactive.launch(request, env) else headless.launch(request, env)

    override fun connect(out: SessionOutput): SessionConnection = headless.connect(out)

    override fun connect(out: SessionOutput, launched: HarnessProcess): SessionConnection =
        if (interactive.owns(launched)) interactive.connect(out, launched) else headless.connect(out, launched)

    override fun terminal(launched: HarnessProcess): Boolean = interactive.owns(launched)
}

/** CLI-PTY-3: whether process [pid] still runs. */
internal fun processAlive(pid: Long): Boolean = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)

internal object TtyJson {
    val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun hooksOf(settings: String?): Map<String, JsonArray> =
        settings?.let { runCatching { (json.parseToJsonElement(it) as? JsonObject)?.get("hooks") as? JsonObject }.getOrNull() }
            ?.mapNotNull { (k, v) -> (v as? JsonArray)?.let { k to it } }?.toMap().orEmpty()

    fun str(s: String) = JsonPrimitive(s)
}
