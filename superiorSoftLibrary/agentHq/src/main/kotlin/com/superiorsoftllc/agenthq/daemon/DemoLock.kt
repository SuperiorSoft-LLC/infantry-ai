package com.superiorsoftllc.agenthq.daemon

import com.superiorsoftllc.agenthq.agents.AgentProfiles
import com.superiorsoftllc.agenthq.mailbox.MailSendRequest
import com.superiorsoftllc.agenthq.mvp.ProjectStore
import com.superiorsoftllc.agenthq.plugin.api.PolicyPreset
import com.superiorsoftllc.agenthq.plugin.api.PolicyWire
import com.superiorsoftllc.agenthq.plugin.api.SeatsSpec
import com.superiorsoftllc.agenthq.policy.ActionCatalog
import com.superiorsoftllc.agenthq.policy.PolicyRuntime
import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.protocol.ProjectSave
import com.superiorsoftllc.agenthq.protocol.SpawnOptions
import com.superiorsoftllc.agenthq.session.InfantryEnv
import com.superiorsoftllc.agenthq.session.SessionManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.Base64

/**
 * DEMO-LOCK (ticket 2f154f5f), since POLICY-1 (docs/POLICY.md) the SEATED LOCKDOWN mechanism of a policy preset. It holds
 * no deployment value of its own: it is on when the active preset ([PolicyRuntime]) has `seats`, and every value (project,
 * seats, notice, refusal texts, which actions pass) comes from that preset; the `AGENTHQ_DEMO_*` env vars (deployment API,
 * docs/docker-deploy.md) override the seat values. Off = nothing in this file is consulted.
 *
 * On, the daemon is DEFAULT-DENY for every token: a frame whose action ([ActionCatalog]) the preset does not allow, or an
 * HTTP route not listed in [httpRefusal], is refused before any handler runs. Of the frames the preset allows:
 *  - per-project reads (catalog scope PROJECT) pass only when they name the seated project;
 *  - `prompt`, `resume` and `replay_request` only naming one of the seated sessions (never a slash command);
 *  - `mail_send` only from one seated session to another (the MCP `agenthq_message` tool).
 * A visitor's prompt / resume / mail_send is also counted per client IP ([turnLimit]; ticket 9bf81983).
 *
 * The seed ([seed]) is the project (directory [projectDir]) and one role session per seat; created on the first locked
 * start and found again (same project directory, same roles) on every later one, so it survives restarts.
 */
class DemoLock(
    val enabled: Boolean,
    /** The pinned notice (the preset's or the deployment's env), never text from core code. */
    val notice: String = "",
    val projectDir: String = "",
    val projectName: String = "",
    /** The seats: role id -> harness/model. */
    val seats: List<Seat> = emptyList(),
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
    /** POLICY-1: the preset this lock enforces; the default refuses every frame (fail closed). */
    val preset: PolicyPreset = PolicyPreset.missing(SEATED),
) {
    data class Seat(val role: String, val provider: String, val model: String)

    @Volatile var projectId: String? = null
        private set
    @Volatile var sessionIds: Set<String> = emptySet()
        private set
    /** Role id -> session id of the seeded sessions, so mail addressed by role resolves to the same allow-list. */
    @Volatile private var roleToSession: Map<String, String> = emptyMap()

    /** The preset's `ready` keys (` key=value` each) and the notice (base64url UTF-8); empty when off. */
    fun readyFields(): String = if (!enabled) "" else buildString {
        for ((k, v) in PolicyWire.of(preset, null, false, emptyList()).readyFields()) append(' ').append(k).append('=').append(v)
        if (notice.isNotBlank()) append(" notice=").append(Base64.getUrlEncoder().withoutPadding().encodeToString(notice.toByteArray(Charsets.UTF_8)))
    }

    /**
     * Find or create the project and its seat sessions, then close the session door ([SessionManager.demoLocked]).
     * Idempotent: a restart finds the same project (by directory) and the same sessions (by project + role).
     */
    fun seed(projects: ProjectStore, manager: SessionManager, agents: AgentProfiles) {
        if (!enabled) return
        File(projectDir).mkdirs()
        val project = projects.list().firstOrNull { it.directory == projectDir }
            ?: projects.save(ProjectSave(name = projectName, goal = preset.seats?.projectGoal.orEmpty(), directory = projectDir))
        val ids = LinkedHashMap<String, String>()
        for (seat in seats) {
            val existing = manager.list().filter { it.projectId == project.id && it.role == seat.role && !it.global }
                .minByOrNull { it.spawnedAt }
            val record = existing ?: bindSessionOnSpawn(
                manager, agents,
                SpawnOptions(provider = seat.provider, model = seat.model, role = seat.role, projectId = project.id),
                null,
            ) ?: error("seated policy: could not create the ${seat.role} session")
            ids[seat.role] = record.id
        }
        adopt(project.id, ids)
        manager.demoLocked = true
        println("[agentHq] SEATED POLICY '${preset.id}' on: project ${project.id} ($projectDir), sessions ${ids.entries.joinToString { "${it.key}=${it.value}" }}; every other frame/route is refused")
    }

    /**
     * Ticket 9bf81983: start each seated session's FIRST run at start-up (the preset's `warmUpPrompt`; none = no warm-up),
     * so both show a finished run with a provider session id before any visitor arrives. On a daemon thread, one session
     * after the other. Idempotent: a session that already has a provider session id is never prompted again. A first run
     * that ends WITHOUT a provider session id is rebooted to never-run ([SessionManager.restart]) and retried every
     * [retryMillis], at most [attempts] times per start. A session a visitor is already talking to is skipped.
     * [compose] builds the first turn exactly as a visitor's first message would (persona, identity, notes).
     */
    fun warmUp(
        manager: SessionManager,
        compose: (com.superiorsoftllc.agenthq.session.SessionRecord, String) -> String,
        attempts: Int = 10,
        retryMillis: Long = 60_000,
    ) {
        val prompt = preset.seats?.warmUpPrompt?.takeIf { it.isNotBlank() } ?: return
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
                        manager.resume(id, compose(manager.get(id)!!, prompt))
                        waitIdle(manager, id)
                        manager.get(id)?.let(::warmed) == true
                    }.onFailure { println("[agentHq] SEATED POLICY warm-up of $id failed (attempt $attempt): ${it.message}") }.getOrDefault(false)
                    if (ok) { println("[agentHq] SEATED POLICY warm-up of $id done"); break }
                    // Back to never-run, so a visitor's first message still works while we wait to retry.
                    runCatching { manager.get(id)?.takeIf { !warmed(it) && it.state !in WORKING && !it.neverRun }?.let { manager.restart(id) } }
                    Thread.sleep(retryMillis)
                }
            }
        }, "seated-warm-up").apply { isDaemon = true }.start()
    }

    /**
     * Warmed = it has a provider session id and its first run did not fail. A FAILED first run is not warmed: it is
     * rebooted and retried. A session that has run more than once is a visitor's, and is left alone whatever its state.
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

    /** The refusal text of frame [type], from the preset's `refusal` template. */
    fun refusal(type: String): String = PolicyRuntime.refusalText(preset, type, ActionCatalog.forFrame(type)?.id)

    /**
     * Null = this frame may proceed; else the refusal text. [active] is the connection's active session (a blank
     * `mail_send.from` means it, as in the daemon's own handler).
     */
    fun frameRefusal(envelope: Envelope, active: String?): String? {
        if (!enabled) return null
        val type = envelope.type
        if (PolicyRuntime.actionRefusal(preset, envelope) != null) return refusal(type)
        val ok = when {
            type == ActionCatalog.REPLAY_REQUEST -> seeded(envelope.sessionId)
            type == Envelope.PROMPT -> seeded(envelope.sessionId) && envelope.data.isNotBlank() && !envelope.data.trimStart().startsWith("/")
            // The Resume button: `<sessionId>\n<prompt>`.
            type == Envelope.RESUME -> envelope.data.substringBefore('\n', "").trim().let(::seeded) &&
                envelope.data.substringAfter('\n', "").let { it.isNotBlank() && !it.trimStart().startsWith("/") }
            type == Envelope.MAIL_SEND -> {
                val request = runCatching { lenient.decodeFromString(MailSendRequest.serializer(), envelope.data) }.getOrNull()
                request != null && mailRefusal(request.copy(from = request.from.ifBlank { envelope.sessionId ?: active ?: "" })) == null
            }
            ActionCatalog.forFrame(type)?.scope == ActionCatalog.Scope.PROJECT -> projectId != null && namedProject(envelope.data) == projectId
            // A SESSION-scoped action the code above does not know how to scope is refused.
            ActionCatalog.forFrame(type)?.scope == ActionCatalog.Scope.SESSION -> false
            else -> true
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

    /** Null = this mail may be sent: from one seated session to ANOTHER one (by session id or role), nothing else. */
    fun mailRefusal(request: MailSendRequest): String? {
        if (!enabled) return null
        val from = request.from
        val to = roleToSession[request.to] ?: request.to
        return if (seeded(from) && seeded(to) && from != to && !request.allowCrossProject) null
        else preset.seats?.mailRefusal ?: "mail is only allowed between the seated agents"
    }

    /** Null = this HTTP request may proceed (the access policy still applies); else the 403 text. */
    fun httpRefusal(method: String, path: String, webAsset: Boolean): String? {
        if (!enabled) return null
        val ok = webAsset || path == OPEN_PATH || path == AccessPolicy.BROWSER_PATH ||
            (method == "GET" && path == VISITOR_TOKEN_PATH) ||
            (method == "POST" && (path == "/mail/send" || path == "/mail/read")) ||
            // GET /attention: the browser bundle checks its token with it (agentHqWeb Main.kt TokenCheck); a read.
            (method == "GET" && (path == "/attention" || path == "/mail/inbox" || path == "/mail/outbox" || path == "/mail/waiting" || path.startsWith("/mail/thread/")))
        return if (ok) null else (preset.seats?.httpRefusal ?: "{method} {path} is disabled").replace("{method}", method).replace("{path}", path)
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
        /** The id of the fail-closed preset a bare `DemoLock(true)` enforces (no preset installed). */
        private const val SEATED = "seated"
        private val FIRST_RUN_FAILED = setOf(com.superiorsoftllc.agenthq.session.SessionState.FAILED, com.superiorsoftllc.agenthq.session.SessionState.ORPHANED)
        private val WORKING = setOf(
            com.superiorsoftllc.agenthq.session.SessionState.STARTING,
            com.superiorsoftllc.agenthq.session.SessionState.BUSY,
            com.superiorsoftllc.agenthq.session.SessionState.WAITING_INPUT,
        )
        private val lenient = Json { ignoreUnknownKeys = true }
        private val PROJECT_ID_KEY = Regex("\"projectId\"")

        /** `role:provider:model` (the model may hold `/`), e.g. `backend:opencode:vendor/model`. */
        fun parseSeats(specs: List<String>): List<Seat> = specs.mapNotNull { spec ->
            val p = spec.trim().split(':', limit = 3)
            if (p.size == 3 && p.all { it.isNotBlank() }) Seat(p[0].trim().lowercase(), p[1].trim(), p[2].trim()) else null
        }

        /**
         * POLICY-1: the lock of [preset]: off when it has no `seats`; else its values, each overridable by the deployment's
         * `AGENTHQ_DEMO_*` env. A preset without a project directory seats under `<state dir>/seated`.
         */
        fun of(preset: PolicyPreset, getenv: (String) -> String?, stateDir: File): DemoLock {
            val spec = preset.seats ?: return DemoLock(false)
            val seats = getenv(SEATS_ENV)?.split(',')?.let(::parseSeats)?.takeIf { it.isNotEmpty() } ?: parseSeats(spec.seats)
            val perIp = getenv(DemoTurnLimit.TURNS_ENV)?.trim()?.toIntOrNull() ?: spec.turnsPerIp
            val hours = getenv(DemoTurnLimit.WINDOW_ENV)?.trim()?.toLongOrNull() ?: spec.turnWindowHours.toLong()
            return DemoLock(
                enabled = true,
                notice = getenv(NOTICE_ENV)?.takeIf { it.isNotBlank() } ?: preset.notice.orEmpty(),
                projectDir = getenv(DIR_ENV)?.takeIf { it.isNotBlank() } ?: spec.projectDir.ifBlank { File(stateDir, SEATED).absolutePath },
                projectName = spec.projectName.ifBlank { preset.label.ifBlank { preset.id } },
                seats = seats,
                publicToken = getenv(PUBLIC_TOKEN_ENV)?.trim()?.takeIf { it.isNotEmpty() }
                    ?: getenv(PUBLIC_TOKEN_FILE_ENV)?.takeIf { it.isNotBlank() }?.let { AccessPolicy.readTokenFile(File(it)) },
                turnLimit = if (perIp > 0 && hours > 0) DemoTurnLimit(perIp, hours * 3_600_000L, File(stateDir, DemoTurnLimit.FILE_NAME), code = spec.turnLimitCode ?: DemoTurnLimit.PREFIX, text = spec.turnLimitText ?: DemoTurnLimit.TEXT) else null,
                trustedProxies = DemoTurnLimit.trustedProxies(getenv),
                preset = preset,
            )
        }

        /** The lock the environment selects (through the policy presets the default providers find); off by default. */
        fun fromEnvironment(getenv: (String) -> String? = InfantryEnv::get): DemoLock {
            if (!PolicyRuntime.truthy(getenv(ENV)) && getenv(PolicyRuntime.POLICY_ENV).isNullOrBlank()) return DemoLock(false)
            val state = com.superiorsoftllc.agenthq.session.stateDir()
            val runtime = PolicyRuntime.fromEnvironment(state, getenv)
            if (runtime.lock.enabled) return runtime.lock
            // The shorthand with no seated preset installed: the env's seat values over a refuse-everything preset (fail closed).
            return if (PolicyRuntime.truthy(getenv(ENV))) of(PolicyPreset.missing(SEATED).copy(seats = SeatsSpec()), getenv, state) else DemoLock(false)
        }
    }
}
