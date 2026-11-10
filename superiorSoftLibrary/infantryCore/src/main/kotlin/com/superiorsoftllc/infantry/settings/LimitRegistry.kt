// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.settings

/**
 * OWNER RULE (2026-10-25): no hard-coded restriction. Every cap, threshold and timeout of the daemon is an entry here:
 * a documented, overridable setting with a default, never a constant a user cannot see or change.
 *
 * Resolution of one limit, first hit wins: the stored daemon setting (`settings_set`, Settings UI, `infantry` CLI) ->
 * the environment ([LimitDef.envNames]) -> [LimitDef.default]. `0` means UNLIMITED where [LimitDef.zeroIsUnlimited].
 * Entries are added stage by stage (S1 = concurrency); `LimitsGuardTest` fails when a limit-like constant appears in
 * the main sources that is neither routed through this registry nor on the allowlist with a reason.
 */
data class LimitDef(
    /** The setting key, `limits.<group>.<name>`. */
    val key: String,
    val default: Long,
    val unit: String,
    val description: String,
    /** Smallest accepted value (0 where [zeroIsUnlimited]). */
    val min: Long = 0,
    /** Largest accepted value; null = no ceiling. */
    val max: Long? = null,
    val zeroIsUnlimited: Boolean = true,
    /** What 0 means where [zeroIsUnlimited] (shown in hints and refusals). */
    val zeroMeans: String = "unlimited",
    /** Extra environment names read before the default; `INFANTRY_LIMIT_<KEY>` is always read first. */
    val legacyEnv: List<String> = emptyList(),
) {
    /** `limits.concurrency.maxPerPod` -> `INFANTRY_LIMIT_CONCURRENCY_MAX_PER_POD`. */
    val env: String = "INFANTRY_LIMIT_" + key.removePrefix(PREFIX).replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
        .replace('.', '_').uppercase()

    val envNames: List<String> get() = listOf(env) + legacyEnv

    /** The sentence every refusal ends with: which setting, where to change it. */
    fun hint(): String =
        "Change it with the setting '$key' (Settings > Limits, `infantry settings set $key <n>`, or env $env)" +
            (if (zeroIsUnlimited) "; 0 = $zeroMeans" else "") + "."

    fun accepts(value: Long): Boolean = value >= min && (max == null || value <= max)

    fun range(): String = "$min.." + (max?.toString() ?: "no ceiling")

    companion object {
        const val PREFIX = "limits."
    }
}

object LimitRegistry {
    val MAX_PER_POD = LimitDef(
        "limits.concurrency.maxPerPod", 4, "sessions",
        "How many live sessions a session (or MCP connection) may grow its pod by; the worker concurrency cap.",
        legacyEnv = listOf("INFANTRY_POD_SPAWN_CAP"),
    )
    /** LIMITS: the daemon-wide cap on sessions working at once (SessionManager). */
    val MAX_SESSIONS = LimitDef(
        "limits.concurrency.maxSessions", 0, "sessions",
        "How many sessions of this whole daemon may be OPEN at once: working on a turn, or idle with their CLI process kept alive (session.keepAliveMinutes). At the cap a new session, or a turn for a session with no live process, is refused until one is stopped or times out.",
    )
    val MAX_DEPTH = LimitDef(
        "limits.concurrency.maxDepth", 1, "levels",
        "How deep spawns from inside sessions may nest (daemon pod guard and MCP write tools share this one value); 1 = a session may spawn, its spawns may not; 0 = sessions may not spawn at all; set a high number for no practical limit.",
        zeroIsUnlimited = false,
        legacyEnv = listOf("INFANTRY_POD_SPAWN_DEPTH"),
    )
    val MCP_MAX_SPAWNS = LimitDef(
        "limits.concurrency.mcpMaxSpawns", 4, "sessions",
        "How many sessions one MCP connection may hold open at once (`--max-spawns` / .infantry/mcp.json lower or raise it per connection).",
    )
    val LANE_DEFAULT = LimitDef(
        "limits.concurrency.laneDefault", 1, "agents",
        "Agents of one role that may run at once in a project, when the project sets no lane cap of its own; never lower than limits.workers.maxParallel (Max parallel workers).",
    )
    val PACING_CEILING = LimitDef(
        "limits.pacing.maxWorkersCeiling", 50, "workers",
        "Highest worker count the usage pacing rule (`usage.pacing`) accepts for maxWorkers and tier workers.",
        min = 1, zeroIsUnlimited = false,
    )
    /** USAGE-CARD-1: how long a plan meter's last good reading is kept (card shows it stale, pacing uses it) after reads fail. */
    val USAGE_STALE_MAX_HOURS = LimitDef(
        "limits.usage.staleMaxHours", 6, "hours",
        "How long a plan meter's last good reading stays on the Usage card (marked with its age) and keeps usage pacing on while live reads fail (rate limited, network); past this the meter reads unknown and pacing fails open.",
        min = 1, max = 168, zeroIsUnlimited = false,
    )
    /** USAGE-CARD-1: ceiling of the exponential wait after a failed plan meter read (no Retry-After). */
    val USAGE_BACKOFF_MAX_MINUTES = LimitDef(
        "limits.usage.backoffMaxMinutes", 60, "minutes",
        "Longest wait before a plan meter whose read failed is asked again; the wait starts at usage.refreshMinutes and doubles per failure up to this (a Retry-After from the provider wins).",
        min = 1, max = 1440, zeroIsUnlimited = false,
    )
    /** USAGE-CARD-1: a manual Refresh inside this many seconds of the last read answers that read. */
    val USAGE_MANUAL_REFRESH_FLOOR_SECONDS = LimitDef(
        "limits.usage.manualRefreshFloorSeconds", 60, "seconds",
        "Shortest gap between two plan meter reads asked by the Refresh button (or usage_get force); a click inside it answers the last read, so clicks cannot hammer the provider's endpoint.",
        max = 3600, zeroMeans = "no floor",
    )

    /** Ticket 66153313 (contract 1.132.0): build worker cap handed to worker sessions (the tool profiles' env templates). */
    val BUILD_MAX_WORKERS = LimitDef(
        "limits.build.maxWorkers", com.superiorsoftllc.infantry.runner.BuildLimits.defaultMaxWorkers().toLong(), "workers",
        "Parallel build workers a worker session's build may use (each tool profile's env templates pass it on, e.g. ${'$'}{maxWorkers}); default logical cores - 2.",
        max = 4096,
    )
    val BUILD_MAX_PARALLEL_FORKS = LimitDef(
        "limits.build.maxParallelForks", 0, "forks",
        "Parallel test processes per test task in a worker session's build (the tool profiles' ${'$'}{maxParallelForks}).",
        max = 4096, zeroMeans = "the build script's own default",
    )
    val CPU_ALERT_PERCENT = LimitDef(
        "limits.cpu.alertPercent", 90, "%",
        "Share of all cores agent processes may hold before the sustained-CPU attention item is raised.",
        min = 1, max = 100, zeroIsUnlimited = false,
    )
    val CPU_ALERT_MINUTES = LimitDef(
        "limits.cpu.alertMinutes", 10, "minutes",
        "How long agent processes must stay above limits.cpu.alertPercent before the attention item is raised.",
        max = 1440, zeroMeans = "alert off",
    )

    /** S2: the unattended-run watchdog and the silent sweeper (SessionManager), read at every pass. */
    val TICKET_DEADLINE_MINUTES = LimitDef(
        "limits.watchdog.ticketDeadlineMinutes", 180, "minutes",
        "How long an unattended ticket worker may run before the watchdog stops it (a gate run alone can take an hour).",
        zeroMeans = "no deadline", legacyEnv = listOf("INFANTRY_TICKET_DEADLINE_MIN"),
    )
    val UNATTENDED_DEADLINE_MINUTES = LimitDef(
        "limits.watchdog.deadlineMinutes", 60, "minutes",
        "How long an unattended run that is not a ticket worker (a pod node, a spawned helper) may run before the watchdog stops it.",
        zeroMeans = "no deadline",
    )
    val STALL_MINUTES = LimitDef(
        "limits.watchdog.stallMinutes", 15, "minutes",
        "How long an unattended run may print nothing (with no bg job or gate run alive) before the watchdog stops it as stalled.",
        zeroMeans = "never stopped for silence",
    )
    val KILL_RETRY_MINUTES = LimitDef(
        "limits.watchdog.killRetryMinutes", 5, "minutes",
        "How long after a watchdog stop that did not end the run the stop is retried, forced.",
        zeroMeans = "never retried",
    )
    val SILENT_MINUTES = LimitDef(
        "limits.watchdog.silentMinutes", 20, "minutes",
        "How long a busy session may print nothing before it is flagged silent (flag only; a provably dead one is ended).",
        zeroMeans = "never flagged",
    )
    val PROGRESS_STALE_MINUTES = LimitDef(
        "limits.progress.staleMinutes", 10, "minutes",
        "How long a working ticket session may show no tool or event activity before its progress bar is flagged stale (PROGRESS-ALL).",
        zeroMeans = "never flagged",
    )
    /** WAITING-FLAG-1: the session's own "active but waiting" flag. */
    val WAITING_DEFAULT_MINUTES = LimitDef(
        "limits.waiting.defaultMinutes", 30, "minutes",
        "How long a session's waiting flag (infantry waiting on) is expected to last when the agent names no duration.",
        min = 1, zeroIsUnlimited = false,
    )
    val WAITING_GRACE_MINUTES = LimitDef(
        "limits.waiting.graceMinutes", 5, "minutes",
        "How long past its expected end a waiting flag still counts; after that the session counts as silent again and the watcher asks it once whether it is still waiting.",
        min = 0, zeroIsUnlimited = false,
    )
    val WAITING_CLEAR_ON_OUTPUT = LimitDef(
        "limits.waiting.clearOnOutput", 0, "flag",
        "1 = the waiting flag also clears at the session's first output after it; 0 = it clears only when the session's next turn starts.",
        max = 1, zeroMeans = "cleared only when the next turn starts",
    )
    val WAITING_OUTPUT_GRACE_SECONDS = LimitDef(
        "limits.waiting.outputGraceSeconds", 10, "seconds",
        "Output within this long after the flag is set is the flagging command's own answer and does not clear it (when limits.waiting.clearOnOutput is on).",
        zeroMeans = "any later output clears it",
    )
    val WAITING_MAX_MINUTES = LimitDef(
        "limits.waiting.maxMinutes", 10_080, "minutes",
        "The longest wait a session may ask for (infantry waiting on --minutes, infantry_waiting untilMinutes); a longer one is refused.",
        min = 1, zeroIsUnlimited = false,
    )
    val WAITING_REASON_CHARS = LimitDef(
        "limits.waiting.reasonMaxChars", 200, "characters",
        "How much of a waiting reason the card chip, the board line and the session JSON keep; the rest is cut.",
        min = 1, zeroIsUnlimited = false,
    )
    /** OVERNIGHT-1: the worker is prompted before its starter is told. */
    val IDLE_NUDGE_MINUTES = LimitDef(
        "limits.watchdog.idleNudgeMinutes", 10, "minutes",
        "How long a ticket worker may wait at rest (turn ended cleanly, ticket open, no report) before the WORKER is prompted once to report or continue. Its starter is told idleNoticeMinutes after that, if it is still idle.",
        zeroMeans = "the worker is not prompted first",
    )
    /** OVERNIGHT-1: hard upper bound for any queued mail to wait before it wakes an idle session. */
    val MAIL_WAKE_MAX_SECONDS = LimitDef(
        "limits.mail.wakeMaxSeconds", 120, "seconds",
        "Hard upper bound: mail queued for a resting session wakes it at the latest this long after it was sent, whatever mail.wakeMode and the debounce say. Minimum 10.",
        max = 3600,
    )
    val IDLE_NOTICE_MINUTES = LimitDef(
        "limits.watchdog.idleNoticeMinutes", 60, "minutes",
        "How long a ticket worker may wait at rest (turn ended cleanly, ticket open, no mail to or from it) before its starter gets one note that it is waiting.",
        zeroMeans = "never noted",
    )

    /** GAP-006b: the in-progress worker invariant's grace (see TicketBoard.sweepWorkers). */
    val UNBRIEFED_GRACE_MINUTES = LimitDef(
        "limits.watchdog.unbriefedGraceMinutes", 3, "minutes",
        "How long an in-progress ticket's worker may go without opening a provider session before the board restarts it (once; at rest) or flags it (busy, or no worker).",
        zeroMeans = "never swept",
    )

    /** GAP 855db590: what a clean run must show before `tickets.exitBehavior=done` moves its ticket (see TicketBoard.finishTicketRun). */
    val AUTO_DONE_MIN_OUTPUTS = LimitDef(
        "limits.tickets.autoDoneMinOutputs", 1, "outputs",
        "How many assistant outputs or tool calls a ticket worker's run must produce (with a provider session opened) before a clean exit moves its ticket to done under tickets.exitBehavior=done; below it the ticket stays in_progress for the worker sweep.",
        zeroMeans = "any clean exit counts, silent or not",
    )

    /** AGENT-TASKS-2: how long unassigning a ticket waits for its busy worker to finish its turn before ending it anyway. */
    val UNASSIGN_WAIT_SECONDS = LimitDef(
        "limits.agentTasks.unassignWaitSeconds", 120, "seconds",
        "How long unassigning a ticket waits for its busy worker to end its current turn before the worker is ended anyway; the ticket is back in todo at once.",
        zeroMeans = "do not wait: end the worker at once",
    )

    /** AGENT-TASKS-3: how many todo tickets one agent's persistent queue holds. */
    val AGENT_QUEUE_MAX = LimitDef(
        "limits.agentTasks.queueMax", 5, "tickets",
        "How many todo tickets one agent's persistent queue may hold; adding past it is refused.",
        zeroMeans = "no agent queue: adding is refused",
    )

    /** RUNNER-POOL-3 (docs/RUNNER-POOL.md section 8): how often a remote runner is expected to report its health. */
    val RUNNERS_HEARTBEAT_SEC = LimitDef(
        "limits.runners.heartbeatSec", 30, "seconds",
        "How often a registered runner must report in; a runner silent for longer is degraded. Longer = cheaper.",
        min = 1, zeroIsUnlimited = false,
    )

    /** RUNNER-POOL-3: how long a runner may stay silent before it counts as down and takes no new starts. */
    val RUNNERS_LOST_AFTER_SEC = LimitDef(
        "limits.runners.lostAfterSec", 120, "seconds",
        "How long a registered runner may be silent before it is down: only up runners receive new ticket starts.",
        min = 1, zeroIsUnlimited = false,
    )

    /** RUNNER-POOL-3: the default of `runners.<name>.maxWorkers`, workers one runner runs at once. */
    val RUNNER_MAX_WORKERS = LimitDef(
        "limits.runners.maxWorkers", 1, "workers",
        "Per-runner concurrency: how many agents ONE runner machine runs at once unless runners.<name>.maxWorkers says otherwise. It does not set how many board workers run: that is 'Max parallel workers' (limits.workers.maxParallel).",
        min = 1, zeroIsUnlimited = false,
    )

    /**
     * WORKER-CAP-UX-1: the ONE board-worker cap. Auto-start and the lane/one-worker guard read it (a hook arg `maxInProgress` still
     * overrides it, see HOOKS.md); a governor may only LOWER it, recording why in project data `workers.cap.status`.
     */
    val WORKERS_MAX_PARALLEL = LimitDef(
        "limits.workers.maxParallel", 1, "workers",
        "Max parallel workers: how many board ticket workers run at once in a project (auto-start and the one-worker guard read it). A usage governor may lower the effective value; project data `workers.cap.status` says why and since when. Per-runner machine concurrency is limits.runners.maxWorkers.",
        min = 1, zeroIsUnlimited = false,
    )

    /** CHAT-QUEUE-1: how many messages one session holds while its turn runs. */
    val CHAT_QUEUE_MAX = LimitDef(
        "limits.chat.queueMax", 20, "messages",
        "How many messages typed while an agent is busy one session may hold for its next turn; queueing past it is refused.",
        min = 1, zeroIsUnlimited = false,
    )

    /** Job logs: how many ended watched jobs per session stay readable (their last lines and exit code). */
    val JOBS_REMEMBERED = LimitDef(
        "limits.jobs.remembered", 5, "jobs",
        "How many ended watched jobs a session keeps readable for a viewer plugin (their last lines and exit code).",
        min = 0, zeroIsUnlimited = false,
    )

    /** Job logs: the most bytes one job log read returns, whatever a client asks for. */
    val JOB_LOG_MAX_READ_BYTES = LimitDef(
        "limits.jobs.maxReadBytes", 65536, "bytes",
        "The most bytes one job-log read (a viewer plugin) returns, whatever the client asks for. Keeps one read cheap.",
        min = 1024, zeroIsUnlimited = false,
    )

    /** CHAT-SENDING-1: how long a client shows a sent message as "sending..." before it offers "not sent - retry". */
    val CHAT_SEND_ACK_SECONDS = LimitDef(
        "limits.chat.sendAckSeconds", 45, "seconds",
        "How long the chat waits for the daemon to accept a sent message before it marks it \"not sent - retry\" (the text is kept).",
        min = 1, zeroIsUnlimited = false,
    )

    /** CHAT-SENDING-1: how many client message ids the daemon remembers so a resend of an accepted message is not run twice. */
    val CHAT_SENT_IDS_KEPT = LimitDef(
        "limits.chat.sentIdsKept", 500, "ids",
        "How many recent client message ids the daemon remembers; a resend with a remembered id is acknowledged, never run twice.",
        min = 1, zeroIsUnlimited = false,
    )

    /** AGENT-TASKS-2: commit subjects listed in the auto handoff note. */
    val HANDOFF_COMMITS = LimitDef(
        "limits.agentTasks.handoffCommits", 10, "commits",
        "How many commit subjects the handoff note of an unassigned ticket lists.",
        zeroMeans = "list none",
    )

    /** AGENT-TASKS-2: worker message length kept in the auto handoff note. */
    val HANDOFF_CHARS = LimitDef(
        "limits.agentTasks.handoffChars", 1500, "characters",
        "How many characters of the worker's last message the handoff note of an unassigned ticket keeps.",
        zeroMeans = "keep none",
    )

    /** BUG PLUGIN-SOURCE-REFRESH-FREEZE-2: how long a todo ticket's "has work" git facts are served before they are checked again. */
    val BOARD_HAS_WORK_TTL_MS = LimitDef(
        "limits.board.hasWorkTtlMs", 60_000, "ms",
        "PLUGIN-SOURCE-REFRESH-FREEZE-2: how old a todo ticket's \"has work\" chip (its branch ahead of its base) may be before the board checks it again. The check runs git, one call at a time on a background thread, and every board list serves the last answer meanwhile; it used to run git for every todo ticket with a branch on EVERY list, under the board lock, which on a busy board spawned thousands of git processes and froze the app. 0 = check on every list (the old, costly behaviour).",
        min = 0, zeroIsUnlimited = false,
    )

    /** RUN-CONSOLE (ticket 67f117b6, contract 1.134.0): the raw terminal output a run keeps for a console to replay. */
    val RUN_SCROLLBACK_BYTES = LimitDef(
        "limits.run.scrollbackBytes", 2L * 1024 * 1024, "bytes",
        "Raw terminal output each saved-command run keeps (newest kept), what its console window replays when opened late or after the run.",
        min = 1024, max = Int.MAX_VALUE.toLong(), zeroIsUnlimited = false,
    )
    /** CLI-PTY-5: the chat items each session keeps across a daemon restart (newest kept), shown at once on open. */
    val CHAT_HISTORY_FRAMES = LimitDef(
        "limits.chat.historyFrames", 200, "items",
        "Chat items (messages, replies, reasoning, file writes) each session keeps on disk, so after a restart its chat shows the newest of them at once, as history, without starting its CLI. Older items are dropped. 0 = keep none.",
        max = 100_000, zeroIsUnlimited = false, zeroMeans = "keep none",
    )
    val RUN_HISTORY = LimitDef(
        "limits.run.history", 20, "runs",
        "Saved-command runs the daemon remembers (the Run tab's pills, each with its scrollback); the oldest finished run is forgotten first.",
        min = 1, max = 10_000, zeroIsUnlimited = false,
    )

    /** SEC-FIX-1 M2 (contract 1.136.0): what a console may type into an interactive run, and how big its terminal may be. */
    val RUN_INPUT_BYTES_PER_SECOND = LimitDef(
        "limits.run.inputBytesPerSecond", 64L * 1024, "bytes/s",
        "Keyboard bytes per second one run accepts through run_input (a paste included); input over it is refused, not queued.",
    )
    val RUN_INPUT_MAX_FRAME_BYTES = LimitDef(
        "limits.run.inputMaxFrameBytes", 16L * 1024, "bytes",
        "Largest single run_input frame (decoded bytes); a bigger one is refused whole.",
        min = 1, max = Int.MAX_VALUE.toLong(), zeroIsUnlimited = false,
    )
    val RUN_INPUT_QUEUE_BYTES = LimitDef(
        "limits.run.inputQueueBytes", 256L * 1024, "bytes",
        "Input waiting to reach a run's terminal (written off the WebSocket loop); while this much waits, more input is refused.",
        min = 1, max = Int.MAX_VALUE.toLong(), zeroIsUnlimited = false,
    )
    val RUN_MAX_COLS = LimitDef(
        "limits.run.maxCols", 1000, "columns",
        "Widest terminal a run_start or run_resize may ask for; a wider one is clamped to it.",
        min = 1, max = 10_000, zeroIsUnlimited = false,
    )
    val RUN_MAX_ROWS = LimitDef(
        "limits.run.maxRows", 1000, "rows",
        "Tallest terminal a run_start or run_resize may ask for; a taller one is clamped to it.",
        min = 1, max = 10_000, zeroIsUnlimited = false,
    )

    /** Ticket 3b30398d: the process-watch pass (ROGUE-1 ledger, CPU sampling), read before every pass. */
    val PROCESS_WATCH_INTERVAL_SECONDS = LimitDef(
        "limits.processes.watchIntervalSeconds", 10, "seconds",
        "How often the process watch walks the session process trees (rogue-process ledger, CPU sampling); also the delay of the start-up cleanup.",
        min = 1, max = 3600, zeroIsUnlimited = false,
    )

    /** PROC-LEAK-1: how long a closing session's build daemons get after their profile's graceful `stop` before they are killed. */
    val PROCESS_STOP_GRACE_SECONDS = LimitDef(
        "limits.processes.stopGraceSeconds", 10, "seconds",
        "When a session closes, its build daemons are first asked to stop (their tool profile's stop command, run in the session's worktree, only when no other process of that profile is alive); whatever is still alive after this many seconds is killed. 0 = kill at once, no graceful step.",
        min = 0, max = 600, zeroMeans = "no graceful step",
    )

    /** PROC-LEAK-1: a tool profile's `stuck` client (a CLI call) that used no CPU for this long is reported stuck on its session. */
    val PROCESS_STUCK_MINUTES = LimitDef(
        "limits.processes.stuckMinutes", 15, "minutes",
        "A short-lived client process of a session (a tool profile's stuck list: the infantry CLI) that has used no CPU for this many minutes is reported as stuck in the processes list and raised once to the owner. 0 = off.",
        min = 0, max = 1440, zeroMeans = "off",
    )

    /** Stale owner questions: an open question whose asker sits idle this long is orphaned by the question sweep. */
    val QUESTION_TTL_MINUTES = LimitDef(
        "limits.questions.ttlMinutes", 1440, "minutes",
        "How long an open owner question may wait while its asking session is not mid-turn before the daemon closes it as orphaned (its session is also checked: a missing one closes it at once).",
        max = 525_600, zeroMeans = "never expires by age",
    )

    /** BG-NOTIFY (ticket 245a7d31): `infantry bg run --notify` notices; a job's own `--notify-tail` / `--notify-timeout` win. */
    val BG_NOTIFY_TAIL_LINES = LimitDef(
        "limits.bg.notifyTailLines", 20, "lines",
        "How many last output lines a background job notice (`bg run --notify`) carries to the worker.",
        min = 1, max = 2000, zeroIsUnlimited = false,
    )
    val BG_NOTIFY_TIMEOUT_MINUTES = LimitDef(
        "limits.bg.notifyTimeoutMinutes", 15, "minutes",
        "The watch heartbeat: how long a watched job may run before its worker gets a status (elapsed, last output, process alive); the interval then doubles up to limits.watch.heartbeatCapMinutes.",
        zeroMeans = "never noticed while running",
    )
    /** STALL-1 (ticket 802c64d5): the daemon's job watch, heartbeat and stall detector (no model tokens). */
    val WATCH_QUIET_MINUTES = LimitDef(
        "limits.watch.quietMinutes", 5, "minutes",
        "How long a watched job may print no new line before its worker is told once that it went quiet.",
        max = 1440, zeroMeans = "never told",
    )
    val WATCH_HEARTBEAT_CAP_MINUTES = LimitDef(
        "limits.watch.heartbeatCapMinutes", 60, "minutes",
        "The largest interval between watch heartbeats (they start at limits.bg.notifyTimeoutMinutes and double); the first beat at it is also told to the watch's recipient rule (watch.escalateTo).",
        min = 1, max = 1440, zeroIsUnlimited = false,
    )
    /** USAGE-REGRESS-1: every stall nudge is a wake that re-reads the whole context; off by default (the board's live state still shows the stall). */
    val WATCH_STALL_NUDGES = LimitDef(
        "limits.watch.stallNudges", 0, "switch",
        "Recommended 0 (off): no stall nudge wakes a worker; the PM still sees the stall in the board's live state. 1 = nudge a stalled worker (limits.watch.stallMinutes / foregroundStallMinutes), which costs one full-context turn per nudge.",
        max = 1, zeroIsUnlimited = false,
    )
    val WATCH_STALL_MINUTES = LimitDef(
        "limits.watch.stallMinutes", 5, "minutes",
        "Stall detector: how long a ticket worker may sit idle with no job, or BUSY and silent after its job ended, before it is nudged and the watch's recipient rule (watch.escalateTo) is told.",
        max = 1440, zeroMeans = "never nudged",
    )
    val WATCH_FOREGROUND_STALL_MINUTES = LimitDef(
        "limits.watch.foregroundStallMinutes", 10, "minutes",
        "Stall detector: how long a ticket worker may stay BUSY with no tool progress and no watched job (a long or hung foreground call) before it is nudged and the watch's recipient rule (watch.escalateTo) is told.",
        max = 1440, zeroMeans = "never nudged",
    )
    val WATCH_EXEMPT_WHEN_TICKET_DONE = LimitDef(
        "limits.watch.exemptWhenTicketDone", 1, "switch",
        "1 = a session whose ticket is done, or that already mailed its final report, gets no idle nudge and no failure notice for a non-zero exit; 0 = watch it like any other.",
        max = 1, zeroIsUnlimited = false,
    )
    val IDLE_COMMENT = LimitDef(
        "limits.watch.idleComment", 1, "switch",
        "1 = a ticket worker that ends its turn cleanly with the ticket still in progress, and neither commented on the ticket nor mailed its starter, gets one open daemon comment on the ticket (branch, tip, dirty flag, last output); 0 = off.",
        max = 1, zeroIsUnlimited = false,
    )
    val FINISH_ONCE = LimitDef(
        "limits.notice.finishOnce", 1, "switch",
        "NOTIFY-DUP-1: 1 = the daemon sends the starter ONE automatic finish notice per ticket and worker session (none when the worker already mailed its report, none for a kill the client asked for); 0 = a notice for every run that ends.",
        max = 1, zeroIsUnlimited = false,
    )
    val WATCH_NOTICE_DEDUPE = LimitDef(
        "limits.watch.noticeDedupe", 1, "switch",
        "STALL-6: 1 = a bg job's queued and future notices are dropped once its owner read its log/status after it ended or ran `infantry bg ack <id>`, a newer job with the same label superseded it, or its end made the 'closed output' notice stale; one 'closed output' and one 'finished' notice per job at most. 0 = every notice is sent.",
        max = 1, zeroIsUnlimited = false,
    )
    val NOTICE_MAIL_PLUGIN_HEALTH = LimitDef(
        "limits.notice.mailPluginHealth", 0, "switch",
        "NOTICE-ROUTE-1: 0 (cheapest) = a daemon plugin cooling down / recovered / failed is written to the daemon log only, no agent mailbox is woken; 1 = also mailed to each project's PM (one full-context wake per notice).",
        max = 1, zeroIsUnlimited = false,
    )
    val NOTICE_MAIL_BG_STATUS = LimitDef(
        "limits.notice.mailBgStatus", 0, "switch",
        "NOTICE-ROUTE-1: 0 (cheapest) = a bg job tells its session only that it finished; the 'quiet for Nm' and 'still running after Nm' notices are not sent; 1 = they are mailed too (each a standalone wake).",
        max = 1, zeroIsUnlimited = false,
    )
    val BG_NOTICE_FOLD_INTO_TURN = LimitDef(
        "limits.bg.noticeFoldIntoTurn", 1, "switch",
        "BG-FOLD-1: 1 (cheapest) = a job-finished notice that arrives while its session is mid-turn waits for the turn to end and is dropped if the result was read meanwhile; 0 = it is queued at once and costs a standalone full-context wake (about 80-100K cache-read tokens).",
        max = 1, zeroIsUnlimited = false,
    )
    val BG_NOTICE_SKIP_IF_RESULT_READ = LimitDef(
        "limits.bg.noticeSkipIfResultRead", 1, "switch",
        "BG-FOLD-1: 1 (cheapest) = a notice is not sent when its owner already read the job's result (bg ack); 0 = it is sent anyway, one more full-context wake.",
        max = 1, zeroIsUnlimited = false,
    )
    val WATCH_HANDLED_KEEP_MINUTES = LimitDef(
        "limits.watch.handledKeepMinutes", 1440, "minutes",
        "STALL-6: how long the daemon remembers that a bg job was handled/ended (to drop its late notices) before forgetting it.",
        min = 1, max = 100_000, zeroIsUnlimited = false,
    )
    val MCP_HEAL_ATTEMPTS = LimitDef(
        "limits.mcp.healAttempts", 5, "tries",
        "MCP-RECONNECT-1: how many times the daemon reconnects a session whose MCP link is disconnected before it gives up and raises a loud note to the session and its project's PM.",
        min = 1, max = 100, zeroIsUnlimited = false,
    )
    val MCP_HEAL_POLL_SECONDS = LimitDef(
        "limits.mcp.healPollSeconds", 2, "seconds",
        "MCP-RECONNECT-1: how often the daemon looks for sessions whose MCP link is disconnected.",
        min = 1, max = 3_600, zeroIsUnlimited = false,
    )
    val MCP_HEAL_DELAY_SECONDS = LimitDef(
        "limits.mcp.healDelaySeconds", 5, "seconds",
        "MCP-RECONNECT-1: the wait before the second reconnect try; each further try waits twice as long, up to limits.mcp.healMaxDelaySeconds.",
        min = 1, max = 3_600, zeroIsUnlimited = false,
    )
    val MCP_HEAL_MAX_DELAY_SECONDS = LimitDef(
        "limits.mcp.healMaxDelaySeconds", 60, "seconds",
        "MCP-RECONNECT-1: the longest wait between two reconnect tries of a disconnected session.",
        min = 1, max = 86_400, zeroIsUnlimited = false,
    )
    val WATCH_TREE_JOB_SECONDS = LimitDef(
        "limits.watch.treeJobSeconds", 30, "seconds",
        "How long a process under a worker's harness (one the daemon did not launch) must have run for its end to be told to the worker.",
        min = 1, max = 86_400, zeroIsUnlimited = false,
    )
    /**
     * STACK-NEUTRAL-1: the process/build watch tooling as a whole (process ledger and reaping, build limits env, PATH shims
     * and harness hooks). Opt-in: a fresh install starts with 0; an install that existed before is migrated to 1 once.
     */
    val WATCH_ENABLED = LimitDef(
        "limits.watch.enabled", 0, "switch",
        "1 = the watch tooling is on: worker processes are tracked and reaped with their session, worker builds get the build limits env of the tool profiles, and long commands run through the watched runner; 0 = off: nothing tracked, nothing killed, no build env, plain exit codes and logs.",
        max = 1, zeroIsUnlimited = false,
    )
    val WATCH_FORCED = LimitDef(
        "limits.watch.forced", 1, "switch",
        "1 = every worker session runs the tool profiles' shimmed programs (PATH shims) and, through its harness hook, their long commands and backgrounded shell commands through the watched runner; 0 = off for every session (owner only). Needs limits.watch.enabled = 1.",
        max = 1, zeroIsUnlimited = false,
    )
    /** BG-NATIVE-1: background mode for harnesses with native background tasks. */
    val WATCH_BG_NATIVE = LimitDef(
        "limits.watch.bgNative", 0, "switch",
        "BG-WAKE-1: 0 = daemon (default): the hook rewrites a backgrounded command into the watched runner, so its end reaches the daemon and the session gets ONE `task finished` line. 1 = native: Claude Code runs `run_in_background` itself; its finish never reaches a daemon-run session.",
        max = 1, zeroIsUnlimited = false,
    )
    val WATCH_HANG_GRACE_SECONDS = LimitDef(
        "limits.watch.hangGraceSeconds", 30, "seconds",
        "BG-NATIVE-1 hang-watch: a background job that printed a 'finished' pattern (tool profiles + project patterns) and has not exited after this long has its process tree ended, so its normal finished note fires. Silent: never mails.",
        max = 86_400, zeroMeans = "hang-watch off",
    )
    val WATCH_TREE_WAIT_MINUTES = LimitDef(
        "limits.watch.treeWaitMinutes", 30, "minutes",
        "After a watched command exits, how long the runner waits for the rest of its process tree (Job Object / subreaper; build daemons excluded) before it reports done anyway, naming the survivors.",
        max = 1440, zeroMeans = "no tree wait (done at the command's own exit)",
    )
    /** STALL-4 (ticket 14ac9383) item 4: the watch under tight disk, memory or message space. */
    val WATCH_NOTICE_LINE_CHARS = LimitDef(
        "limits.watch.noticeLineChars", 200, "characters",
        "A watch notice's output lines are cut to this many characters, so one huge line cannot blow up a mail or the model context.",
        min = 20, max = 4000, zeroIsUnlimited = false,
    )
    val WATCH_LOG_MAX_MB = LimitDef(
        "limits.watch.logMaxMB", 50, "MB",
        "The largest log file the watched runner writes per job; past it the head is cut (the tail is kept, with a 'truncated N bytes' marker).",
        max = 100_000, zeroMeans = "no per-job cap",
    )
    val WATCH_LOG_TOTAL_MB = LimitDef(
        "limits.watch.logTotalMB", 500, "MB",
        "The most space all finished job logs may take together; the oldest finished jobs are pruned past it.",
        max = 1_000_000, zeroMeans = "no total cap",
    )
    val WATCH_LOG_KEEP_JOBS = LimitDef(
        "limits.watch.logKeepJobs", 50, "jobs",
        "How many finished jobs' log files are kept; older ones are pruned when a job starts.",
        max = 100_000, zeroMeans = "kept regardless of count",
    )
    val WATCH_LOG_KEEP_DAYS = LimitDef(
        "limits.watch.logKeepDays", 7, "days",
        "Finished jobs' log files older than this are pruned when a job starts.",
        max = 3650, zeroMeans = "kept regardless of age",
    )
    val WATCH_MIN_FREE_MB = LimitDef(
        "limits.watch.minFreeMB", 200, "MB",
        "Below this much free disk space the runner stops writing a job's log file and keeps streaming its output to the console only (told once).",
        max = 1_000_000, zeroMeans = "never paused",
    )
    val WATCH_READ_MAX_KB = LimitDef(
        "limits.watch.readMaxKB", 256, "KB",
        "The most a watch pass reads from one watched file; a bigger jump since the last pass skips to its tail (the file is never copied).",
        min = 1, max = 1_048_576, zeroIsUnlimited = false,
    )
    val WATCH_TAIL_READ_KB = LimitDef(
        "limits.watch.tailReadKB", 64, "KB",
        "How far from a watched file's end a notice's last lines are read (and the longest unfinished line kept between passes).",
        min = 1, max = 65_536, zeroIsUnlimited = false,
    )
    val WATCH_SPACE_CHECK_SECONDS = LimitDef(
        "limits.watch.spaceCheckSeconds", 5, "seconds",
        "How often the watched runner checks the free disk space against limits.watch.minFreeMB while it writes a job log.",
        min = 1, max = 3600, zeroIsUnlimited = false,
    )
    val WATCH_FILE_IDLE_SECONDS = LimitDef(
        "limits.watch.fileIdleSeconds", 120, "seconds",
        "`infantry watch-file` with no --until-pid: the file is done once it has not grown for this long.",
        min = 1, max = 86_400, zeroIsUnlimited = false,
    )
    val BG_NOTIFY_PATTERN_GRACE_SECONDS = LimitDef(
        "limits.bg.notifyPatternGraceSeconds", 20, "seconds",
        "How long a pattern match on a `bg run --notify` job's output waits for the job to exit before it is noticed on its own (an exit within it sends one notice, not two).",
        max = 3600, zeroMeans = "noticed at the next watch pass",
    )

    /** TICKET-FILES (ticket 55476f3c): the most lines `ticket files` / `ticket_files` / `ticket_related_files` print. */
    val TICKET_FILES_MAX_LINES = LimitDef(
        "limits.ticketFiles.maxLines", 60, "lines",
        "The most file lines `infantry ticket files` and the ticket_files / ticket_related_files tools print in one answer; the rest is counted, never printed.",
        min = 1, zeroIsUnlimited = false,
    )

    /** TICKET-FILES: how many related tickets `ticket_related_files` unions the files of. */
    val TICKET_FILES_RELATED_TOP = LimitDef(
        "limits.ticketFiles.relatedTop", 5, "tickets",
        "How many related tickets (ranked by the Jaccard overlap of their changed files) ticket_related_files and `infantry ticket files <id> --related` take the files of.",
        min = 1, max = 100, zeroIsUnlimited = false,
    )

    /** KNOWLEDGE-PTR-1 (b8ca2bf7): the RELATED WORK block of a ticket's start message (related.brief). */
    val RELATED_BRIEF_TICKETS = LimitDef(
        "limits.ticketFiles.briefTickets", 5, "tickets",
        "How many related tickets (linked, named, same series, same files) the RELATED WORK block of a ticket's start message lists.",
        min = 1, max = 100, zeroIsUnlimited = false,
    )
    val RELATED_BRIEF_FILES = LimitDef(
        "limits.ticketFiles.briefFilesPerTicket", 5, "files",
        "How many changed files of each related ticket the RELATED WORK block of a start message names; the rest is counted.",
        max = 100, zeroIsUnlimited = false,
    )
    val RELATED_BRIEF_CHARS = LimitDef(
        "limits.ticketFiles.briefChars", 1500, "characters",
        "The most characters of the RELATED WORK block of a start message (the prompt is re-read every turn, so it stays small).",
        min = 200, max = 20_000, zeroIsUnlimited = false,
    )
    /** KNOWLEDGE-PTR-1: the stored `RELATED (auto ...)` block at the top of a description (related.descriptionBlock). */
    val RELATED_BLOCK_TICKETS = LimitDef(
        "limits.ticketFiles.blockTickets", 8, "tickets",
        "How many related tickets the stored RELATED (auto) block at the top of a ticket description lists.",
        min = 1, max = 100, zeroIsUnlimited = false,
    )
    val RELATED_BLOCK_FILES = LimitDef(
        "limits.ticketFiles.blockFiles", 5, "files",
        "How many files per related ticket, and top files in all, the stored RELATED (auto) block of a description names.",
        max = 100, zeroIsUnlimited = false,
    )
    val RELATED_BLOCK_CHARS = LimitDef(
        "limits.ticketFiles.blockChars", 2500, "characters",
        "The most characters of the stored RELATED (auto) block of a description; lines past it are dropped.",
        min = 200, max = 20_000, zeroIsUnlimited = false,
    )

    /** SEC-FIX-2a (SECRETS-2): how many secrets a saved command or one run_start may name. */
    val RUN_MAX_SECRETS = LimitDef(
        "limits.secrets.maxPerRun", 16, "secrets",
        "The most keystore secrets one saved command (commands.json) or one run_start may name; more is refused.",
        min = 1, max = 10_000, zeroIsUnlimited = false,
    )

    /** SEC-FIX-2a (SECRETS-2): the most fields one keystore secret holds. */
    val KEYSTORE_MAX_FIELDS = LimitDef(
        "limits.secrets.maxFields", 64, "fields",
        "The most fields one keystore secret may hold; setting a new field beyond it is refused.",
        min = 1, max = 10_000, zeroIsUnlimited = false,
    )

    /** SEC-FIX-2a (SECRETS-2): the most secrets the keystore holds. */
    val KEYSTORE_MAX_SECRETS = LimitDef(
        "limits.secrets.maxSecrets", 256, "secrets",
        "The most secrets the daemon keystore may hold; adding a new secret beyond it is refused.",
        min = 1, max = 100_000, zeroIsUnlimited = false,
    )

    /** SANDBOX-1 (ticket d4c1dbd9): confine agent CLIs (runner/Sandbox.kt); the dirs are `limits.sandbox.allowedDirs`. */
    val SANDBOX_MODE = LimitDef(
        "limits.sandbox.mode", 0, "mode",
        "1 = every agent CLI runs confined: shell and web tools off, files only in its working dir + limits.sandbox.allowedDirs (env INFANTRY_LIMIT_SANDBOX_ALLOWED_DIRS), its own config read-only, a scoped token instead of the owner token. OS confinement is Linux-only (Landlock); elsewhere it is NOT enforced yet.",
        max = 1, zeroMeans = "off",
    )

    /**
     * Ticket 55d60a5e: `/health` and its watchdog (daemon/HealthGate.kt). Read ONCE at start-up, while the database still
     * answers: the watchdog exists for the moment it does not, so it must never read a setting from it. A change applies
     * on the next start.
     */
    val HEALTH_DB_TIMEOUT_SECONDS = LimitDef(
        "limits.health.dbTimeoutSeconds", 3, "seconds",
        "How long /health waits for the database before it answers 503 with database=stuck (the request thread is never held longer). Read at start-up.",
        min = 1, max = 3600, zeroIsUnlimited = false,
    )
    val USAGE_QUOTA_HIT_HOLD_MINUTES = LimitDef(
        "limits.usage.quotaHitHoldMinutes", 60, "minutes",
        "G15: after a harness reports a quota error (e.g. opencode's \"Free usage exceeded\"), its provider reads EXHAUSTED for this long, so pacing starts no unattended work on it and `infantry usage` shows it. Read at start-up.",
        min = 1, max = 10080, zeroIsUnlimited = false,
    )
    /** DAEMON-HEALTH-1: the restart / disconnect log (`<stateDir>/health/events.jsonl`). */
    val HEALTH_EVENT_LOG_MAX = LimitDef(
        "limits.health.eventLogMax", 500, "events",
        "DAEMON-HEALTH-1: how many restart / MCP-disconnect events the daemon keeps in its health log (`infantry health` shows the latest); older ones are dropped. Read at start-up.",
        min = 10, max = 100000, zeroIsUnlimited = false,
    )
    val HEALTH_HEARTBEAT_SECONDS = LimitDef(
        "limits.health.heartbeatSeconds", 10, "seconds",
        "DAEMON-HEALTH-1: how often the daemon refreshes its health record (`last seen`), which `infantry health` uses to tell a dead daemon from a live one when it does not answer. Read at start-up.",
        min = 1, max = 3600, zeroIsUnlimited = false,
    )
    val HEALTH_WATCHDOG_FAILURES = LimitDef(
        "limits.health.watchdogFailures", 0, "checks",
        "Failed /health self-checks in a row after which the headless daemon halts its JVM so its supervisor (Docker restart policy) restarts it; Docker never restarts an unhealthy container by itself. The Docker image sets 3. Read at start-up.",
        max = 1000, zeroMeans = "watchdog off",
    )
    val HEALTH_WATCHDOG_INTERVAL_SECONDS = LimitDef(
        "limits.health.watchdogIntervalSeconds", 10, "seconds",
        "How often the health watchdog asks the daemon's own /health (also the delay before its first check). Read at start-up.",
        min = 1, max = 3600, zeroIsUnlimited = false,
    )
    val HEALTH_WATCHDOG_TIMEOUT_SECONDS = LimitDef(
        "limits.health.watchdogTimeoutSeconds", 5, "seconds",
        "How long one health watchdog check waits for /health before counting it as failed. Read at start-up.",
        min = 1, max = 3600, zeroIsUnlimited = false,
    )

    /** DAEMON-PLUGINS-1 (docs/DAEMON-PLUGINS.md section 5): a daemon plugin's defaults; its module.json `daemon.limits` override them. */
    val PLUGIN_CALL_TIMEOUT_MS = LimitDef(
        "limits.plugins.callTimeoutMs", 2_000, "ms",
        "GUARD-1: how long one daemon plugin hook, frame or route call may take before it is logged as SLOW (never a strike: a busy host stretches calls). A strike needs a call with no result by limits.plugins.hangCeilingMs; a call burning CPU past its budget is disabled at once (a module's daemon.limits.callTimeoutMs overrides it).",
        min = 1, zeroIsUnlimited = false,
    )
    val PLUGIN_HANG_CEILING_MS = LimitDef(
        "limits.plugin.guard.timeoutMs", 30_000, "ms",
        "GUARD-1: the hard wall-clock ceiling for one daemon plugin call (never lower than the call's own timeout); a call with no result by then is a true hang and counts one strike. Read at plugin load.",
        min = 1, zeroIsUnlimited = false,
    )
    val PLUGIN_GUARD_STRIKES = LimitDef(
        "limits.plugin.guard.strikes", 3, "timeouts",
        "PLUGIN-RECOVER-1: consecutive hang-ceiling timeouts that open a daemon plugin's circuit breaker (calls then refuse fast until plugin.guard.cooldownMs passes). Read at plugin load.",
        min = 1, zeroIsUnlimited = false,
    )
    val PLUGIN_GUARD_COOLDOWN_MS = LimitDef(
        "limits.plugin.guard.cooldownMs", 30_000, "ms",
        "PLUGIN-RECOVER-1: how long an open circuit refuses calls before one probe call is let through; a successful probe closes it again, no restart needed. Read at plugin load.",
        min = 0, zeroIsUnlimited = false,
    )
    val PLUGIN_TOOL_TIMEOUT_MS = LimitDef(
        "limits.plugins.toolTimeoutMs", 60_000, "ms",
        "How long one daemon plugin tool call may run before the plugin is disabled (daemon.limits.toolTimeoutMs overrides it); infantry mcp serve waits a quarter longer for the answer.",
        min = 1, zeroIsUnlimited = false,
    )
    /** PLUGIN-BRIDGES-1: a capability call's wait for its daemon handler or client host (a capability's own timeoutMs wins, capped at the tool ceiling). */
    val PLUGIN_CAPABILITY_TIMEOUT_MS = LimitDef(
        "limits.plugins.capabilityTimeoutMs", 15_000, "ms",
        "How long one capability call (infantry_capability_call, ih.call, a plugin's api.capabilities.call) waits for its daemon handler or client host before it fails; a capability's own declared timeoutMs wins, capped at limits.plugins.toolMaxTimeoutMs.",
        min = 1, zeroIsUnlimited = false,
    )
    val PLUGIN_TOOL_MAX_TIMEOUT_MS = LimitDef(
        "limits.plugins.toolMaxTimeoutMs", 1_800_000, "ms",
        "The ceiling for a plugin tool's own declared timeoutMs (a long tool such as a harness install opts in; it can never wait longer than this).",
        min = 1, zeroIsUnlimited = false,
    )
    val PLUGIN_POOL_SIZE = LimitDef(
        "limits.plugins.poolSize", 8, "threads",
        "BOARD-STALL-2: worker threads each daemon plugin gets for its calls; more calls wait in the queue. Read at plugin load.",
        min = 1, zeroIsUnlimited = false,
    )
    val PLUGIN_QUEUE_LIMIT = LimitDef(
        "limits.plugins.queueLimit", 256, "calls",
        "BOARD-STALL-2: calls that may wait for a free plugin worker; a call beyond it is refused at once as busy. Read at plugin load.",
        min = 1, zeroIsUnlimited = false,
    )
    val PLUGIN_ROUTE_RATE = LimitDef(
        "limits.plugins.routeRequestsPerMinute", 600, "requests",
        "HTTP requests a minute one daemon plugin's routes answer before 429 (daemon.limits.routeRequestsPerMinute overrides it).",
    )
    val PLUGIN_START_TIMEOUT_MS = LimitDef(
        "limits.plugins.startTimeoutMs", 30_000, "ms",
        "How long a daemon plugin's start (and each policy-change callback) may run before the plugin is disabled.",
        min = 1, zeroIsUnlimited = false,
    )
    val PLUGIN_AUTO_RELOAD_ATTEMPTS = LimitDef(
        "limits.plugins.autoReload.attempts", 3, "reloads",
        "SCRIPTING-NCDFE-1: how many times per daemon run a daemon plugin that was marked failed (a call threw, ran away) is reloaded by itself, after limits.plugins.autoReload.delayMs; 0 = off (a failed plugin stays down until a restart or a manual reload).",
        min = 0, zeroIsUnlimited = false,
    )
    val PLUGIN_AUTO_RELOAD_DELAY_MS = LimitDef(
        "limits.plugins.autoReload.delayMs", 30_000, "ms",
        "SCRIPTING-NCDFE-1: how long after a daemon plugin is marked failed its automatic reload runs (see limits.plugins.autoReload.attempts).",
        min = 0, zeroIsUnlimited = false,
    )
    val PLUGIN_STOP_TIMEOUT_MS = LimitDef(
        "limits.plugins.stopTimeoutMs", 5_000, "ms",
        "How long the daemon waits for a daemon plugin's stop when it closes.",
        min = 1, zeroIsUnlimited = false,
    )
    val PLUGIN_UNLOAD_CHECK_MS = LimitDef(
        "limits.plugins.unloadCheckMs", 5_000, "ms",
        "PLUGIN-HOTSWAP-1: after a live unload or reload of a daemon half, how long the daemon waits for the old class loader to be garbage collected before it reports that the plugin did not unload cleanly (still in memory until the next daemon start). 0 = no check.",
        min = 0, zeroIsUnlimited = false,
    )

    val PLUGIN_CATALOG_STALE_MS = LimitDef(
        "limits.plugins.catalogStaleMs", 3_600_000, "ms",
        "PLUGIN-HOTSWAP-2: how old a plugin source's cached catalog may be before opening Settings > Plugins refreshes it (default one hour). 0 = refresh on every open.",
        min = 0, zeroIsUnlimited = false,
    )

    val PLUGIN_REFRESH_TIMEOUT_MS = LimitDef(
        "limits.plugins.refreshTimeoutMs", 20_000, "ms",
        "BUG PLUGIN-SOURCE-REFRESH-FREEZE-1: the most Settings > Plugins > Sources > Refresh waits for all sources together (they are fetched in parallel); a source still silent then is reported as timed out and keeps its cached copy.",
        min = 1_000, zeroIsUnlimited = false,
    )

    /** BUG MODEL-CATALOG-1: the `models` answer is cached per harness, so a slow harness CLI never empties the catalog. */
    val MODELS_CATALOG_TTL_MS = LimitDef(
        "limits.models.catalogTtlMs", 300_000, "ms",
        "MODEL-CATALOG-1: how old a harness's model list may be before the `models` answer serves it anyway and refreshes it in the background (the answer then says the list is being refreshed). A listing can take tens of seconds (a cold CLI, a first-run download), and every client asks for the list as it connects; 0 = refresh on every request.",
        min = 0, zeroIsUnlimited = false,
    )
    val MODELS_CATALOG_WAIT_MS = LimitDef(
        "limits.models.catalogWaitMs", 4_000, "ms",
        "MODEL-CATALOG-1: how long ONE `models` request waits for a harness that has never listed its models before it answers that the harness is still listing (and how to pin a model id by hand), while the listing keeps running for the next request. Raise it on a host whose harness CLIs are slow to start; 0 = answer at once, never wait.",
        min = 0, zeroIsUnlimited = false,
    )
    /** MODEL-CATALOG-1 / CLI-TIMEOUT-1: how long one CLI request waits for its answer. */
    val CLI_ANSWER_TIMEOUT_MS = LimitDef(
        "limits.cli.answerTimeoutMs", 20_000, "ms",
        "MODEL-CATALOG-1: how long one `infantry` command waits for the daemon's answer to one request before it reports the daemon unreachable or a frame missing; raise it on a slow host. The older INFANTRY_CLI_ANSWER_TIMEOUT_MS still overrides it.",
        min = 1, zeroIsUnlimited = false,
        legacyEnv = listOf("INFANTRY_CLI_ANSWER_TIMEOUT_MS", "AGENTHQ_CLI_ANSWER_TIMEOUT_MS"),
    )

    /** TOKEN-1: default sizes of the MCP/CLI tool answers (an explicit `limit` argument wins); 0 = no cap. */
    val TOOLS_INBOX_LIMIT = LimitDef(
        "limits.tools.inboxLimit", 20, "messages",
        "How many inbox items infantry_inbox / `infantry inbox` list by default (newest unread first); the `limit` argument overrides it.",
        max = 10_000,
    )
    val TOOLS_LIST_LIMIT = LimitDef(
        "limits.tools.listLimit", 50, "rows",
        "How many rows infantry_list_sessions, infantry_ticket_files and infantry_usage list by default; the `limit` argument overrides it.",
        max = 10_000,
    )
    val TOOLS_SESSION_TAIL_LINES = LimitDef(
        "limits.tools.sessionTailLines", 5, "lines",
        "How many transcript lines infantry_get_session returns by default; the `tail` argument overrides it.",
        max = 10_000,
    )
    val TOOLS_BOARD_QUEUE_SHOWN = LimitDef(
        "limits.tools.boardQueueShown", 10, "lines",
        "How many backlog and todo lines infantry_board / `infantry board` print before folding the rest into a count (columns / limit override it).",
        max = 10_000,
    )
    val TOOLS_BOARD_DONE_SHOWN = LimitDef(
        "limits.tools.boardDoneShown", 5, "lines",
        "How many of the newest done tickets infantry_board / `infantry board` print; older ones fold into a count.",
        max = 10_000,
    )

    val TICKET_GET_DEFAULT_DETAIL = LimitDef(
        "limits.ticket.getDefaultDetail", 0, "level",
        "What infantry_ticket_get returns when the call gives no `detail`: 0 = summary (id, title, status, role, model, branch, epic, open comment count, the description's first chars), 1 = full (every field and the whole description; costs ~2-4K chars per ticket).",
        max = 1, zeroMeans = "summary",
    )

    val TICKET_SUMMARY_DESCRIPTION_CHARS = LimitDef(
        "limits.ticket.summaryDescriptionChars", 300, "chars",
        "How much of the description a summary-detail infantry_ticket_get shows (the rest is cut with a marker); more costs that many chars per ticket read.",
        max = 1_000_000,
    )

    val SKILLS_INLINE_MAX_CHARS = LimitDef(
        "limits.skills.inlineMaxChars", 1500, "chars",
        "A layered skill (global/project/role) whose body is at most this long is written into the first-turn brief; a longer one appears as a description line with the command that shows it.",
        max = 1_000_000, zeroMeans = "never inline",
    )

    val MCP_MAX_RESULT_CHARS = LimitDef(
        "limits.mcp.maxResultChars", 8000, "chars",
        "The most characters one MCP tool result may carry to an agent; a longer one is cut with a note saying how to narrow the call.",
        max = 10_000_000, zeroMeans = "no cap",
    )

    val MCP_DEFAULT_DETAIL = LimitDef(
        "limits.mcp.defaultDetail", 0, "level",
        "How much an MCP tool sends back when the call gives no `detail`: 0 = each tool's own default (acks for writes, compact lists), 1 = ack, 2 = summary, 3 = full (adds verbose to tools that have it).",
        max = 3, zeroMeans = "each tool's own default",
    )

    val MCP_BODY_CHARS = LimitDef(
        "limits.mcp.bodyChars", 0, "chars",
        "Cut a mail body read through infantry_read to this many characters (a role's responseBodyChars sets it per session); the call's bodyChars wins.",
        max = 10_000_000, zeroMeans = "the whole body",
    )

    /** USAGE-REGRESS-1: a mail wake re-reads the whole session context, so mail for a session is held this long to ride in ONE turn. */
    val MAIL_WAKE_DEBOUNCE_SECONDS = LimitDef(
        "limits.mail.wakeDebounceSeconds", 20, "seconds",
        "Recommended 20. How long mail for a resting session waits before it wakes the session, so mail arriving within the window becomes ONE turn (every wake re-sends the whole context). 0 = wake at once.",
        max = 3600, zeroMeans = "wake at once",
    )
    /** USAGE-REGRESS-1: how many further queued messages ride in the same wake turn. */
    val MAIL_COALESCE_MAX = LimitDef(
        "limits.mail.coalesceMax", 10, "messages",
        "Recommended 10. How many further queued messages are folded into the one turn that wakes a session. 0 = one wake per message.",
        max = 100, zeroMeans = "one wake per message",
    )

    /** SCRIPT-VIS-1: how many script run records the Scripting plugin keeps per project (newest win). */
    val SCRIPT_RUN_LOG_MAX = LimitDef(
        "limits.scripting.runLogMax", 200, "runs",
        "Recommended 200. How many script run records (time, script, trigger, actions, log, outcome) are kept per project; the oldest are dropped. 0 = keep none.",
        max = 100_000, zeroIsUnlimited = false,
    )
    /** MAIL-LIMITS-1: the mailbox sender rate limit (was hard-coded in MailLimits). Automation mail (system, trigger:, script hooks) is exempt. */
    val MAIL_PER_SENDER_PER_WINDOW = LimitDef(
        "limits.mail.perSenderPerWindow", 12, "messages",
        "How many messages one sender may send in the last limits.mail.windowMinutes; a send past it is refused (not queued) with this setting named. Automation mail (from system or a trigger, or sent by a script hook) does not count and is never refused by it. 0 = no sender limit.",
        max = 100_000, zeroMeans = "no sender limit",
    )
    /** MAIL-LIMITS-1: the sliding window of [MAIL_PER_SENDER_PER_WINDOW]. */
    val MAIL_WINDOW_MINUTES = LimitDef(
        "limits.mail.windowMinutes", 10, "minutes",
        "The sliding window of limits.mail.perSenderPerWindow: sends older than this many minutes no longer count.",
        min = 1, max = 1440, zeroIsUnlimited = false,
    )
    /** MAIL-LIMITS-1: the per-conversation message ceiling (was hard-coded in MailLimits). */
    val MAIL_MAX_PER_THREAD = LimitDef(
        "limits.mail.maxPerThread", 60, "messages",
        "How many messages one conversation thread may hold; a reply past it is refused and the owner is told to step in (a new message on a full agent pair starts a fresh thread).",
        min = 1, max = 100_000, zeroIsUnlimited = false,
    )
    /** MAIL-TRUNC-1: per-body cap in mail.deliveryMode=truncate. */
    val MAIL_INJECT_MAX_CHARS = LimitDef(
        "limits.mail.injectMaxChars", 2000, "chars",
        "Used by mail.deliveryMode=truncate: each injected mail body is cut to this many characters, ending with '... [N more chars: infantry read <id>]'. The full body stays in the mailbox.",
        max = 1_000_000,
    )
    /** MAIL-TRUNC-1: whole-turn cap in mail.deliveryMode=truncate. */
    val MAIL_INJECT_TOTAL_CHARS = LimitDef(
        "limits.mail.injectTotalChars", 6000, "chars",
        "Used by mail.deliveryMode=truncate: the whole folded mail turn is capped at this many characters; mail past it is listed as NEW MAIL lines (id, sender, subject).",
        max = 10_000_000,
    )

    /** USAGE-OUT-2: context cap for long-lived woken sessions. */
    val SESSION_CONTEXT_CAP_TOKENS = LimitDef(
        "limits.session.contextCapTokens", 100_000, "tokens",
        "Recommended 100000 (a fresh worker turn is about 47K; PM/VC wakes measured 145-152K each). When a resting session whose last request context passed this is woken by mail, it is restarted in place (same id, role, model, worktree) with a short handoff, and the mail rides in that fresh first turn. Per-role override: setting limits.session.contextCapTokens.<role> (or .<sessionId>). 0 = off.",
        max = 10_000_000, zeroMeans = "off",
    )

    /** CONTEXT-DEFAULT-1: the context limit (auto-compact window) a ticket session gets when neither the ticket nor its weight sets one. */
    val SESSION_DEFAULT_CONTEXT_TOKENS = LimitDef(
        "limits.session.defaultContextTokens", 500_000, "tokens",
        "Default context size of a session started for a ticket that pins no context limit and whose weight (tickets.weightContext) sets none. Every harness gets it through the generic context-limit field (infantry_list_harnesses says supported / best_effort / unsupported). A larger window means fewer compactions but each turn re-reads more tokens; a smaller one compacts earlier and pays a summary plus a cache rebuild each time. 0 = off (the harness's own window).",
        max = 10_000_000, zeroMeans = "off",
    )

    /** CONTEXT-DEFAULT-1: ceiling on the size at which any session's context is compacted. */
    val SESSION_COMPACT_MAX_TOKENS = LimitDef(
        "limits.session.compactMaxTokens", 0, "tokens",
        "Maximum context size at which a session compacts: whatever limit applies (ticket, weight or limits.session.defaultContextTokens) is lowered to this when it is larger. Default 0 (off, cheapest: no extra compactions). A lower value caps per-turn cost on long sessions but each compaction costs a full-context summary plus a cache rebuild. Honoured by every harness through the generic context-limit field.",
        max = 10_000_000, zeroMeans = "off",
    )

    /** RUNNER-POOL-5 (docs/RUNNER-POOL.md section 7): how long a `runner pair` code stays valid. */
    val RUNNERS_PAIR_CODE_MIN = LimitDef(
        "limits.runners.pairCodeMin", 10, "minutes",
        "How long the one-time code `infantry runner pair` prints stays valid; it is single use either way. Shorter = safer.",
        min = 1, max = 1440, zeroIsUnlimited = false,
    )

    /** RUNNER-POOL-5: pairing attempts (right or wrong code) a runner accepts per rolling hour; past it the live code is burned. */
    val RUNNERS_PAIR_ATTEMPTS_PER_HOUR = LimitDef(
        "limits.runners.pairAttemptsPerHour", 5, "attempts",
        "How many pairing attempts a runner accepts in one rolling hour; past it every attempt is refused and the live code is burned.",
        min = 1, zeroIsUnlimited = false,
    )

    /** RUNNER-POOL-5: age after which the orchestrator rekeys a runner's pair credential in-band. */
    val RUNNERS_CREDENTIAL_ROTATE_DAYS = LimitDef(
        "limits.runners.credentialRotateDays", 30, "days",
        "Age after which the orchestrator rekeys a paired runner's credential over the link (runners.credentialRotateDays); the old one stops working at once.",
        min = 1, max = 3650, zeroIsUnlimited = false,
    )

    /** RUNNER-UP-1: how long `infantry runner up` waits for the daemon it started to answer. */
    val RUNNERS_UP_START_SEC = LimitDef(
        "limits.runners.upStartSec", 60, "seconds",
        "How long `infantry runner up` waits for the runner daemon it starts to answer before it gives up.",
        min = 1, zeroIsUnlimited = false,
    )

    /** RUNNER-POOL-5: the TCP port a runner's link listener (wss) binds; 0 = any free port. */
    val RUNNERS_LINK_PORT = LimitDef(
        "limits.runners.linkPort", 8790, "port",
        "The port a runner serves its orchestrator link on (wss, or plain ws only with runners.allowPlainTailnet); 0 = any free port.",
        min = 0, max = 65535, zeroIsUnlimited = false,
    )

    /** RUNNER-LINK-1: how many host candidates `runner pair` puts in its pairing link (flags, then runners.advertiseHosts, else detected). */
    val RUNNERS_ADVERTISE_HOSTS_MAX = LimitDef(
        "limits.runners.advertiseHostsMax", 4, "hosts",
        "How many host candidates the `infantry runner pair` link carries (--host flags, then runners.advertiseHosts, else detected addresses); the client tries them in order. More = longer link.",
        min = 1, max = 32, zeroIsUnlimited = false,
    )

    /** RUNNER-GRANT-AT-PAIR-1: how many project ids one pairing may grant (owner decision D24: at most one project, once). */
    val RUNNERS_GRANT_PROJECTS_MAX = LimitDef(
        "limits.runners.grantProjectsMax", 1, "projects",
        "How many project ids a single pairing may grant when the runner's code was issued with --grant-on-pair; more than this in the request is refused. Kept at 1: pairing is one project, granted once (owner decision D24).",
        min = 1, max = 16, zeroIsUnlimited = false,
    )

    /** RUNNER-POOL-5: how many audit lines `infantry runner audit` prints without `--limit`. */
    val RUNNERS_AUDIT_SHOWN = LimitDef(
        "limits.runners.auditShown", 50, "lines",
        "How many of the newest runner audit lines `infantry runner audit` prints when no --limit is given.",
        min = 1, zeroIsUnlimited = false,
    )

    /** RUNNER-POOL-5: how often the daemon looks after the runner link (listener up when paired, down after a detach, due rekeys). */
    val RUNNERS_LINK_CHECK_SECONDS = LimitDef(
        "limits.runners.linkCheckSeconds", 60, "seconds",
        "How often the daemon looks after the runner link: serves it once this runner is paired, stops after a detach, rekeys due credentials.",
        min = 1, zeroIsUnlimited = false,
    )

    /**
     * RUNNER-POOL-8 (was RUNNER-POOL-6's `limits.runners.eventsKept`): the runner's event outbox bound. An event is kept until the
     * orchestrator acknowledges it; past the bound the oldest goes (the orchestrator logs the gap).
     */
    val RUNNERS_OUTBOX_MAX = LimitDef(
        "limits.runners.outboxMax", 500, "events",
        "How many session events (status, finished) of orchestrator-started sessions a runner keeps until its orchestrator acknowledges them; past it the oldest go first (a gap the orchestrator logs). Fewer = less memory.",
        min = 1, zeroIsUnlimited = false,
    )

    /** RUNNER-POOL-8: how long a runner with sessions on it may stay silent before the owner gets an attention item (`runners.onLost`). */
    val RUNNERS_LOST_AFTER_MIN = LimitDef(
        "limits.runners.lostAfterMin", 30, "minutes",
        "How long a runner that runs this orchestrator's workers may stay silent before it counts as lost: an attention item is raised, and with runners.onLost = restart-elsewhere its tickets start again on another runner.",
        min = 1, zeroIsUnlimited = false,
    )

    /** RUNNER-POOL-8: how often a runner looks for new worker commits to push (only sessions started with `runners.pushOnCommit`). */
    val RUNNERS_COMMIT_CHECK_SEC = LimitDef(
        "limits.runners.commitCheckSec", 30, "seconds",
        "How often a runner checks its orchestrator-started worktrees for new commits to push when runners.pushOnCommit is on. Longer = cheaper.",
        min = 1, zeroIsUnlimited = false,
    )

    /** RUNNER-POOL-5: how long one orchestrator -> runner link call (pair, rekey, detach) waits for its answer. */
    val RUNNERS_LINK_CALL_SECONDS = LimitDef(
        "limits.runners.linkCallSeconds", 20, "seconds",
        "How long one orchestrator-to-runner link call (pair, rekey, detach) waits for the runner's answer before it fails.",
        min = 1, zeroIsUnlimited = false,
    )

    /**
     * RUNNER-CHECK-1: how long `infantry runner check` lets the runner's `git ls-remote` of the project's remote run before the row
     * fails. Bounded so a firewall that drops packets shows as a git FAIL, not a hung check.
     */
    val RUNNERS_CHECK_GIT_SEC = LimitDef(
        "limits.runners.checkGitSec", 15, "seconds",
        "How long the runner's health check (`infantry runner check`) waits for `git ls-remote` of the project's git remote before that row fails. Long enough for a slow server, short enough that a blocked port shows as a failure.",
        min = 1, max = 300, zeroIsUnlimited = false,
    )

    /** RUNNER-CHECK-1: the free disk the runner's clone root must have for the health check's disk row to pass. */
    val RUNNERS_CHECK_MIN_FREE_MB = LimitDef(
        "limits.runners.checkMinFreeMb", 1_024, "MB",
        "How much free disk space the runner's clone root (`runners.cloneRoot`) must have for the health check's disk row to pass; a full server fails every start late. 0 = any free space passes.",
        min = 0, zeroIsUnlimited = false,
    )

    /**
     * RUNNER-ADD-LINK-1: how long `infantry runner add '<link>'` waits on ONE host of the link before it tries the next. The link
     * carries several hosts (tailnet first, then LAN); a host that does not answer must not hold up the next one for long.
     */
    /** RUNNER-PAIR-1: how long "Connect a runner" waits for the runner-side command to print its pairing link (and then to exit). */
    val RUNNERS_CONNECT_SEC = LimitDef(
        "limits.runners.connectSec", 60, "seconds",
        "How long Connect a runner (Settings > Runners, `infantry runner connect`) waits for the command that reaches the runner machine to print its pairing link, and afterwards for that command to end.",
        min = 1, max = 600, zeroIsUnlimited = false,
    )

    val RUNNERS_ADD_HOST_TIMEOUT_SEC = LimitDef(
        "limits.runners.addHostTimeoutSec", 5, "seconds",
        "How long adding a runner from a pairing link waits on one host of the link before it tries the next host (each host's server name is given the same chance).",
        min = 1, max = 120, zeroIsUnlimited = false,
    )

    /** RUNNER-POOL-7: how long one forward pull of the orchestrator waits on the runner for frames its workers sent. */
    val RUNNERS_FORWARD_WAIT_SEC = LimitDef(
        "limits.runners.forwardWaitSec", 10, "seconds",
        "How long the orchestrator's pull of a runner's forwarded agent frames (board, mail, knowledge calls of remote workers) waits for one; capped below limits.runners.linkCallSeconds. Pulls run only while the runner has a session of this orchestrator. Longer = fewer link calls when idle.",
        min = 1, zeroIsUnlimited = false,
    )

    /** RUNNER-POOL-7: how long a remote worker's forwarded frame waits for its orchestrator's answer. */
    val RUNNERS_FORWARD_TIMEOUT_SEC = LimitDef(
        "limits.runners.forwardTimeoutSec", 300, "seconds",
        "How long a runner holds an agent frame it forwards to its orchestrator (a remote worker's ticket, comment, mail or script call) before the call fails as unavailable.",
        min = 1, zeroIsUnlimited = false,
    )

    /** RUNNER-POOL-7: how many forwarded frames a runner keeps waiting at once. */
    val RUNNERS_FORWARD_PENDING_MAX = LimitDef(
        "limits.runners.forwardPendingMax", 64, "frames",
        "How many agent frames a runner holds for its orchestrator at once; one more is refused at once. Fewer = less memory.",
        min = 1, zeroIsUnlimited = false,
    )

    /**
     * RUNNER-TEST-1: how long `infantry runner test` waits for the runner's throwaway turn to answer before it stops the session and
     * reports what the transcript had. A real model harness needs a few seconds; a runner that is signed in but too slow to answer
     * must not hold the owner's check forever.
     */
    val RUNNERS_TEST_TURN_SEC = LimitDef(
        "limits.runners.testTurnSec", 90, "seconds",
        "How long `infantry runner test` waits for the test turn's answer from the runner's harness before it stops the session and reports the transcript it has.",
        min = 1, max = 900, zeroIsUnlimited = false,
    )

    /** RUNNER-TEST-1: how often the test turn's transcript is read while it waits for the answer. */
    val RUNNERS_TEST_POLL_MS = LimitDef(
        "limits.runners.testPollMs", 1_000, "ms",
        "How often `infantry runner test` reads the test session's transcript over the link while it waits for the answer. Shorter = the reply shows sooner, at one link call each poll.",
        min = 100, max = 30_000, zeroIsUnlimited = false,
    )

    /** RUNNER-TEST-1: how much of the test turn's answer (`infantry runner test`, the Runners card) is kept and shown. */
    val RUNNERS_TEST_REPLY_CHARS = LimitDef(
        "limits.runners.testReplyChars", 500, "characters",
        "How many characters of the test turn's answer `infantry runner test` and the Runners card keep and show (the rest is cut); the same budget bounds the transcript lines of a test that got no answer.",
        min = 40, max = 20_000, zeroIsUnlimited = false,
    )

    // CORE-PROGRESS-DOWNLOAD-1 (docs/wiki/Progress.md): the core downloader and the core progress system.
    val DOWNLOAD_RETRIES = LimitDef(
        "limits.downloads.retries", 3, "count",
        "CORE-PROGRESS-DOWNLOAD-1: how many times the core downloader tries a failed download again (offline, a timeout, a 5xx, a cut connection), resuming where the server allows. 0 = one try only.",
        min = 0, max = 20, zeroIsUnlimited = false,
    )
    val DOWNLOAD_BACKOFF_MS = LimitDef(
        "limits.downloads.backoffMs", 1_000, "ms",
        "CORE-PROGRESS-DOWNLOAD-1: the wait before the first retry of a download; each next retry waits twice as long (a server's Retry-After wins when longer).",
        min = 0, zeroIsUnlimited = false,
    )
    val DOWNLOAD_CONNECT_TIMEOUT_SECONDS = LimitDef(
        "limits.downloads.connectTimeoutSeconds", 15, "seconds",
        "CORE-PROGRESS-DOWNLOAD-1: how long the core downloader waits to connect to a server.",
        min = 1, zeroIsUnlimited = false,
    )
    val DOWNLOAD_RESPONSE_TIMEOUT_SECONDS = LimitDef(
        "limits.downloads.responseTimeoutSeconds", 60, "seconds",
        "CORE-PROGRESS-DOWNLOAD-1: how long the core downloader waits for a server to start answering a request.",
        min = 1, zeroIsUnlimited = false,
    )
    val DOWNLOAD_STALL_SECONDS = LimitDef(
        "limits.downloads.stallSeconds", 60, "seconds",
        "CORE-PROGRESS-DOWNLOAD-1: a download that receives no byte for this long is broken off and retried (resuming); 0 = never.",
        min = 0, zeroIsUnlimited = true,
    )
    val DOWNLOAD_MAX_MB = LimitDef(
        "limits.downloads.maxMb", 2_048, "MB",
        "CORE-PROGRESS-DOWNLOAD-1: the largest file the core downloader accepts when the caller names no cap of its own. 0 = no cap.",
    )
    val DOWNLOAD_REDIRECTS = LimitDef(
        "limits.downloads.redirects", 5, "count",
        "CORE-PROGRESS-DOWNLOAD-1: how many HTTP redirects one download follows (a secret header is dropped once a redirect leaves the first host).",
        min = 0, max = 20, zeroIsUnlimited = false,
    )
    val DOWNLOAD_MOVE_RETRIES = LimitDef(
        "limits.downloads.moveRetries", 5, "count",
        "CORE-PROGRESS-DOWNLOAD-1: how many times the downloader tries again to move a finished download into place while another program holds the target file (Windows file locks), a backoff apart.",
        min = 0, max = 50, zeroIsUnlimited = false,
    )
    val PROGRESS_KEEP_FINISHED = LimitDef(
        "limits.progress.keepFinished", 20, "tasks",
        "CORE-PROGRESS-DOWNLOAD-1: how many finished progress tasks (done, failed, cancelled) the running-tasks list keeps. 0 = keep none.",
        min = 0, max = 500, zeroIsUnlimited = false,
    )
    val PROGRESS_MIN_INTERVAL_MS = LimitDef(
        "limits.progress.minIntervalMs", 250, "ms",
        "CORE-PROGRESS-DOWNLOAD-1: the shortest gap between two amount-only updates of one progress task sent to the presenters and clients (a step, state or error change is always sent).",
        min = 0, zeroIsUnlimited = false,
    )

    val ALL: List<LimitDef> = listOf(
        SESSION_CONTEXT_CAP_TOKENS, SESSION_DEFAULT_CONTEXT_TOKENS, SESSION_COMPACT_MAX_TOKENS,
        MAIL_INJECT_MAX_CHARS, MAIL_INJECT_TOTAL_CHARS,
        MAIL_PER_SENDER_PER_WINDOW, MAIL_WINDOW_MINUTES, MAIL_MAX_PER_THREAD,
        MCP_BODY_CHARS,
        MCP_DEFAULT_DETAIL,
        TICKET_GET_DEFAULT_DETAIL, TICKET_SUMMARY_DESCRIPTION_CHARS,
        SKILLS_INLINE_MAX_CHARS,
        MCP_MAX_RESULT_CHARS,
        TOOLS_INBOX_LIMIT, TOOLS_LIST_LIMIT, TOOLS_SESSION_TAIL_LINES, TOOLS_BOARD_QUEUE_SHOWN, TOOLS_BOARD_DONE_SHOWN,
        MAX_PER_POD, MAX_SESSIONS, MAX_DEPTH, MCP_MAX_SPAWNS, LANE_DEFAULT, PACING_CEILING,
        USAGE_STALE_MAX_HOURS, USAGE_BACKOFF_MAX_MINUTES, USAGE_MANUAL_REFRESH_FLOOR_SECONDS,
        BUILD_MAX_WORKERS, BUILD_MAX_PARALLEL_FORKS, CPU_ALERT_PERCENT, CPU_ALERT_MINUTES,
        TICKET_DEADLINE_MINUTES, UNATTENDED_DEADLINE_MINUTES, STALL_MINUTES, KILL_RETRY_MINUTES, SILENT_MINUTES, PROGRESS_STALE_MINUTES, WAITING_DEFAULT_MINUTES, WAITING_GRACE_MINUTES, WAITING_CLEAR_ON_OUTPUT, WAITING_OUTPUT_GRACE_SECONDS, WAITING_MAX_MINUTES, WAITING_REASON_CHARS, IDLE_NUDGE_MINUTES, MAIL_WAKE_MAX_SECONDS, IDLE_NOTICE_MINUTES, IDLE_COMMENT, FINISH_ONCE, UNBRIEFED_GRACE_MINUTES,
        UNASSIGN_WAIT_SECONDS, HANDOFF_COMMITS, HANDOFF_CHARS, BOARD_HAS_WORK_TTL_MS, AGENT_QUEUE_MAX, RUNNERS_HEARTBEAT_SEC, RUNNERS_LOST_AFTER_SEC, RUNNER_MAX_WORKERS, WORKERS_MAX_PARALLEL, CHAT_QUEUE_MAX, JOBS_REMEMBERED, JOB_LOG_MAX_READ_BYTES, CHAT_SEND_ACK_SECONDS, CHAT_SENT_IDS_KEPT, CHAT_HISTORY_FRAMES,
        AUTO_DONE_MIN_OUTPUTS, PROCESS_WATCH_INTERVAL_SECONDS, PROCESS_STOP_GRACE_SECONDS, PROCESS_STUCK_MINUTES, RUN_SCROLLBACK_BYTES, RUN_HISTORY,
        RUN_INPUT_BYTES_PER_SECOND, RUN_INPUT_MAX_FRAME_BYTES, RUN_INPUT_QUEUE_BYTES, RUN_MAX_COLS, RUN_MAX_ROWS,
        QUESTION_TTL_MINUTES, BG_NOTIFY_TAIL_LINES, BG_NOTIFY_TIMEOUT_MINUTES, BG_NOTIFY_PATTERN_GRACE_SECONDS,
        MCP_HEAL_ATTEMPTS, MCP_HEAL_POLL_SECONDS, MCP_HEAL_DELAY_SECONDS, MCP_HEAL_MAX_DELAY_SECONDS, WATCH_QUIET_MINUTES, WATCH_HEARTBEAT_CAP_MINUTES, WATCH_STALL_MINUTES, WATCH_FOREGROUND_STALL_MINUTES, WATCH_EXEMPT_WHEN_TICKET_DONE, WATCH_NOTICE_DEDUPE, NOTICE_MAIL_PLUGIN_HEALTH, NOTICE_MAIL_BG_STATUS, BG_NOTICE_FOLD_INTO_TURN, BG_NOTICE_SKIP_IF_RESULT_READ, WATCH_HANDLED_KEEP_MINUTES, WATCH_TREE_JOB_SECONDS, WATCH_ENABLED, WATCH_FORCED, WATCH_BG_NATIVE, WATCH_HANG_GRACE_SECONDS, WATCH_TREE_WAIT_MINUTES,
        WATCH_NOTICE_LINE_CHARS, WATCH_LOG_MAX_MB, WATCH_LOG_TOTAL_MB, WATCH_LOG_KEEP_JOBS, WATCH_LOG_KEEP_DAYS, WATCH_MIN_FREE_MB, WATCH_FILE_IDLE_SECONDS,
        WATCH_READ_MAX_KB, WATCH_TAIL_READ_KB, WATCH_SPACE_CHECK_SECONDS,
        TICKET_FILES_MAX_LINES, TICKET_FILES_RELATED_TOP,
        RELATED_BRIEF_TICKETS, RELATED_BRIEF_FILES, RELATED_BRIEF_CHARS, RELATED_BLOCK_TICKETS, RELATED_BLOCK_FILES, RELATED_BLOCK_CHARS,
        PLUGIN_CALL_TIMEOUT_MS, PLUGIN_HANG_CEILING_MS, PLUGIN_GUARD_STRIKES, PLUGIN_GUARD_COOLDOWN_MS, PLUGIN_POOL_SIZE, PLUGIN_QUEUE_LIMIT, PLUGIN_TOOL_TIMEOUT_MS, PLUGIN_TOOL_MAX_TIMEOUT_MS, PLUGIN_CAPABILITY_TIMEOUT_MS, PLUGIN_ROUTE_RATE, PLUGIN_START_TIMEOUT_MS, PLUGIN_STOP_TIMEOUT_MS, PLUGIN_UNLOAD_CHECK_MS, PLUGIN_CATALOG_STALE_MS, PLUGIN_REFRESH_TIMEOUT_MS, PLUGIN_AUTO_RELOAD_ATTEMPTS, PLUGIN_AUTO_RELOAD_DELAY_MS,
        MODELS_CATALOG_TTL_MS, MODELS_CATALOG_WAIT_MS, CLI_ANSWER_TIMEOUT_MS,
        RUN_MAX_SECRETS, KEYSTORE_MAX_FIELDS, KEYSTORE_MAX_SECRETS,
        SANDBOX_MODE, USAGE_QUOTA_HIT_HOLD_MINUTES,
        HEALTH_DB_TIMEOUT_SECONDS, HEALTH_EVENT_LOG_MAX, HEALTH_HEARTBEAT_SECONDS, HEALTH_WATCHDOG_FAILURES, HEALTH_WATCHDOG_INTERVAL_SECONDS, HEALTH_WATCHDOG_TIMEOUT_SECONDS,
        MAIL_WAKE_DEBOUNCE_SECONDS, MAIL_COALESCE_MAX, WATCH_STALL_NUDGES, SCRIPT_RUN_LOG_MAX,
        RUNNERS_PAIR_CODE_MIN, RUNNERS_PAIR_ATTEMPTS_PER_HOUR, RUNNERS_CREDENTIAL_ROTATE_DAYS, RUNNERS_LINK_PORT, RUNNERS_UP_START_SEC, RUNNERS_ADVERTISE_HOSTS_MAX, RUNNERS_AUDIT_SHOWN, RUNNERS_GRANT_PROJECTS_MAX, RUNNERS_LINK_CALL_SECONDS, RUNNERS_LINK_CHECK_SECONDS,
        RUNNERS_OUTBOX_MAX, RUNNERS_LOST_AFTER_MIN, RUNNERS_COMMIT_CHECK_SEC, RUNNERS_FORWARD_WAIT_SEC, RUNNERS_FORWARD_TIMEOUT_SEC, RUNNERS_FORWARD_PENDING_MAX,
        RUNNERS_ADD_HOST_TIMEOUT_SEC, RUNNERS_CONNECT_SEC, RUNNERS_CHECK_GIT_SEC, RUNNERS_CHECK_MIN_FREE_MB,
        RUNNERS_TEST_TURN_SEC, RUNNERS_TEST_POLL_MS, RUNNERS_TEST_REPLY_CHARS,
        DOWNLOAD_RETRIES, DOWNLOAD_BACKOFF_MS, DOWNLOAD_CONNECT_TIMEOUT_SECONDS, DOWNLOAD_RESPONSE_TIMEOUT_SECONDS, DOWNLOAD_STALL_SECONDS, DOWNLOAD_MAX_MB, DOWNLOAD_REDIRECTS, DOWNLOAD_MOVE_RETRIES,
        PROGRESS_KEEP_FINISHED, PROGRESS_MIN_INTERVAL_MS,
    )

    /** A minutes limit as milliseconds; 0 on an unlimited-capable one = [Long.MAX_VALUE] (never reached). */
    fun minutesMs(value: Long, def: LimitDef): Long =
        if (def.zeroIsUnlimited && value == 0L) Long.MAX_VALUE else value * 60_000L

    private val byKey = ALL.associateBy { it.key }

    fun get(key: String): LimitDef? = byKey[key]

    /** [def] resolved from [env] alone (no stored setting): the first set name wins; a bad value is an error naming it. */
    fun fromEnv(def: LimitDef, env: (String) -> String?): Long {
        for (name in def.envNames) {
            val raw = env(name)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val v = raw.toLongOrNull()?.takeIf { def.accepts(it) }
                ?: throw IllegalArgumentException("$name must be a whole number in ${def.range()}, was '$raw'")
            return v
        }
        return def.default
    }

    /** 0 on an unlimited-capable limit is "no limit". */
    fun cap(value: Long, def: LimitDef): Int =
        if (def.zeroIsUnlimited && value == 0L) Int.MAX_VALUE else value.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}
