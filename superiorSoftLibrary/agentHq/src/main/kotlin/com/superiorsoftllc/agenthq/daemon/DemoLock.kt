package com.superiorsoftllc.agenthq.daemon

import com.superiorsoftllc.agenthq.agents.AgentProfiles
import com.superiorsoftllc.agenthq.mailbox.MailSendRequest
import com.superiorsoftllc.agenthq.mvp.ProjectStore
import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.protocol.ProjectSave
import com.superiorsoftllc.agenthq.protocol.SpawnOptions
import com.superiorsoftllc.agenthq.session.InfantryEnv
import com.superiorsoftllc.agenthq.session.SessionManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Base64

/**
 * DEMO-LOCK (ticket 2f154f5f, branch demo-build only): one process-wide switch, `AGENTHQ_DEMO_LOCK=1` (documented as the
 * setting `limits.demo.lock`). It is read ONCE from the environment at start-up and is NOT stored in the settings table,
 * so no token -- the owner's included -- can turn it off at runtime; changing it is a redeploy. Default: off, and with it
 * off nothing in this file is consulted.
 *
 * With it on the daemon is DEFAULT-DENY for every token: a WebSocket frame or HTTP route not on the allow-list below is
 * refused with a `demo:` error (frame) or 403 (HTTP), before any handler runs. The allow-list:
 *  - connect / auth, `/health`, the browser bundle;
 *  - reads the UI needs to render the demo project and its two agents ([READ_FRAMES]);
 *  - per-project reads (git, epics, notes, pipeline: [PROJECT_READ_FRAMES]) only when they name the demo project;
 *  - `prompt`, `resume` and `replay_request` naming one of the two seeded sessions (`interrupt` and `kill` are refused: ticket 9bf81983);
 *  - `mail_send` from one seeded session to the other (the MCP `agenthq_message` tool), and the mail reads.
 * A visitor's prompt / resume / mail_send is also counted per client IP ([turnLimit]; ticket 9bf81983).
 * Everything else -- projects, sessions, tickets, roles, settings, harnesses, secrets, updates, uploads, spawns -- is refused.
 *
 * The seed ([seed]) is the demo project (directory [projectDir]) and exactly two role sessions; it is created on the first
 * locked start and found again (same project directory, same roles) on every later one, so it survives restarts.
 */
class DemoLock(
    val enabled: Boolean,
    /** The pinned demo notice shown above each agent (demo-only text, from the deployment, never from core code). */
    val notice: String = DEFAULT_NOTICE,
    val projectDir: String = DEFAULT_DIR,
    val projectName: String = DEFAULT_PROJECT_NAME,
    /** The two seats: role id -> harness/model. */
    val seats: List<Seat> = DEFAULT_SEATS,
    /**
     * Ticket 9bf81983: the PUBLIC visitor token (`AGENTHQ_DEMO_PUBLIC_TOKEN` or `..._FILE`), a third token the daemon
     * admits only while the lock is on, so it is default-deny like every other. The page reads it from [VISITOR_TOKEN_PATH]
     * so a visitor lands connected; the owner token is never served. Null = visitors type a token, as before.
     */
    val publicToken: String? = null,
    /** Ticket 9bf81983: visitor turns per client IP (`AGENTHQ_DEMO_TURNS_PER_IP` per `AGENTHQ_DEMO_TURN_WINDOW_HOURS`); null = none. */
    val turnLimit: DemoTurnLimit? = null,
    /** The peers whose `X-Forwarded-For` names the client (`AGENTHQ_DEMO_TRUSTED_PROXIES`, default loopback). */
    val trustedProxies: Set<String> = emptySet(),
) {
    data class Seat(val role: String, val provider: String, val model: String)

    @Volatile var projectId: String? = null
        private set
    @Volatile var sessionIds: Set<String> = emptySet()
        private set
    /** Role id -> session id of the seeded sessions, so mail addressed by role resolves to the same allow-list. */
    @Volatile private var roleToSession: Map<String, String> = emptyMap()

    /** The extra `ready` keys: `demo=lock demoNotice=<base64url>`; empty when off. */
    fun readyFields(): String = if (!enabled) "" else
        " demo=lock" + if (notice.isBlank()) "" else " demoNotice=" + Base64.getUrlEncoder().withoutPadding().encodeToString(notice.toByteArray(Charsets.UTF_8))

    /**
     * Find or create the demo project and its two sessions, then close the session door ([SessionManager.demoLocked]).
     * Idempotent: a restart finds the same project (by directory) and the same sessions (by project + role).
     */
    fun seed(projects: ProjectStore, manager: SessionManager, agents: AgentProfiles) {
        if (!enabled) return
        java.io.File(projectDir).mkdirs()
        val project = projects.list().firstOrNull { it.directory == projectDir }
            ?: projects.save(ProjectSave(name = projectName, goal = "Infantry AI demo", directory = projectDir))
        val ids = LinkedHashMap<String, String>()
        for (seat in seats) {
            val existing = manager.list().filter { it.projectId == project.id && it.role == seat.role && !it.global }
                .minByOrNull { it.spawnedAt }
            val record = existing ?: bindSessionOnSpawn(
                manager, agents,
                SpawnOptions(provider = seat.provider, model = seat.model, role = seat.role, projectId = project.id),
                null,
            ) ?: error("demo seed: could not create the ${seat.role} session")
            ids[seat.role] = record.id
        }
        adopt(project.id, ids)
        manager.demoLocked = true
        println("[agentHq] DEMO LOCK on: project ${project.id} ($projectDir), sessions ${ids.entries.joinToString { "${it.key}=${it.value}" }}; every other frame/route is refused")
    }

    /**
     * Ticket 9bf81983: start each seeded session's FIRST run at start-up, so both show a finished run with a provider
     * session id before any visitor arrives (a never-run session shows "nothing to resume yet"). On a daemon thread, one
     * session after the other (the session cap counts each kept process). Idempotent: a session that already has a
     * provider session id is never prompted again, so a restart sends nothing. A first run that ends WITHOUT a provider
     * session id (no harness key yet, a provider error) would leave the session unreachable, so the daemon reboots it to
     * never-run ([SessionManager.restart]) and tries again every [retryMillis], at most [attempts] times per start.
     * A session a visitor is already talking to (a run in flight) is skipped: that run is its first run.
     * [compose] builds the first turn exactly as a visitor's first message would (persona, identity, notes).
     */
    fun warmUp(
        manager: SessionManager,
        compose: (com.superiorsoftllc.agenthq.session.SessionRecord, String) -> String,
        attempts: Int = 10,
        retryMillis: Long = 60_000,
    ) {
        if (!enabled || sessionIds.isEmpty()) return
        val ids = roleToSession.values.toList()
        Thread({
            for (id in ids) {
                for (attempt in 1..attempts) {
                    val record = manager.get(id) ?: break
                    if (warmed(record)) break
                    if (record.state in WORKING) { waitIdle(manager, id); continue }
                    val ok = runCatching {
                        if (!record.neverRun) manager.restart(id)
                        manager.resume(id, compose(manager.get(id)!!, WARM_UP_PROMPT))
                        waitIdle(manager, id)
                        manager.get(id)?.let(::warmed) == true
                    }.onFailure { println("[agentHq] DEMO LOCK warm-up of $id failed (attempt $attempt): ${it.message}") }.getOrDefault(false)
                    if (ok) { println("[agentHq] DEMO LOCK warm-up of $id done"); break }
                    // Back to never-run, so a visitor's first message still works while we wait to retry.
                    runCatching { manager.get(id)?.takeIf { !warmed(it) && it.state !in WORKING && !it.neverRun }?.let { manager.restart(id) } }
                    Thread.sleep(retryMillis)
                }
            }
        }, "demo-warm-up").apply { isDaemon = true }.start()
    }

    /**
     * Warmed = it has a provider session id and its first run did not fail. A FAILED first run (e.g. the harness key was
     * not in place yet: opencode reports a session id, then "Model not found") is not warmed: it is rebooted and retried.
     * A session that has run more than once is a visitor's, and is left alone whatever its state.
     */
    private fun warmed(r: com.superiorsoftllc.agenthq.session.SessionRecord): Boolean =
        r.providerSessionId != null && (r.runCount > 1 || r.state !in FIRST_RUN_FAILED)

    private fun waitIdle(manager: SessionManager, id: String, timeoutMillis: Long = 180_000) {
        val until = System.currentTimeMillis() + timeoutMillis
        Thread.sleep(1_000)
        while (System.currentTimeMillis() < until && manager.get(id)?.state in WORKING) Thread.sleep(1_000)
    }

    /** The seeded project and role -> session map (the seed calls it; a test can too). */
    internal fun adopt(project: String, sessions: Map<String, String>) {
        projectId = project
        roleToSession = sessions.toMap()
        sessionIds = sessions.values.toSet()
    }

    private fun seeded(id: String?): Boolean = id != null && id in sessionIds

    /**
     * Null = this frame may proceed; else the refusal text. [active] is the connection's active session (a blank
     * `mail_send.from` means it, as in the daemon's own handler).
     */
    fun frameRefusal(envelope: Envelope, active: String?): String? {
        if (!enabled) return null
        val type = envelope.type
        val ok = when (type) {
            in READ_FRAMES -> true
            in PROJECT_READ_FRAMES -> projectId != null && namedProject(envelope.data) == projectId
            REPLAY_REQUEST -> seeded(envelope.sessionId)
            Envelope.PROMPT -> seeded(envelope.sessionId) && envelope.data.isNotBlank() && !envelope.data.trimStart().startsWith("/")
            // The Resume button: `<sessionId>\n<prompt>`.
            Envelope.RESUME -> envelope.data.substringBefore('\n', "").trim().let(::seeded) &&
                envelope.data.substringAfter('\n', "").let { it.isNotBlank() && !it.trimStart().startsWith("/") }
            Envelope.MAIL_SEND -> {
                val request = runCatching { lenient.decodeFromString(MailSendRequest.serializer(), envelope.data) }.getOrNull()
                request != null && mailRefusal(request.copy(from = request.from.ifBlank { envelope.sessionId ?: active ?: "" })) == null
            }
            else -> false
        }
        return if (ok) null else refusal(type)
    }

    /**
     * The project a per-project read names: a JSON payload's `projectId`, else the bare payload (`project_git_status`,
     * `epic_list`, the project-scope `memory_list`). Null when a JSON payload names none, or could name two: a repeated
     * `projectId` key, or any `\u` escape (which could spell the key), is refused, so this check and the handler's own
     * decoder can never read different projects out of one payload.
     */
    private fun namedProject(data: String): String? {
        val text = data.trim()
        if (!text.startsWith("{")) return text
        if (text.contains("\\u") || PROJECT_ID_KEY.findAll(text).count() != 1) return null
        val obj = runCatching { lenient.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        return (obj["projectId"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
    }

    /** Null = this mail may be sent: from one seeded session to the OTHER one (by session id or role), nothing else. */
    fun mailRefusal(request: MailSendRequest): String? {
        if (!enabled) return null
        val from = request.from
        val to = roleToSession[request.to] ?: request.to
        return if (seeded(from) && seeded(to) && from != to && !request.allowCrossProject) null
        else "demo: mail is only allowed between the two demo agents"
    }

    /** Null = this HTTP request may proceed (the access policy still applies); else the 403 text. */
    fun httpRefusal(method: String, path: String, webAsset: Boolean): String? {
        if (!enabled) return null
        val ok = webAsset || path == OPEN_PATH || path == AccessPolicy.BROWSER_PATH ||
            (method == "GET" && path == VISITOR_TOKEN_PATH) ||
            (method == "POST" && (path == "/mail/send" || path == "/mail/read")) ||
            // GET /attention: the browser bundle checks its token with it (agentHqWeb Main.kt TokenCheck); a read.
            (method == "GET" && (path == "/attention" || path == "/mail/inbox" || path == "/mail/outbox" || path == "/mail/waiting" || path.startsWith("/mail/thread/")))
        return if (ok) null else "demo: $method $path is disabled in this demo"
    }

    companion object {
        const val ENV = "AGENTHQ_DEMO_LOCK"
        const val NOTICE_ENV = "AGENTHQ_DEMO_NOTICE"
        const val DIR_ENV = "AGENTHQ_DEMO_DIR"
        const val SEATS_ENV = "AGENTHQ_DEMO_SEATS"
        const val PUBLIC_TOKEN_ENV = "AGENTHQ_DEMO_PUBLIC_TOKEN"
        const val PUBLIC_TOKEN_FILE_ENV = "AGENTHQ_DEMO_PUBLIC_TOKEN_FILE"
        /** Ticket 9bf81983: answered without a token, only while the lock is on and a public token is set. */
        const val VISITOR_TOKEN_PATH = "/demo/visitor-token"
        const val DEFAULT_DIR = "/work/demo"
        const val DEFAULT_PROJECT_NAME = "Demo project"
        /** No text in core code: the deployment's seed (docker/demo-seed.env, AGENTHQ_DEMO_NOTICE) supplies it. */
        const val DEFAULT_NOTICE = ""
        val DEFAULT_SEATS = listOf(
            Seat("backend", "opencode", "opencode-go/beam-free"),
            Seat("pm", "opencode", "opencode-go/beam-free"),
        )
        private const val REPLAY_REQUEST = "replay_request"
        /** Ticket 9bf81983: the start-up first turn of each seeded session (one short run, no visitor turn spent). */
        const val WARM_UP_PROMPT = "Reply with: ready"
        private val FIRST_RUN_FAILED = setOf(com.superiorsoftllc.agenthq.session.SessionState.FAILED, com.superiorsoftllc.agenthq.session.SessionState.ORPHANED)
        private val WORKING = setOf(
            com.superiorsoftllc.agenthq.session.SessionState.STARTING,
            com.superiorsoftllc.agenthq.session.SessionState.BUSY,
            com.superiorsoftllc.agenthq.session.SessionState.WAITING_INPUT,
        )
        private val lenient = Json { ignoreUnknownKeys = true }
        private val PROJECT_ID_KEY = Regex("\"projectId\"")

        /** Read-only frames the browser UI and the agents' MCP tools send to render / answer; none changes state. */
        val READ_FRAMES: Set<String> = setOf(
            Envelope.SESSIONS, Envelope.PROJECT_LIST, Envelope.PROJECT_GET, Envelope.WORKSPACE_LIST,
            Envelope.SETTINGS_GET, Envelope.LIMIT_CATALOG, Envelope.LIMITS, Envelope.USAGE_GET,
            Envelope.TICKET_LIST, Envelope.TRIGGER_LIST, Envelope.COMMAND_LIST, Envelope.ROLE_LIST,
            Envelope.PROVIDERS, Envelope.MODELS, Envelope.SESSION_STATS_GET, Envelope.QUESTION_LIST,
            Envelope.MAIL_INBOX, Envelope.MAIL_OUTBOX, Envelope.MAIL_THREAD, Envelope.MAIL_THREADS,
            Envelope.MAIL_WAITING, Envelope.MAIL_READ,
            // ticket db5b3b70: the lists the UI loads on connect (Profiles, Roles, Attention, provider meters) and the Prompts
            // and Skills tabs (database rows, not files). Each answers from this container's own stores and writes nothing.
            Envelope.AGENT_LIST, Envelope.ROLE_BINDING_LIST, Envelope.TRAIT_LIST, Envelope.ROLE_TRAITS_LIST,
            Envelope.ATTENTION_LIST, Envelope.PROVIDER_STATES, Envelope.PROMPT_LIST, Envelope.SKILL_LIST,
            // DEMO-MERGE-1: the read-only, non-project frames dev added since (FrameGate.DEMO_LOCK_ALLOWED, SEC-FIX-1) the shell needs on connect.
            Envelope.USAGE_METER_GET, Envelope.CLI_LOCATIONS, Envelope.TOOL_APPROVAL, Envelope.APPROVAL_RULES, Envelope.ROLE_BINDING_GET,
            Envelope.PROMPT_GET, Envelope.SKILL_GET, Envelope.TRAIT_GET, Envelope.ROLE_TRAITS_GET, Envelope.AGENT_ROSTER, Envelope.TICKET_GET,
            Envelope.TRIGGER_EVENTS, Envelope.POD_QUERY,
        )

        /**
         * ticket db5b3b70: reads of ONE project, allowed only when the payload names the demo project. The git reads run
         * `git --no-optional-locks` in that project's repository, refuse a path that could leave it and resolve a worktree
         * only from git's own list (ProjectGitService); `project_git_checkout`, the one git write, is not here.
         */
        val PROJECT_READ_FRAMES: Set<String> = setOf(
            Envelope.PROJECT_GIT_STATUS, Envelope.PROJECT_GIT_LOG, Envelope.PROJECT_GIT_DIFF,
            Envelope.PROJECT_GIT_BRANCHES, Envelope.PROJECT_GIT_BRANCH_CHANGES, Envelope.PROJECT_GIT_BRANCH_DIFF,
            Envelope.PROJECT_GIT_WORKTREES, Envelope.PROJECT_GIT_WORKTREE_STATUS,
            Envelope.EPIC_LIST, Envelope.MEMORY_LIST, Envelope.MEMORY_SEARCH, Envelope.MEMORY_GET, Envelope.PIPELINE_SHOW,
        )

        /** Every frame type the lock lets through in some form (the attacker script's expected-allowed column). */
        val ALLOWED_TYPES: Set<String> = READ_FRAMES + PROJECT_READ_FRAMES + setOf(REPLAY_REQUEST, Envelope.PROMPT, Envelope.RESUME, Envelope.MAIL_SEND)

        fun refusal(type: String) = "demo: '$type' is disabled in this demo"

        fun fromEnvironment(getenv: (String) -> String? = InfantryEnv::get): DemoLock {
            val on = getenv(ENV)?.trim()?.lowercase() in setOf("1", "true", "on", "yes")
            if (!on) return DemoLock(false)
            val seats = getenv(SEATS_ENV)?.split(',')?.mapNotNull { spec ->
                // role:provider:model, e.g. backend:opencode:opencode-go/beam-free
                val p = spec.trim().split(':', limit = 3)
                if (p.size == 3 && p.all { it.isNotBlank() }) Seat(p[0].trim().lowercase(), p[1].trim(), p[2].trim()) else null
            }?.takeIf { it.size == 2 } ?: DEFAULT_SEATS
            return DemoLock(
                enabled = true,
                notice = getenv(NOTICE_ENV)?.takeIf { it.isNotBlank() } ?: DEFAULT_NOTICE,
                projectDir = getenv(DIR_ENV)?.takeIf { it.isNotBlank() } ?: DEFAULT_DIR,
                seats = seats,
                publicToken = getenv(PUBLIC_TOKEN_ENV)?.trim()?.takeIf { it.isNotEmpty() }
                    ?: getenv(PUBLIC_TOKEN_FILE_ENV)?.takeIf { it.isNotBlank() }?.let { AccessPolicy.readTokenFile(java.io.File(it)) },
                turnLimit = DemoTurnLimit.fromEnvironment(com.superiorsoftllc.agenthq.session.stateDir(), getenv),
                trustedProxies = DemoTurnLimit.trustedProxies(getenv),
            )
        }
    }
}
