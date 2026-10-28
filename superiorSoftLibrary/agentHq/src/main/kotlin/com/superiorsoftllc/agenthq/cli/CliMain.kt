package com.superiorsoftllc.agenthq.cli

import com.superiorsoftllc.agenthq.session.InfantryEnv

import com.superiorsoftllc.agenthq.agents.KnowledgeLines
import com.superiorsoftllc.agenthq.daemon.AccessConfigurationException
import com.superiorsoftllc.agenthq.daemon.AccessPolicy
import com.superiorsoftllc.agenthq.daemon.AgentHqBin
import com.superiorsoftllc.agenthq.daemon.DaemonPort
import com.superiorsoftllc.agenthq.daemon.RemoteAccess
import com.superiorsoftllc.agenthq.daemon.RosterRow
import com.superiorsoftllc.agenthq.orchestration.NodeSpec
import com.superiorsoftllc.agenthq.orchestration.PodStatus
import com.superiorsoftllc.agenthq.orchestration.PodSubmitRequest
import com.superiorsoftllc.agenthq.protocol.AgentProfile
import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.protocol.EventVocabulary
import com.superiorsoftllc.agenthq.protocol.TicketWeight
import com.superiorsoftllc.agenthq.protocol.FinishedPayload
import com.superiorsoftllc.agenthq.protocol.PromptAnswer
import com.superiorsoftllc.agenthq.protocol.REQUEST_ID_CONTRACT
import com.superiorsoftllc.agenthq.protocol.PromptAsk
import com.superiorsoftllc.agenthq.protocol.QuestionAsk
import com.superiorsoftllc.agenthq.protocol.PromptOption
import com.superiorsoftllc.agenthq.prompts.QuestionSpec
import com.superiorsoftllc.agenthq.epics.Epic
import com.superiorsoftllc.agenthq.epics.EpicSave
import com.superiorsoftllc.agenthq.role.RoleIds
import com.superiorsoftllc.agenthq.mailbox.MailRefused
import com.superiorsoftllc.agenthq.mailbox.MailSendRequest
import com.superiorsoftllc.agenthq.mailbox.Message
import com.superiorsoftllc.agenthq.mailbox.ThreadSummary
import com.superiorsoftllc.agenthq.mailbox.WaitingOn
import com.superiorsoftllc.agenthq.decisions.Decision
import com.superiorsoftllc.agenthq.decisions.DecisionAdd
import com.superiorsoftllc.agenthq.decisions.DecisionGet
import com.superiorsoftllc.agenthq.decisions.DecisionList
import com.superiorsoftllc.agenthq.decisions.parseDecisionRef
import com.superiorsoftllc.agenthq.pipeline.PipelineCheckRequest
import com.superiorsoftllc.agenthq.pipeline.PipelineConfigure
import com.superiorsoftllc.agenthq.pipeline.PipelineRecord
import com.superiorsoftllc.agenthq.pipeline.PipelineShow
import com.superiorsoftllc.agenthq.pipeline.PipelineState
import com.superiorsoftllc.agenthq.issues.Issue
import com.superiorsoftllc.agenthq.issues.IssueAttempt
import com.superiorsoftllc.agenthq.issues.IssueEvent
import com.superiorsoftllc.agenthq.issues.IssueFix
import com.superiorsoftllc.agenthq.issues.IssueGet
import com.superiorsoftllc.agenthq.issues.IssueList
import com.superiorsoftllc.agenthq.issues.IssueOpen
import com.superiorsoftllc.agenthq.issues.IssuePrecheck
import com.superiorsoftllc.agenthq.issues.parseIssueRef
import com.superiorsoftllc.agenthq.memory.MemoryDelete
import com.superiorsoftllc.agenthq.memory.MemoryGet
import com.superiorsoftllc.agenthq.memory.MemoryList
import com.superiorsoftllc.agenthq.memory.MemoryPurgeRole
import com.superiorsoftllc.agenthq.memory.NOTE_SCOPES
import com.superiorsoftllc.agenthq.memory.SCOPE_PROJECT
import com.superiorsoftllc.agenthq.memory.SCOPE_ROLE
import com.superiorsoftllc.agenthq.memory.SCOPE_SESSION
import com.superiorsoftllc.agenthq.memory.MemoryIndexEntry
import com.superiorsoftllc.agenthq.memory.MemoryNote
import com.superiorsoftllc.agenthq.memory.MemorySave
import com.superiorsoftllc.agenthq.memory.MemorySearch
import com.superiorsoftllc.agenthq.memory.MemorySearchResult
import com.superiorsoftllc.agenthq.memory.MemoryStore
import com.superiorsoftllc.agenthq.protocol.RoleTraits
import com.superiorsoftllc.agenthq.protocol.RoleTraitsSave
import com.superiorsoftllc.agenthq.protocol.RosterRequest
import com.superiorsoftllc.agenthq.protocol.StatusPayload
import com.superiorsoftllc.agenthq.protocol.Ticket
import com.superiorsoftllc.agenthq.protocol.TicketLinkRequest
import com.superiorsoftllc.agenthq.protocol.TicketLinkType
import com.superiorsoftllc.agenthq.protocol.TicketMove
import com.superiorsoftllc.agenthq.protocol.TicketSave
import com.superiorsoftllc.agenthq.protocol.TicketListQuery
import com.superiorsoftllc.agenthq.protocol.TicketStatus
import com.superiorsoftllc.agenthq.protocol.Project
import com.superiorsoftllc.agenthq.protocol.ProjectBaseRequest
import com.superiorsoftllc.agenthq.protocol.SkillView
import com.superiorsoftllc.agenthq.protocol.TraitCheck
import com.superiorsoftllc.agenthq.protocol.TraitSave
import com.superiorsoftllc.agenthq.protocol.TraitView
import com.superiorsoftllc.agenthq.session.SessionRecord
import com.superiorsoftllc.agenthq.session.SessionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import com.superiorsoftllc.agenthq.cli.mcp.McpGuardrails
import com.superiorsoftllc.agenthq.cli.mcp.McpConfigException
import com.superiorsoftllc.agenthq.cli.mcp.McpLineage
import com.superiorsoftllc.agenthq.cli.mcp.McpWriteTools
import com.superiorsoftllc.agenthq.cli.mcp.McpServeConfig
import com.superiorsoftllc.agenthq.cli.mcp.McpServer
import com.superiorsoftllc.agenthq.cli.mcp.McpTools
import java.io.File
import java.io.Reader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.net.http.WebSocketHandshakeException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.system.exitProcess

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** Pod states a `watch` stops on. Anything else is still moving. */
private val TERMINAL_POD_STATES = setOf("CONVERGED", "FAILED", "CANCELLED", "INTERRUPTED")

private const val DEFAULT_HOST = "127.0.0.1"
private const val DEFAULT_PORT = 8787

/** How long `watch` waits for a new frame before re-asking the daemon about the pod. */
private const val WATCH_IDLE_MS = 500L

/** How long any single command waits for one answer before it calls the daemon unreachable. */
internal const val ANSWER_TIMEOUT_MS = 20_000L

/** CLI-TIMEOUT-1: overrides [ANSWER_TIMEOUT_MS] for tests and slow hosts; a value <= 0 or non-numeric is ignored. */
internal const val ANSWER_TIMEOUT_ENV = "AGENTHQ_CLI_ANSWER_TIMEOUT_MS"

internal fun answerTimeoutMs(getenv: (String) -> String?): Long =
    getenv(ANSWER_TIMEOUT_ENV)?.trim()?.toLongOrNull()?.takeIf { it > 0 } ?: ANSWER_TIMEOUT_MS

/** Error message when daemon is unreachable. */
private const val DAEMON_UNREACHABLE_MSG = "Infantry AI daemon not running on %s:%d — start it with `infantry daemon`"

/**
 * How long a command keeps waiting for its answer after an `error` frame showed up.
 *
 * This exists because `error` is not only an answer. The same frame is broadcast when a PROVIDER
 * reports a failure anywhere on the daemon, so a client that treats "the next error frame" as the
 * reply to its own request will, on a failing run, read the run's error as its own answer -- and
 * report "scripted failure" for a `submit` that in fact succeeded. A `pod` frame can therefore only
 * be taken as the answer, and an `error` is remembered and used only if no `pod` follows: that is
 * what tells a genuine refusal (an unknown pod id answers `error` and never `pod`) apart from a
 * bystander failure. The window is short because a real answer arrives immediately.
 */
private const val ERROR_GRACE_MS = 1_000L

/**
 * How long `logs` waits for a new line of the run it is tailing before giving up.
 *
 * Generous on purpose: a slow provider can go quiet for a long time while it thinks, and a log
 * command that gives up mid-run is worse than one that waits.
 */
private const val LOGS_IDLE_MS = 30_000L

/**
 * Waits before each reconnect attempt after a dropped connection. The sum (about 16 seconds) is how
 * long a command keeps trying before it gives up. A refused token is never retried.
 */
private val RECONNECT_BACKOFF_MS = listOf(250L, 500L, 1_000L, 2_000L, 4_000L, 8_000L)

/**
 * Who sent a message typed at a terminal: the mailbox's non-session handle (see `Message.from`, "a
 * non-session handle such as `human` / `system`"). Used only when no session is named by
 * [AccessPolicy.SESSION_ID_ENV]; a spawned agent always has one.
 */
private const val HUMAN_SENDER = "user"

/** A mailbox id is a UUID; anything shorter given to `send --reply-to` is a prefix (MAIL-FOOTER-1). */
private const val FULL_MESSAGE_ID_LENGTH = 36

/**
 * What `send` says when the connection drops mid-request. A second copy would deliver the same words
 * twice and spend the thread's ceiling twice, so the command refuses to decide for the user.
 */
private const val MAIL_RESEND_RISK = "Do not re-send unless a duplicate message is acceptable"

/** Set to `1` to get plain-ASCII output (non-ASCII becomes `?`) instead of UTF-8. */
internal const val ASCII_OUTPUT_ENV = "AGENTHQ_CLI_ASCII"

/**
 * Wraps a raw stream so text is written as UTF-8 (or ASCII when [ASCII_OUTPUT_ENV] is `1`), not the
 * platform default charset (Cp1252/Cp437 on Windows), which turns the middle dot and em dash into `?`.
 */
internal fun encodedStream(raw: java.io.OutputStream, getenv: (String) -> String?): java.io.PrintStream {
    val charset = if (getenv(ASCII_OUTPUT_ENV)?.trim() == "1") Charsets.US_ASCII else Charsets.UTF_8
    return java.io.PrintStream(raw, true, charset)
}

fun main(args: Array<String>) {
    val out = encodedStream(java.io.FileOutputStream(java.io.FileDescriptor.out), InfantryEnv::get)
    val err = encodedStream(java.io.FileOutputStream(java.io.FileDescriptor.err), InfantryEnv::get)
    val code = Cli.run(args, out, err, input = { System.`in`.bufferedReader(Charsets.UTF_8) })
    out.flush()
    err.flush()
    exitProcess(code)
}

/**
 * The Infantry AI CLI: the binary you type.
 *
 * ```
 * agenthq submit "<goal>" [--project <id>]   create a pod, print the pod id
 * agenthq watch <podId>       stream a pod's events until it converges, fails or is cancelled
 * agenthq pods                list what is running
 * agenthq runs <podId>        list a pod's runs with their outcome
 * agenthq logs <sessionId>    a run's stdout / stderr, as it is produced
 * agenthq cancel <podId>      cancel a pod gracefully
 * agenthq session rm <id> [--force]   remove a session from the session list; --force kills a running one first
 * agenthq kill <id> [--force]          kill a session (pm / owner: any in their project; others: their own spawns)
 * agenthq epics --project <id> list that project's epics
 * agenthq stories               the story board (--project / --status to narrow)
 * agenthq send <to> "message"   message an agent (by id or role); --reply-to answers one
 * agenthq inbox [--unread]    the messages addressed to me
 * agenthq read <msgId>        one message, printed and marked read (8-char prefix is enough)
 * agenthq ticket show <id>    one ticket in full: title, status, agent, links and the whole description
 * agenthq ticket create --title "..." [--desc "..." | --desc-file <path> | --desc -] [--project <id>] [--role <id>] [--provider <id>] [--model <id>] [--image <file>]... [--status todo|backlog]   a new todo (or backlog) ticket; prints its id
 * agenthq ticket update <id> [--title ..] [--desc ..] [--role <id>] [--provider <id>] [--model <id>] [--image <file>]... [--remove-image <path>]...   edit one ticket
 * agenthq ticket move <id> <status>            move one ticket to a column (backlog, todo, in_progress, pending, done)
 * agenthq ticket move <id> --before <id>|--after <id>|--top|--bottom|--position <n>   reorder inside its column (order = priority)
 * agenthq ticket start <id>                    start ticket work (never with a spawn): its role/provider/model, its own branch
 * agenthq harnesses                            the harnesses (provider ids) a ticket can pin, and each one's models
 * agenthq harness-status [--check]            which harness CLIs (claude, opencode, pi) are installed / missing / outdated (--check asks npm for the latest)
 * agenthq harness-install [id...] [--update]  npm-install the missing harness CLIs into the app prefix (--update refreshes outdated ones)
 * agenthq ticket link <id> <otherId> <type>   link one ticket to another (type: blocked_by, blocks, child_of, ...)
 * agenthq threads              my conversations: who else is in each, how many messages, when last
 * agenthq thread <id>          one conversation, message by message in thread order
 * agenthq waiting              every open question on this daemon and who each is waiting on
 * agenthq knowledge            the project's knowledge at a glance: notes, live decisions, open issues (P10-K8)
 * agenthq memory index|show|search|save   the project's memory notes (P10-K3)
 * agenthq decision list|show|add          the project's append-only decisions log (P10-K5)
 * agenthq pipeline [show|configure|clear|record|check]   the project's optional verification pipeline (PIPE-1)
 * agenthq issue list|show|open|attempt|fix|precheck   the project's issues, attempts and fixes (P10-K7)
 * agenthq trait save|list|show|delete      the daemon's trait library (TRAITS-T7)
 * agenthq skill list|show <name|id>        the daemon's skills library, read-only (P10-K14)
 * agenthq role traits <roleId> [--set a,b | --clear]   a role's ordered trait list (TRAITS-T7)
 * agenthq ask --question "..." --option "label|description" [--multi] [--other] [--timeout <sec>]   ask the owner (PROMPT-5)
 * agenthq ask --test                                                                              raise a sample owner question to this session (ASK-2)
 * agenthq ask --question "..." --option "label|consequence" ... --recommend <label> --reason "..." [--other] [--default-after <min>]
 *                                  non-blocking owner question (ASK-1): prints {"questionId":..}, the pick arrives as mail
 * agenthq --version | version  this CLI's contract and build, and the daemon's: `agenthq <cli> build <b> (daemon: <contract> build <b>|not running)`
 * agenthq mcp serve            an MCP server on stdio (read + guarded write tools over this daemon), see docs/MCP.md
 * ```
 *
 * Where it connects, and with what credential, is decided by [resolveTarget]: `--url`, else
 * `--endpoint`, else a typed `--host`/`--port`, else `AGENTHQ_URL`, else `ws://127.0.0.1:8787/ws`. Every path a command takes is a path on
 * the DAEMON's machine; the client never inspects a path itself.
 *
 * WHY [run] IS SEPARATE FROM [main]: [main] only exits the process. Everything testable lives in
 * [run], which writes to injected streams and returns an exit code instead of calling `exit`. That
 * is what lets the acceptance tests drive the REAL command implementations against a REAL daemon
 * over a REAL socket -- rather than re-implementing them inside the test, which is how a client
 * ends up passing its own tests while shipping a binary that does not work.
 */
internal object Cli {

    /** Runs one command. Returns the exit code: 0 success, 1 runtime failure, 2 a usage error. */
    fun run(
        args: Array<String>,
        out: Appendable,
        err: Appendable,
        getenv: (String) -> String? = InfantryEnv::get,
        input: () -> Reader = { System.`in`.bufferedReader() },
        /** Where `mcp serve` looks for its optional `.agenthq/mcp.json` (P11-C). */
        cwd: File = File(System.getProperty("user.dir") ?: "."),
        /** Contract 1.117.0: the raw stdin `progress --watch` copies through byte for byte. */
        rawInput: () -> java.io.InputStream = { System.`in` },
    ): Int {
        // Contract 1.117.0: `progress ...` and `run -- <cmd...>` never meet the global flag parser either, and never fail
        // outside Infantry AI: no session, no daemon or a bad connection option all mean "report nothing".
        if (args.firstOrNull() == "progress" || args.firstOrNull() == "run") {
            val quiet = getenv(ProgressCli.DEBUG_ENV)?.trim() != "1"
            val open = {
                val target = resolveTarget(parseArgs(emptyArray(), DiscardAppendable) ?: error("no connection target"), getenv)
                FrameStream.open(target, if (quiet) DiscardAppendable else err)
            }
            val stdout = out as? java.io.OutputStream ?: AppendableOutputStream(out)
            return if (args[0] == "progress") {
                ProgressCli.progress(args.drop(1), rawInput(), stdout, err, getenv, open)
            } else {
                val stderr = err as? java.io.OutputStream ?: AppendableOutputStream(err)
                ProgressCli.run(args.drop(1), stdout, stderr, err, getenv, open)
            }
        }
        // `bg run [--name x] -- <cmd...>`: the command after `--` is raw, so it must not meet the global flag parser.
        if (args.firstOrNull() == "bg") {
            // Only the words before `--` are ours: a `--port N` there still addresses the daemon.
            val sep = args.indexOf("--").let { if (it < 0) args.size else it }
            val portAt = args.take(sep).indexOf("--port")
            val bgArgs = if (portAt < 0) args.toList() else args.filterIndexed { i, _ -> i != portAt && i != portAt + 1 }
            val globals = if (portAt < 0) emptyArray() else arrayOf("--port", args.getOrNull(portAt + 1) ?: "")
            val target = try {
                resolveTarget(parseArgs(globals, err) ?: return 2, getenv)
            } catch (e: Exception) {
                err.appendLine("error: ${e.message}")
                return 2
            }
            return BgCli.main(bgArgs.drop(1), out, err, getenv, globals.toList()) { FrameStream.open(target, err) }
        }
        // BUG-CLI-1: `--desc -` / `--desc-file` become the plain description here, before any command runs, so
        // every command that takes --desc gets the text exactly as given and a bad path is a usage error (exit 2).
        val parsed = parseArgs(args, err)?.let { resolveDescription(it, input, err) } ?: return 2
        // MAIL-FOOTER-1: `mail` / `mailbox` (with or without --help) is the mail group of the help, not an error.
        if (parsed.command in MAIL_HELP_ALIASES) {
            printMailUsage(out)
            return 0
        }
        if (parsed.help) {
            printUsage(out)
            return 0
        }
        // BUG-CLI-4: answered before any command check, and never a failure: no daemon is "not running", not an error.
        if (parsed.version || (parsed.command == "version" && parsed.args.isEmpty())) {
            return version(parsed, out, getenv)
        }
        val command = parsed.command
        if (command == null) {
            err.appendLine("error: no command given")
            printUsage(err)
            return 2
        }
        // The command name is checked BEFORE connecting: a typo must be a usage error, not a
        // connection attempt the user never asked for and a confusing "cannot reach the daemon".
        if (command !in COMMANDS) {
            err.appendLine("error: unknown command '$command'")
            printUsage(err)
            return 2
        }

        // Required arguments are validated BEFORE connecting: `submit` without a goal is a usage
        // error (exit 2), not a failed connection attempt (exit 1) against a daemon the user never
        // meant to reach. [getenv] is passed because a spawned agent has no `--project` to type:
        // the daemon exports AGENTHQ_PROJECT_ID into its environment, and `epics` cannot work
        // without one (see [epics]).
        // `remote` edits a local file and contacts no daemon, so it is answered before validation and connecting.
        if (command == "remote") return remote(parsed.args, out, err, getenv)
        val usageError = validateArgs(command, parsed, getenv)
        if (usageError != null) {
            err.appendLine("error: $usageError")
            // `send` is typed by hand (by an agent, or by the human at this terminal) and it is the one
            // command whose mistake is a typo in WHAT TO SAY, not in a flag: it shows the whole command
            // list, so its own `send` line is in front of the reader. An unknown command does the same.
            if (command == "send") printUsage(err)
            return 2
        }

        // Where to connect and with what token are configuration: a bad value is a usage error, and
        // nothing is contacted. A token is read from its file here, and never echoed anywhere.
        val target = try {
            resolveTarget(parsed, getenv)
        } catch (e: UsageFailure) {
            err.appendLine("error: ${e.message}")
            return 2
        } catch (e: IllegalArgumentException) {
            err.appendLine("error: ${e.message}")
            return 2
        } catch (e: AccessConfigurationException) {
            err.appendLine("error: ${e.message}")
            return 2
        }
        warnIfTokenWouldTravelInCleartext(target, err)

        // `mcp serve` is long-lived and must start even while the daemon is down: each tool call connects on
        // its own and reports an unreachable daemon as a tool error, so it branches off BEFORE the connect.
        if (command == "mcp") {
            // P11-C: an optional `.agenthq/mcp.json` in the working directory sets project-scoped defaults;
            // flags override it. A bad file stops the server here, with one line, before `initialize`.
            val config = try {
                McpServeConfig.merge(
                    McpServeConfig(parsed.project, parsed.maxDepth, parsed.maxSpawns, parsed.allowKillAny.takeIf { it }),
                    McpServeConfig.load(cwd),
                )
            } catch (e: McpConfigException) {
                err.appendLine("error: ${e.message}")
                return 2
            }
            val guardrails = try {
                mcpGuardrails(parsed, config, getenv)
            } catch (e: UsageFailure) {
                err.appendLine("error: ${e.message}")
                return 2
            }
            err.appendLine(
                "agenthq mcp: depth ${guardrails.depth}/${guardrails.maxDepth ?: "daemon limits.concurrency.maxDepth"}, max ${guardrails.maxSpawns ?: "daemon limits.concurrency.mcpMaxSpawns"} open spawns, " +
                    "kill ${if (guardrails.allowKillAny) "any session" else "own spawns only"}, " +
                    (config.projectId?.let { "project $it" } ?: "no default project"),
            )
            val tools = McpTools({ FrameStream.open(target, err) }, guardrails, getenv, defaultProjectId = config.projectId)
            return McpServer(input().buffered(), out, err, tools).serve()
        }

        // A daemon that is not running is the common case -- the user just typed the command -- so
        // it is reported as one plain, actionable line: not a stack trace, and not a hang.
        val stream = try {
            FrameStream.open(target, err)
        } catch (e: AuthRefused) {
            err.appendLine("error: ${e.message}")
            return 1
        } catch (e: Exception) {
            // Extract host and port from the target URL for accurate error message
            val uri = try { URI.create(target.url) } catch (_: Exception) { null }
            val host = uri?.host?.takeIf { it.isNotBlank() } ?: parsed.host
            val port = uri?.port?.takeIf { it > 0 } ?: parsed.port
            err.appendLine("error: ${DAEMON_UNREACHABLE_MSG.format(host, port)}")
            return 2
        }

        return try {
            when (command) {
                "submit" -> submit(stream, parsed, out, err, getenv)
                "watch" -> watch(stream, parsed, out, err)
                "pods" -> pods(stream, parsed, out, err)
                "runs" -> runs(stream, parsed, out, err)
                "logs" -> logs(stream, parsed, out, err)
                "cancel" -> cancel(stream, parsed, out, err)
                "tickets" -> tickets(stream, parsed, out, err, getenv)
                "board" -> board(stream, parsed, out, err, getenv)
                "ticket" -> ticket(stream, parsed, out, err, getenv)
                "harnesses" -> harnesses(stream, out, err)
                "harness-status" -> harnessStatus(stream, parsed, out, err)
                "harness-install" -> harnessInstall(stream, parsed, out, err)
                "processes" -> processes(stream, parsed, out, err)
                "agents" -> agents(stream, parsed, out, err, getenv)
                "send" -> send(stream, parsed, out, err, getenv)
                "inbox" -> inbox(stream, parsed, out, err, getenv)
                "read" -> read(stream, parsed, out, err, getenv)
                "threads" -> threads(stream, parsed, out, err, getenv)
                "thread" -> thread(stream, parsed, out, err, getenv)
                "waiting" -> waiting(stream, parsed, out, err, getenv)
                "epics" -> epics(stream, parsed, out, err, getenv)
                "stories" -> stories(stream, parsed, out, err, getenv)
                "epic" -> epic(stream, parsed, out, err, getenv)
                "project" -> project(stream, parsed, out, err)
                "image" -> image(stream, parsed, out, err)
                "session" -> session(stream, parsed, out, err, getenv)
                "kill" -> kill(stream, parsed, out, err, getenv)
                "knowledge" -> knowledge(stream, parsed, out, err, getenv)
                "memory" -> memory(stream, parsed, out, err, getenv, input)
                "decision" -> decision(stream, parsed, out, err, getenv, input)
                "pipeline" -> pipeline(stream, parsed, out, err, getenv)
                "notify" -> notify(stream, parsed, out, err, getenv)
                "issue" -> issue(stream, parsed, out, err, getenv)
                "trait" -> trait(stream, parsed, out, err, input)
                "skill" -> skill(stream, parsed, out, err)
                "role" -> role(stream, parsed, out, err, getenv, input)
                "ask" -> ask(stream, parsed, out, err, getenv)
                "usage" -> runBlocking { UsageCli.run(stream, parsed.args, out, err) }
                "settings" -> runBlocking { checkContract(stream, err); SettingsCli.run(stream, parsed.args, out, err) }
                else -> {
                    // Unreachable: [run] rejects an unknown command before it connects.
                    err.appendLine("error: unknown command '$command'")
                    printUsage(err)
                    2
                }
            }
        } catch (e: AuthRefused) {
            err.appendLine("error: ${e.message}")
            1
        } catch (e: ConnectionLost) {
            err.appendLine("error: ${e.message}")
            1
        } catch (e: Exception) {
            err.appendLine("error: ${e.message}")
            1
        } finally {
            stream.close()
        }
    }

    // ------------------------------------------------------------------------
    // commands
    // ------------------------------------------------------------------------

    /**
     * BUG-CLI-4: `agenthq --version` / `agenthq version`. One line, `agenthq <cli contract> (daemon: <contract>)`, exit 0.
     * The daemon's contract is the `contract=` of its `ready` frame, read under [VERSION_PROBE_MS]; a daemon that is
     * down, unreachable, refuses the token or does not answer in time is "not running", never a failure, because the
     * point of the command is to be answerable on a machine where something is wrong.
     */
    private fun version(parsed: ParsedArgs, out: Appendable, getenv: (String) -> String?): Int {
        val ready = try {
            daemonReady(resolveTarget(parsed, getenv), VERSION_PROBE_MS)
        } catch (_: Exception) {
            null
        }
        out.appendLine(versionLine(com.superiorsoftllc.agenthq.update.BuildInfo.current, ready))
        return 0
    }

    /**
     * BUG-CLI-4 + BUILD-STAMP-1: `agenthq <cli contract> build <cli build> (daemon: <contract> build <daemon build>)`. [ready] is
     * the daemon's `ready` (null = not running); an older daemon that sends no `build=` shows its contract alone.
     */
    internal fun versionLine(cliBuild: String, ready: ReadyInfo?): String {
        val daemon = when {
            ready == null -> "not running"
            ready.build == null -> ready.contract
            else -> "${ready.contract} build ${ready.build}"
        }
        return "agenthq ${EventVocabulary.VERSION} build $cliBuild (daemon: $daemon)"
    }

    /** ASK-1: the shape of `agenthq ask` as a non-blocking owner question. */
    private const val OWNER_QUESTION_SHAPE =
        "agenthq ask --question \"...\" --option \"label|consequence\" --option ... --recommend <label> --reason \"...\" [--other] [--default-after <min>]"

    /** BUG-CLI-4: how long `--version` waits to connect AND for the daemon's `ready` frame, together. */
    private const val VERSION_PROBE_MS = 2_000L

    /**
     * PROMPT-5: `agenthq ask`. Opens a `question` prompt for THIS process's session ([AccessPolicy.SESSION_ID_ENV];
     * [validateArgs] refused a missing one), blocks until it resolves, and prints `{"answers":[...],"otherText":...}`
     * on stdout. Exit 0 answered, 3 no answer (timeout, session end), 2 refused by the daemon, 1 the connection dropped
     * (the daemon withdraws a question whose asker went away, so it is never re-sent).
     */
    private fun ask(stream: FrameStream, parsed: ParsedArgs, out: Appendable, err: Appendable, getenv: (String) -> String?): Int =
        if (parsed.recommend != null || parsed.test) askOwnerQuestion(stream, parsed, out, err, getenv) else runBlocking {
            val session = callerSession(getenv) ?: return@runBlocking 2
            val request = askRequest(parsed)
            checkContract(stream, err)
            stream.send(Envelope(Envelope.PROMPT_ASK, json.encodeToString(PromptAsk.serializer(), request), session))
            // The daemon resolves the prompt within its timeout (ours, or `prompts.timeoutSec` <= 86400 s); the grace
            // only keeps a wedged daemon from holding this process forever.
            val waitSec = (request.timeoutSec ?: PromptAsk.TIMEOUT_SEC_RANGE.last).toLong()
            val deadline = System.currentTimeMillis() + waitSec * 1_000L + ASK_GRACE_MS
            while (true) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) break
                val frame = stream.next(minOf(remaining, ASK_POLL_MS))
                if (frame == null) {
                    if (stream.takeReconnected()) {
                        err.appendLine("error: the connection to the daemon dropped while waiting; the question was withdrawn")
                        return@runBlocking 1
                    }
                    continue
                }
                when {
                    frame.type == Envelope.PROMPT_ANSWER && frame.sessionId == session -> {
                        val answer = json.decodeFromString(PromptAnswer.serializer(), frame.data)
                        out.appendLine(json.encodeToString(AskOutput.serializer(), AskOutput(answer.answers, answer.otherText)))
                        if (answer.answered) return@runBlocking 0
                        err.appendLine("no answer: the question timed out or the session ended")
                        return@runBlocking 3
                    }
                    frame.type == Envelope.ERROR && frame.data.startsWith("prompt_ask refused") -> {
                        err.appendLine("error: ${frame.data}")
                        return@runBlocking 2
                    }
                    frame.type == Envelope.ERROR &&
                        (frame.data.startsWith("prompt_ask failed") || "'${Envelope.PROMPT_ASK}'" in frame.data) -> {
                        err.appendLine("error: ${frame.data}")
                        return@runBlocking 1
                    }
                }
            }
            out.appendLine(json.encodeToString(AskOutput.serializer(), AskOutput(emptyList(), null)))
            err.appendLine("no answer: the daemon did not answer within ${waitSec}s")
            3
        }

    /**
     * ASK-1: `agenthq ask --recommend <label> --reason ".."`, a NON-BLOCKING owner question. Prints
     * `{"questionId":..,"note":..}` and exits 0 at once; the owner's pick arrives later as mail in thread
     * `question:<id>`. Exit 2 refused (bad question, rate limit), 1 the daemon could not be reached or failed.
     */
    private fun askOwnerQuestion(stream: FrameStream, parsed: ParsedArgs, out: Appendable, err: Appendable, getenv: (String) -> String?): Int =
        runBlocking {
            val session = callerSession(getenv) ?: return@runBlocking 2
            checkContract(stream, err)
            val request = if (parsed.test) QuestionAsk.sample() else ownerQuestionRequest(parsed)
            val payload = json.encodeToString(QuestionAsk.serializer(), request)
            // Not idempotent: a re-send after a dropped connection could ask the owner twice.
            val answer = try {
                stream.request(Envelope(Envelope.QUESTION_ASK, payload, session), Envelope.QUESTION_ASKED, "question_ask", idempotent = false)
            } catch (e: Exception) {
                val message = e.message ?: "question_ask failed"
                err.appendLine("error: $message")
                return@runBlocking if (message.startsWith("question_ask refused")) 2 else 1
            }
            out.appendLine(answer.data)
            0
        }

    /** ASK-1: the `question_ask` payload `ask --recommend`'s flags describe. */
    private fun ownerQuestionRequest(parsed: ParsedArgs): QuestionAsk = QuestionAsk(
        question = parsed.question.orEmpty(),
        options = askRequest(parsed).options,
        recommended = parsed.recommend.orEmpty(),
        reason = parsed.reason.orEmpty(),
        allowOther = !parsed.noOther,
        defaultAfterMin = parsed.defaultAfter?.trim()?.toIntOrNull(),
    )

    /** PROMPT-5: this process's session, from [AccessPolicy.SESSION_ID_ENV]; null when there is none. */
    private fun callerSession(getenv: (String) -> String?): String? =
        getenv(AccessPolicy.SESSION_ID_ENV)?.trim()?.takeIf { it.isNotEmpty() }

    /** PROMPT-5: the `prompt_ask` payload `ask`'s flags describe; `--option "label|description"` splits at the first `|`. */
    private fun askRequest(parsed: ParsedArgs): PromptAsk = PromptAsk(
        question = parsed.question.orEmpty(),
        options = parsed.options.map { raw ->
            val bar = raw.indexOf('|')
            if (bar < 0) PromptOption(raw.trim()) else PromptOption(raw.substring(0, bar).trim(), raw.substring(bar + 1).trim())
        },
        multiSelect = parsed.multi,
        allowOther = parsed.other,
        timeoutSec = parsed.timeout?.trim()?.toIntOrNull(),
    )

    /** PROMPT-5: the `ask` usage error, or null. Checked before connecting, with the daemon's own rules. */
    private fun askUsageError(parsed: ParsedArgs, getenv: (String) -> String?): String? {
        val shape = "agenthq ask --question \"...\" --option \"label|description\" [--option ...] [--multi] [--other] [--timeout <sec>]"
        if (callerSession(getenv) == null) {
            return "ask needs the asking session in ${AccessPolicy.SESSION_ID_ENV} (set for every session Infantry AI spawns); " +
                "there is none, so there is no session to ask for"
        }
        if (parsed.args.isNotEmpty()) return "ask takes no positional arguments: $shape"
        if (parsed.test) return null
        if (parsed.question?.isBlank() != false) return "ask requires a question: $shape"
        if (parsed.options.isEmpty()) return "ask requires at least one --option: $shape"
        // ASK-1: --recommend selects the non-blocking owner question; --reason / --default-after belong to it alone.
        if (parsed.recommend == null) {
            if (parsed.reason != null || parsed.defaultAfter != null) {
                return "--reason and --default-after need --recommend <label> (the non-blocking owner question): $OWNER_QUESTION_SHAPE"
            }
        } else {
            if (parsed.multi || parsed.timeout != null) {
                return "--multi and --timeout belong to the blocking ask; an owner question (--recommend) takes neither: $OWNER_QUESTION_SHAPE"
            }
            if (parsed.reason?.isBlank() != false) return "ask --recommend requires --reason: $OWNER_QUESTION_SHAPE"
            parsed.defaultAfter?.let { raw ->
                val range = QuestionAsk.DEFAULT_AFTER_MIN_RANGE
                if (raw.trim().toIntOrNull()?.takeIf { it in range } == null) {
                    return "--default-after expects whole minutes, ${range.first}..${range.last}, got '$raw'"
                }
            }
            return null
        }
        parsed.timeout?.let { raw ->
            val range = PromptAsk.TIMEOUT_SEC_RANGE
            if (raw.trim().toIntOrNull()?.takeIf { it in range } == null) {
                return "--timeout expects whole seconds, ${range.first}..${range.last}, got '$raw'"
            }
        }
        return try {
            QuestionSpec.of(askRequest(parsed))
            null
        } catch (e: IllegalArgumentException) {
            "ask: ${e.message}"
        }
    }

    private fun submit(stream: FrameStream, parsed: ParsedArgs, out: Appendable, err: Appendable, getenv: (String) -> String?): Int =
        runBlocking {
            val goal = parsed.args.joinToString(" ").trim()
            checkContract(stream, err)

            // NOT idempotent: if the connection drops after the daemon received this, a second send would
            // create a second pod. A drop is reported instead, and the caller decides.
            val answer = stream.request(
                Envelope(
                    Envelope.POD_SUBMIT,
                    json.encodeToString(
                        PodSubmitRequest.serializer(),
                        PodSubmitRequest(
                            goal = goal,
                            nodes = listOf(NodeSpec(name = "main", prompt = goal)),
                            maxConcurrency = 1,
                            // BUG-G2b (contract 1.57.0): `--project`, else this agent's own project; none = the daemon's default.
                            projectId = projectIdOf(parsed, getenv),
                        ),
                    ),
                    // BUG-G2d (contract 1.58.0): run inside a session, the pod is that session's spawn and counts in
                    // its pod's cap. Typed by a person there is no AGENTHQ_SESSION_ID, and nothing is charged.
                    getenv(AccessPolicy.SESSION_ID_ENV)?.trim()?.takeIf { it.isNotEmpty() },
                ),
                answerType = Envelope.POD,
                what = "pod_submit",
                idempotent = false,
            )

            // The pod id comes back in the answer itself, so the caller never has to ask twice.
            out.appendLine(podFrame(answer).pod.id)
            0
        }

    /**
     * Stream a pod until it is terminal.
     *
     * `pod` is a PULL, not a push (CONTRACT.md 1.1.0): the daemon announces nothing about pods on
     * its own. So this consumes the broadcast session frames as they arrive for readable live
     * output, and whenever the socket goes quiet it asks `pod_query` again -- the poll is how the
     * terminal state is ever discovered. Every read goes through [FrameStream], which consumes each
     * frame exactly once; scanning the frame list as a whole would hand back the very first frame
     * forever, and this loop would spin without ever seeing the run finish.
     *
     * A dropped connection does not end the watch: [FrameStream] reconnects, and the pod is simply
     * queried again. The pod lives on the daemon, so its state is still there to ask for.
     */
    private fun watch(stream: FrameStream, parsed: ParsedArgs, out: Appendable, err: Appendable): Int =
        runBlocking {
            val podId = parsed.args.first()
            checkContract(stream, err)

            var status = queryPod(stream, podId)
            renderPod(out, status)
            while (status.pod.state !in TERMINAL_POD_STATES) {
                val frame = stream.next(WATCH_IDLE_MS)
                if (frame == null) {
                    // Nothing arrived (or the connection was just re-made): ask again rather than sit on a
                    // pod whose progress the daemon will never announce.
                    status = queryPod(stream, podId)
                    renderPod(out, status)
                    continue
                }
                when (frame.type) {
                    Envelope.TEXT_DELTA -> out.append(frame.data)
                    // The raw stdout line is deliberately not printed: `text_delta` is its
                    // translation, and printing both would show every sentence twice.
                    Envelope.STDERR -> err.appendLine(frame.data)
                    Envelope.STATUS -> out.appendLine(
                        "[status] " + (decodeStatus(frame)?.state ?: frame.data)
                    )
                    Envelope.ERROR -> err.appendLine("[error] ${frame.data}")
                    Envelope.FINISHED -> {
                        val payload = decodeFinished(frame)
                        out.appendLine("[finished] ${payload?.outcome ?: "UNKNOWN"} exit=${payload?.exitCode ?: "?"}")
                        // `finished` is the RUN ending; the pod's own verdict is only knowable by asking.
                        val after = queryPod(stream, podId)
                        if (after.pod.state != status.pod.state) renderPod(out, after)
                        status = after
                    }
                    Envelope.POD -> decodePod(frame)?.let { pushed ->
                        if (pushed.pod.state != status.pod.state) {
                            renderPod(out, pushed)
                            status = pushed
                        }
                    }
                    else -> Unit
                }
            }
            0
        }

    /**
     * What is running.
     *
     * Contract 1.1.0 deliberately has NO "list my pods" frame: a pod id comes from its own
     * `pod_submit` answer. So this lists SESSIONS -- the sessions the daemon currently knows -- and
     * says plainly that it is not a pod list, rather than pretending the gap is not there.
     */
    private fun pods(stream: FrameStream, parsed: ParsedArgs, out: Appendable, err: Appendable): Int =
        runBlocking {
            checkContract(stream, err)
            val answer = stream.request(
                Envelope(Envelope.SESSIONS),
                answerType = Envelope.SESSIONS,
                what = "sessions",
                idempotent = true,
            )
            val records = try {
                json.decodeFromString(ListSerializer(SessionRecord.serializer()), answer.data)
            } catch (e: Exception) {
                // A malformed answer must fail loudly: printing "SESSIONS: 0" would look like an empty daemon.
                err.appendLine("error: malformed sessions answer from the daemon: ${e.message}")
                return@runBlocking 1
            }

            out.appendLine(
                "SESSIONS: ${records.size} (pod listing is not in contract ${EventVocabulary.VERSION})"
            )
            records.forEach { session ->
                out.appendLine("  ${session.id.take(8)} ${if (session.runPhase == "compacting") "compacting" else session.state} started=${session.spawnedAt}" + (session.projectId?.let { " project=$it" } ?: ""))
            }
            0
        }

    /**
     * A pod's runs with their outcome, AND the session id each run is streaming under.
     *
     * The session id is printed because it is what `agenthq logs` takes: `stdout` frames are tagged
     * with a session, so without this column `runs` and `logs` could not be chained.
     */
    private fun runs(stream: FrameStream, parsed: ParsedArgs, out: Appendable, err: Appendable): Int =
        runBlocking {
            val podId = parsed.args.first()
            checkContract(stream, err)
            val status = queryPod(stream, podId)

            out.appendLine("RUNS for pod $podId:")
            if (status.runs.isEmpty()) out.appendLine("  (no runs yet)")
            status.runs.forEach { (nodeId, nodeRuns) ->
                out.appendLine("  node ${nodeId.take(8)}:")
                nodeRuns.forEach { run ->
                    out.appendLine(
                        "    ${run.id.take(8)} outcome=${outcomeOf(run.state)} exit=${run.exitCode ?: "?"} " +
                            "session=${run.sessionId}"
                    )
                }
            }
            0
        }

    /**
     * A run's raw stdout / stderr, as the provider produces it.
     *
     * WHAT THIS IS, PRECISELY: a live tail of one session's `stdout` and `stderr` frames, ending on
     * that session's `finished`. It is keyed by SESSION id, not run id, because that is what the
     * frames carry -- `agenthq runs` prints the session id for exactly this reason. On reconnect,
     * [FrameStream] requests retained frames after the last sequence it delivered; if the daemon's
     * replay buffer no longer covers that cursor, its `replay_gap` report is shown to the user.
     */
    private fun logs(stream: FrameStream, parsed: ParsedArgs, out: Appendable, err: Appendable): Int =
        runBlocking {
            val sessionId = parsed.args.first()
            stream.trackSession(sessionId)
            checkContract(stream, err)
            out.appendLine("streaming raw stdout/stderr for session ${sessionId.take(8)}")

            var seen = 0
            var exitCode: Int? = null
            while (exitCode == null) {
                val frame = stream.next(LOGS_IDLE_MS)
                when {
                    // A reconnect ends the wait early without any output: keep tailing the new connection.
                    frame == null && stream.takeReconnected() -> Unit
                    frame == null -> exitCode = if (seen == 0) {
                        err.appendLine("error: no output from session ${sessionId.take(8)} within ${LOGS_IDLE_MS}ms")
                        1
                    } else 0
                    // One socket carries every session on the daemon; only this run's lines are ours.
                    frame.sessionId != null && frame.sessionId != sessionId -> Unit
                    frame.type == Envelope.STDOUT -> {
                        out.appendLine(frame.data)
                        seen++
                    }
                    frame.type == Envelope.STDERR -> {
                        err.appendLine(frame.data)
                        seen++
                    }
                    // Contract 1.125.0: a turn from another agent / outside source is named by its typed origin.
                    frame.type == Envelope.TURN && frame.origin != null -> out.appendLine(TurnLine.of(frame))
                    frame.type == Envelope.FINISHED -> {
                        val payload = decodeFinished(frame)
                        out.appendLine(
                            "[finished] ${payload?.outcome ?: "UNKNOWN"} " +
                                "exit=${payload?.exitCode ?: "?"} lines=$seen"
                        )
                        exitCode = 0
                    }
                    frame.type == SessionReplayCursor.GAP_TYPE -> err.appendLine(stream.replayGapMessage(frame))
                    frame.type == Envelope.ERROR -> err.appendLine("[error] ${frame.data}")
                    else -> Unit
                }
            }
            exitCode
        }

    private fun cancel(stream: FrameStream, parsed: ParsedArgs, out: Appendable, err: Appendable): Int =
        runBlocking {
            val podId = parsed.args.first()
            checkContract(stream, err)
            // Idempotent: cancelling a pod that is already terminal is a no-op, so a retry is safe.
            val answer = stream.request(
                Envelope(Envelope.POD_CANCEL, if (parsed.force) "$podId force" else podId),
                answerType = Envelope.POD,
                what = "pod_cancel",
                idempotent = true,
            )
            out.appendLine("cancelled $podId -> ${podFrame(answer).pod.state}")
            0
        }

    /**
     * SESSION-RM-1: `session rm <id|prefix>` drops an ENDED session from the daemon's session list (the sidebar entry)
     * through `session_delete`. A live session is refused here, before anything is sent, and by the daemon again if it
     * went live meanwhile. `session_delete` has no direct answer: the daemon handles this connection's frames in
     * order, so a refusal (`error`) arrives before the answer to the `sessions` read sent right after it, and the
     * removal shows as that list no longer naming the session.
     */
    /** `session restart <id> [--force]`: reboot a session in place; a mid-turn session is refused unless `--force`. */
    private suspend fun sessionRestart(stream: FrameStream, requested: String, force: Boolean, out: Appendable, err: Appendable): Int {
        val snapshot = json.decodeFromString(
            com.superiorsoftllc.agenthq.session.SessionListWire,
            stream.request(Envelope(Envelope.SESSIONS), answerType = Envelope.SESSIONS, what = "sessions", idempotent = true).data,
        )
        val matches = snapshot.filter { it.id == requested }.ifEmpty { snapshot.filter { it.id.startsWith(requested) } }
        val session = matches.singleOrNull() ?: run {
            err.appendLine(if (matches.isEmpty()) "error: no session '$requested' on this daemon" else "error: session id '$requested' is ambiguous: ${matches.joinToString(", ") { it.id }}")
            return 1
        }
        if (session.live && !force) {
            err.appendLine("error: session ${session.id} is mid-turn; wait for it, or pass --force to stop it and restart")
            return 1
        }
        val body = """{"sessionId":"${session.id}","force":$force}"""
        stream.send(Envelope(Envelope.SESSION_RESTART, body))
        val deadline = System.currentTimeMillis() + SESSION_RM_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val frame = stream.next(SESSION_RM_POLL_MS) ?: continue
            if (frame.type == Envelope.ERROR && frame.sessionId == null) {
                err.appendLine("error: ${frame.data}")
                return 1
            }
            if (frame.type == Envelope.SESSION) {
                val record = runCatching { json.decodeFromString(SessionRecord.serializer(), frame.data) }.getOrNull()
                if (record?.id == session.id && record.neverRun) {
                    out.appendLine("restarted ${session.id}; its next turn is composed anew (role notes, project notes, identity)")
                    return 0
                }
            }
        }
        err.appendLine("error: no confirmation that ${session.id} restarted; check `agenthq agents --live`")
        return 1
    }

    /** Contract 1.114.0: the scope rule `agenthq_kill` uses, evaluated for the CLI caller. */
    private fun killScopeRefusal(session: SessionRecord, snapshot: List<SessionRecord>, getenv: (String) -> String?): String? =
        McpWriteTools(McpGuardrails(), getenv).killRefusal(session, snapshot)

    /** Sends `kill` and waits for the session list that follows it; returns the session afterwards, or null with [err] written. */
    private suspend fun killAndWait(stream: FrameStream, session: SessionRecord, force: Boolean, err: Appendable): SessionRecord? {
        stream.send(Envelope(Envelope.KILL, if (force) "${session.id} force" else session.id))
        stream.send(Envelope(Envelope.SESSIONS))
        val deadline = System.currentTimeMillis() + SESSION_RM_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val frame = stream.next(SESSION_RM_POLL_MS) ?: continue
            when {
                frame.type == Envelope.ERROR && frame.sessionId == null -> { err.appendLine("error: kill failed: ${frame.data}"); return null }
                frame.type == Envelope.SESSIONS ->
                    json.decodeFromString(com.superiorsoftllc.agenthq.session.SessionListWire, frame.data).firstOrNull { it.id == session.id }
                        ?.let { if (!it.live) return it }
            }
        }
        err.appendLine("error: session ${session.id} is still running after the kill; retry with --force")
        return null
    }

    private suspend fun resolveSessionArg(stream: FrameStream, requested: String, err: Appendable): Pair<SessionRecord, List<SessionRecord>>? {
        val snapshot = json.decodeFromString(
            com.superiorsoftllc.agenthq.session.SessionListWire,
            stream.request(Envelope(Envelope.SESSIONS), answerType = Envelope.SESSIONS, what = "sessions", idempotent = true).data,
        )
        val matches = snapshot.filter { it.id == requested }.ifEmpty { snapshot.filter { it.id.startsWith(requested) } }
        return when (matches.size) {
            1 -> matches.single() to snapshot
            0 -> { err.appendLine("error: no session '$requested' on this daemon"); null }
            else -> { err.appendLine("error: session id '$requested' is ambiguous: ${matches.joinToString(", ") { it.id }}"); null }
        }
    }

    /** `kill <id|prefix> [--force]`: end a session (it stays in the list; `session rm` removes it). */
    private fun kill(stream: FrameStream, parsed: ParsedArgs, out: Appendable, err: Appendable, getenv: (String) -> String?): Int =
        runBlocking {
            checkContract(stream, err)
            val (session, snapshot) = resolveSessionArg(stream, parsed.args[0], err) ?: return@runBlocking 1
            killScopeRefusal(session, snapshot, getenv)?.let { err.appendLine("error: kill refused: $it"); return@runBlocking 1 }
            val after = killAndWait(stream, session, parsed.force, err) ?: return@runBlocking 1
            out.appendLine("killed ${session.id} (${after.state}); remove it with: agenthq session rm ${session.id}")
            0
        }

    private fun session(stream: FrameStream, parsed: ParsedArgs, out: Appendable, err: Appendable, getenv: (String) -> String? = { null }): Int =
        runBlocking {
            val requested = parsed.args[1]
            checkContract(stream, err)
            if (parsed.args[0] == "restart") return@runBlocking sessionRestart(stream, requested, parsed.force, out, err)
            val snapshot = json.decodeFromString(
                com.superiorsoftllc.agenthq.session.SessionListWire,
                stream.request(Envelope(Envelope.SESSIONS), answerType = Envelope.SESSIONS, what = "sessions", idempotent = true).data,
            )
            val matches = snapshot.filter { it.id == requested }.ifEmpty { snapshot.filter { it.id.startsWith(requested) } }
            val session = when (matches.size) {
                1 -> matches.single()
                0 -> { err.appendLine("error: no session '$requested' on this daemon"); return@runBlocking 1 }
                else -> {
                    err.appendLine("error: session id '$requested' is ambiguous: ${matches.joinToString(", ") { it.id }}")
                    return@runBlocking 1
                }
            }
            if (session.live) {
                if (!parsed.force) {
                    err.appendLine("error: ${McpWriteTools.liveRefusal(session)}")
                    return@runBlocking 1
                }
                killScopeRefusal(session, snapshot, getenv)?.let { err.appendLine("error: session rm refused: $it"); return@runBlocking 1 }
                killAndWait(stream, session, true, err) ?: return@runBlocking 1
            }
            stream.send(Envelope(Envelope.SESSION_DELETE, session.id))
            stream.send(Envelope(Envelope.SESSIONS))
            val deadline = System.currentTimeMillis() + SESSION_RM_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                val frame = stream.next(SESSION_RM_POLL_MS) ?: continue
                when {
                    frame.type == Envelope.ERROR && frame.sessionId == null -> {
                        err.appendLine("error: ${frame.data.removePrefix(McpWriteTools.DELETE_REFUSED)}")
                        return@runBlocking 1
                    }
                    frame.type == Envelope.SESSIONS -> {
                        val after = json.decodeFromString(com.superiorsoftllc.agenthq.session.SessionListWire, frame.data)
                        // An earlier push from another client's change may still name it: wait for the next list.
                        if (after.none { it.id == session.id }) {
                            out.appendLine("removed ${session.id} (${session.state}${session.ticketId?.let { ", $it" } ?: ""})")
                            return@runBlocking 0
                        }
                    }
                }
            }
            err.appendLine("error: the daemon did not confirm removing ${session.id}; check `agenthq pods`")
            1
        }

    /**
     * `tickets [--project <id>] [--status <column>]`: the daemon's board, narrowed.
     *
     * `--project` rides on the `ticket_list` payload (UI-BOARD-PROJ), so the DAEMON does the scoping and
     * the client never re-derives it from `ticket.projectId`; inside a spawned agent the daemon exports
     * [AccessPolicy.PROJECT_ID_ENV] into that agent's environment, so plain `tickets` is already that
     * agent's own project's board. No project at all is the whole board, which is what the empty
     * `ticket_list` payload means to a client that predates the filter.
     *
     * `--status` has no wire filter to ride on -- `ticket_list` carries a project and nothing else -- so
     * the column selects here, on the daemon's own status value. The VALUE is validated before anything
     * is contacted (see [validateArgs]); only the filtering happens after the answer.
     */
    private fun tickets(
        stream: FrameStream,
        parsed: ParsedArgs,
        out: Appendable,
        err: Appendable,
        getenv: (String) -> String?,
    ): Int = runBlocking {
        checkContract(stream, err)
        listBoard(stream, parsed, parsed.status, epicOnly = false, out, err, getenv)
    }

    /** `agenthq board`: [BoardSummary] over the project's tickets and the daemon's sessions, in one command. */
    private fun board(
        stream: FrameStream,
        parsed: ParsedArgs,
        out: Appendable,
        err: Appendable,
        getenv: (String) -> String?,
    ): Int = runBlocking {
        checkContract(stream, err)
        val tickets = fetchBoard(stream, boardProjectOf(parsed, getenv), err) ?: return@runBlocking 1
        val sessions = json.decodeFromString(
            ListSerializer(SessionRecord.serializer()),
            stream.request(Envelope(Envelope.SESSIONS), answerType = Envelope.SESSIONS, what = "sessions", idempotent = true).data,
        )
        BoardSummary.render(tickets, sessions, System.currentTimeMillis(), BoardSummary.gitCommitOf(sessions)).forEach { out.appendLine(it) }
        0
    }

    /**
     * The `tickets` listing, shared by `tickets` and the deprecated `stories` so both print the same rows.
     * `--epic <id|prefix>` resolves to ONE epic of the project and rides on `ticket_list`'s `epicId` filter
     * (E1-b), so the daemon answers that epic's tickets in board order. [epicOnly] without `--epic` keeps
     * only the tickets filed under SOME epic (what `stories` used to list). [status] is a ticket column.
     */
    private suspend fun listBoard(
        stream: FrameStream,
        parsed: ParsedArgs,
        status: String?,
        epicOnly: Boolean,
        out: Appendable,
        err: Appendable,
        getenv: (String) -> String?,
    ): Int {
        val projectId = boardProjectOf(parsed, getenv)
        val epicId = parsed.epic?.let { prefix ->
            resolveEpicId(stream, prefix.trim(), projectId.ifBlank { null }, err) ?: return 1
        }
        val listed = fetchBoard(stream, projectId, err, epicId) ?: return 1
        val tickets = listed
            .filter { !epicOnly || it.epicId != null }
            .filter { status == null || it.status == status }
        printTicketRows(tickets, out, empty = "No tickets.")
        return 0
    }

    /** `ID | STATUS | TITLE | AGENT`, one row per ticket in the order given, or [empty] alone when there are none. */
    private fun printTicketRows(tickets: List<Ticket>, out: Appendable, empty: String) {
        if (tickets.isEmpty()) {
            out.appendLine(empty)
            return
        }
        out.appendLine(TICKET_HEADER)
        for (ticket in tickets) {
            val agent = ticket.agentId?.take(8) ?: "-"
            out.appendLine("${shortTicketId(ticket.id)} | ${ticket.status} | ${ticket.title} | $agent")
        }
    }

    /** The project a board read is scoped to: `--project`, else the agent's own project, else the whole board (""). */
    private fun boardProjectOf(parsed: ParsedArgs, getenv: (String) -> String?): String =
        parsed.project?.trim()
            ?: getenv(AccessPolicy.PROJECT_ID_ENV)?.takeIf { it.isNotBlank() }
            ?: ""

    /**
     * One `ticket_list` read for [projectId] ("" = every project), narrowed to [epicId] when given; null after
     * printing why, when the daemon's answer is unusable. Without an epic the payload stays the bare project id
     * every daemon understands; the JSON query form is only sent when the epic filter is actually needed.
     */
    private suspend fun fetchBoard(stream: FrameStream, projectId: String, err: Appendable, epicId: String? = null): List<Ticket>? {
        val payload = if (epicId == null) {
            projectId
        } else {
            json.encodeToString(TicketListQuery.serializer(), TicketListQuery(projectId.ifBlank { null }, epicId))
        }
        val answer = stream.request(
            Envelope(Envelope.TICKET_LIST, payload),
            answerType = Envelope.TICKETS,
            what = "ticket_list",
            idempotent = true,
        )
        return try {
            json.decodeFromString(ListSerializer(Ticket.serializer()), answer.data)
        } catch (e: Exception) {
            err.appendLine("error: malformed tickets answer from the daemon: ${e.message}")
            null
        }
    }

    /**
     * CLI-T2 shared helper: turns the 8-char suffix `tickets` prints (or a full id) into ONE full ticket
     * id, searching the same board `tickets` lists. Exactly one match is returned. None, or several, is
     * printed to [err] and returns null: the caller exits 1. Later ticket commands reuse this rather than
     * re-deriving the match, so a prefix means the same ticket everywhere.
     */
    private suspend fun resolveTicketId(stream: FrameStream, prefix: String, projectId: String, err: Appendable): String? =
        resolveTicket(stream, prefix, projectId, err)?.id

    /** [resolveTicketId]'s match, the whole ticket as the board lists it: `ticket start` needs its status too. */
    private suspend fun resolveTicket(stream: FrameStream, prefix: String, projectId: String, err: Appendable): Ticket? {
        val board = fetchBoard(stream, projectId, err) ?: return null
        return when (val match = matchTicketPrefix(prefix, board)) {
            is TicketPrefixMatch.Unique -> match.ticket
            TicketPrefixMatch.None -> {
                err.appendLine("error: no ticket $prefix")
                null
            }
            is TicketPrefixMatch.Ambiguous -> {
                err.appendLine("error: ticket id '$prefix' is ambiguous; use a longer id or the full id:")
                for (ticket in match.tickets) err.appendLine("  ${ticket.id} | ${ticket.status} | ${ticket.title}")
                null
            }
        }
    }

    /**
     * `ticket <show|create|move|link> ...`: dispatches on the subcommand. [validateArgs] has already
     * refused a missing or unknown subcommand, a missing --title, a blank id, an unknown status and an
     * unknown link type, before any socket opens.
     */
    private fun ticket(
        stream: FrameStream,
        parsed: ParsedArgs,
        out: Appendable,
        err: Appendable,
        getenv: (String) -> String?,
    ): Int = runBlocking {
        when (parsed.args.firstOrNull()) {
            "create" -> createTicket(stream, parsed, out, err, getenv)
            "update" -> updateTicket(stream, parsed, out, err, getenv)
            "move" -> moveTicket(stream, parsed, out, err, getenv)
            "start" -> startTicket(stream, parsed, out, err, getenv)
            "link" -> linkTicket(stream, parsed, out, err, getenv)
            else -> showTicket(stream, parsed, out, err, getenv)
        }
    }

    /**
     * `ticket move <id|prefix> <status>`: asks the daemon to move ONE ticket and prints
     * `moved <fullId> -> <status>` with the status the daemon stored (it may derive `pending` itself).
     *
     * BUG-ASSIGN-1: a move INTO in_progress is the board's start, so it is read as one, with the same answer `ticket
     * start` reads ([TicketStarter]): a start the daemon refuses or holds prints its reason and exits 1, never
     * `moved <id> -> todo`; a queued start says so. A ticket already in progress moves as before. A start prints
     * what its branch was cut from and any launch warning under the moved line, as `ticket start` does.
     */
    private suspend fun moveTicket(
        stream: FrameStream,
        parsed: ParsedArgs,
        out: Appendable,
        err: Appendable,
        getenv: (String) -> String?,
    ): Int {
        checkContract(stream, err)
        val ticket = resolveTicket(stream, parsed.args[1].trim(), boardProjectOf(parsed, getenv), err) ?: return 1
        val fullId = ticket.id
        val status = parsed.args.getOrNull(2)?.trim()
        // BUG-MAIL-2: run inside an agent's session, the move names that session, which the daemon records as the
        // ticket's starter (its worker can then `agenthq send starter`). Typed by a person, there is none.
        val session = getenv(AccessPolicy.SESSION_ID_ENV)?.trim()?.takeIf { it.isNotEmpty() }
        if (status?.lowercase() == TicketStatus.IN_PROGRESS && ticket.status != TicketStatus.IN_PROGRESS) {
            return when (val outcome = TicketStarter.start(stream, ticket, session)) {
                is TicketStarter.Outcome.Started -> {
                    out.appendLine("moved $fullId -> ${outcome.ticket.status}")
                    printStartNotes(out, outcome)
                    0
                }
                is TicketStarter.Outcome.AlreadyInProgress -> {
                    out.appendLine("moved $fullId -> ${outcome.ticket.status}")
                    0
                }
                is TicketStarter.Outcome.NotStarted -> reportNotStarted(outcome, out, err)
            }
        }
        val placement = parsed.placement()
        val position = if (placement.named > 0) {
            // --top/--bottom/--position need no board; --before/--after resolve their target on it.
            val board = if (placement.before != null || placement.after != null) {
                fetchBoard(stream, boardProjectOf(parsed, getenv), err) ?: return 1
            } else {
                emptyList()
            }
            placement.resolve(ticket, status, board).getOrElse {
                err.appendLine("error: ${it.message}")
                return 1
            }
        } else {
            null
        }
        val move = TicketMove(id = fullId, status = status, position = position)
        val answer = stream.request(
            Envelope(Envelope.TICKET_MOVE, json.encodeToString(TicketMove.serializer(), move), session),
            answerType = Envelope.TICKET,
            what = "ticket_move",
            idempotent = false,
        )
        val moved = try {
            json.decodeFromString(Ticket.serializer(), answer.data)
        } catch (e: Exception) {
            err.appendLine("error: malformed ticket answer from the daemon: ${e.message}")
            return 1
        }
        out.appendLine("moved $fullId -> ${moved.status}")
        return 0
    }

    /** `ticket move`'s placement flags, as the one [TicketPlacement] the MCP `agenthq_ticket_move` also uses. */
    private fun ParsedArgs.placement() = TicketPlacement(top, bottom, before, after, position?.toIntOrNull())

    /**
     * BUG-ASSIGN-1: `ticket start <id|prefix>`: starts ONE ticket exactly as the board does when its card moves to In
     * Progress ([TicketStarter], shared with the MCP `agenthq_ticket_start`): the worker runs the ticket's role, provider
     * and model in its own `ticket/<short-id>-<slug>` worktree, and the ticket goes In Progress, its starter the session
     * this runs in (an agent's `AGENTHQ_SESSION_ID`). Prints `started <fullId> -> in_progress`, then the worker: session,
     * role and agent, harness, model, branch and worktree, then what the branch was cut from and any launch warning.
     *
     * A ticket already in progress prints the worker it has; a queued start says so: both exit 0, nothing new runs. A
     * held or refused start prints the daemon's reason and exits 1. NOT idempotent: a connection that drops after the
     * move was sent is reported, never re-sent.
     */
    private suspend fun startTicket(
        stream: FrameStream,
        parsed: ParsedArgs,
        out: Appendable,
        err: Appendable,
        getenv: (String) -> String?,
    ): Int {
        checkContract(stream, err)
        val ticket = resolveTicket(stream, parsed.args[1].trim(), boardProjectOf(parsed, getenv), err) ?: return 1
        val session = getenv(AccessPolicy.SESSION_ID_ENV)?.trim()?.takeIf { it.isNotEmpty() }
        return when (val outcome = TicketStarter.start(stream, ticket, session)) {
            is TicketStarter.Outcome.Started -> {
                out.appendLine("started ${outcome.ticket.id} -> ${outcome.ticket.status}")
                printTicketWorker(out, outcome.ticket, outcome.session)
                printStartNotes(out, outcome)
                0
            }
            is TicketStarter.Outcome.AlreadyInProgress -> {
                out.appendLine("already in progress ${outcome.ticket.id}: nothing new was started")
                printTicketWorker(out, outcome.ticket, outcome.session)
                0
            }
            is TicketStarter.Outcome.NotStarted -> reportNotStarted(outcome, out, err)
        }
    }

    /**
     * A start that ran nothing, as `ticket start` and `ticket move ... in_progress` both print it: a queued one says so
     * (exit 0, the board starts it later); a held or refused one prints the daemon's reason (exit 1).
     */
    private fun reportNotStarted(outcome: TicketStarter.Outcome.NotStarted, out: Appendable, err: Appendable): Int = when (outcome) {
        is TicketStarter.Outcome.Queued -> {
            val lane = (outcome.ticket.queuedFor ?: outcome.lane)?.let { " for $it" } ?: ""
            out.appendLine("queued ${outcome.ticket.id}$lane: every agent of its lane is busy; the board starts it when one frees up")
            0
        }
        is TicketStarter.Outcome.Held -> {
            err.appendLine(
                "error: ticket ${outcome.ticket.id} is held by open prerequisites" +
                    (outcome.detail?.let { " ($it)" } ?: "") + "; it starts once they are done",
            )
            1
        }
        is TicketStarter.Outcome.Refused -> {
            err.appendLine("error: ticket ${outcome.ticket.id} did not start: ${outcome.detail}")
            1
        }
    }

    /**
     * BUG-REGRESS-1: what a start's answer adds, as `ticket start` and `ticket move ... in_progress` both print it:
     * what the branch was cut from, then any launch warning.
     */
    private fun printStartNotes(out: Appendable, outcome: TicketStarter.Outcome.Started) {
        outcome.note?.let { out.appendLine("  note:     $it") }
        outcome.warning?.let { out.appendLine("  warning:  $it") }
    }

    /** The worker a started ticket runs, one labelled line per fact, as `ticket start` prints it. */
    private fun printTicketWorker(out: Appendable, ticket: Ticket, worker: SessionRecord?) {
        if (worker == null) {
            out.appendLine("  session:  ${ticket.sessionId ?: "(not listed by the daemon yet)"}")
            ticket.branch?.let { out.appendLine("  branch:   $it") }
            return
        }
        out.appendLine("  session:  ${worker.id}")
        out.appendLine("  role:     ${worker.role ?: "-"} (agent ${worker.agentId ?: "-"})")
        out.appendLine("  harness:  ${worker.provider}")
        out.appendLine("  model:    ${worker.model ?: "(the harness's default)"}")
        out.appendLine("  branch:   ${worker.worktreeBranch ?: ticket.branch ?: "-"}")
        out.appendLine("  worktree: ${worker.worktreePath ?: worker.workingDir}")
    }

    /** HARNESS-INSTALL-1: `harness-status [--check]`: which harness CLIs are installed / missing / outdated. */
    private fun harnessStatus(stream: FrameStream, parsed: ParsedArgs, out: Appendable, err: Appendable): Int = runBlocking {
        checkContract(stream, err)
        HarnessInstallClient.render(HarnessInstallClient.status(stream, parsed.check), out)
        0
    }

    /** ROGUE-1 (contract 1.120.0): `processes [--all]` lists; `processes reap <pid>|--all` reaps. Exit 1 when a reap is refused. */
    private fun processes(stream: FrameStream, parsed: ParsedArgs, out: Appendable, err: Appendable): Int = runBlocking {
        checkContract(stream, err)
        val req = ProcessesCli.requestFor(parsed.args, parsed.all)
        val answer = ProcessesCli.request(stream, req)
        out.append(ProcessesCli.render(answer, showAll = parsed.all && req.op == "list"))
        if (answer.refused != null) 1 else 0
    }

    /** HARNESS-INSTALL-1: `harness-install [id...] [--update]`: npm-install into the app prefix; exit 1 when any one failed. */
    private fun harnessInstall(stream: FrameStream, parsed: ParsedArgs, out: Appendable, err: Appendable): Int = runBlocking {
        checkContract(stream, err)
        val result = HarnessInstallClient.install(stream, parsed.args.map { it.trim() }, parsed.update)
        HarnessInstallClient.render(result, out)
        if (HarnessInstallClient.failed(result)) 1 else 0
    }

    /**
     * BUG-ASSIGN-1: `harnesses`: every harness (= provider id) a ticket may pin as its provider, with the models it may
     * pin as its model, read the same way as the MCP `agenthq_list_harnesses` ([HarnessCatalog]). One row per harness,
     * `id | default | models`, plus its default model, an unavailable CLI and the daemon's note when there is one.
     */
    private fun harnesses(stream: FrameStream, out: Appendable, err: Appendable): Int = runBlocking {
        checkContract(stream, err)
        val harnesses = HarnessCatalog.read(stream)
        out.appendLine(HARNESS_HEADER)
        for (harness in harnesses) {
            out.appendLine(
                buildString {
                    append(harness.id).append(" | ").append(if (harness.default) "yes" else "no").append(" | ")
                    append(harness.models?.joinToString(", ") ?: "(none listed: any model id its CLI accepts)")
                    harness.defaultModel?.let { append(" | default model: ").append(it) }
                    if (!harness.available) append(" | unavailable: ").append(harness.unavailableReason ?: "its CLI was not found")
                    harness.note?.let { append(" | note: ").append(it) }
                },
            )
        }
        out.appendLine()
        out.appendLine(HarnessCatalog.HOW_TO_PIN)
        0
    }

    /**
     * `ticket link <id|prefix> <otherId|prefix> <type>`: asks the daemon to link the first ticket to the
     * second as [type] (one of [TicketLinkType.ALL]) and prints `linked <fromId> <type> <toId>`.
     */
    private suspend fun linkTicket(
        stream: FrameStream,
        parsed: ParsedArgs,
        out: Appendable,
        err: Appendable,
        getenv: (String) -> String?,
    ): Int {
        checkContract(stream, err)
        val projectId = boardProjectOf(parsed, getenv)
        val fromId = resolveTicketId(stream, parsed.args[1].trim(), projectId, err) ?: return 1
        val toId = resolveTicketId(stream, parsed.args[2].trim(), projectId, err) ?: return 1
        val type = parsed.args[3].trim()
        val request = TicketLinkRequest(ticketId = fromId, relatedId = toId, type = type)
        stream.request(
            Envelope(Envelope.TICKET_LINK, json.encodeToString(TicketLinkRequest.serializer(), request)),
            answerType = Envelope.TICKET,
            what = "ticket_link",
            idempotent = false,
        )
        out.appendLine("linked $fromId $type $toId")
        return 0
    }


    /**
     * `ticket create --title <t> [--desc <d>] [--project <id>]`: saves a NEW ticket, which the daemon puts in
     * `todo`, and prints `created <8-char id>`, the prefix `ticket show` accepts. The project is `--project`,
     * else the agent's own ([boardProjectOf]); a blank one is sent as none. [validateArgs] has already refused a
     * missing or blank title before the socket opens.
     *
     * NOT idempotent: a request that drops after it was sent is refused, never re-sent, because a second copy
     * would be a second ticket.
     */
    private suspend fun createTicket(
        stream: FrameStream,
        parsed: ParsedArgs,
        out: Appendable,
        err: Appendable,
        getenv: (String) -> String?,
    ): Int {
        checkContract(stream, err)
        val title = parsed.title.orEmpty().trim()
        val projectId = boardProjectOf(parsed, getenv).takeIf { it.isNotBlank() }
        val epicId = parsed.epic?.let { prefix -> resolveEpicId(stream, prefix.trim(), projectId, err) ?: return 2 }
        // IMG-AGENT-1: every --image is checked, then uploaded (purpose ticket), before the ticket is saved.
        val images = parsed.images.takeIf { it.isNotEmpty() }?.let { attachImages(stream, projectId, it, err) ?: return 1 }
        val payload = json.encodeToString(
            TicketSave.serializer(),
            TicketSave(
                status = parsed.status?.trim()?.lowercase(),
                title = title,
                description = parsed.description,
                projectId = projectId,
                epicId = epicId,
                agentId = roleAgentId(parsed.role),
                provider = parsed.provider?.trim(),
                model = parsed.model?.trim(),
                weight = parsed.weight,
                images = images,
            ),
        )
        val answer = stream.request(
            Envelope(Envelope.TICKET_SAVE, payload),
            answerType = Envelope.TICKET,
            what = "ticket_save",
            idempotent = false,
        )
        val ticket = try {
            json.decodeFromString(Ticket.serializer(), answer.data)
        } catch (e: Exception) {
            err.appendLine("error: malformed ticket answer from the daemon: ${e.message}")
            return 1
        }
        out.appendLine("created ${shortTicketId(ticket.id)}" + if (images != null) " (${ticket.images.size} image(s))" else "")
        return 0
    }

    /**
     * IMG-AGENT-1: [paths] as the relPaths a ticket of [projectId] stores, each local file uploaded
     * ([TicketImageFiles]); null after printing why, with nothing uploaded and nothing saved.
     */
    private suspend fun attachImages(stream: FrameStream, projectId: String?, paths: List<String>, err: Appendable): List<String>? =
        try {
            TicketImageFiles.upload(stream, projectId, paths)
        } catch (e: IllegalArgumentException) {
            err.appendLine("error: ${e.message}; nothing was saved")
            null
        }

    /**
     * BUG-E2E-1: `ticket update <id|prefix> [--title] [--desc] [--role] [--provider] [--model] [--epic]`: edits ONE
     * ticket through the same `ticket_save` the UI ticket editor uses, and prints `updated <8-char id>`. A flag left out
     * keeps the stored value. The daemon refuses an unknown provider, or a model that provider lists and this is not,
     * and the CLI prints that refusal; nothing is coerced.
     *
     * NOT idempotent, like `create`: a re-sent edit could land over a newer one.
     */
    private suspend fun updateTicket(
        stream: FrameStream,
        parsed: ParsedArgs,
        out: Appendable,
        err: Appendable,
        getenv: (String) -> String?,
    ): Int {
        checkContract(stream, err)
        val projectId = boardProjectOf(parsed, getenv).takeIf { it.isNotBlank() }
        val fullId = resolveTicketId(stream, parsed.args[1].trim(), projectId.orEmpty(), err) ?: return 1
        val epicId = parsed.epic?.let { prefix -> resolveEpicId(stream, prefix.trim(), projectId, err) ?: return 2 }
        val images = if (parsed.images.isEmpty() && parsed.removeImages.isEmpty()) null else editImages(stream, fullId, parsed, err) ?: return 1
        val payload = json.encodeToString(
            TicketSave.serializer(),
            TicketSave(
                id = fullId,
                title = parsed.title?.trim(),
                description = parsed.description,
                epicId = epicId,
                agentId = roleAgentId(parsed.role),
                provider = parsed.provider?.trim(),
                model = parsed.model?.trim(),
                weight = parsed.weight,
                images = images,
            ),
        )
        val answer = stream.request(
            Envelope(Envelope.TICKET_SAVE, payload),
            answerType = Envelope.TICKET,
            what = "ticket_save",
            idempotent = false,
        )
        val ticket = try {
            json.decodeFromString(Ticket.serializer(), answer.data)
        } catch (e: Exception) {
            err.appendLine("error: malformed ticket answer from the daemon: ${e.message}")
            return 1
        }
        // BUG-ASSIGN-1: say what was saved, read off the daemon's answer: the pins with their stored values.
        val saved = buildList {
            if (parsed.title != null) add("title")
            if (parsed.description != null) add("description")
            if (epicId != null) add("epic ${ticket.epicId?.let(::shortTicketId) ?: "-"}")
            if (parsed.role != null) add("role ${ticket.agentId?.removePrefix("role:") ?: "-"}")
            if (parsed.provider != null) add("provider ${ticket.provider ?: "-"}")
            if (parsed.model != null) add("model ${ticket.model ?: "-"}")
            if (parsed.weight != null) add("weight ${ticket.weight ?: "-"}")
            if (images != null) add("${ticket.images.size} image(s)")
        }
        out.appendLine("updated ${shortTicketId(ticket.id)} (saved: ${saved.joinToString(", ")})")
        return 0
    }

    /**
     * IMG-AGENT-1: the image list `ticket update` saves: the stored one, less each `--remove-image` (named by its
     * stored path, file name or original file, [TicketImageFiles.matches]), plus each `--image` uploaded. Null after
     * printing why: a removal that names no attached image, or a refused file; nothing is uploaded or saved then.
     */
    private suspend fun editImages(stream: FrameStream, fullId: String, parsed: ParsedArgs, err: Appendable): List<String>? {
        val ticket = json.decodeFromString(
            Ticket.serializer(),
            stream.request(Envelope(Envelope.TICKET_GET, fullId), Envelope.TICKET, "ticket_get", idempotent = true).data,
        )
        val unknown = parsed.removeImages.firstOrNull { given -> ticket.images.none { TicketImageFiles.matches(it, given) } }
        if (unknown != null) {
            err.appendLine(
                "error: ticket ${shortTicketId(fullId)} has no image '${unknown.take(200)}' " +
                    "(see: agenthq ticket show ${shortTicketId(fullId)}); nothing was saved",
            )
            return null
        }
        val kept = ticket.images.filterNot { stored -> parsed.removeImages.any { TicketImageFiles.matches(stored, it) } }
        val added = attachImages(stream, ticket.projectId, parsed.images, err) ?: return null
        return (kept + added).distinct()
    }

    /**
     * BUG-E2E-1: `--role <id>` as the ticket's `agentId`, in the `role:<id>` form the ticket editor stores for "any
     * agent of this role". A value already written `role:<id>` is taken as is; null when the flag was not given.
     */
    private fun roleAgentId(role: String?): String? =
        role?.trim()?.lowercase()?.let { if (it.startsWith("role:")) it else "role:$it" }

    /**
     * CLI-T3 patch (675e7c4c): resolves an epic prefix to ONE full epic id among [projectId]'s epics, the same
     * rule `ticket show` applies to tickets. `epic_list` matches the project EXACTLY, so an epic of another
     * project never matches. Null after printing why: no project to search in, no match, or several matches.
     */
    private suspend fun resolveEpicId(stream: FrameStream, prefix: String, projectId: String?, err: Appendable): String? {
        if (projectId == null) {
            err.appendLine("error: --epic needs a project to find its epic in: pass --project <id>")
            return null
        }
        val epics = fetchEpics(stream, projectId, err) ?: return null
        return pickEpic(epics, prefix, projectId, err)?.id
    }

    /** The epics of [projectId] (`epic_list` matches the project exactly), or null after printing why. */
    private suspend fun fetchEpics(stream: FrameStream, projectId: String, err: Appendable): List<Epic>? {
        val answer = stream.request(
            Envelope(Envelope.EPIC_LIST, projectId),
            answerType = Envelope.EPICS,
            what = "epic_list",
            idempotent = true,
        )
        return try {
            json.decodeFromString(ListSerializer(Epic.serializer()), answer.data)
        } catch (e: Exception) {
            err.appendLine("error: malformed epics answer from the daemon: ${e.message}")
            null
        }
    }

    /**
     * The ONE epic of [epics] whose id ends with [prefix], or null after printing why: no match, or several.
     * Shared by `ticket create --epic` and `epic show`, so both refuse an ambiguous prefix the same way.
     */
    private fun pickEpic(epics: List<Epic>, prefix: String, projectId: String, err: Appendable): Epic? {
        val matches = epics.filter { it.id.endsWith(prefix) }
        return when (matches.size) {
            0 -> {
                err.appendLine("error: no epic matches '$prefix' in project $projectId")
                null
            }
            1 -> matches.single()
            else -> {
                err.appendLine("error: epic id '$prefix' is ambiguous; use a longer id or the full id:")
                for (epic in matches) err.appendLine("  ${epic.id} | ${epic.name}")
                null
            }
        }
    }

    /**
     * BASE-1a: `project base <projectId> [<branch>|--clear]` reads, sets or clears the branch new ticket starts in the
     * project cut from; `project branches <projectId>` lists the project repo's local branches (`*` = checked out).
     * [validateArgs] has refused everything else.
     */
    private fun project(stream: FrameStream, parsed: ParsedArgs, out: Appendable, err: Appendable): Int = runBlocking {
        checkContract(stream, err)
        if (parsed.args[0] == "config") return@runBlocking projectConfig(stream, parsed.args, out, err)
        if (parsed.args[0] == "routing") return@runBlocking projectRouting(stream, parsed.args[1].trim(), parsed.file, parsed.clear, out, err)
        val projectId = parsed.args[1].trim()
        when (parsed.args[0]) {
            "branches" -> projectBranches(stream, projectId, out, err)
            "rename" -> projectRename(stream, projectId, parsed.args[2], out, err)
            else -> projectBase(stream, projectId, parsed.args.getOrNull(2)?.trim(), parsed.clear, out, err)
        }
    }

    /**
     * LANE-CAPS: `project config get <projectId>` prints each role's cap on concurrent agents; `project config set
     * <projectId> <role> <n>` sets one (n >= 1; 1 is the default). Lowering a cap never stops a running agent.
     */
    private suspend fun projectConfig(stream: FrameStream, args: List<String>, out: Appendable, err: Appendable): Int {
        val projectId = args[2].trim()
        val autoRemove = if (args[1] == "set" && args[3].trim() == AUTO_REMOVE_SETTING) onOff(args[4]) else null
        val experiment = if (args[1] == "set" && args[3].trim().startsWith(EXPERIMENT_PREFIX)) mapOf(args[3].trim().removePrefix(EXPERIMENT_PREFIX) to args[4].trim()) else null
        val caps = if (args[1] == "set" && autoRemove == null && experiment == null) mapOf(args[3].trim() to args[4].trim().toInt()) else null
        val payload = json.encodeToString(
            com.superiorsoftllc.agenthq.protocol.ProjectConfigRequest.serializer(),
            com.superiorsoftllc.agenthq.protocol.ProjectConfigRequest(projectId = projectId, laneCaps = caps, autoRemoveDoneWorkers = autoRemove, experiments = experiment),
        )
        val answer = stream.request(Envelope(Envelope.PROJECT_CONFIG, payload), Envelope.PROJECT, "project_config", idempotent = true)
        val project = try {
            json.decodeFromString(Project.serializer(), answer.data)
        } catch (e: Exception) {
            err.appendLine("error: malformed project answer from the daemon: ${e.message}")
            return 1
        }
        out.appendLine("project ${project.id} lane caps (max concurrent agents per role; unlisted = 1):")
        if (project.laneCaps.isEmpty()) out.appendLine("  (none set)")
        for ((role, cap) in project.laneCaps) out.appendLine("  $role $cap")
        out.appendLine("$AUTO_REMOVE_SETTING: ${if (project.autoRemoveDoneWorkers) "on" else "off"} (a done ticket's worker session is ended and removed)")
        for ((k, v) in project.experiments) out.appendLine("$EXPERIMENT_PREFIX$k: $v (experimental)")
        return 0
    }

    /**
     * ROUTING-1 (contract 1.128.0): `project routing <projectId>` prints the project's routing rules in order;
     * `--file <rules.json>` REPLACES them with the JSON array in the file (each `{name, weights?, ticketTypes?, role?,
     * provider, model, effort?, setRole?}`, the last one a default row with no match field); `--clear` removes them all.
     */
    private suspend fun projectRouting(stream: FrameStream, projectId: String, file: String?, clear: Boolean, out: Appendable, err: Appendable): Int {
        val rules: List<com.superiorsoftllc.agenthq.protocol.RoutingRule>? = when {
            clear -> emptyList()
            file != null -> try {
                json.decodeFromString(
                    kotlinx.serialization.builtins.ListSerializer(com.superiorsoftllc.agenthq.protocol.RoutingRule.serializer()),
                    File(file).readText(),
                )
            } catch (e: Exception) {
                err.appendLine("error: $file is not a JSON array of routing rules: ${e.message?.take(200)}")
                return 2
            }
            else -> null
        }
        val payload = json.encodeToString(
            com.superiorsoftllc.agenthq.protocol.ProjectConfigRequest.serializer(),
            com.superiorsoftllc.agenthq.protocol.ProjectConfigRequest(projectId = projectId, routingRules = rules),
        )
        val answer = stream.request(Envelope(Envelope.PROJECT_CONFIG, payload), Envelope.PROJECT, "project_config", idempotent = true)
        val project = try {
            json.decodeFromString(Project.serializer(), answer.data)
        } catch (e: Exception) {
            err.appendLine("error: malformed project answer from the daemon: ${e.message}")
            return 1
        }
        out.append(routingRulesText(project))
        return 0
    }

    /** ROUTING-1: the rule table `project routing` prints; first match wins, the last row is the default. */
    internal fun routingRulesText(project: Project): String = buildString {
        appendLine("project ${project.id} routing rules (first match wins; a ticket's own provider/model pin always wins):")
        if (project.routingRules.isEmpty()) {
            appendLine("  (none: ticket pin > profile / role binding > tickets.weightExecutors > default)")
            return@buildString
        }
        project.routingRules.forEachIndexed { i, r ->
            val match = if (com.superiorsoftllc.agenthq.backlog.RoutingRules.isDefault(r)) "default" else listOfNotNull(
                "weight ${com.superiorsoftllc.agenthq.backlog.RoutingRules.weightLabel(r.weights)}".takeIf { r.weights.isNotEmpty() },
                "type ${r.ticketTypes.joinToString(",")}".takeIf { r.ticketTypes.isNotEmpty() },
                r.role?.let { "role $it" },
            ).joinToString(" + ")
            val result = listOfNotNull("${r.provider}/${r.model}", r.effort?.let { "effort $it" }, r.setRole?.let { "as role $it" }).joinToString(", ")
            appendLine("  ${i + 1}. ${r.name} | $match -> $result")
        }
    }

    /** BASICS-KILL-1 (1.118.0): the `project config set` key for [Project.autoRemoveDoneWorkers]. */
    private const val AUTO_REMOVE_SETTING = "auto-remove-done-workers"

    /** EXPERIMENTS-1 (1.126.0): `project config set <id> experiment.<key> on|off`. */
    private const val EXPERIMENT_PREFIX = "experiment."

    private fun onOff(value: String): Boolean? = when (value.trim().lowercase()) {
        "on", "true" -> true
        "off", "false" -> false
        else -> null
    }

    private suspend fun projectBase(
        stream: FrameStream,
        projectId: String,
        branch: String?,
        clear: Boolean,
        out: Appendable,
        err: Appendable,
    ): Int {
        val payload = json.encodeToString(
            ProjectBaseRequest.serializer(),
            ProjectBaseRequest(projectId = projectId, branch = branch, clear = clear),
        )
        // A set or clear writes the same value again on a re-send, so it is safe to repeat after a dropped link.
        val answer = stream.request(Envelope(Envelope.PROJECT_BASE, payload), Envelope.PROJECT, "project_base", idempotent = true)
        val project = try {
            json.decodeFromString(Project.serializer(), answer.data)
        } catch (e: Exception) {
            err.appendLine("error: malformed project answer from the daemon: ${e.message}")
            return 1
        }
        val fallback = "new tickets cut from the daemon's ${com.superiorsoftllc.agenthq.worktree.TicketBaseRef.ENV}, else their start is refused"
        out.appendLine(
            when {
                project.baseBranch.isNotEmpty() -> "base ${project.baseBranch}"
                clear -> "base cleared: $fallback"
                else -> "base (none): $fallback"
            },
        )
        return 0
    }

    /** `project rename <id> <name>`: an edit `project_save` carrying only the id and the new name; the id never changes. */
    private suspend fun projectRename(stream: FrameStream, projectId: String, name: String, out: Appendable, err: Appendable): Int {
        val payload = json.encodeToString(
            com.superiorsoftllc.agenthq.protocol.ProjectSave.serializer(),
            com.superiorsoftllc.agenthq.protocol.ProjectSave(id = projectId, name = name),
        )
        val answer = stream.request(Envelope(Envelope.PROJECT_SAVE, payload), Envelope.PROJECT, "project_save", idempotent = true)
        val project = try {
            json.decodeFromString(Project.serializer(), answer.data)
        } catch (e: Exception) {
            err.appendLine("error: malformed project answer from the daemon: ${e.message}")
            return 1
        }
        out.appendLine("project ${project.id} renamed to ${project.name}")
        return 0
    }

    private suspend fun projectBranches(stream: FrameStream, projectId: String, out: Appendable, err: Appendable): Int {
        // Which repository the list comes from: the project's own directory (`project_git_branches` is rooted there).
        val project = json.decodeFromString(
            Project.serializer(),
            stream.request(Envelope(Envelope.PROJECT_GET, projectId), Envelope.PROJECT, "project_get", idempotent = true).data,
        )
        val payload = json.encodeToString(
            com.superiorsoftllc.agenthq.projectgit.ProjectGitBranchesRequest.serializer(),
            com.superiorsoftllc.agenthq.projectgit.ProjectGitBranchesRequest(projectId),
        )
        val answer = stream.request(
            Envelope(Envelope.PROJECT_GIT_BRANCHES, payload), Envelope.PROJECT_GIT_BRANCH_LIST, "project_git_branches", idempotent = true,
        )
        val list = json.decodeFromString(com.superiorsoftllc.agenthq.projectgit.ProjectGitBranchList.serializer(), answer.data)
        if (!list.available) {
            err.appendLine("error: no branch list for project $projectId: ${list.reason.ifBlank { "unavailable" }}")
            return 1
        }
        out.appendLine("repo ${project.directory}")
        list.branches.forEach { out.appendLine((if (it.name == list.current) "* " else "  ") + it.name) }
        if (list.truncated) out.appendLine("(truncated)")
        return 0
    }

    /**
     * IMG-1: `image upload <projectId> <file>` sends the file to the daemon (`image_upload`), which checks it and stores
     * it in the project's `.agenthq/uploads/`; prints the stored absolute path, the thing an agent is handed.
     * The size and type checks are the DAEMON's, so the CLI refuses nothing a remote client could still send.
     */
    private fun image(stream: FrameStream, parsed: ParsedArgs, out: Appendable, err: Appendable): Int = runBlocking {
        checkContract(stream, err)
        val file = File(parsed.args[2])
        val mime = when (file.extension.lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            else -> "" // the daemon takes the type from the bytes
        }
        val payload = json.encodeToString(
            com.superiorsoftllc.agenthq.protocol.ImageUpload.serializer(),
            com.superiorsoftllc.agenthq.protocol.ImageUpload(
                projectId = parsed.args[1].trim(),
                purpose = parsed.purpose ?: com.superiorsoftllc.agenthq.protocol.ImageUpload.PURPOSE_TICKET,
                fileName = file.name,
                mimeType = mime,
                base64 = java.util.Base64.getEncoder().encodeToString(file.readBytes()),
            ),
        )
        // Content-addressed: the same bytes land at the same path, so a re-send after a dropped link is harmless.
        val answer = stream.request(Envelope(Envelope.IMAGE_UPLOAD, payload), Envelope.IMAGE, "image_upload", idempotent = true)
        val stored = try {
            json.decodeFromString(com.superiorsoftllc.agenthq.protocol.ImageStored.serializer(), answer.data)
        } catch (e: Exception) {
            err.appendLine("error: malformed image answer from the daemon: ${e.message}")
            return@runBlocking 1
        }
        out.appendLine(stored.path)
        0
    }

    /** `epic <show|create|base> ...`: dispatches on the subcommand; [validateArgs] has refused everything else. */
    private fun epic(
        stream: FrameStream,
        parsed: ParsedArgs,
        out: Appendable,
        err: Appendable,
        getenv: (String) -> String?,
    ): Int = runBlocking {
        when (parsed.args.firstOrNull()) {
            "create" -> createEpic(stream, parsed, out, err, getenv)
            "base" -> epicBase(stream, parsed.args[1].trim(), parsed.args.getOrNull(2)?.trim(), parsed.clear, out, err)
            else -> showEpic(stream, parsed, out, err, getenv)
        }
    }

    /**
     * BASE-1c: `epic base <epicId> [<branch>|--clear]` reads, sets or clears the branch new tickets in the epic cut
     * from, overriding the project's base. The daemon checks a branch resolves before storing it.
     */
    private suspend fun epicBase(
        stream: FrameStream,
        epicId: String,
        branch: String?,
        clear: Boolean,
        out: Appendable,
        err: Appendable,
    ): Int {
        checkContract(stream, err)
        val payload = json.encodeToString(
            com.superiorsoftllc.agenthq.epics.EpicBaseRequest.serializer(),
            com.superiorsoftllc.agenthq.epics.EpicBaseRequest(epicId = epicId, branch = branch, clear = clear),
        )
        // A set or clear writes the same value again on a re-send, so it is safe to repeat after a dropped link.
        val answer = stream.request(Envelope(Envelope.EPIC_BASE, payload), Envelope.EPIC, "epic_base", idempotent = true)
        val epic = try {
            json.decodeFromString(Epic.serializer(), answer.data)
        } catch (e: Exception) {
            err.appendLine("error: malformed epic answer from the daemon: ${e.message}")
            return 1
        }
        val fallback = "new tickets in the epic cut from the project's base"
        out.appendLine(
            when {
                epic.baseBranch.isNotEmpty() -> "base ${epic.baseBranch}"
                clear -> "base cleared: $fallback"
                else -> "base (none): $fallback"
            },
        )
        return 0
    }

    /**
     * `epic create --title <t> [--desc <d>] [--project <id>]`: saves a new epic in the project and prints
     * `created <id>`. [validateArgs] has refused a blank title and a missing project before this runs.
     */
    private suspend fun createEpic(
        stream: FrameStream,
        parsed: ParsedArgs,
        out: Appendable,
        err: Appendable,
        getenv: (String) -> String?,
    ): Int {
        checkContract(stream, err)
        val payload = json.encodeToString(
            EpicSave.serializer(),
            EpicSave(
                projectId = projectIdOf(parsed, getenv) ?: return 2,
                name = parsed.title.orEmpty().trim(),
                goal = parsed.description.orEmpty(),
            ),
        )
        val answer = stream.request(
            Envelope(Envelope.EPIC_SAVE, payload),
            answerType = Envelope.EPIC,
            what = "epic_save",
            idempotent = false,
        )
        val epic = try {
            json.decodeFromString(Epic.serializer(), answer.data)
        } catch (e: Exception) {
            err.appendLine("error: malformed epic answer from the daemon: ${e.message}")
            return 1
        }
        out.appendLine("created ${shortTicketId(epic.id)}")
        return 0
    }

    /**
     * `epic show <id|prefix> [--project <id>]`: the epic's id and title, then its TICKETS (E1-e) as the tickets
     * board prints them, in board order. The daemon filters on the ticket's own `epicId` (`ticket_list`, E1-b),
     * so another epic's tickets and loose tickets of the same project never appear here.
     */
    private suspend fun showEpic(
        stream: FrameStream,
        parsed: ParsedArgs,
        out: Appendable,
        err: Appendable,
        getenv: (String) -> String?,
    ): Int {
        checkContract(stream, err)
        // Both non-null: [validateArgs] refused a missing id and a missing project before the socket opened.
        val projectId = projectIdOf(parsed, getenv) ?: return 2
        val prefix = parsed.args[1].trim()
        val epics = fetchEpics(stream, projectId, err) ?: return 1
        val epic = pickEpic(epics, prefix, projectId, err) ?: return 1
        val tickets = fetchBoard(stream, projectId, err, epic.id) ?: return 1

        out.appendLine("ID: ${epic.id}")
        out.appendLine("TITLE: ${epic.name}")
        printTicketRows(tickets, out, empty = "No tickets in epic ${shortTicketId(epic.id)}.")
        return 0
    }

    /**
     * Prints ONE ticket in full: the title, the status and who holds it, its links, and the WHOLE
     * description verbatim. `tickets` prints one line per ticket and cannot carry a multi-line brief;
     * this is the command that does.
     */
    private suspend fun showTicket(
        stream: FrameStream,
        parsed: ParsedArgs,
        out: Appendable,
        err: Appendable,
        getenv: (String) -> String?,
    ): Int {
        checkContract(stream, err)
        // Non-blank: [validateArgs] refuses a missing id before the socket opens.
        val prefix = parsed.args[1].trim()
        val fullId = resolveTicketId(stream, prefix, boardProjectOf(parsed, getenv), err) ?: return 1
        val answer = stream.request(
            Envelope(Envelope.TICKET_GET, fullId),
            answerType = Envelope.TICKET,
            what = "ticket_get",
            idempotent = true,
        )
        val ticket = try {
            json.decodeFromString(Ticket.serializer(), answer.data)
        } catch (e: Exception) {
            err.appendLine("error: malformed ticket answer from the daemon: ${e.message}")
            return 1
        }

        out.appendLine("ID: ${ticket.id}")
        out.appendLine("TITLE: ${ticket.title}")
        out.appendLine("STATUS: ${ticket.status}")
        out.appendLine("AGENT: ${ticket.agentId ?: "-"}")
        // BUG-ASSIGN-1: the harness and model the ticket pins, so an agent sees that its change was saved.
        out.appendLine("PROVIDER: ${ticket.provider ?: "-"}")
        out.appendLine("MODEL: ${ticket.model ?: "-"}")
        // TICKET-WEIGHT: the weight, and which provider / model will run and why (source: ticket, profile, role, weight or default).
        // ROUTING-1: an unweighted ticket is flagged so the PM sets a weight before it starts.
        out.appendLine("WEIGHT: ${ticket.weight ?: "- (unweighted: set one with ticket update --weight 1-5 before start)"}")
        ticket.effectiveExecutor?.let { e ->
            out.appendLine("EFFECTIVE: ${e.provider?.value ?: "-"} (${e.provider?.source ?: "-"}) / ${e.model?.value ?: "-"} (${e.model?.source ?: "-"})" +
                (e.rule?.let { " via rule '$it'" } ?: ""))
        }
        out.appendLine("PROJECT: ${ticket.projectId ?: "-"}")
        out.appendLine("EPIC: ${ticket.epicId ?: "-"}")
        out.appendLine("BRANCH: ${ticket.branch ?: "-"}")
        out.appendLine("BASE: ${ticket.base ?: "-"}")
        if (ticket.links.isEmpty()) {
            out.appendLine("LINKS: none")
        } else {
            out.appendLine("LINKS:")
            for (link in ticket.links) out.appendLine("  ${link.type} ${link.ticketId}")
        }
        // IMG-AGENT-1: each attached image as a path the reader can open: absolute under the project's directory.
        if (ticket.images.isEmpty()) {
            out.appendLine("IMAGES: none")
        } else {
            val directory = ticket.projectId?.let { id ->
                runCatching {
                    json.decodeFromString(
                        Project.serializer(),
                        stream.request(Envelope(Envelope.PROJECT_GET, id), Envelope.PROJECT, "project_get", idempotent = true).data,
                    ).directory
                }.getOrNull()?.takeIf { it.isNotBlank() }
            }
            out.appendLine("IMAGES:")
            for (image in ticket.images) {
                out.appendLine("  " + (directory?.let { File(it, image).absoluteFile.normalize().path } ?: image))
            }
        }
        out.appendLine("DESCRIPTION:")
        out.appendLine(ticket.description.ifBlank { "(none)" })
        return 0
    }

    private fun agents(stream: FrameStream, parsed: ParsedArgs, out: Appendable, err: Appendable, getenv: (String) -> String?): Int =
        runBlocking {
            checkContract(stream, err)
            if (parsed.live && parsed.profiles) {
                err.appendLine("error: --live and --profiles are exclusive; omit both for the combined list")
                return@runBlocking 1
            }
            if (parsed.live) return@runBlocking agentsLive(stream, parsed, out, err, getenv)
            val answer = stream.request(
                Envelope(Envelope.AGENT_LIST, ""),
                answerType = Envelope.AGENTS,
                what = "agent_list",
                idempotent = true,
            )
            val profiles = try {
                json.decodeFromString(ListSerializer(AgentProfile.serializer()), answer.data)
            } catch (e: Exception) {
                err.appendLine("error: malformed agents answer from the daemon: ${e.message}")
                return@runBlocking 1
            }
            val projectId = agentsProjectId(parsed, getenv)
            // Without a project there is no live roster to merge: the profile table, exactly as `--profiles` prints it.
            if (parsed.profiles || projectId.isBlank()) {
                if (profiles.isEmpty()) {
                    out.appendLine("No agent profiles.")
                } else {
                    out.appendLine("ID | NAME | ROLE | MODEL")
                    for (profile in profiles) {
                        out.appendLine("${profile.id} | ${profile.name} | ${profile.role} | ${profile.model ?: "-"}")
                    }
                }
                return@runBlocking 0
            }

            val rows = fetchRoster(stream, projectId, err) ?: return@runBlocking 1
            val liveIds = rows.map { it.agentId }.toSet()
            val idle = profiles.filter { it.id !in liveIds && (it.projectId == null || it.projectId == projectId) }
            if (rows.isEmpty() && idle.isEmpty()) {
                out.appendLine("No agents or profiles.")
            } else {
                out.appendLine("KIND | ID | NAME | ROLE | STATUS | DETAIL")
                for (row in rows) {
                    out.appendLine("agent | ${row.agentId} | ${row.name} | ${row.role} | ${row.status} | ${row.title ?: "-"}")
                }
                for (profile in idle) {
                    out.appendLine("profile | ${profile.id} | ${profile.name} | ${profile.role} | - | ${profile.model ?: "-"}")
                }
            }
            0
        }

    private fun agentsProjectId(parsed: ParsedArgs, getenv: (String) -> String?): String =
        parsed.project?.trim() ?: getenv(AccessPolicy.PROJECT_ID_ENV)?.takeIf { it.isNotBlank() } ?: ""

    private suspend fun fetchRoster(stream: FrameStream, projectId: String, err: Appendable): List<RosterRow>? {
        val answer = stream.request(
            Envelope(Envelope.AGENT_ROSTER, json.encodeToString(RosterRequest.serializer(), RosterRequest(projectId))),
            answerType = Envelope.ROSTER,
            what = "agent_roster",
            idempotent = true,
        )
        return try {
            json.decodeFromString(ListSerializer(RosterRow.serializer()), answer.data)
        } catch (e: Exception) {
            err.appendLine("error: malformed roster answer from the daemon: ${e.message}")
            null
        }
    }

    /**
     * `agents --live --project <projectId>`: the live, project-scoped roster. Sends `agent_roster` and
     * prints the `roster` rows the daemon answers with. A blank project id is refused by the daemon
     * and surfaces through [FrameStream.request] as a clean failure (the `error` frame's message),
     * never a crash.
     */
    private suspend fun agentsLive(stream: FrameStream, parsed: ParsedArgs, out: Appendable, err: Appendable, getenv: (String) -> String?): Int {
        val rows = fetchRoster(stream, agentsProjectId(parsed, getenv), err) ?: return 1

        if (rows.isEmpty()) {
            out.appendLine("No agents here.")
        } else {
            out.appendLine("ID | NAME | ROLE | STATUS | TITLE | PENDING")
            for (row in rows) {
                val title = row.title ?: "-"
                out.appendLine("${row.agentId} | ${row.name} | ${row.role} | ${row.status} | $title | ${row.pending}")
            }
        }
        return 0
    }

    // ------------------------------------------------------------------------
    // send (MAIL-2)
    // ------------------------------------------------------------------------

    /**
     * `send <agentId|role> "<message>" [--reply-to <msgId>]`: a message to another agent's console.
     *
     * WHO IS SENDING is the part that is easy to get wrong, and it is not decided here. The daemon
     * fills a BLANK `from` from the session the frame names (`actor = envelope.sessionId ?: active`),
     * so this command names the sender on the ENVELOPE -- [AccessPolicy.SESSION_ID_ENV], the variable
     * the daemon exports into a spawned agent -- and leaves the payload's `from` empty. That is also
     * what role resolution reads: `send pm` resolves to the pm of the SENDER's project
     * (`MailRouter.resolveRecipient`), and the sender's project comes from that session. A person at
     * a terminal has no session, so the mailbox's own non-session handle ([HUMAN_SENDER]) is used
     * instead; a message from it still reaches a session id or an agent id, while a ROLE cannot
     * resolve without a project.
     *
     * WHY THE ANSWER IS THE LAST FRAME OF A BURST, AND NOT THE FIRST. `mail` is not only this
     * command's answer: the daemon BROADCASTS a `mail` frame for every mailbox event ([broadcastMail]),
     * and a `mail_send` provokes at least two of them before the daemon replies to it -- the message
     * being stored (state QUEUED, before any delivery attempt) and, when `--reply-to` is used, the
     * message being ANSWERED, which is broadcast FIRST of all. So the first `mail` frame a client
     * sees is not even necessarily its own message, and on a delivery failure a first-frame reader
     * would report the stale QUEUED snapshot as success. The daemon's reply is the LAST frame -- it
     * is written once the whole request, broadcasts and delivery attempt included, has been handled
     * -- and [FrameStream.requestLastOf] is how that is known without a sleep.
     *
     * A role nobody in this project holds is not a refusal: the message is stored and then marked
     * UNDELIVERABLE ("no pm agent in this project"), which is exit 1 with that reason. Only an
     * accepted, present message is "sent", and the id printed is the one the daemon stored -- what
     * `mail_read`, `mail_thread` and `--reply-to` take.
     */
    private fun send(
        stream: FrameStream,
        parsed: ParsedArgs,
        out: Appendable,
        err: Appendable,
        getenv: (String) -> String?,
    ): Int = runBlocking {
        checkContract(stream, err)
        // Non-empty here: [validateArgs] refuses a missing recipient or message before the socket opens.
        val to = parsed.args.first().trim()
        val body = parsed.args.drop(1).joinToString(" ").trim()
        // MAIL-FOOTER-1: cmd.exe does not treat '...' as quotes, so `send pm 'two words'` arrives as two
        // arguments that still carry the quotes. It is sent as typed; the warning says how to quote next time.
        if (body.length > 1 && body.startsWith('\'') && body.endsWith('\'')) {
            err.appendLine("warning: the message is wrapped in '...'; cmd.exe keeps those quotes, use \"double quotes\"")
        }
        val session = getenv(AccessPolicy.SESSION_ID_ENV)?.trim()?.takeIf { it.isNotEmpty() }
        // Who the daemon will record as the sender, and therefore the key this command reads back: the
        // session it was told to speak as, else the mailbox's non-session handle.
        val sender = session ?: HUMAN_SENDER

        // MAIL-FOOTER-1: `inbox` prints the 8-char id, so that is what gets pasted into --reply-to, and the
        // daemon only knows FULL ids ("unknown replyTo"). A short one is resolved against MY inbox, the way
        // `read` resolves it; a full id goes through as typed, so replying to any message still works.
        val replyTo = parsed.replyTo?.trim()?.let { id ->
            if (id.length >= FULL_MESSAGE_ID_LENGTH) return@let id
            val matches = (fetchInbox(stream, sender, session, err) ?: return@runBlocking 1)
                .filter { it.id.startsWith(id) }
            when (matches.size) {
                1 -> matches.single().id
                0 -> {
                    err.appendLine(
                        "error: no message '$id' in my inbox to reply to; --reply-to takes an id from " +
                            "`agenthq inbox`, or the full id from the message",
                    )
                    return@runBlocking 1
                }
                else -> {
                    err.appendLine(
                        "error: message id '$id' is ambiguous: " + matches.joinToString(", ") { it.id.take(8) },
                    )
                    return@runBlocking 1
                }
            }
        }

        val request = MailSendRequest(
            from = if (session == null) HUMAN_SENDER else "",
            to = to,
            body = body,
            replyTo = replyTo,
            allowCrossProject = parsed.crossProject,
        )
        // NOT idempotent: if the connection dropped after the daemon received this, a second copy
        // would be a SECOND message (and a second debit against the thread's ceiling). The drop is
        // reported instead, and the caller decides whether to say it again.
        val answer = stream.requestLastOf(
            Envelope(Envelope.MAIL_SEND, json.encodeToString(MailSendRequest.serializer(), request), session),
            answerTypes = setOf(Envelope.MAIL, Envelope.MAIL_REFUSED),
            fence = Envelope(Envelope.MAIL_OUTBOX, sender),
            fenceType = Envelope.MAILS,
            what = "mail_send",
            dropWarning = MAIL_RESEND_RISK,
        )
        if (answer.type == Envelope.MAIL_REFUSED) {
            // The daemon's reason, verbatim: it names the boundary that was hit.
            val refusal = runCatching { json.decodeFromString(MailRefused.serializer(), answer.data) }.getOrNull()
            error(refusal?.reason ?: "mail_send refused: ${answer.data}")
        }

        val message = try {
            json.decodeFromString(Message.serializer(), answer.data)
        } catch (e: Exception) {
            err.appendLine("error: malformed mail answer from the daemon: ${e.message}")
            return@runBlocking 1
        }
        if (message.failed) {
            // Stored, then the delivery failed for good. Saying "sent" here would be the lie the
            // mailbox's UNDELIVERABLE state exists to prevent.
            err.appendLine("error: ${message.failureReason ?: "the message could not be delivered"}")
            return@runBlocking 1
        }
        out.appendLine("sent ${message.id}")
        0
    }

    // ------------------------------------------------------------------------
    // inbox + read (MAIL-3)
    // ------------------------------------------------------------------------

    /**
     * `inbox [--unread]`: the messages addressed to ME, oldest first, one row each.
     *
     * WHAT "ME" IS, and it is the part that is easy to get wrong. A message is keyed in the mailbox
     * by the address it was SENT to (`Mailbox.inbox` is `it.to == who`), so an inbox is a question
     * about ONE address, never "everything with my name on it". The address asked about here is the
     * session named by [AccessPolicy.SESSION_ID_ENV] -- the variable the daemon exports into every
     * spawned agent, and the same identity `send` speaks as -- and, at a terminal with no session,
     * the mailbox's non-session handle ([HUMAN_SENDER]). That is exactly the pair [send] already
     * uses, so a message `send` addressed to a session id or to `$HUMAN_SENDER` is one this command
     * can find.
     *
     * A message addressed to a ROLE or an AGENT ID is stored under THAT address and is therefore a
     * different inbox: resolving it needs the SENDER's project (`MailRouter.resolveRecipient`), which
     * a bare client does not have. Only the session handle is unambiguously mine, and printing an
     * empty listing for mail that exists is the failure this note exists to prevent -- so a caller
     * that needs role-addressed mail asks for the role's inbox by sending to it, not by reading its
     * own.
     *
     * `--unread` is a FILTER over what the daemon answered, not a different request: the mailbox has
     * no unread listing, and the state it would filter on ([Message.read]) is already on every row.
     * A message that is not read is exactly one `read` has not acknowledged.
     */
    private fun inbox(
        stream: FrameStream,
        parsed: ParsedArgs,
        out: Appendable,
        err: Appendable,
        getenv: (String) -> String?,
    ): Int = runBlocking {
        checkContract(stream, err)
        val session = sessionOf(getenv)
        val messages = fetchInbox(stream, session ?: HUMAN_SENDER, session, err) ?: return@runBlocking 1
        val listed = if (parsed.unread) messages.filterNot { it.read } else messages
        if (listed.isEmpty()) {
            out.appendLine(if (parsed.unread) "No unread messages." else "No messages.")
            return@runBlocking 0
        }
        out.appendLine(INBOX_HEADER)
        for (message in listed) {
            out.appendLine(
                "${message.id.take(8)} | ${message.from} | ${iso(message.sentAt)} | " +
                    "${if (message.read) "yes" else "no"} | ${firstLineOf(message.body)}",
            )
        }
        0
    }

    /**
     * `read <msgId>`: the whole message, and it is acknowledged as read.
     *
     * PREFIX. The id `inbox` prints is the 8-char prefix, because a full mailbox id is long and a
     * terminal is not. The wire takes the FULL id (`mail_read` is `mailbox.markRead(<exact id>)`), so
     * the prefix is resolved HERE, against MY inbox -- the only list this client holds, and the only
     * one that can contain a message addressed to me.
     *
     * A prefix that matches nothing is exit 1, `no such message`. One that matches SEVERAL is refused
     * by name rather than guessed at: marking the wrong message read is a silent loss (the wrongly
     * acknowledged one disappears from `inbox --unread`), which is the exact class of failure this
     * mailbox exists to prevent.
     */
    private fun read(
        stream: FrameStream,
        parsed: ParsedArgs,
        out: Appendable,
        err: Appendable,
        getenv: (String) -> String?,
    ): Int = runBlocking {
        checkContract(stream, err)
        // Non-blank here: [validateArgs] refuses a missing id before the socket opens.
        val prefix = parsed.args.first().trim()
        val session = sessionOf(getenv)
        val messages = fetchInbox(stream, session ?: HUMAN_SENDER, session, err) ?: return@runBlocking 1
        val matches = messages.filter { it.id.startsWith(prefix) }
        if (matches.isEmpty()) {
            err.appendLine("error: no such message '$prefix'")
            return@runBlocking 1
        }
        if (matches.size > 1) {
            err.appendLine(
                "error: message id '$prefix' is ambiguous: " +
                    matches.joinToString(", ") { it.id.take(8) },
            )
            return@runBlocking 1
        }
        val found = matches.single()

        // A message that never reached a console cannot be acknowledged: the mailbox's
        // DELIVERED -> READ transition has nothing to move from, and the daemon answers an error
        // WITHOUT saying which of the two reasons it was. Both are knowable client-side, from the row
        // the listing just gave us, so the reason is named instead of passed through opaquely.
        if (found.failed) {
            err.appendLine(
                "error: message '${found.id.take(8)}' was never delivered: " +
                    (found.failureReason ?: "the delivery failed"),
            )
            return@runBlocking 1
        }
        if (!found.delivered) {
            err.appendLine(
                "error: message '${found.id.take(8)}' has not reached a console yet " +
                    "(state ${found.state}), so it cannot be marked read",
            )
            // MAIL-FOOTER-1: a cold agent hit this mid-turn (its own mail queues until the turn ends) and fell back
            // to curl. Name the command that shows the message NOW; it is marked read once it is delivered.
            err.appendLine("hint: it is delivered when your current turn ends. Read it now with: agenthq thread \"${found.threadId}\"")
            return@runBlocking 1
        }

        // Idempotent: re-acknowledging a READ message returns it unchanged, so a reconnect may
        // safely re-send this -- unlike `send`, whose second copy would be a second message.
        val answer = stream.request(
            Envelope(Envelope.MAIL_READ, found.id, session),
            answerType = Envelope.MAIL,
            what = "mail_read",
            idempotent = true,
        )
        val updated = try {
            json.decodeFromString(Message.serializer(), answer.data)
        } catch (e: Exception) {
            err.appendLine("error: malformed mail answer from the daemon: ${e.message}")
            return@runBlocking 1
        }
        out.appendLine("[mail] ${updated.id}")
        out.appendLine(
            "[mail] from ${updated.from} -> ${updated.to} · thread ${updated.threadId} · " +
                (if (updated.read) "read" else "unread"),
        )
        if (updated.subject.isNotBlank()) out.appendLine("[mail] subject ${updated.subject}")
        out.appendLine("[mail] sent ${iso(updated.sentAt)} · state ${updated.state}")
        out.appendLine()
        out.appendLine(updated.body)
        0
    }

    /**
     * The messages addressed to [who], as the daemon holds them -- every state, oldest first, so a
     * failed one is visible rather than missing.
     *
     * Null means the answer was unusable; the reason has already been written to [err], and the
     * caller returns a failure rather than an empty listing ("the daemon sent nonsense" and "you
     * have no mail" must never look alike).
     */
    private suspend fun fetchInbox(
        stream: FrameStream,
        who: String,
        session: String?,
        err: Appendable,
    ): List<Message>? {
        val answer = stream.request(
            Envelope(Envelope.MAIL_INBOX, who, session),
            answerType = Envelope.MAILS,
            what = "mail_inbox",
            idempotent = true,
        )
        return try {
            json.decodeFromString(ListSerializer(Message.serializer()), answer.data)
        } catch (e: Exception) {
            err.appendLine("error: malformed mails answer from the daemon: ${e.message}")
            null
        }
    }

    // ------------------------------------------------------------------------
    // threads + thread + waiting (MAIL-4)
    // ------------------------------------------------------------------------

    /**
     * `threads`: MY conversations -- who else is in each, how many messages, and when it last moved.
     *
     * WHY THIS IS FILTERED HERE AND NOT ON THE WIRE. `mail_threads` carries no payload, and the daemon
     * answers it with `mailbox.threads()`: EVERY thread it holds, of every agent on the daemon. So the
     * frame is a mailbox-wide listing and the CLI is where "mine" is decided -- the same shape `inbox`
     * lives with, and the same rule: a thread survives only if I am one of its
     * [ThreadSummary.participants], which for an agent-pair conversation are the two session ids whose
     * conversation it is. Printing the daemon's whole mailbox under the name `threads` would look
     * perfectly correct on a one-agent daemon and leak every other conversation on a busy one.
     *
     * WHO I AM is [sessionOf], else the mailbox's non-session handle ([HUMAN_SENDER]) -- exactly the
     * identity `send`, `inbox` and `read` already speak as. The honest limit that comes with it is the
     * one [inbox] documents: a conversation ADDRESSED to me by role or agent id is keyed by that
     * address, so it is not mine under this identity either, and it is dropped rather than shown to
     * somebody else. That is also why the second column is `WITH` -- the address the other side used,
     * which is the only name the mailbox actually holds -- rather than a display name it does not.
     */
    private fun threads(
        stream: FrameStream,
        parsed: ParsedArgs,
        out: Appendable,
        err: Appendable,
        getenv: (String) -> String?,
    ): Int = runBlocking {
        checkContract(stream, err)
        val session = sessionOf(getenv)
        val me = session ?: HUMAN_SENDER
        val answer = stream.request(
            Envelope(Envelope.MAIL_THREADS, "", session),
            answerType = Envelope.MAIL_THREADS,
            what = "mail_threads",
            idempotent = true,
        )
        val all = try {
            json.decodeFromString(ListSerializer(ThreadSummary.serializer()), answer.data)
        } catch (e: Exception) {
            err.appendLine("error: malformed mail_threads answer from the daemon: ${e.message}")
            return@runBlocking 1
        }
        val mine = all.filter { me in it.participants }
        if (mine.isEmpty()) {
            out.appendLine("No threads.")
            return@runBlocking 0
        }
        out.appendLine(THREADS_HEADER)
        for (summary in mine) {
            out.appendLine(
                "${summary.id} | ${summary.participants.filterNot { it == me }.joinToString(", ")} | " +
                    "${summary.messageCount} | ${iso(summary.lastAt)}",
            )
        }
        0
    }

    /**
     * `thread <id>`: one conversation, whole, message by message in thread order.
     *
     * THE ID IS THE ONE `threads` PRINTS -- the full thread id, not a prefix. A thread id is short
     * enough to copy (`pair:<a>|<b>`, the two session ids), and unlike the 8-char MESSAGE prefixes
     * `read` resolves, a thread id has no ambiguity to resolve against a listing: it is the key.
     *
     * PRINT THE ORDER THE DAEMON GAVE. `mailbox.thread` answers sorted by [Message.seq], which is
     * monotonic WITHIN a thread and is precisely what keeps two messages sent in the same millisecond
     * readable in order; re-sorting here could only disagree with the daemon about that.
     *
     * AN EMPTY ANSWER IS AN UNKNOWN THREAD, and saying so is not a guess: a thread is DERIVED from the
     * messages in it, so a thread the daemon holds is never empty. Reporting "0 messages" for a typo
     * would hand the reader a real-looking conversation with nothing in it -- which is the failure the
     * mailbox's UNDELIVERABLE state exists to prevent, one surface over.
     */
    private fun thread(
        stream: FrameStream,
        parsed: ParsedArgs,
        out: Appendable,
        err: Appendable,
        getenv: (String) -> String?,
    ): Int = runBlocking {
        checkContract(stream, err)
        // Non-blank here: [validateArgs] refuses a missing id before the socket opens.
        val id = parsed.args.first().trim()
        val answer = stream.request(
            Envelope(Envelope.MAIL_THREAD, id, sessionOf(getenv)),
            answerType = Envelope.MAILS,
            what = "mail_thread",
            idempotent = true,
        )
        val messages = try {
            json.decodeFromString(ListSerializer(Message.serializer()), answer.data)
        } catch (e: Exception) {
            err.appendLine("error: malformed mails answer from the daemon: ${e.message}")
            return@runBlocking 1
        }
        if (messages.isEmpty()) {
            err.appendLine("error: no such thread '$id'")
            // MAIL-FOOTER-1: a pair id is `pair:<a>|<b>`, and an unquoted `|` is a pipe to cmd.exe and bash:
            // the shell cut the id and ran the rest as a command. Say so instead of a bare "no such thread".
            if (id.startsWith("pair:") && '|' !in id) {
                err.appendLine("hint: a pair thread id contains '|'; put the whole id in \"double quotes\" (see: agenthq threads)")
            }
            return@runBlocking 1
        }
        out.appendLine("THREAD $id · ${messages.size} messages")
        messages.forEachIndexed { index, message ->
            out.appendLine(
                "[mail] ${index + 1}/${messages.size} ${message.id} from ${message.from} -> ${message.to} · " +
                    "${message.state} · ${iso(message.sentAt)}",
            )
            out.appendLine(message.body)
            out.appendLine()
        }
        0
    }

    /**
     * `waiting`: every open question this daemon holds -- the message an agent is stuck on, the session
     * that is blocked, and WHO it is waiting for.
     *
     * WHAT THE DAEMON ALREADY DECIDED, and why this command adds no filter of its own: `mail_waiting`
     * answers [com.superiorsoftllc.agenthq.mailbox.MailRouter.waiting], which is the mailbox's
     * needs-input rows narrowed to askers with NO run in flight. A working agent is therefore not
     * listed, deliberately -- "waiting on you" from an agent that is still working is the false signal
     * that erodes trust in the whole mailbox -- and neither is a signal whose asker the daemon does not
     * know, because it cannot tell whether that asker is at rest.
     *
     * That makes this a mailbox-WIDE view rather than one identity's inbox, and that is the point of
     * it: the question "who is stuck, and on whom?" is asked by an orchestrator about the whole board,
     * not by an agent about its own mail. The session column is therefore on every row -- a needs-input
     * signal always belongs to somebody, and a listing that hid which agent it was would answer the
     * question with a table nobody can act on.
     */
    private fun waiting(
        stream: FrameStream,
        parsed: ParsedArgs,
        out: Appendable,
        err: Appendable,
        getenv: (String) -> String?,
    ): Int = runBlocking {
        checkContract(stream, err)
        val answer = stream.request(
            Envelope(Envelope.MAIL_WAITING, "", sessionOf(getenv)),
            answerType = Envelope.MAIL_WAITING,
            what = "mail_waiting",
            idempotent = true,
        )
        val rows = try {
            json.decodeFromString(ListSerializer(WaitingOn.serializer()), answer.data)
        } catch (e: Exception) {
            err.appendLine("error: malformed mail_waiting answer from the daemon: ${e.message}")
            return@runBlocking 1
        }
        if (rows.isEmpty()) {
            out.appendLine("Nothing is waiting.")
            return@runBlocking 0
        }
        out.appendLine(WAITING_HEADER)
        for (row in rows) {
            out.appendLine(
                "${row.messageId.take(8)} | ${row.session} | ${row.waitingOn} | ${iso(row.since)} | ${row.subject}",
            )
        }
        0
    }

    // ------------------------------------------------------------------------
    // epics + stories (AGENT-CLI-2)
    // ------------------------------------------------------------------------

    /**
     * `epics --project <id>`: a project's epics, from the daemon's own epic store.
     *
     * WHY THIS COMMAND REQUIRES A PROJECT ID, where `stories` does not. The two listings are NOT
     * symmetric on the wire, and this is the whole reason the command is shaped the way it is:
     *
     * - `epic_list` carries the RAW project id and `EpicStore.list` filters on an EXACT match
     *   (`it.projectId == projectId.trim()`). An empty payload therefore matches nothing and
     *   answers `[]`. It does NOT mean "every project" -- there is no all-projects epic listing.
     * - `story_list` with an empty payload means the WHOLE board (`BoardHandler.board` treats a null
     *   filter as no filter).
     *
     * So a `epics` with no project id has nothing to send that would work. Rather than print "no
     * epics" for a daemon full of them, it is refused as a USAGE error before connecting, and
     * [validateArgs] is where that happens. When neither `--project` nor
     * [AccessPolicy.PROJECT_ID_ENV] names a project, the honest answer is to say so.
     *
     * The answer is matched on the `epics` frame TYPE ([Envelope.EPICS]), never on "something epic
     * shaped". That matters: a `story_save` carrying an `epicId` makes the daemon PUSH the changed
     * epic to every connected client (`broadcastEpic`), so this socket can be handed an `epic` frame
     * it never asked for, at any moment. [FrameStream.request] drops every frame that is not the
     * answer type it was asked for, which is exactly the behaviour this relies on.
     */
    private fun epics(
        stream: FrameStream,
        parsed: ParsedArgs,
        out: Appendable,
        err: Appendable,
        getenv: (String) -> String?,
    ): Int = runBlocking {
        checkContract(stream, err)
        // Non-null here: [validateArgs] already refused a blank project before the socket opened.
        val projectId = projectIdOf(parsed, getenv) ?: ""
        val answer = stream.request(
            Envelope(Envelope.EPIC_LIST, projectId),
            answerType = Envelope.EPICS,
            what = "epic_list",
            idempotent = true,
        )
        val epics = try {
            json.decodeFromString(ListSerializer(Epic.serializer()), answer.data)
        } catch (e: Exception) {
            err.appendLine("error: malformed epics answer from the daemon: ${e.message}")
            return@runBlocking 1
        }

        if (epics.isEmpty()) {
            out.appendLine("No epics in project $projectId.")
        } else {
            out.appendLine(EPIC_HEADER)
            for (epic in epics) {
                val goal = epic.goal.ifBlank { "-" }
                // storyIds is the epic's ordered story list, and its size is what an agent reading
                // this listing wants first: an epic with nothing under it yet.
                out.appendLine("${shortTicketId(epic.id)} | ${epic.name} | $goal | ${epic.storyIds.size}")
            }
        }
        0
    }

    /**
     * `stories [--project <id>] [--epic <id|prefix>] [--status <column>]`: DEPRECATED (E1-e). A story is now a
     * board ticket filed under an epic, so this prints ONE deprecation line and then exactly the rows
     * `tickets --epic` prints: with `--epic`, that epic's tickets; without it, every ticket filed under SOME
     * epic (what the story board used to hold), never a loose one. `--status` is a ticket column, and the old
     * story column `doing` is read as the `in_progress` it became.
     */
    private fun stories(
        stream: FrameStream,
        parsed: ParsedArgs,
        out: Appendable,
        err: Appendable,
        getenv: (String) -> String?,
    ): Int = runBlocking {
        checkContract(stream, err)
        out.appendLine(STORIES_DEPRECATION)
        val status = parsed.status?.let { if (it == STORY_DOING) TicketStatus.IN_PROGRESS else it }
        listBoard(stream, parsed, status, epicOnly = true, out, err, getenv)
    }

    // ------------------------------------------------------------------------
    // memory (P10-K3, docs/AGENTHQ-PHASE10-KNOWLEDGE.md section 5)
    // ------------------------------------------------------------------------

    /** `memory <index|show|search|save> ...`: [validateArgs] has refused a missing subcommand, argument or project. */
    private fun memory(
        stream: FrameStream,
        parsed: ParsedArgs,
        out: Appendable,
        err: Appendable,
        getenv: (String) -> String?,
        input: () -> Reader,
    ): Int = runBlocking {
        checkContract(stream, err)
        val projectId = projectIdOf(parsed, getenv) ?: return@runBlocking 2
        when (parsed.args.first()) {
            "index" -> memoryIndex(stream, projectId, parsed.folder?.trim()?.trim('/'), out, err, scoped(parsed, getenv))
                .also { if (it == 0) roleNotesHint(stream, projectId, scoped(parsed, getenv), out) }
            "show" -> memoryShow(stream, projectId, parsed.args[1].trim(), out, err, scoped(parsed, getenv))
            "search" -> memorySearch(stream, projectId, parsed.args.drop(1).joinToString(" ").trim(), out, err, scoped(parsed, getenv))
            "delete" -> memoryDelete(stream, projectId, parsed.args[1].trim(), out, scoped(parsed, getenv))
            "purge-role" -> memoryPurgeRole(stream, parsed.args[1].trim(), parsed.args.getOrNull(2)?.trim().orEmpty(), out)
            else -> memorySave(stream, projectId, parsed, out, err, input, scoped(parsed, getenv))
        }
    }

    /**
     * ROLE-NOTES-1: a plain `memory index` (project scope) hides the caller's role and session notes; say when they exist.
     * Best effort: a daemon that cannot answer the extra listing just gives no hint.
     */
    private suspend fun roleNotesHint(stream: FrameStream, projectId: String, at: MemoryList, out: Appendable) {
        if (at.scope != null) return
        for (scope in listOf(SCOPE_ROLE, SCOPE_SESSION)) {
            val count = runCatching {
                val answer = stream.request(
                    Envelope(Envelope.MEMORY_LIST, json.encodeToString(MemoryList.serializer(), at.copy(projectId = projectId, scope = scope))),
                    answerType = Envelope.MEMORY_NOTES, what = "memory_list", idempotent = true,
                )
                json.decodeFromString(ListSerializer(MemoryIndexEntry.serializer()), answer.data).size
            }.getOrDefault(0)
            if (count > 0) out.appendLine("+$count $scope notes: use --scope $scope")
        }
    }

    /** NOTES-1: the scope a `memory` call addresses: `--scope` (default project), the calling session, `--role`. */
    private fun scoped(parsed: ParsedArgs, getenv: (String) -> String?) = MemoryList(
        projectId = "",
        scope = parsed.scope,
        sessionId = getenv(AccessPolicy.SESSION_ID_ENV)?.trim()?.takeIf { it.isNotEmpty() },
        role = parsed.role,
    )

    /** `memory delete <name> [--scope s]`: remove one note of a scope. */
    private suspend fun memoryDelete(stream: FrameStream, projectId: String, name: String, out: Appendable, at: MemoryList): Int {
        val request = MemoryDelete(projectId, name, at.scope, at.sessionId, at.role)
        stream.request(
            Envelope(Envelope.MEMORY_DELETE, json.encodeToString(MemoryDelete.serializer(), request)),
            answerType = Envelope.MEMORY_NOTES,
            what = "memory_delete",
            idempotent = false,
        )
        out.appendLine("deleted ${at.scope ?: SCOPE_PROJECT} note $name")
        return 0
    }

    /** `memory purge-role <role> <role>`: delete ALL of a role's notes; the role id is typed twice to confirm. */
    private suspend fun memoryPurgeRole(stream: FrameStream, role: String, confirm: String, out: Appendable): Int {
        stream.request(
            Envelope(Envelope.MEMORY_PURGE_ROLE, json.encodeToString(MemoryPurgeRole.serializer(), MemoryPurgeRole(role, confirm))),
            answerType = Envelope.MEMORY_NOTES,
            what = "memory_purge_role",
            idempotent = false,
        )
        out.appendLine("purged all notes of role $role")
        return 0
    }

    /**
     * `memory index [--folder f]`: one `folder/name: description` line per note, ordered by folder then name.
     * Without `--folder`, an index above [MEMORY_INDEX_FOLD] notes prints its top-level folders with their counts
     * plus the root notes (an index you walk down one folder at a time, instead of reading everything). `--folder f` lists
     * the notes filed in `f` and below it.
     */
    private suspend fun memoryIndex(
        stream: FrameStream,
        projectId: String,
        folder: String?,
        out: Appendable,
        err: Appendable,
        at: MemoryList = MemoryList(""),
    ): Int {
        val answer = stream.request(
            Envelope(
                Envelope.MEMORY_LIST,
                if (at.scope == null) projectId else json.encodeToString(MemoryList.serializer(), at.copy(projectId = projectId)),
            ),
            answerType = Envelope.MEMORY_NOTES,
            what = "memory_list",
            idempotent = true,
        )
        val all = try {
            json.decodeFromString(ListSerializer(MemoryIndexEntry.serializer()), answer.data)
        } catch (e: Exception) {
            err.appendLine("error: malformed memory_notes answer from the daemon: ${e.message}")
            return 1
        }
        val entries = all
            .filter { folder.isNullOrEmpty() || it.folder == folder || it.folder.startsWith("$folder/") }
            .sortedWith(compareBy<MemoryIndexEntry>({ it.folder }, { it.name }))
        if (entries.isEmpty()) {
            out.appendLine(
                if (folder.isNullOrEmpty()) "No memory notes in project $projectId."
                else "No memory notes under folder '$folder' in project $projectId.",
            )
            return 0
        }
        if (folder.isNullOrEmpty() && entries.size > MEMORY_INDEX_FOLD) {
            val (root, filed) = entries.partition { it.folder.isEmpty() }
            out.appendLine(
                "${entries.size} notes; folders shown with their counts (agenthq memory index --folder <f> walks one)",
            )
            filed.groupBy { it.folder.substringBefore('/') }.toSortedMap().forEach { (top, notes) ->
                out.appendLine("$top/ (${notes.size} notes)")
            }
            root.forEach { out.appendLine(indexLine(it)) }
            return 0
        }
        entries.forEach { out.appendLine(indexLine(it)) }
        return 0
    }

    private fun indexLine(entry: MemoryIndexEntry): String = KnowledgeLines.indexLine(entry)

    /** `memory show <name>`: the whole note (`memory_get`). An unknown name is refused `note_not_found`, exit 1. */
    private suspend fun memoryShow(stream: FrameStream, projectId: String, name: String, out: Appendable, err: Appendable, at: MemoryList = MemoryList("")): Int {
        val note = fetchNote(stream, projectId, name, err, at) ?: return 1
        out.appendLine("SCOPE: ${note.scope}${if (note.ownerId.isEmpty()) "" else " (${note.ownerId})"}")
        out.appendLine("NAME: ${note.name}")
        out.appendLine("FOLDER: ${note.folder.ifEmpty { "-" }}")
        out.appendLine("DESCRIPTION: ${note.description}")
        out.appendLine("UPDATED: ${note.updatedAt}")
        out.appendLine("CHARS: ${note.body.length}")
        out.appendLine()
        out.appendLine(note.body)
        return 0
    }

    /** `memory_get` (throws the daemon's refusal text, which the caller prints) or null on a malformed answer. */
    private suspend fun fetchNote(stream: FrameStream, projectId: String, name: String, err: Appendable, at: MemoryList = MemoryList("")): MemoryNote? {
        val answer = stream.request(
            Envelope(Envelope.MEMORY_GET, json.encodeToString(MemoryGet.serializer(), MemoryGet(projectId, name, at.scope, at.sessionId, at.role))),
            answerType = Envelope.MEMORY_NOTE,
            what = "memory_get",
            idempotent = true,
        )
        return try {
            json.decodeFromString(MemoryNote.serializer(), answer.data)
        } catch (e: Exception) {
            err.appendLine("error: malformed memory_note answer from the daemon: ${e.message}")
            null
        }
    }

    /** `memory search <term>`: ranked hits, each `name: description` then its snippet, indented. */
    private suspend fun memorySearch(stream: FrameStream, projectId: String, term: String, out: Appendable, err: Appendable, at: MemoryList = MemoryList("")): Int {
        val answer = stream.request(
            Envelope(Envelope.MEMORY_SEARCH, json.encodeToString(MemorySearch.serializer(), MemorySearch(projectId, term, at.scope, at.sessionId, at.role))),
            answerType = Envelope.MEMORY_HITS,
            what = "memory_search",
            idempotent = true,
        )
        val hits = try {
            json.decodeFromString(ListSerializer(MemorySearchResult.serializer()), answer.data)
        } catch (e: Exception) {
            err.appendLine("error: malformed memory_hits answer from the daemon: ${e.message}")
            return 1
        }
        if (hits.isEmpty()) {
            out.appendLine("No memory notes match '$term' in project $projectId.")
            return 0
        }
        hits.forEach { hit ->
            out.appendLine("${hit.name}: ${hit.description}")
            out.appendLine("    " + hit.snippet.replace('\n', ' '))
        }
        return 0
    }

    /**
     * `memory save <name> [--description d] [--folder f] [--append | --edit --find <passage>] (--content | --file | -)`.
     * Prints the metadata and `chars`, then the tail (append) or an excerpt around the change (edit): NEVER the
     * body, so a big note stays out of the caller's context. A refusal is the daemon's text, code first, exit 1.
     *
     * NOT idempotent: an append re-sent after a dropped connection would append twice, so a drop is reported.
     */
    private suspend fun memorySave(
        stream: FrameStream,
        projectId: String,
        parsed: ParsedArgs,
        out: Appendable,
        err: Appendable,
        input: () -> Reader,
        at: MemoryList = MemoryList(""),
    ): Int {
        val name = parsed.args[1].trim()
        val text = when {
            parsed.content != null -> parsed.content
            parsed.file != null -> try {
                File(parsed.file).readText()
            } catch (e: Exception) {
                err.appendLine("error: cannot read --file ${parsed.file}: ${e.message}")
                return 2
            }
            else -> input().use { it.readText() }
        }
        val mode = when {
            parsed.append -> MemorySave.MODE_APPEND
            parsed.edit -> MemorySave.MODE_EDIT
            else -> MemorySave.MODE_REPLACE
        }
        // Where the edit lands, so the excerpt is the changed passage and not an earlier lookalike. Read before
        // the save; a missing note or a bad anchor is left for the save itself to refuse with its code.
        val editAt = if (mode == MemorySave.MODE_EDIT) {
            runCatching { fetchNote(stream, projectId, name, err, at) }.getOrNull()?.body?.let { body ->
                body.indexOf(parsed.find!!).takeIf { MemoryStore.occurrences(body, parsed.find) == 1 }
            }
        } else {
            null
        }
        val request = MemorySave(
            projectId = projectId,
            name = name,
            description = parsed.description,
            body = text.takeIf { mode == MemorySave.MODE_REPLACE },
            mode = mode,
            find = parsed.find.takeIf { mode == MemorySave.MODE_EDIT },
            content = text.takeIf { mode != MemorySave.MODE_REPLACE },
            folder = parsed.folder,
            scope = at.scope,
            sessionId = at.sessionId,
            role = at.role,
        )
        val answer = stream.request(
            Envelope(Envelope.MEMORY_SAVE, json.encodeToString(MemorySave.serializer(), request)),
            answerType = Envelope.MEMORY_NOTE,
            what = "memory_save",
            idempotent = false,
        )
        val note = try {
            json.decodeFromString(MemoryNote.serializer(), answer.data)
        } catch (e: Exception) {
            err.appendLine("error: malformed memory_note answer from the daemon: ${e.message}")
            return 1
        }
        if (note.scope != SCOPE_PROJECT) {
            out.appendLine("scope: ${note.scope} (${note.ownerId})${if (note.scope == SCOPE_SESSION) " - temporary, deleted with the session" else ""}")
        }
        out.appendLine("saved ${if (note.folder.isEmpty()) note.name else "${note.folder}/${note.name}"} ($mode)")
        out.appendLine("description: ${note.description}")
        out.appendLine("updated: ${note.updatedAt}")
        out.appendLine("chars: ${note.body.length}")
        when (mode) {
            MemorySave.MODE_APPEND -> out.appendLine("tail: " + clip(note.body.takeLast(MEMORY_EXCERPT_CHARS), cutStart = note.body.length > MEMORY_EXCERPT_CHARS, cutEnd = false))
            MemorySave.MODE_EDIT -> {
                val at = editAt ?: 0
                val start = (at - MEMORY_EXCERPT_RADIUS).coerceAtLeast(0)
                val end = (at + text.length + MEMORY_EXCERPT_RADIUS).coerceAtMost(note.body.length)
                out.appendLine("excerpt: " + clip(note.body.substring(start, end), cutStart = start > 0, cutEnd = end < note.body.length))
            }
            else -> Unit
        }
        return 0
    }

    private fun clip(text: String, cutStart: Boolean, cutEnd: Boolean): String =
        (if (cutStart) "…" else "") + text + (if (cutEnd) "…" else "")

    /** The usage error for a `memory` invocation, or null. Every subcommand needs a project. */
    private fun memoryUsageError(parsed: ParsedArgs, getenv: (String) -> String?): String? {
        val args = parsed.args
        val usage = "memory needs a subcommand: agenthq memory index [--folder f] | show <name> | search <term> | " +
            "save <name> [--description d] [--folder f] [--append | --edit --find <passage>] (--content <text> | --file <path> | -)"
        val sub = args.firstOrNull() ?: return usage
        if (parsed.scope != null && parsed.scope !in NOTE_SCOPES) {
            return "--scope must be one of ${NOTE_SCOPES.joinToString("|")}"
        }
        val error = when (sub) {
            "index" -> null
            "delete" -> if (args.getOrNull(1)?.isBlank() != false) "memory delete requires a note name: agenthq memory delete <name> [--scope s]" else null
            "purge-role" -> if (args.getOrNull(1)?.isBlank() != false || args.getOrNull(2) != args.getOrNull(1)) {
                "memory purge-role deletes ALL notes of a role: agenthq memory purge-role <role> <role> (the role id twice, to confirm)"
            } else {
                null
            }
            "show" -> if (args.getOrNull(1)?.isBlank() != false) "memory show requires a note name: agenthq memory show <name>" else null
            "search" -> if (args.drop(1).all { it.isBlank() }) "memory search requires a term: agenthq memory search <term>" else null
            "save" -> {
                val sources = listOfNotNull(parsed.content, parsed.file).size + (if (parsed.stdin) 1 else 0)
                when {
                    args.getOrNull(1)?.isBlank() != false -> "memory save requires a note name: agenthq memory save <name> ..."
                    parsed.append && parsed.edit -> "memory save takes --append or --edit, not both"
                    parsed.find != null && !parsed.edit -> "memory save --find belongs to --edit"
                    parsed.edit && parsed.find.isNullOrEmpty() -> "memory save --edit requires --find <passage>"
                    sources == 0 -> "memory save requires the text: --content <text>, --file <path>, or - (stdin)"
                    sources > 1 -> "memory save takes one of --content, --file or - (stdin)"
                    !parsed.append && !parsed.edit && parsed.description == null ->
                        "memory save (replace) requires --description; use --append or --edit to keep the stored one"
                    else -> null
                }
            }
            else -> return usage
        }
        return error ?: if (projectIdOf(parsed, getenv) == null) {
            "memory $sub needs a project id (--project <id>, or ${AccessPolicy.PROJECT_ID_ENV})"
        } else {
            null
        }
    }

    // ------------------------------------------------------------------------
    // knowledge (P10-K8, docs/AGENTHQ-PHASE10-KNOWLEDGE.md §5)
    // ------------------------------------------------------------------------

    /**
     * `knowledge`: the project's knowledge at a glance, read-only, from the three list frames (`memory_list`,
     * `decision_list`, `issue_list`). A count line, then three sections:
     * - NOTES: the note index as `memory index` prints it (above [MEMORY_INDEX_FOLD] notes, the folders with their
     *   counts, then the root notes), at most [MEMORY_INDEX_FOLD] lines;
     * - DECISIONS: the ACTIVE decisions only, `D<n> summary`, at most [KNOWLEDGE_DECISIONS_CAP];
     * - ISSUES: the OPEN issues only, `I<n> summary (location)`, at most [KNOWLEDGE_ISSUES_CAP].
     * A capped list ends with `N more: <the command that lists them all>`. Superseded decisions and fixed issues are
     * never asked for, and filtered again here, so a daemon that sends them anyway cannot surface a dead answer.
     */
    private fun knowledge(
        stream: FrameStream,
        parsed: ParsedArgs,
        out: Appendable,
        err: Appendable,
        getenv: (String) -> String?,
    ): Int = runBlocking {
        checkContract(stream, err)
        val projectId = projectIdOf(parsed, getenv) ?: return@runBlocking 2
        val notes = try {
            json.decodeFromString(
                ListSerializer(MemoryIndexEntry.serializer()),
                stream.request(Envelope(Envelope.MEMORY_LIST, projectId), answerType = Envelope.MEMORY_NOTES, what = "memory_list", idempotent = true).data,
            )
        } catch (e: Exception) {
            err.appendLine("error: malformed memory_notes answer from the daemon: ${e.message}")
            return@runBlocking 1
        }.sortedWith(compareBy<MemoryIndexEntry>({ it.folder }, { it.name }))
        val decisions = try {
            json.decodeFromString(
                ListSerializer(Decision.serializer()),
                stream.request(
                    Envelope(Envelope.DECISION_LIST, json.encodeToString(DecisionList.serializer(), DecisionList(projectId, includeSuperseded = false))),
                    answerType = Envelope.DECISIONS,
                    what = "decision_list",
                    idempotent = true,
                ).data,
            )
        } catch (e: Exception) {
            err.appendLine("error: malformed decisions answer from the daemon: ${e.message}")
            return@runBlocking 1
        }.filter { it.active }.sortedBy { it.number }
        val issues = try {
            json.decodeFromString(
                ListSerializer(Issue.serializer()),
                stream.request(
                    Envelope(Envelope.ISSUE_LIST, json.encodeToString(IssueList.serializer(), IssueList(projectId, includeFixed = false))),
                    answerType = Envelope.ISSUES,
                    what = "issue_list",
                    idempotent = true,
                ).data,
            )
        } catch (e: Exception) {
            err.appendLine("error: malformed issues answer from the daemon: ${e.message}")
            return@runBlocking 1
        }.filter { it.status == Issue.STATUS_OPEN }

        out.appendLine(
            "Knowledge of project $projectId: ${plural(notes.size, "note")}, " +
                "${plural(decisions.size, "active decision")}, ${plural(issues.size, "open issue")}",
        )
        // BUG-G2e: the same line builders the daemon injects into a first turn ([KnowledgeLines]).
        val noteLines = KnowledgeLines.notes(notes)
        knowledgeSection(out, "NOTES", noteLines, MEMORY_INDEX_FOLD, "agenthq memory index")
        knowledgeSection(out, "DECISIONS", KnowledgeLines.decisions(decisions), KNOWLEDGE_DECISIONS_CAP, "agenthq decision list")
        knowledgeSection(
            out,
            "ISSUES",
            KnowledgeLines.issues(issues),
            KNOWLEDGE_ISSUES_CAP,
            "agenthq issue list",
        )
        0
    }

    /** One `knowledge` section: its heading, at most [cap] lines (`(none)` when empty), then `N more: [more]`. */
    private fun knowledgeSection(out: Appendable, heading: String, lines: List<String>, cap: Int, more: String) {
        out.appendLine()
        out.appendLine(heading)
        if (lines.isEmpty()) out.appendLine("(none)")
        lines.take(cap).forEach { out.appendLine(it) }
        if (lines.size > cap) out.appendLine("${lines.size - cap} more: $more")
    }

    private fun plural(n: Int, noun: String): String = "$n $noun" + if (n == 1) "" else "s"

    /** The usage error for a `knowledge` invocation, or null. It takes no argument and needs a project. */
    private fun knowledgeUsageError(parsed: ParsedArgs, getenv: (String) -> String?): String? = when {
        parsed.args.isNotEmpty() -> "knowledge takes no argument: agenthq knowledge [--project <id>]"
        projectIdOf(parsed, getenv) == null -> "knowledge needs a project id (--project <id>, or ${AccessPolicy.PROJECT_ID_ENV})"
        else -> null
    }

    // ------------------------------------------------------------------------
    // decision (P10-K5, docs/AGENTHQ-PHASE10-KNOWLEDGE.md K-D7)
    // ------------------------------------------------------------------------

    /** `decision <list|show|add> ...`: [validateArgs] has refused a missing subcommand, argument or project. */
    private fun decision(
        stream: FrameStream,
        parsed: ParsedArgs,
        out: Appendable,
        err: Appendable,
        getenv: (String) -> String?,
        input: () -> Reader,
    ): Int = runBlocking {
        checkContract(stream, err)
        val projectId = projectIdOf(parsed, getenv) ?: return@runBlocking 2
        when (parsed.args.first()) {
            "list" -> decisionList(stream, projectId, parsed.all, out, err)
            "show" -> decisionShow(stream, projectId, parsed.args[1].trim(), out, err)
            else -> decisionAdd(stream, projectId, parsed, out, err, getenv, input)
        }
    }

    /**
     * `decision list [--all]`: one `D<n> | summary` line per active decision, in number order. With `--all`, each
     * active decision is followed by the chain it replaced, newest first, each line `  ^ D<n> | summary (superseded by
     * D<m>)`, so a reader sees what every decision overturned.
     */
    private suspend fun decisionList(stream: FrameStream, projectId: String, all: Boolean, out: Appendable, err: Appendable): Int {
        val answer = stream.request(
            Envelope(Envelope.DECISION_LIST, json.encodeToString(DecisionList.serializer(), DecisionList(projectId, includeSuperseded = all))),
            answerType = Envelope.DECISIONS,
            what = "decision_list",
            idempotent = true,
        )
        val decisions = try {
            json.decodeFromString(ListSerializer(Decision.serializer()), answer.data)
        } catch (e: Exception) {
            err.appendLine("error: malformed decisions answer from the daemon: ${e.message}")
            return 1
        }
        val active = decisions.filter { it.active }
        if (active.isEmpty()) {
            out.appendLine("No decisions in project $projectId.")
            return 0
        }
        val byNumber = decisions.associateBy { it.number }
        for (decision in active) {
            out.appendLine("${decision.ref} | ${decision.summary}")
            if (!all) continue
            var previous = decision.supersedes?.let(byNumber::get)
            while (previous != null) {
                out.appendLine("  ^ ${previous.ref} | ${previous.summary} (superseded by D${previous.supersededBy})")
                previous = previous.supersedes?.let(byNumber::get)
            }
        }
        return 0
    }

    /** `decision show <ref>`: the whole decision (`decision_get`). An unknown ref is refused `decision_not_found`, exit 1. */
    private suspend fun decisionShow(stream: FrameStream, projectId: String, ref: String, out: Appendable, err: Appendable): Int {
        val answer = stream.request(
            Envelope(Envelope.DECISION_GET, json.encodeToString(DecisionGet.serializer(), DecisionGet(projectId, ref))),
            answerType = Envelope.DECISION_ITEM,
            what = "decision_get",
            idempotent = true,
        )
        val decision = try {
            json.decodeFromString(Decision.serializer(), answer.data)
        } catch (e: Exception) {
            err.appendLine("error: malformed decision_item answer from the daemon: ${e.message}")
            return 1
        }
        out.appendLine("${decision.ref}: ${decision.summary}")
        out.appendLine("STATUS: " + (decision.supersededBy?.let { "superseded by D$it" } ?: "active"))
        out.appendLine("SUPERSEDES: " + (decision.supersedes?.let { "D$it" } ?: "-"))
        out.appendLine(
            "AUTHOR: " + listOfNotNull(
                decision.authorRole?.let { "role $it" },
                decision.authorAgentId?.let { "agent $it" },
                decision.authorSessionId?.let { "session $it" },
            ).ifEmpty { listOf("-") }.joinToString(", "),
        )
        out.appendLine("CREATED: ${java.time.Instant.ofEpochMilli(decision.createdAt)}")
        if (decision.body.isNotEmpty()) {
            out.appendLine()
            out.appendLine(decision.body)
        }
        return 0
    }

    /**
     * `decision add <summary...> [--body <text|-> | --file <path>] [--supersedes <D1|1>]`: the author is the session in
     * `AGENTHQ_SESSION_ID` (none: no author). Prints `added D<n>` and what it supersedes. A refusal is the daemon's text,
     * code first, exit 1. NOT idempotent: a re-send after a dropped connection would add twice, so a drop is reported.
     */
    private suspend fun decisionAdd(
        stream: FrameStream,
        projectId: String,
        parsed: ParsedArgs,
        out: Appendable,
        err: Appendable,
        getenv: (String) -> String?,
        input: () -> Reader,
    ): Int {
        val summary = parsed.args.drop(1).joinToString(" ").trim()
        val body = when {
            parsed.body == "-" -> input().use { it.readText() }
            parsed.body != null -> parsed.body
            parsed.file != null -> try {
                File(parsed.file).readText()
            } catch (e: Exception) {
                err.appendLine("error: cannot read --file ${parsed.file}: ${e.message}")
                return 2
            }
            else -> null
        }
        val request = DecisionAdd(
            projectId = projectId,
            summary = summary,
            sessionId = getenv(AccessPolicy.SESSION_ID_ENV)?.trim()?.takeIf { it.isNotEmpty() },
            body = body,
            supersedes = parsed.supersedes?.trim(),
        )
        val answer = stream.request(
            Envelope(Envelope.DECISION_ADD, json.encodeToString(DecisionAdd.serializer(), request)),
            answerType = Envelope.DECISION_ITEM,
            what = "decision_add",
            idempotent = false,
        )
        val decision = try {
            json.decodeFromString(Decision.serializer(), answer.data)
        } catch (e: Exception) {
            err.appendLine("error: malformed decision_item answer from the daemon: ${e.message}")
            return 1
        }
        out.appendLine("added ${decision.ref}" + (decision.supersedes?.let { " (supersedes D$it)" } ?: "") + ": ${decision.summary}")
        return 0
    }

    /**
     * PIPE-1 `pipeline`: the project's optional verification pipeline.
     *  - `pipeline [show]`: the stages, each commit's current stage, and the running checks (a project with no
     *    pipeline prints one line saying so);
     *  - `pipeline configure <stage>... [--exclusive]` / `pipeline clear`: set or remove the pipeline;
     *  - `pipeline record <commit> <stage> [--body <evidence>]`: move a commit; the session in AGENTHQ_SESSION_ID is who;
     *  - `pipeline check start <commit> <what...> [--scope <dir>]` / `check ping` / `check finish`: the running check.
     * A refusal is the daemon's text, code first, exit 1.
     */
    private fun pipeline(
        stream: FrameStream,
        parsed: ParsedArgs,
        out: Appendable,
        err: Appendable,
        getenv: (String) -> String?,
    ): Int = runBlocking {
        checkContract(stream, err)
        val projectId = projectIdOf(parsed, getenv) ?: return@runBlocking 2
        val session = getenv(AccessPolicy.SESSION_ID_ENV)?.trim()?.takeIf { it.isNotEmpty() }
        val args = parsed.args
        val (type, data, idempotent) = when (args.firstOrNull() ?: "show") {
            "show" -> Triple(Envelope.PIPELINE_SHOW, json.encodeToString(PipelineShow.serializer(), PipelineShow(projectId)), true)
            "configure" -> Triple(
                Envelope.PIPELINE_CONFIG,
                json.encodeToString(PipelineConfigure.serializer(), PipelineConfigure(projectId, args.drop(1), parsed.exclusive)),
                true,
            )
            "clear" -> Triple(
                Envelope.PIPELINE_CONFIG,
                json.encodeToString(PipelineConfigure.serializer(), PipelineConfigure(projectId, clear = true)),
                true,
            )
            "record" -> Triple(
                Envelope.PIPELINE_RECORD,
                json.encodeToString(PipelineRecord.serializer(), PipelineRecord(projectId, args[1], args[2], parsed.body, session)),
                false,
            )
            else -> Triple(
                Envelope.PIPELINE_CHECK,
                json.encodeToString(
                    PipelineCheckRequest.serializer(),
                    PipelineCheckRequest(
                        projectId = projectId,
                        action = args[1],
                        scope = parsed.scope ?: "",
                        commit = args.getOrNull(2),
                        what = args.drop(3).joinToString(" ").ifBlank { null },
                        sessionId = session,
                    ),
                ),
                false,
            )
        }
        val answer = stream.request(Envelope(type, data), answerType = Envelope.PIPELINE_STATE, what = type, idempotent = idempotent)
        val state = try {
            json.decodeFromString(PipelineState.serializer(), answer.data)
        } catch (e: Exception) {
            err.appendLine("error: malformed pipeline_state answer from the daemon: ${e.message}")
            return@runBlocking 1
        }
        printPipeline(state, out)
        0
    }

    private fun printPipeline(state: PipelineState, out: Appendable) {
        val config = state.config
        if (!state.configured || config == null) {
            out.appendLine("No pipeline configured for project ${state.projectId}.")
            return
        }
        out.appendLine("STAGES: ${config.stages.joinToString(" > ")}" + if (config.exclusiveCheck) " (one running check per scope)" else "")
        if (state.commits.isEmpty()) out.appendLine("No commits recorded.")
        for (c in state.commits) {
            out.appendLine(
                "${c.commit} | ${c.stage} | ${c.movedBy ?: "-"} | ${java.time.Instant.ofEpochMilli(c.movedAt)}" +
                    (c.evidence?.let { " | $it" } ?: ""),
            )
        }
        for (c in state.checks) {
            out.appendLine(
                "RUNNING [${c.scope.ifEmpty { "default" }}] ${c.commit} | ${c.what} | started ${java.time.Instant.ofEpochMilli(c.startedAt)}" +
                    " | last output ${java.time.Instant.ofEpochMilli(c.lastOutputAt)} | ${c.startedBy ?: "-"}",
            )
        }
    }

    /**
     * VOICE-1 `notify`: raise a spoken attention alert. `agenthq notify --say "<short text>" [--level info|needs-owner|urgent]`
     * (the text may also be given bare). Only this short text is spoken; the project (--project / AGENTHQ_PROJECT_ID) is
     * named first by the client. The daemon pushes a `spoken` frame to every client; there is no reply to wait for.
     */
    private fun notify(stream: FrameStream, parsed: ParsedArgs, out: Appendable, err: Appendable, getenv: (String) -> String?): Int = runBlocking {
        checkContract(stream, err)
        val text = parsed.say ?: parsed.args.joinToString(" ")
        val req = com.superiorsoftllc.agenthq.notify.NotifyRequest(
            say = text,
            level = parsed.level ?: com.superiorsoftllc.agenthq.notify.NotifyLevel.INFO,
            projectId = projectIdOf(parsed, getenv),
            ticketId = getenv("AGENTHQ_TICKET_ID")?.trim()?.takeIf { it.isNotEmpty() },
        )
        val answer = stream.request(
            Envelope(Envelope.NOTIFY, json.encodeToString(com.superiorsoftllc.agenthq.notify.NotifyRequest.serializer(), req)),
            answerType = Envelope.SPOKEN, what = Envelope.NOTIFY, idempotent = false,
        )
        out.appendLine("notified: ${answer.data.let { runCatching { json.decodeFromString(com.superiorsoftllc.agenthq.notify.SpokenNotice.serializer(), it).say }.getOrDefault(text) }}")
        0
    }

    private fun notifyUsageError(parsed: ParsedArgs): String? {
        val text = parsed.say ?: parsed.args.joinToString(" ")
        val level = parsed.level
        return when {
            text.isBlank() -> "usage: agenthq notify --say \"<short text>\" [--level info|needs-owner|urgent]"
            level != null && level !in com.superiorsoftllc.agenthq.notify.NotifyLevel.ALL ->
                "--level must be one of ${com.superiorsoftllc.agenthq.notify.NotifyLevel.ALL.joinToString(", ")}"
            else -> null
        }
    }

    private fun pipelineUsageError(parsed: ParsedArgs, getenv: (String) -> String?): String? {
        val args = parsed.args
        val usage = "pipeline [show] | configure <stage>... [--exclusive] | clear | record <commit> <stage> [--body <evidence>] | " +
            "check start <commit> <what...> [--scope <dir>] | check ping|finish [--scope <dir>]"
        return when (args.firstOrNull() ?: "show") {
            "show", "clear" -> if (args.size > 1) "usage: agenthq $usage" else null
            "configure" -> if (args.size < 2) "pipeline configure needs at least one stage: agenthq pipeline configure <stage>... [--exclusive]" else null
            "record" -> if (args.size != 3) "usage: agenthq pipeline record <commit> <stage> [--body <evidence>]" else null
            "check" -> when (args.getOrNull(1)) {
                "start" -> if (args.size < 4) "usage: agenthq pipeline check start <commit> <what...> [--scope <dir>]" else null
                "ping", "finish" -> if (args.size != 2) "usage: agenthq pipeline check ${args[1]} [--scope <dir>]" else null
                else -> "usage: agenthq $usage"
            }
            else -> "usage: agenthq $usage"
        } ?: if (projectIdOf(parsed, getenv) == null) "pipeline needs a project: --project <id> or AGENTHQ_PROJECT_ID" else null
    }

    /** The usage error for a `decision` invocation, or null. Every subcommand needs a project. */
    private fun decisionUsageError(parsed: ParsedArgs, getenv: (String) -> String?): String? {
        val args = parsed.args
        val usage = "decision needs a subcommand: agenthq decision list [--all] | show <D7|7> | " +
            "add <summary> [--body <text|-> | --file <path>] [--supersedes <D1|1>]"
        val sub = args.firstOrNull() ?: return usage
        fun badRef(ref: String) = runCatching { parseDecisionRef(ref) }.isFailure
        val error = when (sub) {
            "list" -> if (parsed.supersedes != null) "decision list does not take --supersedes" else null
            "show" -> when {
                args.getOrNull(1)?.isBlank() != false -> "decision show requires a ref: agenthq decision show <D7|7>"
                badRef(args[1]) -> "invalid decision ref '${args[1]}'; expected D<n> or <n>"
                else -> null
            }
            "add" -> when {
                args.drop(1).all { it.isBlank() } -> "decision add requires a summary: agenthq decision add <summary> ..."
                parsed.body != null && parsed.file != null -> "decision add takes --body or --file, not both"
                parsed.supersedes != null && badRef(parsed.supersedes) ->
                    "invalid --supersedes '${parsed.supersedes}'; expected D<n> or <n>"
                parsed.all -> "decision add does not take --all"
                else -> null
            }
            else -> return usage
        }
        return error ?: if (projectIdOf(parsed, getenv) == null) {
            "decision $sub needs a project id (--project <id>, or ${AccessPolicy.PROJECT_ID_ENV})"
        } else {
            null
        }
    }

    // ------------------------------------------------------------------------
    // issue (P10-K7, docs/AGENTHQ-PHASE10-KNOWLEDGE.md K-D8)
    // ------------------------------------------------------------------------

    /**
     * `issue <list|show|open|attempt|fix|precheck> ...`: [validateArgs] has refused a missing subcommand, argument or
     * project. The session in `AGENTHQ_SESSION_ID` is sent on every write: it is the author AND the scope of the ACTIVE
     * issue an `attempt` or `fix` without `I<n>` goes to (the daemon resolves it, per session).
     */
    private fun issue(
        stream: FrameStream,
        parsed: ParsedArgs,
        out: Appendable,
        err: Appendable,
        getenv: (String) -> String?,
    ): Int = runBlocking {
        checkContract(stream, err)
        val projectId = projectIdOf(parsed, getenv) ?: return@runBlocking 2
        val sessionId = getenv(AccessPolicy.SESSION_ID_ENV)?.trim()?.takeIf { it.isNotEmpty() }
        val args = parsed.args
        when (args.first()) {
            "list" -> issueListing(
                stream,
                Envelope(
                    Envelope.ISSUE_LIST,
                    json.encodeToString(IssueList.serializer(), IssueList(projectId, includeFixed = parsed.all, location = parsed.location)),
                ),
                "issue_list",
                emptyText = (if (parsed.all) "No issues" else "No open issues") + " in project $projectId" +
                    (parsed.location?.let { " at $it" } ?: "") + ".",
                withEvents = false,
                out = out,
                err = err,
            )
            "precheck" -> issueListing(
                stream,
                Envelope(Envelope.ISSUE_PRECHECK, json.encodeToString(IssuePrecheck.serializer(), IssuePrecheck(projectId, args[1].trim()))),
                "issue_precheck",
                emptyText = "No issues at ${args[1].trim()} in project $projectId.",
                withEvents = true,
                out = out,
                err = err,
            )
            "show" -> {
                val issue = issueRequest(
                    stream,
                    Envelope(Envelope.ISSUE_GET, json.encodeToString(IssueGet.serializer(), IssueGet(projectId, args[1].trim()))),
                    "issue_get",
                    idempotent = true,
                    err = err,
                ) ?: return@runBlocking 1
                printIssue(issue, out)
                0
            }
            "open" -> {
                val request = IssueOpen(projectId, parsed.summary!!, parsed.location!!, sessionId, parsed.ticket?.trim())
                val issue = issueRequest(
                    stream,
                    Envelope(Envelope.ISSUE_OPEN, json.encodeToString(IssueOpen.serializer(), request)),
                    "issue_open",
                    idempotent = false,
                    err = err,
                ) ?: return@runBlocking 1
                out.appendLine("opened ${issue.ref}: ${issue.summary} (${issue.location})")
                0
            }
            "attempt" -> {
                val named = args.getOrNull(1)?.trim()
                val request = IssueAttempt(projectId, parsed.summary!!, sessionId, parsed.outcome?.trim(), parsed.location, named)
                val issue = issueRequest(
                    stream,
                    Envelope(Envelope.ISSUE_ATTEMPT, json.encodeToString(IssueAttempt.serializer(), request)),
                    "issue_attempt",
                    idempotent = false,
                    err = err,
                ) ?: return@runBlocking 1
                val event = issue.events.last()
                if (named == null && issue.implicit && issue.events.size == 1) {
                    out.appendLine("opened implicit ${issue.ref}: ${issue.summary} (no active issue for this session)")
                }
                out.appendLine("attempt #${event.seq} on ${issue.ref} (${event.outcome}): ${event.summary}")
                0
            }
            else -> {
                val request = IssueFix(projectId, parsed.summary!!, sessionId, args.getOrNull(1)?.trim())
                val issue = issueRequest(
                    stream,
                    Envelope(Envelope.ISSUE_FIX, json.encodeToString(IssueFix.serializer(), request)),
                    "issue_fix",
                    idempotent = false,
                    err = err,
                ) ?: return@runBlocking 1
                out.appendLine("fixed ${issue.ref}: ${issue.events.last().summary}")
                0
            }
        }
    }

    /**
     * One `issue_item` answer, or null after printing a malformed answer. The writes are NOT idempotent: a dropped
     * connection is reported instead of re-sending (a re-send could record an attempt twice).
     */
    private suspend fun issueRequest(stream: FrameStream, envelope: Envelope, what: String, idempotent: Boolean, err: Appendable): Issue? {
        val answer = stream.request(envelope, answerType = Envelope.ISSUE_ITEM, what = what, idempotent = idempotent)
        return try {
            json.decodeFromString(Issue.serializer(), answer.data)
        } catch (e: Exception) {
            err.appendLine("error: malformed issue_item answer from the daemon: ${e.message}")
            null
        }
    }

    /** `issue list` / `issue precheck`: one line per issue; precheck adds each issue's events under it. */
    private suspend fun issueListing(
        stream: FrameStream,
        envelope: Envelope,
        what: String,
        emptyText: String,
        withEvents: Boolean,
        out: Appendable,
        err: Appendable,
    ): Int {
        val answer = stream.request(envelope, answerType = Envelope.ISSUES, what = what, idempotent = true)
        val issues = try {
            json.decodeFromString(ListSerializer(Issue.serializer()), answer.data)
        } catch (e: Exception) {
            err.appendLine("error: malformed issues answer from the daemon: ${e.message}")
            return 1
        }
        if (issues.isEmpty()) {
            out.appendLine(emptyText)
            return 0
        }
        for (issue in issues) {
            out.appendLine(issueLine(issue))
            if (withEvents) issue.events.forEach { out.appendLine("    " + eventLine(it)) }
        }
        return 0
    }

    private fun issueLine(issue: Issue): String =
        "${issue.ref} | ${issue.status} | ${issue.summary}" +
            (if (issue.location.isNotEmpty()) " (${issue.location})" else "") +
            (if (issue.implicit) " [implicit]" else "")

    private fun eventLine(event: IssueEvent): String =
        "#${event.seq} " + (if (event.kind == IssueEvent.KIND_FIX) "fix" else "attempt ${event.outcome}") + ": " +
            event.summary.lineSequence().first() +
            (event.location?.let { " ($it)" } ?: "") +
            (event.sessionId?.let { " [session $it]" } ?: "")

    /** `issue show`: the whole issue, then every event in order with its full summary. */
    private fun printIssue(issue: Issue, out: Appendable) {
        out.appendLine("${issue.ref}: ${issue.summary}")
        out.appendLine("STATUS: ${issue.status}" + if (issue.implicit) " (implicit: opened by an attempt)" else "")
        out.appendLine("LOCATION: ${issue.location.ifEmpty { "-" }}")
        out.appendLine("TICKET: ${issue.ticketId ?: "-"}")
        out.appendLine(
            "OPENED BY: " + listOfNotNull(
                issue.openedByAgent?.let { "agent $it" },
                issue.openedBySession?.let { "session $it" },
            ).ifEmpty { listOf("-") }.joinToString(", "),
        )
        out.appendLine("CREATED: ${java.time.Instant.ofEpochMilli(issue.createdAt)}")
        if (issue.events.isEmpty()) {
            out.appendLine("No attempts yet.")
            return
        }
        out.appendLine()
        for (event in issue.events) {
            out.appendLine(eventLine(event))
            event.summary.lines().drop(1).forEach { out.appendLine("    $it") }
        }
    }

    /** The usage error for an `issue` invocation, or null. Every subcommand needs a project. */
    private fun issueUsageError(parsed: ParsedArgs, getenv: (String) -> String?): String? {
        val args = parsed.args
        val usage = "issue needs a subcommand: agenthq issue list [--all] [--location <p>] | show <I7|7> | " +
            "open --summary <s> --location <p> [--ticket <id>] | " +
            "attempt [I<n>] --summary <s> [--outcome worked|failed|partial] [--location <p>] | " +
            "fix [I<n>] --summary <s> | precheck <path>"
        val sub = args.firstOrNull() ?: return usage
        fun badRef(ref: String) = runCatching { parseIssueRef(ref) }.isFailure
        fun refArg(command: String): String? = when {
            args.size > 2 -> "issue $command takes at most one issue ref; put the text in --summary"
            args.size == 2 && badRef(args[1]) -> "invalid issue ref '${args[1]}'; expected I<n> or <n>"
            else -> null
        }
        val noSummary = parsed.summary?.isBlank() != false
        val error = when (sub) {
            "list" -> if (args.size > 1) "issue list takes no argument (use --location <p>)" else null
            "show" -> when {
                args.getOrNull(1)?.isBlank() != false -> "issue show requires a ref: agenthq issue show <I7|7>"
                args.size > 2 || badRef(args[1]) -> "invalid issue ref '${args.drop(1).joinToString(" ")}'; expected I<n> or <n>"
                else -> null
            }
            "precheck" -> when {
                args.getOrNull(1)?.isBlank() != false -> "issue precheck requires a path: agenthq issue precheck <path>"
                args.size > 2 -> "issue precheck takes one path"
                else -> null
            }
            "open" -> when {
                args.size > 1 -> "issue open takes no argument; use --summary <s> --location <p>"
                noSummary -> "issue open requires --summary <s>"
                parsed.location.isNullOrBlank() -> "issue open requires --location <path>: the path the issue is about"
                parsed.outcome != null -> "issue open does not take --outcome"
                else -> null
            }
            "attempt" -> refArg(sub) ?: when {
                noSummary -> "issue attempt requires --summary <s>: what was tried"
                parsed.outcome != null && parsed.outcome.trim() !in IssueEvent.OUTCOMES ->
                    "invalid --outcome '${parsed.outcome}'; expected worked, failed or partial"
                parsed.ticket != null -> "issue attempt does not take --ticket"
                else -> null
            }
            "fix" -> refArg(sub) ?: when {
                noSummary -> "issue fix requires --summary <s>: what fixed it"
                parsed.outcome != null || parsed.location != null || parsed.ticket != null ->
                    "issue fix takes only [I<n>] and --summary"
                else -> null
            }
            else -> return usage
        }
        return error ?: if (projectIdOf(parsed, getenv) == null) {
            "issue $sub needs a project id (--project <id>, or ${AccessPolicy.PROJECT_ID_ENV})"
        } else {
            null
        }
    }

    // ------------------------------------------------------------------------
    // traits (TRAITS-T7, docs/AGENTHQ-ROLE-TRAITS.md section 7; frames of contract 1.48.0 / 1.49.0)
    // ------------------------------------------------------------------------

    /** `trait <save|list|show|delete> ...`: [validateArgs] has refused a missing subcommand or id. Traits are daemon-wide, no project. */
    private fun trait(stream: FrameStream, parsed: ParsedArgs, out: Appendable, err: Appendable, input: () -> Reader): Int = runBlocking {
        checkContract(stream, err)
        when (parsed.args.first()) {
            "save" -> traitSave(stream, parsed, out, err, input)
            "list" -> traitList(stream, out, err)
            "show" -> traitShow(stream, parsed.args[1].trim(), out, err)
            else -> traitDelete(stream, parsed.args[1].trim(), out)
        }
    }

    /** `trait_list`, decoded; null (after one error line) on a malformed answer. */
    private suspend fun fetchTraits(stream: FrameStream, err: Appendable): List<TraitView>? {
        val answer = stream.request(Envelope(Envelope.TRAIT_LIST, ""), answerType = Envelope.TRAITS, what = "trait_list", idempotent = true)
        return try {
            json.decodeFromString(ListSerializer(TraitView.serializer()), answer.data)
        } catch (e: Exception) {
            err.appendLine("error: malformed traits answer from the daemon: ${e.message}")
            null
        }
    }

    /**
     * `trait save <id> [--name n] --body <text|-> [--report-to <role>] [--report-fields a,b]`. A save replaces the
     * whole trait (save-as-update by id), so a re-save without `--name` keeps the stored name, read first through
     * `trait_list` (never a refusing `trait_get`); a new trait without one is named after its id. Without
     * `--report-fields` the trait has no check. Prints the metadata, never the body.
     *
     * NOT idempotent: a save re-sent after a dropped connection could bump the version twice, so a drop is reported.
     */
    private suspend fun traitSave(stream: FrameStream, parsed: ParsedArgs, out: Appendable, err: Appendable, input: () -> Reader): Int {
        val id = parsed.args[1].trim()
        val body = if (parsed.body == "-") input().use { it.readText() } else parsed.body!!
        val name = parsed.name?.trim()?.takeIf { it.isNotEmpty() }
            ?: (fetchTraits(stream, err) ?: return 1).firstOrNull { it.id == id }?.name
            ?: id
        val check = parsed.reportFields?.let { fields ->
            TraitCheck(
                kind = TraitCheck.KIND_REPORT,
                reportTo = parsed.reportTo?.trim()?.takeIf { it.isNotEmpty() } ?: TraitCheck.DEFAULT_REPORT_TO,
                fields = splitList(fields),
            )
        }
        val answer = stream.request(
            Envelope(Envelope.TRAIT_SAVE, json.encodeToString(TraitSave.serializer(), TraitSave(id, name, body, check))),
            answerType = Envelope.TRAIT_ITEM,
            what = "trait_save",
            idempotent = false,
        )
        val saved = try {
            json.decodeFromString(TraitView.serializer(), answer.data)
        } catch (e: Exception) {
            err.appendLine("error: malformed trait_item answer from the daemon: ${e.message}")
            return 1
        }
        out.appendLine("saved ${saved.id} v${saved.version}")
        out.appendLine("name: ${saved.name}")
        out.appendLine("check: ${checkLine(saved.check)}")
        out.appendLine("chars: ${saved.body.length}")
        return 0
    }

    private fun checkLine(check: TraitCheck?): String =
        check?.let { "${it.kind} to ${it.reportTo}: ${it.fields.joinToString(", ")}" } ?: "none"

    /** `trait list`: `ID | VERSION | NAME | CHECK`, ordered by id. */
    private suspend fun traitList(stream: FrameStream, out: Appendable, err: Appendable): Int {
        val traits = fetchTraits(stream, err) ?: return 1
        if (traits.isEmpty()) {
            out.appendLine("No traits.")
            return 0
        }
        out.appendLine(TRAIT_HEADER)
        traits.sortedBy { it.id }.forEach { t ->
            val check = t.check?.let { "${it.kind} to ${it.reportTo} (${it.fields.size} fields)" } ?: "-"
            out.appendLine("${t.id} | ${t.version} | ${t.name} | $check")
        }
        return 0
    }

    /** `trait show <id>`: the whole trait (`trait_get`). An unknown id is the daemon's refusal, exit 1. */
    private suspend fun traitShow(stream: FrameStream, id: String, out: Appendable, err: Appendable): Int {
        val answer = stream.request(Envelope(Envelope.TRAIT_GET, id), answerType = Envelope.TRAIT_ITEM, what = "trait_get", idempotent = true)
        val trait = try {
            json.decodeFromString(TraitView.serializer(), answer.data)
        } catch (e: Exception) {
            err.appendLine("error: malformed trait_item answer from the daemon: ${e.message}")
            return 1
        }
        out.appendLine("ID: ${trait.id}")
        out.appendLine("NAME: ${trait.name}")
        out.appendLine("VERSION: ${trait.version}")
        out.appendLine("CHECK: ${checkLine(trait.check)}")
        out.appendLine("UPDATED: ${iso(trait.updatedAt)}")
        out.appendLine()
        out.appendLine(trait.body)
        return 0
    }

    /**
     * `trait delete <id>`: answered by the `traits` push. A trait a role or an agent still binds is refused
     * (`trait_delete refused: ... still bound to ...`), which [run] prints as the error line, exit 1.
     * Not re-sent after a drop; a second delete of a gone trait is refused as unknown anyway.
     */
    private suspend fun traitDelete(stream: FrameStream, id: String, out: Appendable): Int {
        stream.request(Envelope(Envelope.TRAIT_DELETE, id), answerType = Envelope.TRAITS, what = "trait_delete", idempotent = false)
        out.appendLine("deleted $id")
        return 0
    }

    /**
     * `role traits <roleId> [--set a,b | --clear]`: without a flag, the stored list (`role_traits_get`); `--set`
     * replaces it in order and `--clear` empties it (`role_traits_set`, answered by the `role_traits` push).
     * The daemon refuses an unknown role or trait, a duplicate and more than 8, nothing written. A set is a
     * whole-list replace, so re-sending it after a drop is safe.
     */
    private fun role(stream: FrameStream, parsed: ParsedArgs, out: Appendable, err: Appendable, getenv: (String) -> String?, input: () -> java.io.Reader): Int = runBlocking {
        checkContract(stream, err)
        if (parsed.args.first() != "traits") return@runBlocking roleCatalog(stream, parsed, out, err, getenv, input)
        val roleId = parsed.args[1].trim()
        val answer = if (parsed.set == null && !parsed.clear) {
            stream.request(Envelope(Envelope.ROLE_TRAITS_GET, roleId), answerType = Envelope.ROLE_TRAITS, what = "role_traits_get", idempotent = true)
        } else {
            val ids = if (parsed.clear) emptyList() else splitList(parsed.set!!)
            stream.request(
                Envelope(Envelope.ROLE_TRAITS_SET, json.encodeToString(RoleTraitsSave.serializer(), RoleTraitsSave(roleId, ids))),
                answerType = Envelope.ROLE_TRAITS,
                what = "role_traits_set",
                idempotent = true,
            )
        }
        val stored = try {
            json.decodeFromString(RoleTraits.serializer(), answer.data)
        } catch (e: Exception) {
            err.appendLine("error: malformed role_traits answer from the daemon: ${e.message}")
            return@runBlocking 1
        }
        out.appendLine("${stored.roleId}: " + (stored.traitIds.joinToString(", ").ifEmpty { "no traits" }))
        0
    }

    /**
     * ROLE-EDIT-1: `role list | show <id> | notes <id> --body <text|-> | describe <id> --body <text> | delete <id>`, on the
     * `role_list` / `role_save` / `role_delete` frames. A caller inside a session (an agent) may list, show and change the
     * notes or description of an existing role, and nothing else: `delete` is refused for it, as `agenthq_role_delete` is.
     */
    private suspend fun roleCatalog(stream: FrameStream, parsed: ParsedArgs, out: Appendable, err: Appendable, getenv: (String) -> String?, input: () -> java.io.Reader): Int {
        val sub = parsed.args.first()
        val id = parsed.args.getOrNull(1)?.trim().orEmpty()
        val roles = json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(com.superiorsoftllc.agenthq.role.Role.serializer()),
            stream.request(Envelope(Envelope.ROLE_LIST, ""), answerType = Envelope.ROLES, what = "role_list", idempotent = true).data,
        )
        if (sub == "list") {
            roles.forEach { out.appendLine("${it.id}	${it.name}	${it.source.name.lowercase()}	${it.description}") }
            return 0
        }
        val role = roles.firstOrNull { it.id == com.superiorsoftllc.agenthq.role.RoleIds.normalize(id) }
        if (role == null) {
            err.appendLine("error: unknown role '$id'")
            return 1
        }
        when (sub) {
            "show" -> {
                out.appendLine("ID: ${role.id}")
                out.appendLine("NAME: ${role.name}")
                out.appendLine("SOURCE: ${role.source.name.lowercase()}")
                out.appendLine("DESCRIPTION: ${role.description}")
                out.appendLine()
                out.appendLine(role.persona)
            }
            "notes", "describe" -> {
                val text = if (parsed.body == "-") input().use { it.readText() } else parsed.body!!
                val save = com.superiorsoftllc.agenthq.protocol.RoleSave(
                    id = role.id,
                    name = role.name,
                    persona = if (sub == "notes") text else role.persona,
                    description = if (sub == "describe") text.trim() else null,
                )
                stream.request(
                    Envelope(Envelope.ROLE_SAVE, json.encodeToString(com.superiorsoftllc.agenthq.protocol.RoleSave.serializer(), save)),
                    answerType = Envelope.ROLE, what = "role_save", idempotent = true,
                )
                out.appendLine("saved ${role.id} ($sub); it applies to new sessions, not running ones")
            }
            "delete" -> {
                if (sessionOf(getenv) != null || !getenv("AGENTHQ_ROLE").isNullOrBlank()) {
                    err.appendLine("error: role delete needs a non-agent caller: an agent may only change a role's notes and description")
                    return 1
                }
                val reset = role.id in com.superiorsoftllc.agenthq.role.RoleIds.BUILT_IN
                stream.request(
                    Envelope(Envelope.ROLE_DELETE, role.id),
                    answerType = if (reset) Envelope.ROLE else Envelope.ROLE_REMOVED, what = "role_delete", idempotent = false,
                )
                out.appendLine(if (reset) "reset ${role.id} to its bundled definition" else "deleted ${role.id}")
            }
        }
        return 0
    }

    /** `a, b ,c` as `[a, b, c]`: trimmed, blanks dropped. */
    private fun splitList(value: String): List<String> = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    // ------------------------------------------------------------------------
    // skills (P10-K14, docs/AGENTHQ-PHASE10-KNOWLEDGE.md section 6; frames of contract 1.45.0 / 1.56.0)
    // ------------------------------------------------------------------------

    /** `skill <list|show>`: [validateArgs] has refused a missing subcommand or name. Skills are daemon-wide, no project. */
    private fun skill(stream: FrameStream, parsed: ParsedArgs, out: Appendable, err: Appendable): Int = runBlocking {
        checkContract(stream, err)
        when (parsed.args.first()) {
            "list" -> skillList(stream, out, err)
            else -> skillShow(stream, parsed.args.drop(1).joinToString(" ").trim(), out, err)
        }
    }

    /**
     * `skill list`: one `name: description` line per skill, by name. `attachedOnly` skills are left out (K-D9: they are
     * hidden from every session they are not attached to); `skill show` still opens one by name or id.
     */
    private suspend fun skillList(stream: FrameStream, out: Appendable, err: Appendable): Int {
        val answer = stream.request(Envelope(Envelope.SKILL_LIST, ""), answerType = Envelope.SKILLS, what = "skill_list", idempotent = true)
        val skills = try {
            json.decodeFromString(ListSerializer(SkillView.serializer()), answer.data)
        } catch (e: Exception) {
            err.appendLine("error: malformed skills answer from the daemon: ${e.message}")
            return 1
        }
        val visible = skills.filterNot { it.attachedOnly }.sortedWith(compareBy({ it.name }, { it.id }))
        if (visible.isEmpty()) {
            out.appendLine("No skills.")
            return 0
        }
        visible.forEach { s -> out.appendLine(if (s.description.isEmpty()) s.name else "${s.name}: ${s.description}") }
        return 0
    }

    /** `skill show <name|id>`: the whole skill (`skill_get`, which takes either). An unknown one is the daemon's refusal, exit 1. */
    private suspend fun skillShow(stream: FrameStream, key: String, out: Appendable, err: Appendable): Int {
        val answer = stream.request(Envelope(Envelope.SKILL_GET, key), answerType = Envelope.SKILL_ITEM, what = "skill_get", idempotent = true)
        val skill = try {
            json.decodeFromString(SkillView.serializer(), answer.data)
        } catch (e: Exception) {
            err.appendLine("error: malformed skill_item answer from the daemon: ${e.message}")
            return 1
        }
        out.appendLine("ID: ${skill.id}")
        out.appendLine("NAME: ${skill.name}")
        out.appendLine("DESCRIPTION: ${skill.description.ifEmpty { "-" }}")
        out.appendLine("TAGS: ${skill.tags.joinToString(", ").ifEmpty { "-" }}")
        if (skill.attachedOnly) out.appendLine("ATTACHED ONLY: yes")
        out.appendLine("UPDATED: ${iso(skill.updatedAt)}")
        out.appendLine()
        out.appendLine(skill.body)
        return 0
    }

    /** The usage error for a `skill` invocation, or null. Skills are daemon-wide: no project is needed. */
    private fun skillUsageError(parsed: ParsedArgs): String? {
        val args = parsed.args
        return when (args.firstOrNull()) {
            "list" -> if (args.size > 1) "skill list takes no arguments: agenthq skill list" else null
            "show" -> if (args.drop(1).joinToString(" ").isBlank()) "skill show requires a skill name or id: agenthq skill show <name|id>" else null
            else -> "skill needs a subcommand: agenthq skill list | show <name|id>"
        }
    }

    /** The usage error for a `trait` invocation, or null. Traits are daemon-wide: no project is needed. */
    private fun traitUsageError(parsed: ParsedArgs): String? {
        val args = parsed.args
        val usage = "trait needs a subcommand: agenthq trait save <id> [--name n] --body <text|-> " +
            "[--report-to <role> --report-fields a,b] | list | show <id> | delete <id>"
        val sub = args.firstOrNull() ?: return usage
        val noId = args.getOrNull(1)?.isBlank() != false
        return when (sub) {
            "list" -> null
            "show", "delete" -> if (noId) "trait $sub requires a trait id: agenthq trait $sub <id>" else null
            "save" -> when {
                noId -> "trait save requires a trait id: agenthq trait save <id> --body <text|->"
                parsed.body == null -> "trait save requires --body <text> (or --body - to read stdin)"
                parsed.reportTo != null && parsed.reportFields == null -> "trait save --report-to needs --report-fields a,b"
                parsed.reportFields != null && splitList(parsed.reportFields).isEmpty() -> "trait save --report-fields needs at least one field"
                else -> null
            }
            else -> usage
        }
    }

    /** The usage error for a `role` invocation, or null. */
    private fun roleUsageError(parsed: ParsedArgs): String? {
        val args = parsed.args
        val usage = "role needs a subcommand: agenthq role list | show <id> | notes <id> --body <text|-> | describe <id> --body <text> | delete <id> | traits <roleId> [--set a,b | --clear]"
        when (args.firstOrNull()) {
            "list" -> return null
            "show", "delete" -> return if (args.getOrNull(1)?.isBlank() != false) "role ${args[0]} requires a role id" else null
            "notes", "describe" -> return when {
                args.getOrNull(1)?.isBlank() != false -> "role ${args[0]} requires a role id"
                parsed.body == null -> "role ${args[0]} requires --body <text> (or --body - to read stdin)"
                else -> null
            }
            "traits" -> Unit
            else -> return usage
        }
        return when {
            args.getOrNull(1)?.isBlank() != false -> "role traits requires a role id: agenthq role traits <roleId> [--set a,b | --clear]"
            parsed.set != null && parsed.clear -> "role traits takes --set or --clear, not both"
            parsed.set != null && splitList(parsed.set).isEmpty() -> "role traits --set needs at least one trait id; use --clear to empty the list"
            else -> null
        }
    }

    /**
     * The session this command speaks as: [AccessPolicy.SESSION_ID_ENV], which the daemon exports
     * into every spawned agent. Null at a terminal, where there is no session to be.
     */
    private fun sessionOf(getenv: (String) -> String?): String? =
        getenv(AccessPolicy.SESSION_ID_ENV)?.trim()?.takeIf { it.isNotEmpty() }

    /** A mailbox instant as an ISO-8601 UTC stamp to the second: millis are exact but unreadable. */
    private fun iso(epochMillis: Long): String = WHEN_FORMAT.format(Instant.ofEpochMilli(epochMillis))

    /** The first non-blank line of a body, clipped so one long line cannot break the `inbox` table. */
    private fun firstLineOf(body: String): String =
        body.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(FIRST_LINE_WIDTH) ?: ""

    /**
     * The project this command is about: `--project`, else [AccessPolicy.PROJECT_ID_ENV] (set by the
     * daemon for a spawned agent), else null for "nothing named one".
     *
     * Null is a MEANINGFUL answer, not a fallback: for `stories` it means the empty payload, which is
     * every project; for `epics` it is a usage error. The caller decides which, because only it knows
     * what its frame does with an empty payload.
     */
    private fun projectIdOf(parsed: ParsedArgs, getenv: (String) -> String?): String? =
        parsed.project?.trim()?.takeIf { it.isNotEmpty() }
            ?: getenv(AccessPolicy.PROJECT_ID_ENV)?.trim()?.takeIf { it.isNotEmpty() }

    // ------------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------------

    private suspend fun checkContract(stream: FrameStream, err: Appendable) {
        val ready = stream.await(Envelope.READY)
        contractNotice(EventVocabulary.VERSION, ready.data)?.let { notice ->
            // A minor difference is a hint, printed once per process; a major one warns on every call.
            if (notice.major || contractHintShown.compareAndSet(false, true)) err.appendLine(notice.line)
        }
    }

    private val contractHintShown = java.util.concurrent.atomic.AtomicBoolean(false)

    /** What [checkContract] prints, or nothing; [major] marks a real incompatibility. */
    internal data class ContractNotice(val major: Boolean, val line: String)

    /**
     * BUG-CLI-1: the CLI's contract [cliVersion] against the `contract=` of a ready frame's [readyData]. Same rule as
     * the UI status bar: only a different MAJOR is a warning. A different minor/patch (CLI and daemon from different
     * builds, still compatible) is a one-line hint; the same version, nothing. A ready frame with no `contract=` warns.
     */
    internal fun contractNotice(cliVersion: String, readyData: String): ContractNotice? {
        val daemon = readyData.split(' ')
            .firstOrNull { it.startsWith("contract=") }
            ?.removePrefix("contract=")
            ?.removePrefix("agenthq/")
            ?: return ContractNotice(true, "warning: no contract version in ready frame: $readyData")
        if (daemon == cliVersion) return null
        fun majorOf(v: String) = v.substringBefore('.')
        fun shortOf(v: String) = v.split('.').take(2).joinToString(".")
        return if (majorOf(daemon) != majorOf(cliVersion)) {
            ContractNotice(true, "warning: incompatible contract: CLI $cliVersion, daemon $daemon (ready frame: $readyData)")
        } else {
            ContractNotice(false, "hint: CLI ${shortOf(cliVersion)}, daemon ${shortOf(daemon)}: rebuild/reinstall both for new features")
        }
    }

    /** `pod_query` and unwrap the answer, turning the daemon's `error` frame into a failure. */
    private suspend fun queryPod(stream: FrameStream, podId: String): PodStatus {
        val answer = stream.request(
            Envelope(Envelope.POD_QUERY, podId),
            answerType = Envelope.POD,
            what = "pod_query",
            idempotent = true,
        )
        return podFrame(answer)
    }

    private fun podFrame(answer: Envelope): PodStatus =
        decodePod(answer) ?: error("malformed pod frame")

    private fun renderPod(out: Appendable, status: PodStatus) {
        out.appendLine("[pod] ${status.pod.id.take(8)} ${status.pod.state} goal=${status.pod.goal}")
        status.nodes.forEach { node ->
            out.appendLine(
                "  [node] ${node.id.take(8)} ${node.name} ${node.state} " +
                    "attempts=${node.attempts}/${node.maxAttempts}"
            )
        }
    }

    private fun outcomeOf(state: SessionState): String = when (state) {
        SessionState.IDLE -> "SUCCEEDED"
        SessionState.FAILED -> "FAILED"
        SessionState.EXITED -> "KILLED"
        SessionState.ORPHANED -> "ORPHANED"
        SessionState.BUSY, SessionState.STARTING -> "RUNNING"
        SessionState.WAITING_INPUT -> "WAITING"
    }

    private fun decodePod(frame: Envelope): PodStatus? =
        runCatching { json.decodeFromString(PodStatus.serializer(), frame.data) }.getOrNull()

    private fun decodeStatus(frame: Envelope): StatusPayload? =
        runCatching { json.decodeFromString(StatusPayload.serializer(), frame.data) }.getOrNull()

    private fun decodeFinished(frame: Envelope): FinishedPayload? =
        runCatching { json.decodeFromString(FinishedPayload.serializer(), frame.data) }.getOrNull()

    /**
     * The daemon this command goes to, and the credential it sends.
     *
     * Precedence for the address: `--url`, then `--endpoint NAME`, then an explicit `--host` / `--port`, then
     * `AGENTHQ_URL`, then the default host and port (127.0.0.1:8787). For the token: `--token-file`, then the
     * selected endpoint's own token file, and ONLY when no endpoint is selected the environment
     * (`AGENTHQ_TOKEN_FILE`, then `AGENTHQ_TOKEN`). An endpoint never inherits the environment token: that would
     * send one daemon's secret to whichever daemon the user named.
     *
     * BUG-G2d: a typed `--port` beats `AGENTHQ_URL`. The daemon exports `AGENTHQ_URL` into every session it spawns
     * (BUG-G2c), so with the old order a `--port` typed INSIDE a session -- a test suite's own fixture daemon was the
     * G2 case -- was silently sent to the live daemon instead, and every test `submit` opened a real pod there.
     */
    private fun resolveTarget(parsed: ParsedArgs, getenv: (String) -> String?): Target {
        val endpoint = parsed.endpoint?.let { name ->
            val file = parsed.endpointsFile?.let { File(it) } ?: Endpoints.defaultFile(getenv)
            Endpoints.load(file).firstOrNull { it.name == name }
                ?: throw UsageFailure("no endpoint named '$name' in ${file.path}")
        }
        val typed = "ws://${parsed.host}:${parsed.port}/ws"
        val rawUrl = parsed.url
            ?: endpoint?.url
            ?: typed.takeIf { parsed.addressGiven }
            ?: getenv(AgentHqBin.URL_ENV)?.takeIf { it.isNotBlank() }
            ?: installedUrl(getenv)
            ?: typed
        val url = normaliseUrl(rawUrl)
        val (token, source) = when {
            parsed.tokenFile != null -> AccessPolicy.readTokenFile(File(parsed.tokenFile)) to "--token-file"
            endpoint != null -> endpoint.tokenFile
                ?.let { AccessPolicy.readTokenFile(File(it)) to "endpoint '${endpoint.name}'" }
                ?: (null to "endpoint '${endpoint.name}', which names no token file")
            else -> environmentToken(getenv, installedStateDir(getenv))
        }
        return Target(url = url, token = token, tokenSource = source, answerTimeoutMs = answerTimeoutMs(getenv))
    }

    /** BUG-G2d: the daemon URL [args] would connect to under [getenv], or null for unusable args. Contacts nothing. */
    internal fun targetUrl(args: Array<String>, getenv: (String) -> String?): String? =
        parseArgs(args, StringBuilder())?.let { resolveTarget(it, getenv).url }

    /**
     * The installed app's state dir when the environment names one (`AGENTHQ_INSTALL_PORT` / `AGENTHQ_INSTALL_STATE_DIR`),
     * else null: a dev shell with neither keeps the dev defaults. Default dir = `%LOCALAPPDATA%\SuperiorSoft\Infantry AI`.
     */
    internal fun installedStateDir(getenv: (String) -> String?): File? {
        val dir = getenv("AGENTHQ_INSTALL_STATE_DIR")?.takeIf { it.isNotBlank() }
        val port = getenv("AGENTHQ_INSTALL_PORT")?.takeIf { it.isNotBlank() }
        if (dir == null && port == null) return null
        if (dir != null) return File(dir).absoluteFile
        val base = getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
            ?: File(System.getProperty("user.home"), "AppData/Local").path
        return File(File(base, "SuperiorSoft"), "AgentHQ").absoluteFile
    }

    /** The installed daemon's address: `AGENTHQ_INSTALL_PORT` (default 8797, the installed app's own default). */
    internal fun installedUrl(getenv: (String) -> String?): String? {
        installedStateDir(getenv) ?: return null
        val port = getenv("AGENTHQ_INSTALL_PORT")?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 } ?: 8797
        return "ws://127.0.0.1:$port/ws"
    }

    private fun environmentToken(getenv: (String) -> String?, installDir: File? = null): Pair<String?, String> {
        // tokenFromEnvironment refuses BOTH variables set at once, so the CLI and the daemon agree on that.
        val envToken = AccessPolicy.tokenFromEnvironment(getenv)
        // Remote access on: the token the daemon generated is picked up automatically on this machine.
        val token = envToken
            ?: installDir?.let { d -> if (RemoteAccess.load(d).remote) RemoteAccess.storedToken(d) else null }
            ?: RemoteAccess.clientToken(getenv)
        val source = when {
            getenv(AccessPolicy.TOKEN_FILE_ENV)?.isNotBlank() == true -> AccessPolicy.TOKEN_FILE_ENV
            envToken != null -> AccessPolicy.TOKEN_ENV
            token != null -> "stored remote-access token"
            else -> "no token configured"
        }
        return token to source
    }

    /** Accepts `ws://` / `wss://` URLs with a host, and adds `/ws` when the path is omitted. */
    private fun normaliseUrl(raw: String): String {
        if (!raw.startsWith("ws://") && !raw.startsWith("wss://")) {
            throw UsageFailure("the daemon URL must start with ws:// or wss://, got '$raw'")
        }
        val uri = try {
            URI.create(raw)
        } catch (e: IllegalArgumentException) {
            throw UsageFailure("not a valid daemon URL: '$raw'")
        }
        if (uri.host.isNullOrBlank()) throw UsageFailure("the daemon URL has no host: '$raw'")
        return if (uri.path.isNullOrEmpty() || uri.path == "/") raw.trimEnd('/') + "/ws" else raw
    }

    /** A token sent over ws:// to another machine crosses the network in cleartext. Say so, once. */
    private fun warnIfTokenWouldTravelInCleartext(target: Target, err: Appendable) {
        if (target.token == null || !target.url.startsWith("ws://")) return
        val host = runCatching { URI.create(target.url).host }.getOrNull() ?: return
        if (AccessPolicy.isLoopbackHost(host)) return
        err.appendLine(
            "warning: the token is sent in cleartext over ws:// to $host; use wss:// through a TLS proxy " +
                "or a tunnel (see README, \"Remote daemon\")"
        )
    }

    private fun printUsage(to: Appendable) {
        to.appendLine(usageText())
    }

    /**
     * MAIL-FOOTER-1: `agenthq mail` / `agenthq mailbox`, the two names people guess for the mailbox: only the
     * "Mail:" group of [usageText], so its commands are found without reading the whole list.
     */
    private fun printMailUsage(to: Appendable) {
        to.appendLine(usageText().lineSequence().dropWhile { it != "Mail:" }.takeWhile { it.isNotBlank() }.joinToString("\n"))
    }

    /** ASSISTANT-1: the real `--help` text, so the Assistant's standing instructions are generated from it and stay current. */
    fun helpText(): String = usageText()

    /** The whole `--help`, grouped under headings (MAIL-FOOTER-1) so related commands sit together. */
    private fun usageText(): String =
        """
            Infantry AI CLI - control the Infantry AI daemon (`infantry`; `agenthq` is an alias)

            Usage: infantry [connection options] <command> [args...]

            Tickets:
              tickets             List tickets from the daemon
              tickets --project <id>         Only that project's tickets
              board [--project <id>]         One-call summary: tickets by column, each with its worker, live state, branch@commit
              tickets --status <column>      Only that column: backlog, todo, in_progress, pending, done
              tickets --epic <id|prefix> --project <id>   Only that epic's tickets, in board order
              ticket show <id|prefix>        One ticket in full: title, status, agent, links, description
              ticket create --title <t> [--desc <d> | --desc-file <path> | --desc -] [--project <id>] [--epic <id|prefix>] [--role <id>] [--provider <id>] [--model <id>] [--weight 1-5] [--image <file>]... [--status todo|backlog]   Save a todo (or backlog) ticket; prints its id
              ticket update <id|prefix> [--title <t>] [--desc <d> | --desc-file <path> | --desc -] [--epic <id|prefix>] [--role <id>] [--provider <id>] [--model <id>] [--weight 1-5|0] [--image <file>]... [--remove-image <path>]...   Edit a ticket; flags left out keep their value
                                  --weight: how likely the ticket is to come back wrong, not its size (ROUTING-1 rubric):
                        4-5 touches the wire / contract, the daemon, auth or project scope, concurrency or SSR timing,
                            an Android build, or more than one module
                        3   gate fixes and bisects; UI that depends on daemon behaviour
                        1-2 copy changes, icons, renames, docs: mechanical, one owner file
                      An unweighted ticket is flagged; set a weight before it starts (project routing rules match on it).
                      --image (repeatable) attaches a png/jpeg/gif/webp file (max 10 MB), uploaded to the
                                  project's .agenthq/uploads/ticket/; on update it is ADDED to the stored images.
                                  --remove-image (repeatable) drops an attached image, named by the path `ticket show`
                                  prints, its file name, or the original file. A missing, oversized or non-image file,
                                  or an image the ticket does not have, is refused and nothing is saved
                                  Multi-line description: use --desc-file <path> (or --desc - to read stdin). A multi-line
                                  --desc is cut at its first line on Windows (the agenthq.cmd launcher drops the rest).
              ticket move <id|prefix> <status>   Move one ticket to a column (backlog, todo, in_progress, pending, done); a move to
                                  in_progress is a start (see ticket start): a refused or held one exits 1 with the reason
              ticket move <id|prefix> [<status>] --before <id|prefix> | --after <id|prefix> | --top | --bottom | --position <n>   Reorder: ticket order is priority
                                  (the board and `tickets` list a column in that order; new tickets go to the bottom)
              ticket start <id|prefix>       Start ticket work this way, never with a spawn: the worker runs the ticket's
                                  role, provider and model on its own ticket/<short-id>-<slug> branch; the ticket goes
                                  In Progress. A refused or held start exits 1 with the reason
              ticket link <id|prefix> <otherId|prefix> <type>   Link one ticket to another (see the link types)
              remote on|off|status  Let the daemon accept outside (Tailscale) connections; prints the ws address
                                  and token. Takes effect on the next daemon restart.
              bg run [--name L] [--keep] -- CMD  Run CMD as a registered background job (session reads Running; --keep: survives the session closing); bg list|status|logs|stop ID
              bg run --notify [--notify-on RE]... [--notify-tail N] [--notify-timeout MIN] [--no-default-patterns] -- CMD
                                     Start CMD detached; the daemon MAILS this session on exit, a pattern match or the timeout: end your turn, never sleep/poll
              bg wait ID [--timeout MIN]  Block until job ID exits (fallback when mail cannot wake the harness); prints its tail, returns its exit code
              progress <0-100> ["label"] | plan "a" "b" .. | plan N | step [name] | bytes D T | count D T | status "label" | done | fail | clear | show
                                  Report your long job's progress, shown as a live bar on your card (progress --help: all forms)
              progress --watch [--reader gradle|junit|gate]   Copy stdin to stdout unchanged, reporting ##infantry marker lines
              run [--label L] [--reader R] -- CMD   Run CMD with its output and exit code unchanged; progress done on exit 0, failed otherwise
                                  Harmless outside Infantry AI: no session or daemon = no report, exit 0 (run keeps CMD's code)
              harnesses           The harnesses (provider ids) a ticket can pin, and each one's models
              usage [show]        Plan usage per meter (claude, opencode-go, ...): percent used, reset, pace, and how many
                                  ticket workers the pacing rule allows (default <60%: 3, <80%: 2, else 1) vs how many run
              usage set <meter> <pct> [<resetsInMin>] | clear <meter>   Type a percent for a meter with no readable usage
              usage override <meter> <n|none> [<hours>] | override <meter> clear   Allowance override (default: until reset, else 2h)
              usage rule <60:3,80:2,1|on|off|default>   The pacing rule: below:workers tiers, the last one workers alone
              settings [get [<key>]]   The daemon's durable settings, one `key = value` per line
              settings set <key> <value>   Store one (a number, true/false, JSON, or text); a refusal names the accepted range
              settings limits     Every limit (caps, thresholds, timeouts): value, default, range, what 0 means, its env name
              processes [--all]   Rogue background processes (Gradle/Kotlin daemons, node, JVMs) left by ended or idle sessions,
                                  with MB, age and owner session; --all adds healthy ones and heavy processes that are not ours
              processes reap <pid> | --all   Kill one process (or every rogue one) that a session of this daemon started;
                                  anything else is refused. Setting processes.autoReap (default off) reaps rogue ones on a timer
              harness-status      Which harness CLIs are installed / missing / outdated; harness-install [id...] [--update] installs them via npm
              epics --project <id> List that project's epics, with each one's goal and story count
              epic show <id|prefix> [--project <id>]   One epic: its title, then its tickets as ID | STATUS | TITLE | AGENT
              epic create --title <t> [--desc <d>] [--project <id>]   Save an epic; prints its id
              epic base <epicId> [<branch>|--clear]   Read/set/clear the branch the epic's new tickets cut from (BASE-1c)
              stories              DEPRECATED: one notice, then the tickets filed under an epic (tickets --epic)
              stories --epic <id|prefix> --project <id>   The same rows as tickets --epic
              stories --status <column>      A ticket column; the old `doing` reads as in_progress
              project base <projectId> [<branch>|--clear]   The branch new ticket starts in the project cut from:
              project rename <projectId> "<name>"   Rename a project; its id, tickets, sessions and mail are untouched
                                  read it, set it (refused unless it resolves where tickets are cut), or clear it
                                  (then AGENTHQ_BASE_BRANCH, else the start is refused). Tickets already started keep their base.
              project config get <projectId>   Each role's cap on concurrent agents (LANE-CAPS; unlisted roles run 1)
              project config set <projectId> <role> <n>   Set a role's cap (n >= 1); lowering never stops a running agent
              project config set <projectId> auto-remove-done-workers on|off   End and remove a done ticket's worker session (default off)
              project routing <projectId> [--file <rules.json> | --clear]   The task routing rules (ROUTING-1): print them, replace
                                  them with a JSON array ({name, weights?, ticketTypes?, role?, provider, model, effort?, setRole?},
                                  first match wins, the last one a default row with no match field), or clear them. A rule naming a
                                  provider / model its harness does not list is refused; Opus only as pi + anthropic/claude-opus-5-5
              project branches <projectId>   The project repo's local branches; * marks the checked-out one
              image upload <projectId> <file> [--purpose chat|ticket]   Store a png/jpeg/gif/webp (max 10 MB) in
                                  the project's .agenthq/uploads/<purpose>/ (default ticket) and print its absolute
                                  path (same bytes and purpose = same path)

            Agents and sessions:
              agents              Who is available: live agents (BUSY/IDLE/OFFLINE) plus saved profiles with no
                                  live session yet (with a project: --project <id> or AGENTHQ_PROJECT_ID; without
                                  one, profiles only). A profile is a saved template; an agent is a running session.
              agents --profiles   Saved profiles only (templates, not running)
              agents --live --project <id>  Live agents only: a project's roster (who's here)
              ask --question <q> --option "label|description" [--option ...] [--multi] [--other] [--timeout <sec>]
                                             Ask the owner a choice for AGENTHQ_SESSION_ID's session and wait;
                                             prints {"answers":[...],"otherText":...}. Exit 0 answered,
                                             3 no answer / timeout, 2 usage error or refused
              ask --question <q> --option "label|consequence" [--option ...] --recommend <label> --reason <why>
                  [--no-other] [--default-after <min>]   (the owner may always type an own answer; --no-other forbids it)
                                             Ask the owner WITHOUT waiting (preferred): prints {"questionId":..}
                                             at once; the pick arrives as mail in thread question:<id>.
                                             Exit 0 asked, 2 usage error / refused / rate limited
              role traits <roleId> [--set a,b | --clear]   Show, replace (in order) or empty a role's traits
              trait save <id> [--name <n>] --body <text|-> [--report-to <role> --report-fields a,b]
                                             Save a trait (a re-save bumps its version); prints metadata
              trait list                     The trait library as ID | VERSION | NAME | CHECK
              trait show <id>                One trait in full
              trait delete <id>              Delete a trait; refused while a role or agent still binds it
              skill list                     The skills library as name: description lines (attached-only
                                             skills are left out)
              skill show <name|id>           One skill in full, by exact name or by id
              logs <sessionId>    A run's raw stdout / stderr as it is produced
              session rm <sessionId|prefix>   Remove an ENDED session (exited, failed, orphaned) from the
                                  session list the sidebar shows; refused while it runs (kill it first).
                                  Keeps the ticket, runs history, branch/worktree and mail; it can no longer be resumed

            Mail:
              send <agentId|role> "message"  Message an agent, by id or role; print its message id
              send <agentId|role> "message" --reply-to <msgId>  Answer that message, in its thread (the 8-char id or the full one)
              inbox               The messages addressed to me (--unread: only the unread ones)
              read <msgId>        Print one message and mark it read; takes the 8-char id `inbox` shows
              threads             The conversations I am in: who else, how many, when it last moved
              thread <id>         One conversation, message by message, in thread order
              waiting             Every open question on this daemon, and who each one waits on
              mail, mailbox       Print only these mail commands
                                  cmd.exe: wrap the message in "double quotes" (never 'single'), keep it on one
                                  line, and quote a thread id: it contains |. See docs/AGENT-QUICKREF.md

            Memory and decisions:
              knowledge                      The project's knowledge at a glance: counts, then the notes,
                                             the active decisions (<= 50) and the open issues (<= 30),
                                             each list capped with an "N more" line
              memory index [--folder <f>]    The project's memory notes as folder/name: description
                                             (above 60 notes: folders with counts, then root notes)
              memory show <name>             One memory note in full
              memory search <term>           Ranked memory hits with snippets
              memory save <name> [--description <d>] [--folder <f>] [--append | --edit --find <passage>]
                          (--content <text> | --file <path> | -)
                                             Replace (default; needs --description), append (one blank line
                                             between) or edit one passage; prints metadata, never the body
              decision list [--all]          The project's active decisions as D<n> | summary;
                                             --all adds each superseded one under the decision that replaced it
              decision show <D7|7>           One decision in full: status, chain, author and body
              decision add <summary> [--body <text|-> | --file <path>] [--supersedes <D1|1>]
                                             Append a decision (never edited or deleted: supersede it instead);
                                             the author is AGENTHQ_SESSION_ID's session
              issue list [--all] [--location <p>]
                                             The project's open issues as I<n> | status | summary (location);
                                             --all adds fixed ones, --location keeps those at or under <p>
              issue show <I7|7>              One issue in full, with every attempt and its fix
              issue open --summary <s> --location <p> [--ticket <id>]
                                             Open an issue; it becomes this session's active issue
              issue attempt [I<n>] --summary <s> [--outcome worked|failed|partial] [--location <p>]
                                             Record an attempt (default outcome failed) on I<n>, else on this
                                             session's active issue, else on a new implicit issue
              issue fix [I<n>] --summary <s> Close I<n>, else this session's active issue
              issue precheck <path>          The issues at or under <path>, open first, with their attempts;
                                             run it before changing a file

            Daemon and pods:
              submit "<goal>"     Create a pod, print the pod id (--project <id>: its project)
              submit "<goal>"     Create a pod, print the pod id
              watch <podId>       Stream a pod until it converges, fails or is cancelled
              pods                List the sessions the daemon knows about
              runs <podId>        List a pod's runs with outcome and session id
              cancel <podId>      Cancel a pod gracefully (--force to kill the trees)
              mcp serve           Serve Infantry AI tools (read + spawn/prompt/message/kill) as an MCP server on stdio
              mcp serve --max-depth <n> --max-spawns <n> [--allow-kill-any] [--mcp-depth <n>] [--lineage-dir <dir>]
                        [--project <id>]   defaults also read from ./.agenthq/mcp.json (flags win), see docs/MCP.md
                                  Write-tool guardrails (defaults 2 / 4 / own spawns only), see docs/MCP.md

            Where to connect (first one given wins):
              --url URL           A ws:// or wss:// daemon URL, e.g. ws://10.0.0.5:8787/ws
              --endpoint NAME     A daemon from the endpoints file
              --host HOST         Daemon host (default: 127.0.0.1)
              --port PORT         Daemon port (default: 8787)
              AGENTHQ_URL         Environment fallback for --url

            Credentials:
              --token-file FILE   Read the bearer token from FILE (never pass the token itself)
              --endpoints FILE    Endpoints JSON (default: AGENTHQ_ENDPOINTS, else <state dir>/endpoints.json)
              AGENTHQ_TOKEN_FILE  Token file, used when no endpoint is named
              AGENTHQ_TOKEN       Token value, used when no endpoint is named

            Options:
              --force             With cancel: kill the process trees outright
              --live              With agents: only the live agents (the project's roster)
              --profiles          With agents: only the saved profiles
              --project <id>      With tickets: only that project's tickets (default:
                                  AGENTHQ_PROJECT_ID, else every project)
                                  With agents --live: the project to roster
                                  REQUIRED with epics: epic_list matches the project exactly, so
                                  there is no all-projects listing to fall back to
              --status <column>   With tickets: backlog, todo, in_progress, pending or done
                                  With stories: the same, or the old doing (= in_progress)
              --reply-to <msgId>  With send: answer that message, in its thread and to its sender (8-char id from inbox, or the full id)
              --unread            With inbox: only the messages that have not been read yet
              AGENTHQ_PROJECT_ID  Env fallback for --project (set by a spawned agent)
              AGENTHQ_SESSION_ID  The session `send` speaks as, and the mailbox `inbox`, `read` and
                                  `threads` read (set by a spawned agent); without it a message is
                                  sent as "$HUMAN_SENDER" and `inbox` reads the "$HUMAN_SENDER" mailbox
              --help, -h          Show this help
              --version, version  Print this CLI's contract and build and the daemon's: agenthq <cli> build <b> (daemon: <contract> build <b>|not running)
        """.trimIndent()

    private data class ParsedArgs(
        val command: String? = null,
        val args: List<String> = emptyList(),
        val host: String = DEFAULT_HOST,
        val port: Int = DEFAULT_PORT,
        /** BUG-G2d: `--host` or `--port` was typed, so it beats an inherited `AGENTHQ_URL`. */
        val addressGiven: Boolean = false,
        val force: Boolean = false,
        val live: Boolean = false,
        /** HARNESS-INSTALL-1: `--update` (harness-install refreshes outdated CLIs) / `--check` (harness-status asks npm for the latest). */
        val update: Boolean = false,
        val check: Boolean = false,
        val profiles: Boolean = false,
        val project: String? = null,
        val status: String? = null,
        val title: String? = null,
        val description: String? = null,
        /** BUG-CLI-1: `--desc-file <path>`: the description is that file's text, read before connecting. */
        val descFile: String? = null,
        /** BUG-CLI-1: `--desc -`: the description is read from stdin before connecting. */
        val descStdin: Boolean = false,
        val replyTo: String? = null,
        val unread: Boolean = false,
        /** Contract 1.100.0: `send --cross-project` overrides the cross-project guard. */
        val crossProject: Boolean = false,
        val help: Boolean = false,
        /** BUG-CLI-4: `--version` (or the `version` command) prints the CLI's and the daemon's contract. */
        val version: Boolean = false,
        val url: String? = null,
        val endpoint: String? = null,
        val endpointsFile: String? = null,
        val tokenFile: String? = null,
        val epic: String? = null,
        /** `ticket move --before <id|prefix>`: put the ticket just above that one. */
        val before: String? = null,
        /** `ticket move --top` / `--bottom`: first or last place in its column. */
        val top: Boolean = false,
        val bottom: Boolean = false,
        /** `ticket move --after <id|prefix>`: just below that one. `--position <n>`: index n of the column (0 = top). */
        val after: String? = null,
        val position: String? = null,
        // `mcp serve` guardrails (P11-B, docs/MCP.md).
        val mcpDepth: Int? = null,
        val maxDepth: Int? = null,
        val maxSpawns: Int? = null,
        val allowKillAny: Boolean = false,
        val lineageDir: String? = null,
        // `memory save` (P10-K3).
        val folder: String? = null,
        // NOTES-1: `memory ... --scope project|role|session [--role <id>]`.
        val scope: String? = null,
        /** VOICE-1: `notify --say <text> [--level info|needs-owner|urgent]`. */
        val say: String? = null,
        val level: String? = null,
        val append: Boolean = false,
        val exclusive: Boolean = false,
        val edit: Boolean = false,
        val find: String? = null,
        val content: String? = null,
        val file: String? = null,
        val stdin: Boolean = false,
        // `decision list --all` / `decision add --supersedes` (P10-K5).
        val all: Boolean = false,
        val supersedes: String? = null,
        // `issue open / attempt / fix` (P10-K7).
        val summary: String? = null,
        val location: String? = null,
        val ticket: String? = null,
        val outcome: String? = null,
        // `trait save` / `role traits` (TRAITS-T7).
        val name: String? = null,
        val body: String? = null,
        val reportTo: String? = null,
        val reportFields: String? = null,
        val set: String? = null,
        val clear: Boolean = false,
        // BUG-E2E-1: `ticket create` / `ticket update` executor pins (the TICKET-EXEC-UI fields).
        val role: String? = null,
        val provider: String? = null,
        val model: String? = null,
        val weight: Int? = null,
        // `ask` (PROMPT-5).
        val question: String? = null,
        val options: List<String> = emptyList(),
        val multi: Boolean = false,
        val other: Boolean = false,
        val noOther: Boolean = false,
        val timeout: String? = null,
        /** ASK-1: `ask --recommend <label> --reason ".." [--default-after <min>]` (raw text, like --question). */
        val recommend: String? = null,
        val reason: String? = null,
        val defaultAfter: String? = null,
        /** ASK-2: `ask --test` raises a canned sample owner question ([QuestionAsk.sample]). */
        val test: Boolean = false,
        /** IMG-1: `image upload --purpose chat|ticket` (default ticket). */
        val purpose: String? = null,
        /** IMG-AGENT-1: `ticket create|update --image <file>` (repeatable): image files to attach. */
        val images: List<String> = emptyList(),
        /** IMG-AGENT-1: `ticket update --remove-image <path>` (repeatable): attached images to drop. */
        val removeImages: List<String> = emptyList(),
    )

    /**
     * The write tools' guardrails. Depth: `--mcp-depth`, else `AGENTHQ_MCP_DEPTH`, else the depth an MCP server
     * recorded for this process's own session (`AGENTHQ_SESSION_ID`) in the lineage directory, else 0.
     */
    private fun mcpGuardrails(parsed: ParsedArgs, config: McpServeConfig, getenv: (String) -> String?): McpGuardrails {
        val lineage = McpLineage(
            (parsed.lineageDir ?: getenv(McpGuardrails.LINEAGE_DIR_ENV)?.trim()?.takeIf { it.isNotEmpty() })
                ?.let(::File) ?: McpLineage.defaultDir(),
        )
        val envDepth = getenv(McpGuardrails.DEPTH_ENV)?.trim()?.takeIf { it.isNotEmpty() }?.let {
            it.toIntOrNull()?.takeIf { d -> d >= 0 }
                ?: throw UsageFailure("${McpGuardrails.DEPTH_ENV} must be a whole number >= 0, got '$it'")
        }
        val depth = parsed.mcpDepth ?: envDepth ?: lineage.depthOf(getenv(AccessPolicy.SESSION_ID_ENV)) ?: 0
        return McpGuardrails(
            depth = depth,
            maxDepth = config.maxDepth,
            maxSpawns = config.maxSpawns,
            allowKillAny = config.allowKillAny ?: false,
            lineage = lineage,
        )
    }

    /** Null means the arguments were unusable; the reason has already been written to [err]. */
    private fun parseArgs(args: Array<String>, err: Appendable): ParsedArgs? {
        val rest = mutableListOf<String>()
        var host = DEFAULT_HOST
        var port = DEFAULT_PORT
        var addressGiven = false
        var force = false
        var live = false
        var update = false
        var check = false
        var profiles = false
        var project: String? = null
        var status: String? = null
        var title: String? = null
        var purpose: String? = null
        var description: String? = null
        var descFile: String? = null
        var descStdin = false
        var descSources = 0
        var replyTo: String? = null
        var unread = false
        var crossProject = false
        var help = false
        var version = false
        var url: String? = null
        var endpoint: String? = null
        var endpointsFile: String? = null
        var tokenFile: String? = null
        var epic: String? = null
        var before: String? = null
        var top = false
        var bottom = false
        var after: String? = null
        var position: String? = null
        var mcpDepth: Int? = null
        var maxDepth: Int? = null
        var maxSpawns: Int? = null
        var allowKillAny = false
        var lineageDir: String? = null
        var folder: String? = null
        var scope: String? = null
        var say: String? = null
        var level: String? = null
        var append = false
        var exclusive = false
        var edit = false
        var find: String? = null
        var content: String? = null
        var file: String? = null
        var stdin = false
        var name: String? = null
        var body: String? = null
        var reportTo: String? = null
        var reportFields: String? = null
        var set: String? = null
        var clear = false
        var all = false
        var supersedes: String? = null
        var summary: String? = null
        var location: String? = null
        var ticket: String? = null
        var outcome: String? = null
        var role: String? = null
        var provider: String? = null
        var model: String? = null
        var weight: Int? = null
        var question: String? = null
        val options = mutableListOf<String>()
        val images = mutableListOf<String>()
        val removeImages = mutableListOf<String>()
        var multi = false
        var other = false
        var noOther = false
        var timeout: String? = null
        var recommend: String? = null
        var reason: String? = null
        var defaultAfter: String? = null
        var test = false

        var i = 0
        while (i < args.size) {
            when (val arg = args[i]) {
                "--host" -> {
                    val value = args.getOrNull(++i) ?: return fail(err, "--host expects a value")
                    if (value.startsWith("-")) return fail(err, "--host expects a value")
                    host = value
                    addressGiven = true
                    i++
                }
                "--port" -> {
                    port = args.getOrNull(++i)?.toIntOrNull()
                        ?: return fail(err, "--port expects a number")
                    addressGiven = true
                    i++
                }
                "--url" -> {
                    url = flagValue(args, ++i) ?: return fail(err, "--url expects a value")
                    i++
                }
                "--endpoint" -> {
                    endpoint = flagValue(args, ++i) ?: return fail(err, "--endpoint expects a name")
                    i++
                }
                "--endpoints" -> {
                    endpointsFile = flagValue(args, ++i) ?: return fail(err, "--endpoints expects a file path")
                    i++
                }
                "--token-file" -> {
                    tokenFile = flagValue(args, ++i) ?: return fail(err, "--token-file expects a file path")
                    i++
                }
                "--force" -> {
                    force = true
                    i++
                }
                "--update" -> {
                    update = true
                    i++
                }
                "--check" -> {
                    check = true
                    i++
                }
                "--live" -> {
                    live = true
                    i++
                }
                "--profiles" -> {
                    profiles = true
                    i++
                }
                "--project" -> {
                    project = flagValue(args, ++i) ?: return fail(err, "--project expects a project id")
                    i++
                }
                "--status" -> {
                    status = flagValue(args, ++i)
                        ?: return fail(err, "--status expects a column: ${TicketStatus.ALL.joinToString(", ")}")
                    i++
                }
                "--title" -> {
                    title = flagValue(args, ++i) ?: return fail(err, "--title expects a value")
                    i++
                }
                "--purpose" -> {
                    purpose = flagValue(args, ++i) ?: return fail(err, "--purpose expects chat or ticket")
                    i++
                }
                "--image" -> {
                    images += flagValue(args, ++i) ?: return fail(err, "--image expects an image file path")
                    i++
                }
                "--remove-image" -> {
                    removeImages += flagValue(args, ++i)
                        ?: return fail(err, "--remove-image expects an attached image (its path or file name)")
                    i++
                }
                // BUG-CLI-1: free text, taken RAW like --description, so a markdown list item ("- a") or a
                // multi-line value is kept exactly. `--desc -` reads it from stdin, `--desc-file <path>` from a file:
                // a multi-line --desc does NOT survive the Windows launcher (cmd.exe ends %* at the first newline).
                "--desc" -> {
                    val value = args.getOrNull(++i) ?: return fail(err, "--desc expects a value (or - for stdin)")
                    if (value == "-") descStdin = true else description = value
                    descSources++
                    i++
                }
                "--desc-file" -> {
                    descFile = args.getOrNull(++i)?.takeIf { it.isNotBlank() }
                        ?: return fail(err, "--desc-file expects a file path")
                    descSources++
                    i++
                }
                // Free text: taken RAW, so a value starting with '-' (a markdown list item) is still a value.
                "--description" -> {
                    description = args.getOrNull(++i) ?: return fail(err, "--description expects a value")
                    i++
                }
                "--content" -> {
                    content = args.getOrNull(++i) ?: return fail(err, "--content expects a value")
                    i++
                }
                // Free text, taken RAW like --content; `--body -` reads it from stdin.
                "--body" -> {
                    body = args.getOrNull(++i) ?: return fail(err, "--body expects a value (or - for stdin)")
                    i++
                }
                "--name" -> {
                    name = args.getOrNull(++i) ?: return fail(err, "--name expects a value")
                    i++
                }
                "--report-to" -> {
                    reportTo = flagValue(args, ++i) ?: return fail(err, "--report-to expects a role id")
                    i++
                }
                // Raw: a field label may hold a space ("wall time").
                "--report-fields" -> {
                    reportFields = args.getOrNull(++i) ?: return fail(err, "--report-fields expects a comma-separated list")
                    i++
                }
                "--set" -> {
                    set = flagValue(args, ++i) ?: return fail(err, "--set expects comma-separated trait ids")
                    i++
                }
                "--clear" -> {
                    clear = true
                    i++
                }
                "--all" -> {
                    all = true
                    i++
                }
                "--supersedes" -> {
                    supersedes = flagValue(args, ++i) ?: return fail(err, "--supersedes expects a decision ref (D1 or 1)")
                    i++
                }
                "--summary" -> {
                    summary = args.getOrNull(++i) ?: return fail(err, "--summary expects a one-line summary")
                    i++
                }
                "--location" -> {
                    location = flagValue(args, ++i) ?: return fail(err, "--location expects a path")
                    i++
                }
                "--ticket" -> {
                    ticket = flagValue(args, ++i) ?: return fail(err, "--ticket expects a ticket id")
                    i++
                }
                "--outcome" -> {
                    outcome = flagValue(args, ++i) ?: return fail(err, "--outcome expects worked, failed or partial")
                    i++
                }
                "--find" -> {
                    find = args.getOrNull(++i) ?: return fail(err, "--find expects the passage to replace")
                    i++
                }
                "--folder" -> {
                    folder = flagValue(args, ++i) ?: return fail(err, "--folder expects a folder")
                    i++
                }
                "--say" -> {
                    say = flagValue(args, ++i) ?: return fail(err, "--say expects the text to speak")
                    i++
                }
                "--level" -> {
                    level = flagValue(args, ++i) ?: return fail(err, "--level expects info, needs-owner or urgent")
                    i++
                }
                "--scope" -> {
                    scope = flagValue(args, ++i) ?: return fail(err, "--scope expects project, role or session")
                    i++
                }
                "--file" -> {
                    file = flagValue(args, ++i) ?: return fail(err, "--file expects a file path")
                    i++
                }
                "--exclusive" -> {
                    exclusive = true
                    i++
                }
                "--append" -> {
                    append = true
                    i++
                }
                "--edit" -> {
                    edit = true
                    i++
                }
                // `-` alone reads the text from stdin (`memory save <name> -`).
                "-" -> {
                    stdin = true
                    i++
                }
                "--role" -> {
                    role = flagValue(args, ++i) ?: return fail(err, "--role expects a role id (see: agenthq role list)")
                    i++
                }
                "--provider" -> {
                    provider = flagValue(args, ++i) ?: return fail(err, "--provider expects a harness id (claude, opencode, pi, ...)")
                    i++
                }
                "--model" -> {
                    model = flagValue(args, ++i) ?: return fail(err, "--model expects a model id the provider lists")
                    i++
                }
                // TICKET-WEIGHT: 1..5 sets, 0 clears.
                "--weight" -> {
                    weight = flagValue(args, ++i)?.toIntOrNull()?.takeIf { it in 0..TicketWeight.MAX }
                        ?: return fail(err, "--weight expects ${TicketWeight.MIN}..${TicketWeight.MAX} (0 clears it)")
                    i++
                }
                // PROMPT-5: `ask`. Question and option text are taken RAW, like --summary.
                "--question" -> {
                    question = args.getOrNull(++i) ?: return fail(err, "--question expects the question text")
                    i++
                }
                "--option" -> {
                    options += args.getOrNull(++i) ?: return fail(err, "--option expects \"label|description\"")
                    i++
                }
                "--multi" -> {
                    multi = true
                    i++
                }
                "--no-other" -> {
                    noOther = true
                    i++
                }
                "--other" -> {
                    other = true
                    i++
                }
                // ASK-1: `ask --recommend` = a NON-BLOCKING owner question (question_ask).
                "--recommend" -> {
                    recommend = args.getOrNull(++i) ?: return fail(err, "--recommend expects the label of the recommended option")
                    i++
                }
                "--test" -> {
                    test = true
                    i++
                }
                "--reason" -> {
                    reason = args.getOrNull(++i) ?: return fail(err, "--reason expects why the recommended option is recommended")
                    i++
                }
                "--default-after" -> {
                    defaultAfter = args.getOrNull(++i) ?: return fail(err, "--default-after expects whole minutes")
                    i++
                }
                "--timeout" -> {
                    timeout = args.getOrNull(++i) ?: return fail(err, "--timeout expects whole seconds")
                    i++
                }
                "--before" -> {
                    before = flagValue(args, ++i)
                        ?: return fail(err, "--before expects a ticket id or prefix (see: agenthq tickets)")
                    i++
                }
                "--top" -> {
                    top = true
                    i++
                }
                "--after" -> {
                    after = flagValue(args, ++i)
                        ?: return fail(err, "--after expects a ticket id or prefix (see: agenthq tickets)")
                    i++
                }
                "--position" -> {
                    position = flagValue(args, ++i)
                        ?: return fail(err, "--position expects a whole number, 0 = the top of the column")
                    i++
                }
                "--bottom" -> {
                    bottom = true
                    i++
                }
                "--epic" -> {
                    epic = flagValue(args, ++i)
                        ?: return fail(err, "--epic expects an epic id or prefix (see: agenthq epics --project <id>)")
                    i++
                }
                "--reply-to" -> {
                    replyTo = flagValue(args, ++i)
                        ?: return fail(err, "--reply-to expects a message id (see: agenthq send)")
                    i++
                }
                "--unread" -> {
                    unread = true
                    i++
                }
                "--cross-project" -> {
                    crossProject = true
                    i++
                }
                "--mcp-depth" -> {
                    mcpDepth = flagValue(args, ++i)?.toIntOrNull()?.takeIf { it >= 0 }
                        ?: return fail(err, "--mcp-depth expects a whole number >= 0")
                    i++
                }
                "--max-depth" -> {
                    maxDepth = flagValue(args, ++i)?.toIntOrNull()?.takeIf { it >= 0 }
                        ?: return fail(err, "--max-depth expects a whole number >= 0")
                    i++
                }
                "--max-spawns" -> {
                    maxSpawns = flagValue(args, ++i)?.toIntOrNull()?.takeIf { it >= 0 }
                        ?: return fail(err, "--max-spawns expects a whole number >= 0")
                    i++
                }
                "--allow-kill-any" -> {
                    allowKillAny = true
                    i++
                }
                "--lineage-dir" -> {
                    lineageDir = flagValue(args, ++i) ?: return fail(err, "--lineage-dir expects a directory path")
                    i++
                }
                "--help", "-h" -> {
                    help = true
                    i++
                }
                "--version" -> {
                    version = true
                    i++
                }
                // Flags are accepted anywhere, not only before the command: `watch <podId> --port N`
                // is how anyone actually types it.
                else -> {
                    if (arg.startsWith("-")) return fail(err, "unknown flag '$arg'")
                    rest += arg
                    i++
                }
            }
        }
        if (descSources > 1) return fail(err, "give the description once: --desc <text>, --desc - (stdin) or --desc-file <path>")
        return ParsedArgs(
            command = rest.firstOrNull(),
            args = rest.drop(1),
            host = host,
            port = port,
            addressGiven = addressGiven,
            force = force,
            live = live,
            update = update,
            check = check,
            profiles = profiles,
            project = project,
            status = status,
            replyTo = replyTo,
            unread = unread,
            crossProject = crossProject,
            help = help,
            version = version,
            url = url,
            endpoint = endpoint,
            endpointsFile = endpointsFile,
            title = title,
            description = description,
            descFile = descFile,
            descStdin = descStdin,
            epic = epic,
            before = before,
            top = top,
            bottom = bottom,
            after = after,
            position = position,
            tokenFile = tokenFile,
            mcpDepth = mcpDepth,
            maxDepth = maxDepth,
            maxSpawns = maxSpawns,
            allowKillAny = allowKillAny,
            lineageDir = lineageDir,
            folder = folder,
            scope = scope,
            say = say,
            level = level,
            append = append,
            exclusive = exclusive,
            edit = edit,
            find = find,
            content = content,
            file = file,
            stdin = stdin,
            name = name,
            body = body,
            reportTo = reportTo,
            reportFields = reportFields,
            set = set,
            clear = clear,
            all = all,
            supersedes = supersedes,
            summary = summary,
            location = location,
            ticket = ticket,
            outcome = outcome,
            role = role,
            provider = provider,
            model = model,
            weight = weight,
            question = question,
            options = options.toList(),
            images = images.toList(),
            removeImages = removeImages.toList(),
            multi = multi,
            other = other,
            noOther = noOther,
            timeout = timeout,
            recommend = recommend,
            reason = reason,
            defaultAfter = defaultAfter,
            test = test,
            purpose = purpose,
        )
    }

    /** A flag's value: the next argument, unless it is missing or is itself a flag. */
    /**
     * BUG-CLI-1: the description named by `--desc -` (stdin) or `--desc-file <path>`, read VERBATIM (newlines,
     * markdown, leading '-' all kept) into [ParsedArgs.description]. Null, with the reason on [err], when the
     * file cannot be read. A plain `--desc <text>` passes through untouched.
     */
    private fun resolveDescription(parsed: ParsedArgs, input: () -> Reader, err: Appendable): ParsedArgs? = when {
        parsed.descStdin -> parsed.copy(description = input().use { it.readText() }, descStdin = false)
        parsed.descFile != null -> try {
            parsed.copy(description = File(parsed.descFile).readText(), descFile = null)
        } catch (e: Exception) {
            fail(err, "cannot read --desc-file ${parsed.descFile}: ${e.message}")
        }
        else -> parsed
    }

    private fun flagValue(args: Array<String>, index: Int): String? =
        args.getOrNull(index)?.takeUnless { it.startsWith("-") }

    private fun fail(err: Appendable, message: String): ParsedArgs? {
        err.appendLine("error: $message")
        return null
    }

    /**
     * Validates that a command has its required arguments BEFORE any connection is attempted.
     * Returns an error message if validation fails, or null if the arguments are valid.
     */
    private fun validateArgs(command: String, parsed: ParsedArgs, getenv: (String) -> String? = { null }): String? = when (command) {
        "submit" -> if (parsed.args.isEmpty()) "submit requires a goal" else null
        "mcp" -> if (parsed.args != listOf("serve")) "mcp needs a subcommand: agenthq mcp serve" else null
        "watch" -> if (parsed.args.isEmpty()) "watch requires a pod id" else null
        "runs" -> if (parsed.args.isEmpty()) "runs requires a pod id" else null
        "logs" -> if (parsed.args.isEmpty()) "logs requires a session id (see: agenthq runs <podId>)" else null
        "cancel" -> if (parsed.args.isEmpty()) "cancel requires a pod id" else null
        // `--status` names a column of the board, so a value the board has no column for is a usage
        // error naming the ones that exist -- never a silently empty listing.
        // `--epic` is a prefix resolved among the project's epics, and `epic_list` matches the project
        // exactly, so it needs a project: refused here, before connecting, like `epics`.
        "tickets" -> boardListUsageError("tickets", parsed, getenv, TicketStatus.ALL)
        // E1-e: `stories` lists board tickets now, so its columns are the ticket board's; the old story
        // column `doing` stays accepted and reads as `in_progress`.
        "stories" -> boardListUsageError("stories", parsed, getenv, TicketStatus.ALL + STORY_DOING)
        // `epic_list` filters on an EXACT project match, so there is nothing to send without one --
        // an empty payload answers `[]`, not "every project". Refused here, BEFORE connecting, so
        // the user is told what is missing instead of being shown an empty listing for a daemon full
        // of epics. `stories` deliberately has no such rule: there the empty payload IS every project.
        "epics" -> if (projectIdOf(parsed, getenv) == null) {
            "epics requires a project id (--project <id>, or ${AccessPolicy.PROJECT_ID_ENV})"
        } else {
            null
        }
        "agents" -> null
        // `send` needs two things -- someone to write to and something to say -- and both are refused
        // BEFORE the socket opens, so a typo is never reported as a failed connection. A second
        // argument that is present but empty (`send pm ""`) is a missing message, not a message.
        "send" -> when {
            // A recipient that is absent or blank, and a message that is absent or blank: both are the
            // typo this command is most often hit by, so both are named rather than sent to the daemon
            // to be refused as "two distinct, non-empty sessions".
            parsed.args.isEmpty() || parsed.args.first().isBlank() ->
                "send requires a recipient: agenthq send <agentId|role> \"message\""
            parsed.args.drop(1).all { it.isBlank() } ->
                "send requires a message: agenthq send <agentId|role> \"message\""
            else -> null
        }
        // `read` names ONE message, so a missing or blank id is the typo this command is hit by.
        // Refused before the socket opens, like `send`'s two requirements, so a slip is a usage error
        // and never a failed connection the user did not ask for.
        "read" -> if (parsed.args.firstOrNull()?.isBlank() != false) {
            "read requires a message id: agenthq read <msgId> (see: agenthq inbox)"
        } else {
            null
        }
        // `thread` names ONE conversation, so a missing or blank id is the typo it is hit by --
        // refused before the socket opens, exactly like `read`'s.
        "thread" -> if (parsed.args.firstOrNull()?.isBlank() != false) {
            "thread requires a thread id: agenthq thread <id> (see: agenthq threads)"
        } else {
            null
        }
        "ticket" -> ticketUsageError(parsed)
        "harnesses" -> if (parsed.args.isNotEmpty()) "harnesses takes no arguments: agenthq harnesses" else null
        "harness-status" -> if (parsed.args.isNotEmpty()) "harness-status takes no arguments: agenthq harness-status [--check]" else null
        "harness-install" -> null
        "processes" -> ProcessesCli.usageError(parsed.args, parsed.all)
        "epic" -> epicUsageError(parsed, getenv)
        "project" -> projectUsageError(parsed)
        "image" -> imageUsageError(parsed)
        "kill" -> if (parsed.args.size != 1) "usage: agenthq kill <sessionId|prefix> [--force]" else null
        "session" -> if (parsed.args.size != 2 || parsed.args[0] !in listOf("rm", "restart")) "usage: agenthq session rm|restart <sessionId|prefix> [--force] (rm --force kills a running session first)" else null
        "knowledge" -> knowledgeUsageError(parsed, getenv)
        "memory" -> memoryUsageError(parsed, getenv)
        "decision" -> decisionUsageError(parsed, getenv)
        "pipeline" -> pipelineUsageError(parsed, getenv)
        "notify" -> notifyUsageError(parsed)
        "issue" -> issueUsageError(parsed, getenv)
        "trait" -> traitUsageError(parsed)
        "skill" -> skillUsageError(parsed)
        "role" -> roleUsageError(parsed)
        "ask" -> askUsageError(parsed, getenv)
        "usage" -> UsageCli.usageError(parsed.args)
        "settings" -> SettingsCli.usageError(parsed.args)
        else -> null
    }

    /** IMG-1: the usage error for an `image` invocation, or null when it is valid. */
    private fun imageUsageError(parsed: ParsedArgs): String? {
        val usage = "agenthq image upload <projectId> <file> [--purpose chat|ticket]"
        val args = parsed.args
        return when {
            args.firstOrNull() != "upload" -> "image needs a subcommand: $usage"
            args.getOrNull(1)?.isBlank() != false -> "image upload requires a project id: $usage"
            args.getOrNull(2)?.isBlank() != false -> "image upload requires a file: $usage"
            args.size > 3 -> "image upload takes one file: $usage"
            !File(args[2]).isFile -> "image upload: no such file: ${args[2]}"
            parsed.purpose != null && parsed.purpose !in com.superiorsoftllc.agenthq.protocol.ImageUpload.PURPOSES ->
                "image upload: --purpose must be chat or ticket, not '${parsed.purpose}': $usage"
            else -> null
        }
    }

    /** BASE-1a: the usage error for a `project` invocation, or null when it is valid. */
    private fun projectUsageError(parsed: ParsedArgs): String? {
        val args = parsed.args
        val usage = "agenthq project base <projectId> [<branch>|--clear] | branches <projectId> | rename <projectId> \"<name>\" | config get <projectId> | config set <projectId> <role> <n> | config set <projectId> $AUTO_REMOVE_SETTING on|off | routing <projectId> [--file <rules.json>|--clear]"
        return when {
            args.firstOrNull() == "config" -> when {
                args.getOrNull(1) == "get" && args.size == 3 && args[2].isNotBlank() -> null
                args.getOrNull(1) == "set" && args.size == 5 && args[2].isNotBlank() && args[3].trim() == AUTO_REMOVE_SETTING ->
                    if (onOff(args[4]) != null) null else "project config set <projectId> $AUTO_REMOVE_SETTING takes on or off: $usage"
                args.getOrNull(1) == "set" && args.size == 5 && args[2].isNotBlank() && args[3].isNotBlank() &&
                    (args[4].trim().toIntOrNull() ?: 0) >= 1 -> null
                else -> "project config takes get <projectId> or set <projectId> <role> <n >= 1>: $usage"
            }
            args.firstOrNull() == "routing" -> when {
                args.size != 2 || args[1].isBlank() -> "project routing takes one project id: agenthq project routing <projectId> [--file <rules.json> | --clear]"
                parsed.clear && parsed.file != null -> "project routing takes --file or --clear, not both"
                parsed.file != null && !File(parsed.file).isFile -> "project routing: no such file: ${parsed.file}"
                else -> null
            }
            args.firstOrNull() !in setOf("base", "branches", "rename") -> "project needs a subcommand: $usage"
            args.getOrNull(1)?.isBlank() != false -> "project ${args[0]} requires a project id: $usage"
            args[0] == "branches" && (args.size > 2 || parsed.clear) -> "project branches takes only a project id: $usage"
            args[0] == "rename" -> when {
                parsed.clear || args.size != 3 || args[2].isBlank() -> "project rename takes a project id and one non-blank name: $usage"
                else -> null
            }
            args.size > 3 -> "project base takes one branch: $usage"
            parsed.clear && args.size == 3 -> "project base takes a branch or --clear, not both: $usage"
            args.size == 3 && args[2].isBlank() -> "project base: the branch is blank (use --clear to remove it): $usage"
            else -> null
        }
    }

    /**
     * The usage error for an `epic` invocation, or null when its subcommand and arguments are valid. Both
     * subcommands need a project: an epic is found and saved inside one, so a missing project is refused
     * here, before the socket opens, exactly like `epics`.
     */
    private fun epicUsageError(parsed: ParsedArgs, getenv: (String) -> String?): String? {
        val noProject = "needs a project id (--project <id>, or ${AccessPolicy.PROJECT_ID_ENV})"
        val baseUsage = "agenthq epic base <epicId> [<branch>|--clear]"
        return when (parsed.args.firstOrNull()) {
            // BASE-1c: an epic id is global, so `epic base` needs no project.
            "base" -> when {
                parsed.args.getOrNull(1)?.isBlank() != false -> "epic base requires an epic id: $baseUsage"
                parsed.args.size > 3 -> "epic base takes one branch: $baseUsage"
                parsed.clear && parsed.args.size == 3 -> "epic base takes a branch or --clear, not both: $baseUsage"
                parsed.args.size == 3 && parsed.args[2].isBlank() -> "epic base: the branch is blank (use --clear to remove it): $baseUsage"
                else -> null
            }
            "show" -> when {
                parsed.args.getOrNull(1)?.isBlank() != false ->
                    "epic show requires an epic id: agenthq epic show <id|prefix> [--project <id>]"
                projectIdOf(parsed, getenv) == null -> "epic show $noProject"
                else -> null
            }
            "create" -> when {
                parsed.title?.isBlank() != false ->
                    "epic create requires a title: agenthq epic create --title \"...\" [--desc \"...\"] [--project <id>]"
                projectIdOf(parsed, getenv) == null -> "epic create $noProject"
                else -> null
            }
            else -> "epic needs a subcommand: agenthq epic show <id|prefix> | create --title \"...\" [--desc \"...\"] [--project <id>] | base <epicId> [<branch>|--clear]"
        }
    }

    /**
     * The usage error for a board listing (`tickets`, `stories`), or null: a `--status` outside [accepted] is
     * refused naming the ticket board's columns, and `--epic` without a project has no epic list to resolve in.
     */
    private fun boardListUsageError(
        command: String,
        parsed: ParsedArgs,
        getenv: (String) -> String?,
        accepted: List<String>,
    ): String? = when {
        parsed.status != null && parsed.status !in accepted ->
            "unknown status '${parsed.status}'; expected one of ${TicketStatus.ALL.joinToString(", ")}"
        parsed.epic != null && projectIdOf(parsed, getenv) == null ->
            "$command --epic needs a project id (--project <id>, or ${AccessPolicy.PROJECT_ID_ENV})"
        else -> null
    }

    /** The usage error for a `ticket` invocation, or null when its subcommand and arguments are valid. */
    private fun ticketUsageError(parsed: ParsedArgs): String? {
        val args = parsed.args
        fun blank(index: Int) = args.getOrNull(index)?.isBlank() != false
        return when (args.firstOrNull()) {
            "show" -> if (blank(1)) {
                "ticket show requires a ticket id: agenthq ticket show <id|prefix> (see: agenthq tickets)"
            } else {
                null
            }
            "update" -> when {
                blank(1) -> "ticket update requires a ticket id: agenthq ticket update <id|prefix> [--title ...] [--desc ...] [--role <id>] [--provider <id>] [--model <id>] [--epic <id|prefix>]"
                parsed.title == null && parsed.description == null && parsed.role == null &&
                    parsed.provider == null && parsed.model == null && parsed.weight == null && parsed.epic == null &&
                    parsed.images.isEmpty() && parsed.removeImages.isEmpty() ->
                    "ticket update needs something to change: --title, --desc, --role, --provider, --model, --weight, --epic, --image or --remove-image"
                parsed.title?.isBlank() == true -> "ticket update --title cannot be blank"
                else -> roleUsageError(parsed.role)
            }
            "create" -> if (parsed.removeImages.isNotEmpty()) {
                "ticket create has no image to remove: --remove-image is for ticket update"
            } else if (parsed.title?.isBlank() != false) {
                "ticket create requires a title: agenthq ticket create --title \"...\" [--desc \"...\"] [--project <id>]"
            } else if (parsed.epic?.isBlank() == true) {
                "ticket create --epic needs an epic id or prefix: agenthq ticket create --title \"...\" [--epic <id|prefix>] [--project <id>]"
            } else if (parsed.status != null &&
                parsed.status.trim().lowercase() !in listOf(TicketStatus.TODO, TicketStatus.BACKLOG)
            ) {
                "ticket create --status is ${TicketStatus.TODO} (the sprint, default) or ${TicketStatus.BACKLOG}, not '${parsed.status}'"
            } else {
                roleUsageError(parsed.role)
            }
            "move" -> when {
                blank(1) -> "ticket move requires a ticket id: agenthq ticket move <id|prefix> <status>"
                listOf(parsed.before != null, parsed.after != null, parsed.top, parsed.bottom, parsed.position != null).count { it } > 1 ->
                    "ticket move takes one of --before, --after, --top, --bottom, --position"
                parsed.position != null && (parsed.position.trim().toIntOrNull()?.takeIf { it >= 0 } == null) ->
                    "--position expects a whole number, 0 = the top of the column; was '${parsed.position}'"
                args.size == 2 && (parsed.before != null || parsed.after != null || parsed.top || parsed.bottom || parsed.position != null) -> null
                blank(2) -> "ticket move requires a status: agenthq ticket move <id|prefix> <status|--before <id>|--after <id>|--top|--bottom|--position <n>>"
                args[2].trim() !in TicketStatus.ALL ->
                    "unknown status '${args[2]}'; expected one of ${TicketStatus.ALL.joinToString(", ")}"
                else -> null
            }
            "start" -> when {
                blank(1) -> "ticket start requires a ticket id: agenthq ticket start <id|prefix> (see: agenthq tickets)"
                args.size > 2 -> "ticket start takes one ticket id: agenthq ticket start <id|prefix>"
                else -> null
            }
            "link" -> when {
                blank(1) -> "ticket link requires a ticket id: agenthq ticket link <id|prefix> <otherId|prefix> <type>"
                blank(2) -> "ticket link requires a second ticket id: agenthq ticket link <id|prefix> <otherId|prefix> <type>"
                blank(3) -> "ticket link requires a link type: expected one of ${TicketLinkType.ALL.joinToString(", ")}"
                args[3].trim() !in TicketLinkType.ALL ->
                    "unknown link type '${args[3]}'; expected one of ${TicketLinkType.ALL.joinToString(", ")}"
                else -> null
            }
            else -> "ticket needs a subcommand: agenthq ticket show <id|prefix> | create --title \"...\" | update <id|prefix> ... | move <id|prefix> <status> | start <id|prefix> | link <id|prefix> <otherId|prefix> <type>"
        }
    }

    /**
     * BUG-E2E-1: a `--role` that is not a canonical role id, refused before any socket opens; null when fine or absent.
     * BUG-ASSIGN-1: the refusal lists the built-in role ids, so an agent that passed a display name ("Backend
     * Developer") can pass the id instead; a display name is never matched to an id.
     */
    private fun roleUsageError(role: String?): String? {
        val id = role?.trim()?.lowercase()?.removePrefix("role:") ?: return null
        return if (RoleIds.PATTERN.matches(id)) {
            null
        } else {
            "--role '$role' is not a role id (a lowercase letter, then lowercase letters, digits or '-'); " +
                "valid role ids: ${RoleIds.CANONICAL.joinToString(", ")} (or the id of a custom role)"
        }
    }

    /** The commands this CLI answers to. Kept next to the [when] above so the two cannot drift. */
    private val COMMANDS = setOf(
        "submit", "watch", "pods", "runs", "logs", "cancel", "tickets", "board", "ticket", "agents", "send", "inbox",
        "read", "threads", "thread", "waiting", "epics", "stories", "epic", "project", "mcp", "knowledge", "memory", "decision", "pipeline", "notify", "issue",
        "trait", "skill", "role", "ask", "harnesses", "harness-status", "harness-install", "image", "session", "remote", "bg", "kill", "usage",
        "processes", "settings",
    )

    /** `agenthq remote on|off|status`: edits `<stateDir>/network.properties`; takes effect on the next daemon restart. */
    private fun remote(args: List<String>, out: Appendable, err: Appendable, getenv: (String) -> String?): Int {
        val dir = com.superiorsoftllc.agenthq.session.stateDir { getenv(it) ?: com.superiorsoftllc.agenthq.session.agentHqSetting(it) }
        val action = args.firstOrNull()?.lowercase()
        if (action !in setOf("on", "off", "status")) {
            err.appendLine("error: usage: agenthq remote on|off|status")
            return 2
        }
        val current = RemoteAccess.load(dir)
        when (action) {
            "on" -> RemoteAccess.save(dir, current.copy(remote = true))
            "off" -> RemoteAccess.save(dir, current.copy(remote = false))
        }
        val settings = RemoteAccess.load(dir)
        out.appendLine("remote access: ${if (settings.remote) "ON" else "OFF"}")
        if (!settings.remote) {
            out.appendLine("The daemon listens on loopback only.")
        } else {
            val token = RemoteAccess.ensureToken(dir)
            val port = settings.port ?: getenv("AGENTHQ_PORT")?.toIntOrNull() ?: DaemonPort.DEFAULT
            val address = settings.host?.takeIf { !it.startsWith("0.") } ?: RemoteAccess.tailscaleAddress() ?: "<tailscale-ip>"
            out.appendLine("address: ws://$address:$port/ws")
            out.appendLine("token:   $token")
            out.appendLine("Allow inbound TCP $port from 100.64.0.0/10 in the firewall. The token and traffic are cleartext ws://.")
        }
        if (action != "status") out.appendLine("Restart the app/daemon for this to take effect (it was not restarted).")
        return 0
    }

    /** SESSION-RM-1: how long `session rm` waits for the daemon to confirm, and its poll step. */
    private const val SESSION_RM_TIMEOUT_MS = 30_000L
    private const val SESSION_RM_POLL_MS = 100L

    /** MAIL-FOOTER-1: the names people guess for the mailbox; each prints the "Mail:" help group. */
    private val MAIL_HELP_ALIASES = setOf("mail", "mailbox")

    /** BUG-ASSIGN-1: the `harnesses` table header. */
    private const val HARNESS_HEADER = "HARNESS | DEFAULT | MODELS"

    /** PROMPT-5: how long `ask` waits past its timeout for the daemon's `prompt_answer`. */
    private const val ASK_GRACE_MS = 10_000L

    /** PROMPT-5: how long `ask` waits for one frame before it looks at the deadline again. */
    private const val ASK_POLL_MS = 1_000L

    /** The `trait list` table header. */
    private const val TRAIT_HEADER = "ID | VERSION | NAME | CHECK"

    /** Above this many notes, `memory index` without `--folder` folds the folders into counts (K-D6). */
    private const val MEMORY_INDEX_FOLD = 60

    /** At most this many active decisions in `knowledge` (§5). */
    private const val KNOWLEDGE_DECISIONS_CAP = 50

    /** At most this many open issues in `knowledge` (§5). */
    private const val KNOWLEDGE_ISSUES_CAP = 30

    /** Characters `memory save --append` prints from the end of the note. */
    private const val MEMORY_EXCERPT_CHARS = 200

    /** Characters `memory save --edit` prints on each side of the changed passage. */
    private const val MEMORY_EXCERPT_RADIUS = 80

    /** The `epics` table header. [epics] prints it; the acceptance test parses rows against it. */
    private const val EPIC_HEADER = "ID | NAME | GOAL | STORIES"

    /** The tickets board header: `tickets`, `epic show` and the deprecated `stories` all print these rows (E1-e). */
    private const val TICKET_HEADER = "ID | STATUS | TITLE | AGENT"

    /** The ONE line the deprecated `stories` prints before its rows (E1-e). */
    private const val STORIES_DEPRECATION =
        "deprecated: `agenthq stories` lists board tickets now; use `agenthq tickets --epic <id|prefix>`"

    /** The old story column `stories --status` still accepts; it reads as [TicketStatus.IN_PROGRESS]. */
    private const val STORY_DOING = "doing"

    /** The `inbox` table header. [inbox] prints it; the acceptance test parses rows against it. */
    private const val INBOX_HEADER = "ID | FROM | WHEN | READ | FIRST LINE"

    /** The `threads` table header. [threads] prints it; the acceptance test parses rows against it. */
    private const val THREADS_HEADER = "THREAD | WITH | MESSAGES | LAST"

    /** The `waiting` table header, [THREADS_HEADER]'s counterpart. */
    private const val WAITING_HEADER = "ID | SESSION | WAITING ON | SINCE | SUBJECT"

    /** How wide `inbox` lets a body's first line get before clipping it. */
    private const val FIRST_LINE_WIDTH = 72

    /**
     * How [iso] renders a message's `sentAt`. Epoch millis are exact but unreadable at a terminal;
     * the mailbox stamps UTC, so the stamp is UTC and says so with its trailing `Z`.
     */
    private val WHEN_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)
}

/** PROMPT-5: what `agenthq ask` prints on stdout. */
@kotlinx.serialization.Serializable
private data class AskOutput(val answers: List<String>, val otherText: String?)

/** A configuration problem found before connecting: exit 2, nothing contacted. */
private class UsageFailure(message: String) : RuntimeException(message)

/**
 * Where a command goes and the credential it sends. [tokenSource] says where the token came from, and
 * is for messages only: it never contains the token's value.
 */
internal class Target(
    val url: String,
    val token: String?,
    val tokenSource: String,
    val answerTimeoutMs: Long = ANSWER_TIMEOUT_MS,
)

/** The daemon refused this client's credential (HTTP 401 or 403). Never retried. */
internal class AuthRefused(message: String) : RuntimeException(message)

/** The connection died and could not be re-established, or a request cannot be safely repeated. */
internal class ConnectionLost(message: String) : RuntimeException(message)

/**
 * A read cursor over the frames the daemon has sent, consuming each one EXACTLY ONCE, over a connection
 * that can be re-made.
 *
 * It exists because of the bug it prevents. The obvious implementation --
 * `frames.firstOrNull { it.type == X }` -- answers with the first matching frame that was ever
 * received and never moves, so any loop that "waits for the next frame" silently re-reads frame
 * zero forever. A cursor is the whole fix: each frame is returned once, in arrival order.
 *
 * RECONNECTING. When the socket drops, [next] and [send] re-make it with backoff and carry on. The
 * daemon keeps every session and pod alive while no client is connected, so a reconnect loses nothing
 * the daemon still holds; it loses only the live frames produced while the client was away, which the
 * contract does not replay. [request] re-sends an idempotent request after a reconnect, and refuses to
 * re-send a non-idempotent one.
 */
internal class FrameStream private constructor(
    private val target: Target,
    private val err: Appendable,
) {
    private var link: Link = openLink(target)

    /** How long one request waits for its answer: [ANSWER_TIMEOUT_MS] unless [ANSWER_TIMEOUT_ENV] overrides it. */
    val answerTimeoutMs: Long get() = target.answerTimeoutMs
    private var cursor = 0
    private val replayCursor = SessionReplayCursor()

    /** Counts reconnects, so [request] can tell that the request it sent may have been lost. */
    private var generation = 0
    private var reconnected = false

    companion object {
        private const val POLL_MS = 10L

        /** How long [request] waits for one frame before it looks again. */
        private const val POLL_WINDOW_MS = 100L

        /**
         * How long [reconnect] waits for the replacement daemon's `ready` before deciding identity. The
         * daemon sends `ready` first thing on every connection, so this is generous; a timeout is treated
         * as "no identity" (the pre-1.11 behaviour), not as a restart.
         */
        private const val READY_WAIT_MS = 2_000L

        /**
         * Connect to [target]. Throws [AuthRefused] when the daemon refuses the token, and a connection
         * exception when nothing is listening.
         */
        fun open(target: Target, err: Appendable): FrameStream = FrameStream(target, err)

        /** Parse `provider=.. contract=.. instance=.. since=..`; a key the daemon did not send is null. */
        private fun readyIdentity(frame: Envelope): Pair<String?, Long?> {
            val fields = frame.data.split(' ')
                .mapNotNull { it.split('=', limit = 2).takeIf { p -> p.size == 2 } }
                .associate { it[0] to it[1] }
            return fields["instance"] to fields["since"]?.toLongOrNull()
        }
    }

    // The live socket is the top-level [Link] below, so the connection helper can name it.

    /** The next frame not yet consumed, or null if none arrives within [timeoutMs] (or a reconnect happened). */
    suspend fun next(timeoutMs: Long): Envelope? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val current = link
            nextAvailableFrame(current)?.let { return it }
            if (current.recorder.closed) {
                reconnect()
                return null
            }
            delay(POLL_MS)
        }
        return null
    }

    /** True once after a reconnect: lets a tailing command say so instead of taking the empty wait for a timeout. */
    fun takeReconnected(): Boolean {
        val was = reconnected
        reconnected = false
        return was
    }

    /** Register a session before its first event so reconnect can request output from sequence zero. */
    fun trackSession(sessionId: String) = replayCursor.track(sessionId)

    fun replayGapMessage(frame: Envelope): String = replayCursor.gapMessage(frame)

    /** Fold a `ready` frame's identity into the replay cursor (contract 1.11.0). */
    private fun foldReady(frame: Envelope) {
        val (instance, since) = readyIdentity(frame)
        replayCursor.observeReady(instance, since)
    }

    /**
     * The `(instance, since)` the replacement daemon announced, waiting briefly for its `ready`. Nulls mean
     * the daemon did not name an identity within [READY_WAIT_MS] (a pre-1.11 daemon, or nothing on the wire
     * yet); the caller treats that as "no identity", never as a restart.
     */
    private suspend fun awaitReadyIdentity(link: Link, timeoutMs: Long = READY_WAIT_MS): Pair<String?, Long?> {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            link.recorder.frames.firstOrNull { it.type == Envelope.READY }?.let { return readyIdentity(it) }
            if (link.recorder.closed) break
            delay(POLL_MS)
        }
        return null to null
    }

    /** The next frame of [type]; everything before it is consumed and dropped. */
    /**
     * The next UNCONSUMED frame of [type], or null if none has arrived -- WITHOUT consuming it.
     *
     * [next] and [await] consume what they return, which is what every command wants. A test that
     * needs to assert something is ALREADY buffered before it sends its request cannot use either:
     * the frame would be gone by the time the request went out, and the assertion would be about a
     * race rather than about the socket's state. This is that read-only look.
     */
    fun peek(type: String): Envelope? =
        link.recorder.frames.drop(cursor).firstOrNull { it.type == type }

    suspend fun await(type: String, timeoutMs: Long = target.answerTimeoutMs): Envelope {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val current = link
            val frame = nextAvailableFrame(current)
            if (frame != null) {
                if (frame.type == type) return frame
                continue
            }
            if (current.recorder.closed) reconnect()
            delay(POLL_MS)
        }
        error("timed out after ${timeoutMs}ms waiting for '$type'; saw ${link.recorder.frames.map { it.type }}")
    }

    /** Send one frame, re-making the connection first if it has died. */
    suspend fun send(envelope: Envelope) {
        if (link.recorder.closed) reconnect()
        val text = json.encodeToString(Envelope.serializer(), envelope)
        try {
            link.socket.sendText(text, true).join()
        } catch (first: Exception) {
            reconnect()
            try {
                link.socket.sendText(text, true).join()
            } catch (second: Exception) {
                throw ConnectionLost("could not send to the daemon: ${second.message}")
            }
        }
    }

    /**
     * Send [envelope] and wait for the frame of [answerType] that answers it.
     *
     * [idempotent] says whether the request may be sent again if the connection drops before the answer.
     * A read or a cancel may (the answer is the same either way). A submit may NOT: the first copy may
     * already have created the pod, so a second copy would create another one.
     */
    suspend fun request(envelope: Envelope, answerType: String, what: String, idempotent: Boolean, timeoutMs: Long = target.answerTimeoutMs): Envelope {
        // BUG-MCP-RACE-1: a backlog answer has the same TYPE as every other ticket's push, so matching by type took a
        // started ticket's `ticket` push as a ticket_save's answer. From contract 1.87.0 the request carries an id the
        // daemon echoes on its direct answer only; an older daemon cannot echo it, so it keeps the type match.
        var sent = correlate(envelope)
        send(sent)
        var sentAt = generation
        var lastError: String? = null
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (generation != sentAt) {
                if (!idempotent) {
                    throw ConnectionLost(
                        "the connection dropped before the daemon answered $what, so it is unknown whether it " +
                            "was applied. The contract has no pod listing to check against: do not re-send " +
                            "unless a duplicate is acceptable",
                    )
                }
                // The replacement daemon may be another build: decide correlation again against it.
                sent = correlate(envelope)
                send(sent)
                sentAt = generation
                lastError = null
            }
            val frame = next(if (lastError == null) POLL_WINDOW_MS else ERROR_GRACE_MS)
            if (frame == null) {
                if (generation == sentAt) lastError?.let { error(it.ifBlank { "$what failed" }) }
                continue
            }
            val id = sent.requestId
            when (frame.type) {
                // Correlated: only the frame echoing OUR id answers; a push (no id) or another request's answer is skipped.
                answerType -> if (id == null || frame.requestId == id) return frame
                // An error naming another request is not ours; one naming none keeps its pre-1.87 meaning.
                Envelope.ERROR -> if (id == null || frame.requestId == null || frame.requestId == id) lastError = frame.data
            }
        }
        error(lastError ?: "no answer to $what within ${timeoutMs}ms")
    }

    /**
     * BUG-MCP-RACE-1: [envelope] with a fresh `requestId` when its answer type is also broadcast
     * ([Envelope.CORRELATED_REQUESTS]) AND the connected daemon echoes one (contract >= [REQUEST_ID_CONTRACT]);
     * otherwise [envelope] unchanged, matched by type as before. A daemon whose `ready` names no contract is treated as old.
     */
    private suspend fun correlate(envelope: Envelope): Envelope {
        if (envelope.type !in Envelope.CORRELATED_REQUESTS || envelope.requestId != null) return envelope
        val contract = awaitReadyContract(link) ?: return envelope
        if (!contractAtLeast(contract, REQUEST_ID_CONTRACT)) return envelope
        return envelope.copy(requestId = java.util.UUID.randomUUID().toString())
    }

    /** The `contract=` of [link]'s `ready` (without consuming it), waiting up to [READY_WAIT_MS]; null if none came. */
    private suspend fun awaitReadyContract(link: Link): String? {
        val deadline = System.currentTimeMillis() + READY_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            link.recorder.frames.firstOrNull { it.type == Envelope.READY }?.let { ready ->
                return ready.data.split(' ').firstOrNull { it.startsWith("contract=") }
                    ?.removePrefix("contract=")?.removePrefix("agenthq/")
            }
            if (link.recorder.closed) break
            delay(POLL_MS)
        }
        return null
    }

    /**
     * Send [envelope], then [fence], and return the LAST answer frame of [answerTypes] that arrived
     * BEFORE [fence] was answered with a frame of type [fenceType].
     *
     * WHY THIS EXISTS, and it is not a timing trick. [answerTypes] may hold a frame type the daemon
     * also BROADCASTS: `mail` is both the answer to `mail_send` and the notification for every
     * mailbox event, so a client that took "the next `mail` frame" would read a bystander -- or, in
     * the `replyTo` case, the message being ANSWERED rather than the one it just stored. The daemon's
     * own reply IS the last frame of the burst (it is written once the request -- broadcasts and
     * delivery attempt included -- has been handled entirely).
     *
     * [fence] is what bounds the burst deterministically: frames on ONE connection are handled in
     * order, so the answer to a second, harmless request sent immediately after can only arrive once
     * the first request is completely done. Its own answer is not needed; the ordering is the point,
     * which is why this waits on no clock and needs no grace window.
     *
     * [envelope] is never re-sent after a dropped connection: unlike [request], this is used by a
     * command whose second copy would be a second message, so a drop is reported, not retried.
     */
    suspend fun requestLastOf(
        envelope: Envelope,
        answerTypes: Set<String>,
        fence: Envelope,
        fenceType: String,
        what: String,
        dropWarning: String,
    ): Envelope {
        send(envelope)
        send(fence)
        val sentAt = generation
        var last: Envelope? = null
        var lastError: String? = null
        val deadline = System.currentTimeMillis() + target.answerTimeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (generation != sentAt) {
                throw ConnectionLost(
                    "the connection dropped before the daemon answered $what, so it is unknown whether it " +
                        "was applied. $dropWarning",
                )
            }
            val frame = next(if (lastError == null) POLL_WINDOW_MS else ERROR_GRACE_MS)
            if (frame == null) {
                if (generation == sentAt) lastError?.let { error(it.ifBlank { "$what failed" }) }
                continue
            }
            when {
                // The request is over: whatever the answer was, it has already arrived.
                frame.type == fenceType -> return last
                    ?: error(lastError ?: "no answer to $what within ${target.answerTimeoutMs}ms")
                frame.type in answerTypes -> last = frame
                frame.type == Envelope.ERROR -> lastError = frame.data
            }
        }
        error(lastError ?: "no answer to $what within ${target.answerTimeoutMs}ms")
    }

    /** Consume arrival-order frames, suppressing sequenced session frames already delivered. */
    private fun nextAvailableFrame(current: Link): Envelope? {
        while (true) {
            val frame = current.recorder.frames.getOrNull(cursor) ?: return null
            cursor++
            // A `ready` names the daemon process; folding it keeps the replay cursor keyed to the process
            // that issued it, so a restarted daemon resets the cursor instead of replaying stale history.
            if (frame.type == Envelope.READY) foldReady(frame)
            if (replayCursor.shouldDeliver(frame)) return frame
        }
    }

    fun close() {
        runCatching { link.socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye").join() }
    }

    /**
     * Re-make the connection with backoff. A refused token is never retried: the same token will be
     * refused again, and retrying only hides the problem behind a long wait.
     */
    private suspend fun reconnect() {
        err.appendLine("[client] connection to ${target.url} lost; reconnecting")
        for ((attempt, waitMs) in RECONNECT_BACKOFF_MS.withIndex()) {
            delay(waitMs)
            var restarted = false
            var instance: String? = null
            try {
                val replacement = openLink(target)
                // The daemon names its process in `ready` BEFORE it can answer a replay request, so read it
                // first. A different process than the cursors were built against means the numbering
                // restarted: reset every cursor so the replay asks for the whole session, not a sequence the
                // new process never issued.
                val (readyInstance, readySince) = awaitReadyIdentity(replacement)
                instance = readyInstance
                restarted = replayCursor.observeReady(readyInstance, readySince)
                try {
                    replayCursor.replayRequests().forEach { request ->
                        replacement.socket.sendText(json.encodeToString(Envelope.serializer(), request), true).join()
                    }
                } catch (e: Exception) {
                    runCatching { replacement.socket.sendClose(WebSocket.NORMAL_CLOSURE, "replay request failed").join() }
                    throw e
                }
                link = replacement
            } catch (refused: AuthRefused) {
                throw refused
            } catch (e: Exception) {
                err.appendLine(
                    "[client] reconnect attempt ${attempt + 1}/${RECONNECT_BACKOFF_MS.size} failed " +
                        "(${e.javaClass.simpleName})"
                )
                continue
            }
            cursor = 0
            generation++
            reconnected = true
            err.appendLine("[client] reconnected")
            if (restarted) {
                err.appendLine(
                    "[client] the daemon is a new instance (${instance?.take(8)}); replaying tracked sessions " +
                        "from the start"
                )
            }
            return
        }
        throw ConnectionLost(
            "lost the connection to ${target.url} and could not re-establish it after " +
                "${RECONNECT_BACKOFF_MS.size} attempts"
        )
    }
}

/** One live socket and the listener that records what arrives on it. */
private class Link(val socket: WebSocket, val recorder: FrameRecorder)

/**
 * Open one WebSocket to [target], presenting its bearer token when it has one.
 *
 * A refused handshake (HTTP 401 or 403) becomes [AuthRefused] with an explanation that never includes the
 * token. Any other failure is re-thrown as the connection exception it actually is.
 */
/**
 * BUG-CLI-4: the contract the daemon at [target] announces in its `ready` frame, or null when nothing answers within
 * [timeoutMs] (connect and `ready` together). A `ready` without `contract=` is "unknown": a daemon IS running.
 * Deliberately not a [FrameStream]: that one reconnects with backoff and logs, which `--version` must never do.
 */
/**
 * BUG-MCP-RACE-1: whether contract [version] is at least [minimum], comparing `major.minor.patch` numerically. A part
 * that is not a number makes the answer false (an unknown daemon is treated as an old one).
 */
internal fun contractAtLeast(version: String, minimum: String): Boolean {
    val have = version.split('.').map { it.toIntOrNull() ?: return false }
    val want = minimum.split('.').map { it.toIntOrNull() ?: return false }
    for (i in 0 until maxOf(have.size, want.size)) {
        val a = have.getOrElse(i) { 0 }
        val b = want.getOrElse(i) { 0 }
        if (a != b) return a > b
    }
    return true
}

/** BUG-CLI-4 + BUILD-STAMP-1: the `contract=` ("unknown" when absent) and `build=` (null when absent) of a daemon's `ready`. */
internal data class ReadyInfo(val contract: String, val build: String?)

internal fun daemonContract(target: Target, timeoutMs: Long): String? = daemonReady(target, timeoutMs)?.contract

/** The [ReadyInfo] of the daemon at [target], or null when nothing answers within [timeoutMs]; see [daemonContract]. */
internal fun daemonReady(target: Target, timeoutMs: Long): ReadyInfo? {
    val deadline = System.currentTimeMillis() + timeoutMs
    val recorder = FrameRecorder()
    val builder = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofMillis(timeoutMs)).build().newWebSocketBuilder()
    target.token?.let { builder.header("Authorization", "Bearer $it") }
    val socket = try {
        builder.buildAsync(URI.create(target.url), recorder).get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
    } catch (_: Exception) {
        return null
    }
    try {
        while (System.currentTimeMillis() < deadline) {
            recorder.frames.firstOrNull { it.type == Envelope.READY }?.let { ready ->
                val fields = ready.data.split(' ')
                fun field(key: String) = fields.firstOrNull { it.startsWith("$key=") }?.removePrefix("$key=")?.takeIf { it.isNotBlank() }
                return ReadyInfo(field("contract") ?: "unknown", field("build"))
            }
            if (recorder.closed) return null
            Thread.sleep(10)
        }
        return null
    } finally {
        runCatching { socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye").get(500, java.util.concurrent.TimeUnit.MILLISECONDS) }
        socket.abort()
    }
}

private fun openLink(target: Target): Link {
    val recorder = FrameRecorder()
    val builder = HttpClient.newHttpClient().newWebSocketBuilder()
    target.token?.let { builder.header("Authorization", "Bearer $it") }
    val socket = try {
        builder.buildAsync(URI.create(target.url), recorder).join()
    } catch (e: CompletionException) {
        val cause = e.cause ?: throw e
        if (cause is WebSocketHandshakeException) {
            val status = cause.response?.statusCode()
            if (status == 401 || status == 403) throw AuthRefused(refusalMessage(target, status))
        }
        throw cause
    }
    return Link(socket, recorder)
}

private fun refusalMessage(target: Target, status: Int): String {
    val why = if (target.token == null) {
        "no token was sent: pass --token-file, --endpoint NAME, or set AGENTHQ_TOKEN_FILE"
    } else {
        "the token from ${target.tokenSource} was not accepted: it must match the daemon's"
    }
    return "the daemon at ${target.url} refused this client (HTTP $status): $why"
}

/**
 * The [WebSocket.Listener] half of [FrameStream]: it records what arrives and notices when the socket dies.
 *
 * Split out because the listener has to exist BEFORE the socket is built, while the cursor that reads
 * the frames has to exist before the commands run.
 */
private class FrameRecorder : WebSocket.Listener {

    val frames = CopyOnWriteArrayList<Envelope>()

    /** Set when the socket closes or fails. The stream reads it to decide whether to reconnect. */
    @Volatile
    var closed = false
        private set

    private val buffer = StringBuilder()

    override fun onOpen(webSocket: WebSocket) {
        webSocket.request(1)
    }

    override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
        buffer.append(data)
        if (last) {
            val text = buffer.toString()
            buffer.setLength(0)
            runCatching { json.decodeFromString(Envelope.serializer(), text) }
                .getOrNull()?.let { frames += it }
        }
        // Ask for the next chunk on EVERY call, not only the last: a partial message stalls otherwise.
        webSocket.request(1)
        return null
    }

    override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
        closed = true
        return null
    }

    override fun onError(webSocket: WebSocket, error: Throwable) {
        closed = true
    }
}
