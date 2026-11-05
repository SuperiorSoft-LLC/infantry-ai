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
 * CLI-PTY-1 (the default, `harness.claude.mode = interactive`): Claude Code run as a person runs it: the interactive `claude` (no `-p`) in a
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
 * - **Permissions (G3, ticket 11754e3b):** in every mode but allowAll the settings carry a `PermissionRequest` http hook to this plugin's
 *   loopback [PermissionHookServer], answered from the Tool approval ([ClaudePermissionHook]: deny, or the owner under ask). Should the CLI
 *   still show its permission dialog with no hook call open (the hook timed out or failed), it is answered No (Esc) after [DIALOG_GRACE_MS]
 *   and the turn ends INTERRUPTED with that reason, so a turn never sits on a dialog nobody sees.
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
    /** G3: [ClaudePermissionHook.TIMEOUT_SETTING], read at every launch. */
    private val approvalHookSeconds: () -> Int = { ClaudePermissionHook.DEFAULT_TIMEOUT_SEC },
    private val permissionHooks: PermissionHookServer = PermissionHookServer(),
) : HarnessSession {
    override val label: String = "claude"

    override fun idleMinutes(): Int = idleMinutes.invoke()

    /** What [launch] decided for a process, until [connect] takes it. Keyed by identity: the host hands back the very object. */
    /** [transcriptStart] = CLI-PTY-4: the length of a resumed session's transcript at launch; what is before it is history, never read as live. */
    /** [approval] / [hookToken] = G3: the Tool approval the permission hook answers from, and the token in its URL (null = no hook). */
    /**
     * [firstPrompt] = G2: where this session's first prompt is kept until claude records it; [recovered] = the launch was asked to resume an
     * id claude has no transcript for, so it starts that id fresh and its first turn carries the kept first prompt again ([ClaudeResume]).
     */
    data class Launch(
        val sessionId: String, val cwd: String, val events: File, val settings: File, val mcpPid: File? = null, val transcriptStart: Long = 0,
        val approval: ToolApproval = ToolApproval.ALLOW_ALL, val hookToken: String? = null,
        val firstPrompt: File? = null, val recovered: Boolean = false,
    )

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
        val stored = request.sessionId?.takeIf { it.isNotBlank() }
        // G2: an id claude never wrote a transcript for cannot be resumed: start it fresh under the same id (no new id to report).
        val recovered = stored != null && !ClaudeResume.resumable(configDir(), request.project, stored)
        val resume = stored.takeUnless { recovered }
        val id = stored ?: newSessionId()
        val dir = File(stateDir(), TTY_DIR).apply { mkdirs() }
        val events = File(dir, "$id.events").apply { delete() }
        val settings = File(dir, "$id.settings.json")
        val mcpPid = File(dir, "$id.mcp.pid").apply { delete() }
        val watch = watchSettings(env).takeIf { request.toolApproval == ToolApproval.ALLOW_ALL }
        val hookToken = UUID.randomUUID().toString().takeIf { ClaudePermissionHook.wanted(request.toolApproval) }
        val hook = hookToken?.let { ClaudePermissionHook.entry(permissionHooks.url(it), approvalHookSeconds()) }
        settings.writeText(ClaudeCommand.ttySettings(events.absolutePath, watch, bypass = ClaudeCommand.permissionMode(request.toolApproval) == "bypassPermissions", permissionHook = hook))
        val process = HarnessProcess(
            command = argv(executable(), request, if (resume == null) id else null, resume, settings.absolutePath, env, mapOf(MCP_PID_FILE_ENV to mcpPid.absolutePath)),
            env = mapOf("CLAUDE_CODE_ENABLE_TODO_TOOLS" to "1") + ClaudeCommand.contextLimitEnv(request.contextLimit),
        )
        synchronized(launches) { launches[process] = Launch(
                id, request.project, events, settings, mcpPid, transcriptStart(resume, request.project), request.toolApproval, hookToken,
                // Only a session that starts a conversation here keeps (or gets back) a first prompt; a real resume has its own history.
                firstPrompt = if (resume == null) ClaudeResume.firstPromptFile(stateDir(), id) else null, recovered = recovered,
            ) }
        return process
    }

    /** A resumed session's transcript already holds its whole history (long sessions: many MB); only what claude adds after launch is this process's. */
    private fun transcriptStart(resume: String?, cwd: String): Long =
        resume?.let { ClaudeTranscripts.find(configDir(), cwd, it)?.length() } ?: 0L

    override fun connect(out: SessionOutput): SessionConnection = error("an interactive claude connects with its launched process")

    override fun connect(out: SessionOutput, launched: HarnessProcess): SessionConnection {
        val launch = synchronized(launches) { launches.remove(launched) } ?: error("this process was not launched by the interactive claude session")
        return TtyConnection(out, launch, configDir(), clock, bootTimeoutMs, pidAlive, permissionHooks).also { if (poll) it.startPolling() }
    }

    /** One interactive claude in its terminal. Every state change happens in [tick] (the poller thread) or under [lock]. */
    class TtyConnection(
        private val out: SessionOutput,
        private val launch: Launch,
        private val configDir: File,
        private val clock: () -> Long,
        private val bootTimeoutMs: Long,
        private val pidAlive: (Long) -> Boolean = ::processAlive,
        private val hooks: PermissionHookServer? = null,
    ) : SessionConnection {
        /** [own] = CLI-PTY-4: the CLI began it by itself (nothing to paste); reported with [SessionOutput.turnStarted]. */
        private class Turn(val prompt: String, val own: Boolean = false) {
            var abortAt: Long? = null
            var pastedAt: Long? = null
            var enteredAt: Long? = null
            var enterRetried = false
            var submitted = false
            var stopAt: Long? = null
            /** G3: why this turn was interrupted by the session itself (a permission dialog answered No); null = the host asked. */
            var abortReason: String? = null
        }

        private val lock = Any()
        private val mapper = TranscriptMapper(launch.sessionId)
        private val events = AppendedLines { launch.events }
        private var transcriptFile: File? = null
        private val transcript = AppendedLines(launch.transcriptStart) {
            transcriptFile ?: ClaudeTranscripts.find(configDir, launch.cwd, launch.sessionId)?.also {
                transcriptFile = it
                // G2: claude has recorded the conversation; its first prompt need not be kept for a recovery any more.
                launch.firstPrompt?.delete()
            }
        }
        /** G2: the first turn of a [Launch.recovered] launch still owes the session's lost first prompt. */
        private var recoveryOwed = launch.recovered
        private val queue = ArrayDeque<Turn>()
        private var active: Turn? = null
        /** CHAT-QUEUE-1: messages pasted into the busy CLI (its own queue takes them into the running turn), each awaiting its Enter. */
        private val injects = ArrayDeque<Long>()
        private val launchedAt = clock()
        private var firstScreenAt: Long? = null
        private var startedAt: Long? = null
        private var trustSeen = false
        private var trustDownAt: Long? = null
        private var trustDowns = 0
        private var trustEnterDone = false
        /** When the screen last changed (the trust answer waits for it to settle). */
        private var lastOutputAt = 0L
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
        /** CLI-PTY-4: why the CLI began a turn by itself, seen while no turn was open (or the open one had stopped); started at the next step. */
        private var ownPending: String? = null
        /** CLI-PTY-4: a Stop seen while no turn was open; a turn of its own seen in the same read is that turn (it was over that fast). */
        private var unclaimedStopAt: Long? = null
        /** G3: permission hook calls being answered now (an ASK waits for the owner): the dialog fallback waits while any is open. */
        private val hookCalls = java.util.concurrent.atomic.AtomicInteger()
        /** G3: [screenTotal] when the last hook call was answered or the last dialog answered No; dialog text before it is history. */
        private var dialogMark = 0L
        private var dialogSeenAt: Long? = null

        init { launch.hookToken?.let { token -> hooks?.register(token, ::answerPermission) } }

        /** G3: one `PermissionRequest` hook call (a server thread): answered from the launch's Tool approval. */
        fun answerPermission(body: String): String {
            hookCalls.incrementAndGet()
            try {
                return ClaudePermissionHook.decide(launch.approval, body, { tool, input -> out.requestApproval(tool, input) }) { tool ->
                    out.detail("interactive claude: denied $tool (tool approval ${launch.approval.name.lowercase()})")
                }
            } finally {
                synchronized(lock) {
                    hookCalls.decrementAndGet()
                    dialogMark = screenTotal
                    dialogSeenAt = null
                }
            }
        }

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
                val at = clock()
                if (firstScreenAt == null) firstScreenAt = at
                lastOutputAt = at
                screen.append(text)
                screenTotal += text.length
                if (screen.length > SCREEN_KEEP_CHARS) screen.delete(0, screen.length - SCREEN_KEEP_CHARS)
            }
        }

        override fun sendTurn(request: HarnessRequest) {
            val turn = Turn(firstPrompt(request.prompt))
            val failed = synchronized(lock) { if (exited) true else { queue.add(turn); false } }
            if (failed) out.turnEnded(SessionTurnEnd.FAILED, null, "the interactive claude has exited")
        }

        /**
         * G2: a new session keeps its first prompt (the daemon's brief rides on it) until claude records it; a recovered launch's first turn
         * sends that prompt again ahead of its own, as the conversation it was in never reached the model.
         */
        private fun firstPrompt(prompt: String): String {
            val file = launch.firstPrompt ?: return prompt
            if (!synchronized(lock) { recoveryOwed.also { recoveryOwed = false } }) {
                if (transcriptFile == null && !launch.recovered) ClaudeResume.keepFirstPrompt(file, prompt)
                return prompt
            }
            val saved = ClaudeResume.savedFirstPrompt(file)
            out.detail(ClaudeResume.recoveredDetail(launch.sessionId, briefResent = saved != null && saved != prompt))
            return ClaudeResume.recoveryPrompt(saved, prompt)
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

        /**
         * CHAT-QUEUE-1: type [text] into the CLI while its turn runs (pasted now, Enter after [PASTE_SETTLE_MS] in [tick]); the interactive
         * claude takes a message entered while busy into the running turn at its next step, without stopping it. Refused (false) with no
         * turn running, one being stopped or one whose own prompt is not entered yet.
         */
        override fun injectInput(text: String): Boolean = synchronized(lock) {
            val turn = active ?: return false
            if (exited || turn.stopAt != null || turn.abortAt != null || !(turn.own || turn.enteredAt != null)) return false
            if (injects.isNotEmpty()) return false
            paste(text)
            injects.addLast(clock())
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
            launch.hookToken?.let { hooks?.unregister(it) }
            // The screen is the only account of why a terminal program quit: show its tail when it did not exit cleanly.
            if (exitCode != 0) out.detail("the interactive claude exited (code $exitCode); screen: " + synchronized(lock) { screenTail() })
            // The host ends its turn in flight itself (sent or still queued here), so nothing is ended twice.
            synchronized(lock) {
                exited = true
                active = null
                queue.clear()
                injects.clear()
            }
            thread?.interrupt()
        }

        /** One step of the state machine; the poller calls it every [TICK_MS]. */
        fun tick() {
            val now = clock()
            for (e in events.read()) {
                when (e.trim().lowercase()) {
                    EV_START -> synchronized(lock) { if (startedAt == null) startedAt = now }
                    EV_SUBMIT -> synchronized(lock) { active?.takeIf { it.stopAt == null }?.let { it.submitted = true } ?: own("a prompt nobody sent") }
                    EV_STOP -> synchronized(lock) {
                        val turn = active
                        if (turn != null && (turn.submitted || turn.enteredAt != null || turn.own)) turn.stopAt = turn.stopAt ?: now
                        else if (turn == null) unclaimedStopAt = now
                    }
                }
            }
            if (!initSent) {
                initSent = true
                out.providerSession(launch.sessionId)
                out.output(mapper.initLine())
            }
            answerTrust(now)
            answerDialog(now)
            checkMcp(now)
            var apiError: String? = null
            for (line in transcript.read()) for (signal in mapper.map(line)) onSignal(signal)?.let { apiError = it }
            val ends = ArrayList<Triple<SessionTurnEnd, Int?, String?>>()
            val starts = ArrayList<String?>()
            synchronized(lock) {
                val turn = active
                when {
                    turn == null && ownPending != null -> {
                        // CLI-PTY-4: the CLI is working on a turn of its own: track it, and paste nothing into it until it ends.
                        val why = ownPending
                        ownPending = null
                        val own = Turn("", own = true).apply { submitted = true }
                        unclaimedStopAt?.let { own.stopAt = it }
                        unclaimedStopAt = null
                        active = own
                        starts += why
                    }
                    turn == null -> {
                        if (queue.isNotEmpty() && injects.isEmpty() && ready(now)) {
                            val next = queue.poll()
                            unclaimedStopAt = null
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
                        ends += Triple(SessionTurnEnd.INTERRUPTED, null, turn.abortReason)
                        active = null
                    }
                    apiError != null -> {
                        out.output(mapper.resultLine(isError = true, error = apiError))
                        ends += Triple(SessionTurnEnd.FAILED, null, apiError)
                        active = null
                    }
                    turn.stopAt != null && now - turn.stopAt!! >= STOP_SETTLE_MS -> {
                        // The hook can fire just before the last record is flushed: one more read, then the turn's result.
                        // A turn the CLI begins at once after this one (a queued notice) is noticed here too, never lost.
                        for (line in transcript.read()) for (signal in mapper.map(line)) onSignal(signal)
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
            synchronized(lock) {
                // CHAT-QUEUE-1: the Enter of a message typed into the running turn, once its paste settled.
                val pastedAt = injects.peekFirst()
                if (pastedAt != null && now - pastedAt >= PASTE_SETTLE_MS) { injects.pollFirst(); key(ENTER) }
            }
            for ((end, code, reason) in ends) out.turnEnded(end, code, reason)
            for (why in starts) out.turnStarted(why)
            synchronized(lock) { unclaimedStopAt?.let { if (now - it >= STOP_CLAIM_MS) unclaimedStopAt = null } }
        }

        /** One transcript signal; returns an API error's text. */
        private fun onSignal(signal: TranscriptSignal): String? {
            when (signal) {
                is TranscriptSignal.Line -> out.output(signal.json)
                TranscriptSignal.PromptRecorded -> synchronized(lock) { active?.takeIf { it.stopAt == null }?.let { it.submitted = true } ?: own("a prompt typed in the terminal", specific = true) }
                is TranscriptSignal.OwnPrompt -> synchronized(lock) { active?.takeIf { it.stopAt == null }?.let { it.submitted = true } ?: own(ownReason(signal.kind), specific = true) }
                TranscriptSignal.Interrupted -> synchronized(lock) { interruptedSeen = true }
                is TranscriptSignal.ApiError -> return signal.text
            }
            return null
        }

        /** CLI-PTY-4: a turn began with none open (or the open one already stopped). Called under [lock]; the first reason wins. */
        private fun own(why: String, specific: Boolean = false) { if (ownPending == null || specific) ownPending = why }

        private fun ownReason(kind: String) = if (kind == ORIGIN_TASK_NOTIFICATION) "a background task ended" else "a $kind"

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
            if (trustSeen && !trustEnterDone) return false
            startedAt?.let { return now - it >= READY_AFTER_START_MS }
            return firstScreenAt?.let { now - it >= READY_FALLBACK_MS } ?: false
        }

        /**
         * The folder trust question: a two-row menu ("No, exit" preselected) where Down wraps, so a key is never sent blind
         * (HARNESS-GAP f0b7173f: a retried Down plus a late redraw put the pointer back on "No, exit" and Enter quit the CLI).
         * The selected row is read from the screen: whichever pointer line was drawn last. Down is pressed only while "No, exit"
         * is selected: once, then again only after [TRUST_RETRY_MS] with no Down taken (no redraw). Enter only while the accept
         * row is selected and the screen has settled: [KEY_GAP_MS] after the one Down, or, once Down was sent more than once
         * (a slow redraw of an earlier key may still come), [TRUST_RETRY_MS] with no new output.
         */
        private fun answerTrust(now: Long) = synchronized(lock) {
            if (trustEnterDone) return@synchronized
            val tail = ScreenText.squeeze(screen.takeLast(TRUST_WINDOW_CHARS).toString())
            if (!trustSeen) {
                if (!tail.contains(TRUST_OPTION)) return@synchronized
                trustSeen = true
            }
            val onYes = tail.lastIndexOf(TRUST_SELECTED) > tail.lastIndexOf(TRUST_DECLINE_SELECTED)
            val quiet = now - lastOutputAt
            val down = trustDownAt
            if (onYes) {
                val settled = when {
                    down == null -> quiet >= KEY_GAP_MS
                    trustDowns == 1 -> now - down >= KEY_GAP_MS
                    else -> now - down >= KEY_GAP_MS && quiet >= TRUST_RETRY_MS
                }
                if (settled) {
                    key(ENTER)
                    trustEnterDone = true
                }
            } else if (down == null || (now - down >= TRUST_RETRY_MS && quiet >= TRUST_RETRY_MS)) {
                key(DOWN)
                trustDownAt = now
                trustDowns++
            }
        }

        /**
         * G3: the CLI's permission dialog on screen (drawn after the last answered hook call) with no hook call open for [DIALOG_GRACE_MS]:
         * nothing will answer it, so answer No (Esc) and end the turn as interrupted with the reason.
         */
        private fun answerDialog(now: Long) = synchronized(lock) {
            val turn = active
            if (turn == null || turn.abortAt != null || hookCalls.get() > 0) { dialogSeenAt = null; return@synchronized }
            val since = (screenTotal - dialogMark).coerceIn(0L, screen.length.toLong()).toInt()
            val tail = ScreenText.squeeze(screen.takeLast(minOf(since, DIALOG_WINDOW_CHARS)).toString()).lowercase()
            if (DIALOG_MARKERS.none { it in tail }) { dialogSeenAt = null; return@synchronized }
            val seen = dialogSeenAt ?: now.also { dialogSeenAt = it }
            if (now - seen < DIALOG_GRACE_MS) return@synchronized
            out.detail("interactive claude: a permission dialog nothing answered; answered No")
            turn.abortAt = now
            turn.abortReason = DIALOG_DENIED_REASON
            interruptedSeen = false
            dialogMark = screenTotal
            dialogSeenAt = null
            key(ESC)
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
        /** The `origin.kind` claude gives the prompt it makes when one of its background tasks ends. */
        const val ORIGIN_TASK_NOTIFICATION = "task-notification"

        const val ESC = "\u001b"
        const val ENTER = "\r"
        const val DOWN = "\u001b[B"
        const val PASTE_START = "\u001b[200~"
        const val PASTE_END = "\u001b[201~"

        /** What the trust question's accept option reads as once spaces are squeezed out. */
        const val TRUST_OPTION = "itrustthisfolder"

        /** The accept option with the menu pointer on it, spaces squeezed out. */
        const val TRUST_SELECTED = "❯yes,itrustthisfolder"

        /** The decline option with the menu pointer on it, spaces squeezed out. */
        const val TRUST_DECLINE_SELECTED = "❯no,exit"

        const val TICK_MS = 150L
        /** How often the MCP server's pid is checked (CLI-PTY-3). */
        const val MCP_CHECK_MS = 2_000L
        const val KEY_GAP_MS = 400L
        const val TRUST_RETRY_MS = 1_500L
        const val PASTE_SETTLE_MS = 400L
        const val SUBMIT_RETRY_MS = 4_000L
        const val STOP_SETTLE_MS = 300L
        /** CLI-PTY-4: how long a Stop seen with no turn open can still end a turn of the CLI's own whose prompt record is read after it. */
        const val STOP_CLAIM_MS = 2_000L
        const val ABORT_GRACE_MS = 3_000L
        const val READY_AFTER_START_MS = 1_500L
        const val READY_FALLBACK_MS = 8_000L
        const val SCREEN_KEEP_CHARS = 32 * 1024
        const val SCREEN_TAIL_CHARS = 600
        const val TRUST_WINDOW_CHARS = 4096

        /** G3: what every permission dialog of the CLI reads as once spaces are squeezed out and lower-cased (its "No" option). */
        val DIALOG_MARKERS = listOf("tellclaudewhattododifferently", "doyouwanttoproceed?")
        /** G3: how long the dialog must stay on screen with no hook call open before it is answered No. */
        const val DIALOG_GRACE_MS = 3_000L
        const val DIALOG_WINDOW_CHARS = 4096
        const val DIALOG_DENIED_REASON = "claude showed a permission dialog that no approval answered; it was answered No"
    }
}

/** interactive (default) or headless (explicit opt-in), read at every launch; a change restarts the process (the mode is part of the fingerprint). */
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
