package com.superiorsoftllc.infantry.plugins.demo.lock.daemon

import com.superiorsoftllc.infantry.daemon.api.Frame
import com.superiorsoftllc.infantry.daemon.api.Policy
import com.superiorsoftllc.infantry.daemon.api.Projects
import com.superiorsoftllc.infantry.daemon.api.SessionInfo
import com.superiorsoftllc.infantry.daemon.api.Sessions
import com.superiorsoftllc.infantry.plugin.api.PolicyPreset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.io.File

/**
 * DEMO-LOCK (ticket 2f154f5f), since POLICY-1 (docs/POLICY.md) the SEATED LOCKDOWN of a policy preset, and since
 * DAEMON-PLUGINS-1 (docs/DAEMON-PLUGINS.md) Demo_Lock's daemon half: core holds none of it. It holds no deployment value of
 * its own: it is on when the active preset has `seats`, and every value (project, seats, notice, refusal texts) comes from
 * that preset; the `INFANTRY_DEMO_*` env vars (deployment API, docs/docker-deploy.md) override the seat values.
 *
 * On, core already refused every frame whose action the preset does not allow; of the frames it allows, this scopes:
 *  - per-project reads (catalog scope PROJECT) pass only when they name the seated project;
 *  - `prompt`, `resume` and `replay_request` only naming one of the seated sessions (never a slash command);
 *  - `mail_send` only from one seated session to another (the MCP `infantry_message` tool).
 * A visitor's prompt / resume / mail_send is also counted per client IP ([turnLimit]; ticket 9bf81983).
 *
 * The seed ([seed]) is the project (directory [projectDir]) and one role session per seat; created on the first locked
 * start and found again (same project directory, same roles) on every later one, so it survives restarts.
 */
class DemoLock(
    val enabled: Boolean,
    /** The pinned notice (the deployment's env, else the preset's), never text from core code. */
    val notice: String = "",
    val projectDir: String = "",
    val projectName: String = "",
    val projectGoal: String = "",
    /** The seats: role id -> harness/model. */
    val seats: List<Seat> = emptyList(),
    /**
     * Ticket 9bf81983: the PUBLIC visitor token (`INFANTRY_DEMO_PUBLIC_TOKEN` or `..._FILE`), a guest token the daemon
     * admits only while the lock is on (the `admission` hook), so it is default-deny like every other. The page reads it
     * from [VISITOR_TOKEN_PATH] so a visitor lands connected; the owner token is never served. Null = visitors type a token.
     */
    val publicToken: String? = null,
    /** Ticket 9bf81983: visitor turns per client IP (`INFANTRY_DEMO_TURNS_PER_IP` per `INFANTRY_DEMO_TURN_WINDOW_HOURS`); null = none. */
    val turnLimit: DemoTurnLimit? = null,
    /** The peers whose `X-Forwarded-For` names the client (`INFANTRY_DEMO_TRUSTED_PROXIES`, default loopback). */
    val trustedProxies: Set<String> = emptySet(),
    /** The first turn each seat runs at start-up (the preset's `warmUpPrompt`); null = no warm-up. */
    val warmUpPrompt: String? = null,
    private val mailRefusalText: String = "mail is only allowed between the seated agents",
    private val httpRefusalText: String = "{method} {path} is disabled",
) {
    data class Seat(val role: String, val provider: String, val model: String)

    @Volatile var projectId: String? = null
        private set
    @Volatile var sessionIds: Set<String> = emptySet()
        private set
    /** Role id -> session id of the seeded sessions, so mail addressed by role resolves to the same allow-list. */
    @Volatile private var roleToSession: Map<String, String> = emptyMap()

    /**
     * Find or create the project and its seat sessions. Idempotent: a restart finds the same project (by directory) and the
     * same sessions (by project + role). The plugin's spawn gate lets only this through.
     */
    fun seed(projects: Projects, sessions: Sessions, log: (String) -> Unit) {
        if (!enabled) return
        val project = projects.create(projectName, projectGoal, projectDir)
        val ids = LinkedHashMap<String, String>()
        for (seat in seats) {
            val existing = sessions.list().filter { it.projectId == project.id && it.role == seat.role && !it.global }
                .minByOrNull { it.spawnedAt }
            ids[seat.role] = (existing ?: sessions.spawn(seat.provider, seat.model, seat.role, project.id)).id
        }
        adopt(project.id, ids)
        log("SEATED POLICY on: project ${project.id} ($projectDir), sessions ${ids.entries.joinToString { "${it.key}=${it.value}" }}; every other frame/route is refused")
    }

    /**
     * Ticket 9bf81983: start each seated session's FIRST run at start-up ([warmUpPrompt]; none = no warm-up), so each shows
     * a finished run with a provider session id before any visitor arrives. On a daemon thread, one session after the
     * other. Idempotent: a session that already has a provider session id is never prompted again. A first run that ends
     * WITHOUT a provider session id is rebooted to never-run and retried every [retryMillis], at most [attempts] times per
     * start. A session a visitor is already talking to is skipped. [Sessions.resume] composes the first turn exactly as a
     * visitor's first message would (persona, identity, notes).
     */
    fun warmUp(sessions: Sessions, log: (String) -> Unit, attempts: Int = 10, retryMillis: Long = 60_000): Thread? {
        val prompt = warmUpPrompt?.takeIf { it.isNotBlank() } ?: return null
        if (!enabled || sessionIds.isEmpty()) return null
        val ids = roleToSession.values.toList()
        return Thread({
            try {
                for (id in ids) {
                    for (attempt in 1..attempts) {
                        val record = sessions.get(id) ?: break
                        if (warmed(record)) break
                        if (record.state in WORKING) { waitIdle(sessions, id); continue }
                        val ok = runCatching {
                            if (!record.neverRun) sessions.restart(id)
                            sessions.resume(id, prompt)
                            waitIdle(sessions, id)
                            sessions.get(id)?.let(::warmed) == true
                        }.onFailure { log("SEATED POLICY warm-up of $id failed (attempt $attempt): ${it.message}") }.getOrDefault(false)
                        if (ok) { log("SEATED POLICY warm-up of $id done"); break }
                        // Back to never-run, so a visitor's first message still works while we wait to retry.
                        runCatching { sessions.get(id)?.takeIf { !warmed(it) && it.state !in WORKING && !it.neverRun }?.let { sessions.restart(id) } }
                        Thread.sleep(retryMillis)
                    }
                }
            } catch (_: InterruptedException) {
                // The plugin stopped.
            }
        }, "seated-warm-up").apply { isDaemon = true; start() }
    }

    /**
     * Warmed = it has a provider session id and its first run did not fail. A FAILED first run is not warmed: it is
     * rebooted and retried. A session that has run more than once is a visitor's, and is left alone whatever its state.
     */
    private fun warmed(r: SessionInfo): Boolean = r.providerSessionId != null && (r.runCount > 1 || r.state !in FIRST_RUN_FAILED)

    private fun waitIdle(sessions: Sessions, id: String, timeoutMillis: Long = 180_000) {
        val until = System.currentTimeMillis() + timeoutMillis
        Thread.sleep(1_000)
        while (System.currentTimeMillis() < until && sessions.get(id)?.state in WORKING) Thread.sleep(1_000)
    }

    /** The seeded project and role -> session map (the seed calls it; a test can too). */
    fun adopt(project: String, sessions: Map<String, String>) {
        projectId = project
        roleToSession = sessions.toMap()
        sessionIds = sessions.values.toSet()
    }

    private fun seeded(id: String?): Boolean = id != null && id in sessionIds

    /**
     * Null = this frame may proceed; else the refusal text (the preset's `refusal` template, through [policy]). [active] is
     * the connection's active session (a blank `mail_send.from` means it, as in the daemon's own handler). Core has already
     * refused every frame the preset's rules refuse; this only scopes the ones they allow.
     */
    fun frameRefusal(frame: Frame, active: String?, policy: Policy): String? {
        if (!enabled) return null
        val type = frame.type
        val ok = when {
            type == REPLAY_REQUEST -> seeded(frame.sessionId)
            type == PROMPT -> seeded(frame.sessionId) && frame.data.isNotBlank() && !frame.data.trimStart().startsWith("/")
            // The Resume button: `<sessionId>\n<prompt>`.
            type == RESUME -> frame.data.substringBefore('\n', "").trim().let(::seeded) &&
                frame.data.substringAfter('\n', "").let { it.isNotBlank() && !it.trimStart().startsWith("/") }
            type == MAIL_SEND -> {
                val request = runCatching { lenient.parseToJsonElement(frame.data) as? JsonObject }.getOrNull()
                request != null && run {
                    val from = request.string("from").ifBlank { frame.sessionId ?: active ?: "" }
                    val cross = (request["allowCrossProject"] as? JsonPrimitive)?.booleanOrNull ?: false
                    mailRefusal(from, request.string("to"), cross) == null
                }
            }
            else -> when (policy.actionOf(frame)?.scope?.uppercase()) {
                "PROJECT" -> projectId != null && namedProject(frame.data) == projectId
                // A SESSION-scoped action the code above does not know how to scope is refused.
                "SESSION" -> false
                else -> true
            }
        }
        return if (ok) null else policy.refusalText(type)
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
    fun mailRefusal(from: String, to: String, allowCrossProject: Boolean): String? {
        if (!enabled) return null
        val target = roleToSession[to] ?: to
        return if (seeded(from) && seeded(target) && from != target && !allowCrossProject) null else mailRefusalText
    }

    /** Null = this HTTP request may proceed (the access policy still applies); else the 403 text. */
    fun httpRefusal(method: String, path: String, webAsset: Boolean): String? {
        if (!enabled) return null
        val ok = webAsset || path == HEALTH_PATH || path == SOCKET_PATH ||
            (method == "GET" && path == VISITOR_TOKEN_PATH) ||
            (method == "POST" && (path == "/mail/send" || path == "/mail/read")) ||
            // GET /attention: the browser bundle checks its token with it (infantryWeb Main.kt TokenCheck); a read.
            (method == "GET" && (path == "/attention" || path == "/mail/inbox" || path == "/mail/outbox" || path == "/mail/waiting" || path.startsWith("/mail/thread/")))
        return if (ok) null else httpRefusalText.replace("{method}", method).replace("{path}", path)
    }

    companion object {
        const val NOTICE_ENV = "INFANTRY_DEMO_NOTICE"
        const val DIR_ENV = "INFANTRY_DEMO_DIR"
        const val SEATS_ENV = "INFANTRY_DEMO_SEATS"
        const val PUBLIC_TOKEN_ENV = "INFANTRY_DEMO_PUBLIC_TOKEN"
        const val PUBLIC_TOKEN_FILE_ENV = "INFANTRY_DEMO_PUBLIC_TOKEN_FILE"
        /** Ticket 9bf81983: answered without a token, only while the lock is on and a public token is set. */
        const val VISITOR_TOKEN_PATH = "/demo/visitor-token"
        /** Once seeded, no path (frame, mail, trigger, ticket) may create a session. */
        const val SPAWN_REFUSAL = "demo: new sessions are disabled in this demo"

        const val PROMPT = "prompt"
        const val RESUME = "resume"
        const val MAIL_SEND = "mail_send"
        const val REPLAY_REQUEST = "replay_request"
        /** Ticket 9bf81983: the frames a visitor's turn limit counts (each makes an agent run). */
        val COUNTED_TURNS = setOf(PROMPT, RESUME, MAIL_SEND)

        private const val HEALTH_PATH = "/health"
        private const val SOCKET_PATH = "/ws"
        /** The project directory of a preset that names none, under the plugin's own state folder. */
        private const val SEATED = "seated"
        private val FIRST_RUN_FAILED = setOf("FAILED", "ORPHANED")
        private val WORKING = setOf("STARTING", "BUSY", "WAITING_INPUT")
        private val lenient = Json { ignoreUnknownKeys = true }
        private val PROJECT_ID_KEY = Regex("\"projectId\"")

        private fun JsonObject.string(key: String): String = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()

        /** `role:provider:model` (the model may hold `/`), e.g. `backend:opencode:vendor/model`. */
        fun parseSeats(specs: List<String>): List<Seat> = specs.mapNotNull { spec ->
            val p = spec.trim().split(':', limit = 3)
            if (p.size == 3 && p.all { it.isNotBlank() }) Seat(p[0].trim().lowercase(), p[1].trim(), p[2].trim()) else null
        }

        /**
         * The lock of [preset]: off when it has no `seats`; else its values, each overridable by the deployment's
         * `INFANTRY_DEMO_*` env. A preset without a project directory seats under `<plugin state dir>/seated`.
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
                projectGoal = spec.projectGoal,
                seats = seats,
                publicToken = getenv(PUBLIC_TOKEN_ENV)?.trim()?.takeIf { it.isNotEmpty() }
                    ?: getenv(PUBLIC_TOKEN_FILE_ENV)?.takeIf { it.isNotBlank() }?.let { readTokenFile(File(it)) },
                turnLimit = if (perIp > 0 && hours > 0) DemoTurnLimit(perIp, hours * 3_600_000L, File(stateDir, DemoTurnLimit.FILE_NAME), code = spec.turnLimitCode ?: DemoTurnLimit.PREFIX, text = spec.turnLimitText ?: DemoTurnLimit.TEXT) else null,
                trustedProxies = DemoTurnLimit.trustedProxies(getenv),
                warmUpPrompt = spec.warmUpPrompt,
                mailRefusalText = spec.mailRefusal,
                httpRefusalText = spec.httpRefusal,
            )
        }

        /** The token file's text, trimmed (a UTF-8 BOM dropped); an unreadable or empty file is an error, as for the owner token. */
        fun readTokenFile(file: File): String =
            file.readText(Charsets.UTF_8).removePrefix("\uFEFF").trim().ifEmpty { error("the token file ${file.path} is empty") }
    }
}
