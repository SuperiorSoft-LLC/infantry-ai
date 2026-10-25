package com.superiorsoftllc.agenthq.opencode

import com.superiorsoftllc.agenthq.session.InfantryEnv

import com.superiorsoftllc.agenthq.daemon.AgentHqMcp
import com.superiorsoftllc.agenthq.harness.ToolApproval
import com.superiorsoftllc.agenthq.provider.RunRequest
import com.superiorsoftllc.agenthq.runner.ProcessListener
import com.superiorsoftllc.agenthq.runner.ProcessRunner
import com.superiorsoftllc.agenthq.runner.ProcessSpec
import com.superiorsoftllc.agenthq.runner.RunningProcess
import com.superiorsoftllc.agenthq.session.host.AdapterSink
import com.superiorsoftllc.agenthq.session.host.HarnessSessionAdapter
import com.superiorsoftllc.agenthq.session.host.HostedProcess
import com.superiorsoftllc.agenthq.session.host.TurnEnd
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * BUG-M2-11b-2 (docs/AGENTHQ-SESSION-HOST-PLAN.md section 6.1): the `opencode serve` PROTOCOL, and nothing else. The
 * lifecycle (one process per session, reuse, the restart after a crash, close, shutdown, the chat boot line) belongs to
 * [com.superiorsoftllc.agenthq.session.host.SessionHost], the same for every harness.
 *
 *  - [launch] starts `opencode serve` through the host's runner, in the session's cwd with the session's env, on a
 *    loopback port with a random basic-auth password; waits for `listening on`; subscribes to `/event`; then resumes
 *    the request's `ses_...` id or creates one (`POST /session`) and reports it through [AdapterSink.providerSession];
 *  - [sendTurn] is `POST /session/{ses}/prompt_async`, the model in the body (per turn, so NOT in the [fingerprint]);
 *  - the server's `message.part.updated` events are re-emitted as the exact lines `opencode run --format json` prints
 *    (`step_start` / `text` / `tool_use` / `step_finish`), so the daemon's parsers see no difference; `session.idle`
 *    ends the turn;
 *  - [abortTurn] is `POST /session/{ses}/abort`, and the process lives on; [close] answers false: opencode has no
 *    graceful exit (stdin EOF is ignored), so the host tree-kills.
 *
 * BUG-PERM-2: a permission request is answered from the turn's Tool approval ([OpenCodePermissions.serveReply]); every
 * reject also emits the denial line the daemon turns into the BUG-PERM-1 chat notice. A question is still rejected:
 * nobody can answer it. Verified against opencode 1.18.35.
 *
 * PROMPT-4: under Tool approval `ask` the server is launched with [OpenCodePermissions.ASK_PERMISSIONS] set to `ask`
 * ([OpenCodePermissions.serveConfig]), and each `permission.asked` goes to the owner ([AdapterSink.requestApproval], on
 * a thread of its own so the event stream keeps flowing). The answer is the reply: allow_once -> `once`, allow_session
 * -> `always`, deny / timeout / session end -> `reject` (+ the denial line, once, marked `prompted`). `ask` is part of
 * the [fingerprint], so switching into or out of it restarts the server: an `always` never outlives the mode.
 * `agenthq *` is never prompted (BUG-PERM-5).
 */
class OpenCodeServeAdapter(
    private val executable: () -> String = OpenCodeCommand::resolveExecutable,
    private val portPicker: () -> Int = ::freeLoopbackPort,
    override val bootTimeoutMs: Long = 30_000,
    /** The model a turn names: the session's choice, else the configured default (the rule `opencode run` uses). */
    private val modelFor: (String?) -> String = { OpenCodeCommand.effectiveModel(it) },
    // HTTP/1.1: the JDK client's default h2c upgrade attempt leaves opencode's (Bun) server never answering /event.
    private val http: HttpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(5)).build(),
) : HarnessSessionAdapter {
    override val label: String = "opencode"

    /** cwd + env + whether the ask rules are in its config: what the server process is bound to. The model is per turn. */
    override fun fingerprint(request: RunRequest, env: Map<String, String>): String =
        request.workingDir?.absolutePath + "\u0000" + env.toSortedMap() + (if (request.toolApproval == ToolApproval.ASK) "\u0000ask" else "")

    override fun launch(request: RunRequest, env: Map<String, String>, runner: ProcessRunner, sink: AdapterSink): HostedProcess {
        val server = Server(sink)
        server.boot(request, env, runner)
        try {
            val resumed = request.resumeSessionId?.takeIf { it.isNotBlank() }
            val ses = resumed ?: server.createSession()
            server.providerSessionId = ses
            sink.detail("opencode serve on ${server.url}, " + if (resumed != null) "resuming provider session $ses" else "provider session $ses")
            sink.providerSession(ses)
        } catch (e: Exception) {
            server.killTree()
            throw e
        }
        return server
    }

    override fun sendTurn(process: HostedProcess, request: RunRequest) = (process as Server).send(request)

    override fun abortTurn(process: HostedProcess): Boolean = (process as Server).abort()

    override fun close(process: HostedProcess): Boolean = false

    // --- one `opencode serve` process -----------------------------------------------------------------

    private inner class Server(private val sink: AdapterSink) : HostedProcess {
        private val password = randomPassword()
        private val auth = "Basic " + Base64.getEncoder().encodeToString("opencode:$password".toByteArray())
        private val urlFuture = CompletableFuture<String>()
        private val bootStderr = Collections.synchronizedList(mutableListOf<String>())
        /** The last server stderr lines, kept for the "no reply" notice (the raw reason a silent turn can name). */
        private val recentStderr = Collections.synchronizedList(mutableListOf<String>())
        private val booted = AtomicBoolean(false)
        private val stopped = AtomicBoolean(false)
        private val subscribed = CountDownLatch(1)
        private val exitLock = Any()
        private val exitHandlers = mutableListOf<(Int) -> Unit>()
        @Volatile private var exitCode: Int? = null
        @Volatile private var process: RunningProcess? = null
        @Volatile private var sse: Thread? = null
        @Volatile private var active: Turn? = null
        /** BUG-PERM-2: the Tool approval of the turn in flight; what a permission request is answered from. */
        @Volatile private var toolApproval: ToolApproval = ToolApproval.DENY
        @Volatile var providerSessionId: String? = null
        lateinit var url: String
        /** PROMPT-4: the owner prompts in flight (thread -> withdrawn), so an abort or a kill can withdraw them. */
        private val prompting = ConcurrentHashMap<Thread, AtomicBoolean>()

        override val pid: Long? get() = process?.pid
        override val isAlive: Boolean get() = !stopped.get() && exitCode == null && process?.isAlive == true

        override fun onExit(handler: (exitCode: Int) -> Unit) {
            val code = synchronized(exitLock) { exitCode ?: run { exitHandlers += handler; null } } ?: return
            handler(code)
        }

        override fun killTree() {
            if (!stopped.compareAndSet(false, true)) return
            withdrawPrompts()
            sse?.interrupt()
            process?.let { p -> runCatching { p.killTree() } }
        }

        fun boot(request: RunRequest, env: Map<String, String>, runner: ProcessRunner) {
            val port = portPicker()
            val command = listOf(executable(), "serve", "--port", port.toString(), "--hostname", "127.0.0.1")
            // BUG-PERM-5: the agenthq allow rides the inline config, merged into whatever the child would inherit.
            val inherited = env[OpenCodePermissions.CONFIG_CONTENT_ENV] ?: InfantryEnv.get(OpenCodePermissions.CONFIG_CONTENT_ENV)
            // PROMPT-4: in `ask` mode the gated permissions are `ask` too, so opencode emits permission.asked.
            val config = OpenCodePermissions.CONFIG_CONTENT_ENV to OpenCodePermissions.serveConfig(request.toolApproval, inherited, env) // MCP-1: + mcp.agenthq
            process = runner.start(ProcessSpec(command, request.workingDir, env + config + (PASSWORD_ENV to password)), object : ProcessListener {
                override fun onStdout(line: String) {
                    LISTENING.find(line)?.let { urlFuture.complete(it.groupValues[1].trimEnd('/')) }
                }

                // Until the server is up, stderr is kept for the launch-failure message only: the launch runs on the
                // caller's thread, which may hold the session manager's monitor that a forwarded line would need.
                override fun onStderr(line: String) {
                    recentStderr += line
                    while (recentStderr.size > 10) recentStderr.removeAt(0)
                    if (booted.get()) return sink.diagnostic(line)
                    bootStderr += line
                    while (bootStderr.size > 10) bootStderr.removeAt(0)
                }

                override fun onExit(exitCode: Int) = died(exitCode)
            })
            url = try {
                urlFuture.get(bootTimeoutMs, TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
                killTree()
                throw IllegalStateException(
                    "opencode serve did not start within ${bootTimeoutMs}ms" +
                        bootStderr.toList().takeIf { it.isNotEmpty() }?.joinToString(" | ", prefix = ": ").orEmpty(),
                )
            }
            sse = Thread({ readEvents() }, "agenthq-opencode-sse").apply { isDaemon = true; start() }
            if (!subscribed.await(bootTimeoutMs, TimeUnit.MILLISECONDS) || !isAlive) {
                killTree()
                throw IllegalStateException("opencode serve at $url never opened its event stream")
            }
            booted.set(true)
        }

        private fun died(code: Int) {
            urlFuture.completeExceptionally(IllegalStateException("opencode serve exited with code $code"))
            subscribed.countDown()
            val handlers = synchronized(exitLock) {
                if (exitCode != null) return
                exitCode = code
                exitHandlers.toList().also { exitHandlers.clear() }
            }
            handlers.forEach { runCatching { it(code) } }
        }

        fun createSession(): String {
            val created = post("/session", "{}")
            return (Json.parseToJsonElement(created) as JsonObject)["id"].str()
                ?: error("opencode serve created a session without an id: ${created.take(200)}")
        }

        fun send(request: RunRequest) {
            toolApproval = request.toolApproval
            val model = modelFor(request.model)
            val turn = Turn(sink, model) { recentStderr.toList() }.also { active = it }
            val ses = providerSessionId ?: return turn.end(TurnEnd.FAILED, 1, "opencode serve has no session")
            try {
                val body = buildJsonObject {
                    put("parts", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", request.prompt) }) })
                    splitModel(model)?.let { (providerId, modelId) ->
                        put("model", buildJsonObject { put("providerID", providerId); put("modelID", modelId) })
                    }
                }
                post("/session/$ses/prompt_async", body.toString())
            } catch (e: Exception) {
                sink.diagnostic("[agenthq] opencode serve refused the turn: ${e.message}")
                turn.end(TurnEnd.FAILED, 1, "opencode serve refused the turn")
            }
        }

        /** Stops the TURN (`/abort`); the server lives on. The turn ends now, so a late idle for it is ignored. */
        fun abort(): Boolean {
            withdrawPrompts()
            val turn = active ?: return true
            providerSessionId?.let { ses ->
                Thread({ runCatching { post("/session/$ses/abort", "{}") } }, "agenthq-opencode-abort").apply { isDaemon = true; start() }
            }
            turn.end(TurnEnd.INTERRUPTED, 130, null)
            return true
        }

        /**
         * PROMPT-4: the owner prompts in flight no longer matter: when they resolve (the daemon resolves a session's
         * prompts at its turn's end), their threads post nothing and announce nothing.
         */
        private fun withdrawPrompts() {
            for (cancelled in prompting.values) cancelled.set(true)
        }

        /** PROMPT-4: ask the owner on a thread of its own, then reply; a non-approval is announced once. */
        private fun askOwner(requestId: String, sessionId: String, tool: String, input: String) {
            val cancelled = AtomicBoolean(false)
            val thread = Thread({
                try {
                    val decision = sink.requestApproval(tool, input)
                    if (cancelled.get() || stopped.get()) return@Thread
                    runCatching { post("/permission/$requestId/reply", OpenCodePermissions.serveReplyBody(decision)) }
                    if (!decision.allows) sink.output(OpenCodePermissions.denialLine(sessionId, tool, prompted = true))
                } finally {
                    prompting.remove(Thread.currentThread())
                }
            }, "agenthq-opencode-prompt")
            thread.isDaemon = true
            prompting[thread] = cancelled
            thread.start()
        }

        fun post(path: String, body: String): String {
            val request = HttpRequest.newBuilder(URI.create(url + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", auth)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            check(response.statusCode() in 200..299) { "opencode serve $path answered ${response.statusCode()}: ${response.body().take(300)}" }
            return response.body()
        }

        private fun readEvents() {
            try {
                val request = HttpRequest.newBuilder(URI.create("$url/event")).header("Authorization", auth)
                    .header("Accept", "text/event-stream").GET().build()
                val response = http.send(request, HttpResponse.BodyHandlers.ofLines())
                if (response.statusCode() !in 200..299) return
                subscribed.countDown()
                response.body().use { lines ->
                    for (line in lines) {
                        if (stopped.get()) break
                        if (!line.startsWith("data:")) continue
                        val event = runCatching { Json.parseToJsonElement(line.removePrefix("data:").trim()) as? JsonObject }.getOrNull() ?: continue
                        runCatching { dispatch(event) }
                    }
                }
            } catch (_: Exception) {
                // The stream ends when the server dies or is stopped; the host hears of a death through onExit.
            } finally {
                subscribed.countDown()
            }
        }

        private fun dispatch(event: JsonObject) {
            val type = event["type"].str() ?: return
            val props = event["properties"] as? JsonObject ?: return
            val part = props["part"] as? JsonObject
            val sessionId = props["sessionID"].str() ?: part?.get("sessionID").str()
            val mine = sessionId != null && sessionId == providerSessionId
            when (type) {
                // BUG-PERM-2: answered from the turn's Tool approval; a reject is reported as a denial.
                "permission.asked", "permission.updated" -> if (mine) {
                    val mode = toolApproval
                    val id = props.requestId()
                    // PROMPT-4: under `ask`, everything but an agenthq command goes to the owner.
                    if (id != null && needsOwner(props, mode)) askOwner(id, sessionId!!, permissionName(props), approvalInput(props))
                    else permissionDecision(props, mode)?.let { d ->
                        runCatching { post("/permission/${d.requestId}/reply", d.replyBody) }
                        d.deniedTool?.let { sink.output(OpenCodePermissions.denialLine(sessionId!!, it)) }
                    }
                }
                "question.asked" -> if (mine) props.requestId()?.let { runCatching { post("/question/$it/reject", "{}") } }
                else -> if (mine) active?.onEvent(type, props, part, sessionId!!)
            }
        }
    }

    // --- one turn -------------------------------------------------------------------------------------

    internal class Turn(private val sink: AdapterSink, private val model: String, private val stderrTail: () -> List<String> = { emptyList() }) {
        private val done = AtomicBoolean(false)
        private val userMessages = ConcurrentHashMap.newKeySet<String>()
        /** True once a `text` or `tool_use` line was emitted: the turn produced something the owner can read. */
        @Volatile private var replied = false
        private val emitted = ConcurrentHashMap.newKeySet<String>()
        @Volatile private var sawBusy = false
        @Volatile private var errored = false

        fun end(end: TurnEnd, exitCode: Int?, reason: String?) {
            if (done.compareAndSet(false, true)) sink.turnEnded(end, exitCode, reason)
        }

        private fun idle() = when {
            errored -> end(TurnEnd.FAILED, 1, "opencode reported an error")
            // An idle with nothing said is not a success: the owner would see a finished turn and no answer.
            !replied -> end(TurnEnd.FAILED, null, noReply(model, stderrTail()))
            else -> end(TurnEnd.COMPLETED, 0, null)
        }

        fun onEvent(type: String, props: JsonObject, part: JsonObject?, sessionId: String) {
            if (done.get()) return
            when (type) {
                "session.status" -> when ((props["status"] as? JsonObject)?.get("type").str()) {
                    "busy" -> sawBusy = true
                    "idle" -> if (sawBusy) idle()
                }
                "session.idle" -> if (sawBusy) idle()
                "message.updated" -> {
                    val info = props["info"] as? JsonObject
                    if (info?.get("role").str() == "user") info?.get("id").str()?.let { userMessages += it }
                }
                "session.error" -> {
                    errored = true
                    sawBusy = true
                    val error = props["error"] as? JsonObject
                    val message = (error?.get("data") as? JsonObject)?.get("message").str() ?: error?.get("message").str()
                        ?: error?.get("name").str() ?: "opencode reported an error"
                    emit(buildJsonObject {
                        put("type", "error"); put("timestamp", System.currentTimeMillis()); put("sessionID", sessionId)
                        put("error", buildJsonObject { put("message", message); error?.get("name").str()?.let { put("name", it) } })
                    })
                }
                "message.part.updated" -> {
                    part ?: return
                    // The prompt's own echo (a user message part) is no sign the model started: an idle after it
                    // (a late one from an aborted turn) must not end this turn as if it had answered.
                    if (part["messageID"].str()?.let { it in userMessages } == true) return
                    sawBusy = true
                    val lineType = runLineType(part) ?: return
                    val partId = part["id"].str()
                    // A tool part is seen twice (running, then completed): the start is its own line, deduped apart.
                    // A late `running` after the part already finished would reopen a tool nothing closes: dropped.
                    if (lineType == "tool_start" && partId != null && partId in emitted) return
                    if (partId != null && !emitted.add(if (lineType == "tool_start") "start:$partId" else partId)) return
                    val startReported = lineType == "tool_use" && partId != null && "start:$partId" in emitted
                    if (lineType == "text" || lineType == "tool_use") replied = true
                    emit(buildJsonObject {
                        put("type", lineType)
                        put("timestamp", (props["time"] as? JsonPrimitive)?.longOrNull ?: System.currentTimeMillis())
                        put("sessionID", sessionId)
                        put("part", part)
                        // BUG-PERM-4: this adapter already emitted `permission_denied` for its own rejects; the normaliser
                        // must not announce the same refusal again from the part's error.
                        put(com.superiorsoftllc.agenthq.protocol.EventNormalizer.OPENCODE_DENIAL_REPORTED, true)
                        if (startReported) put(com.superiorsoftllc.agenthq.protocol.EventNormalizer.OPENCODE_START_REPORTED, true)
                    })
                }
            }
        }

        private fun emit(line: JsonObject) = sink.output(line.toString())
    }

    companion object {
        /** `opencode serve` reads its basic-auth password from here; set per child so no other local process can drive it. */
        const val PASSWORD_ENV = "OPENCODE_SERVER_PASSWORD"

        /** The "no reply" reason: names the model asked and the raw stderr, so a silent turn is never a plain success. */
        internal fun noReply(model: String, stderr: List<String> = emptyList()): String =
            "no reply from $model: the turn ended with no assistant output" +
                stderr.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.joinToString(" | ", prefix = " (opencode stderr: ", postfix = ")").orEmpty()

        private val LISTENING = Regex("""listening on (https?://\S+)""")

        /** The `opencode run --format json` line type for a finished server part, or null when run would print nothing. */
        internal fun runLineType(part: JsonObject): String? = when (part["type"].str()) {
            "step-start" -> "step_start"
            "step-finish" -> "step_finish"
            "text" -> if ((part["time"] as? JsonObject)?.get("end") != null) "text" else null
            "reasoning" -> if ((part["time"] as? JsonObject)?.get("end") != null) "reasoning" else null
            "tool" -> when ((part["state"] as? JsonObject)?.get("status").str()) {
                "running" -> "tool_start"
                "completed", "error" -> "tool_use"
                else -> null
            }
            else -> null
        }

        /** BUG-PERM-2: how one permission request is answered; [deniedTool] is set exactly when the reply is a reject. */
        internal data class PermissionDecision(val requestId: String, val replyBody: String, val deniedTool: String?)

        /**
         * The answer to a `permission.asked` (1.x: `{id, permission, patterns, ...}`) or `permission.updated` (older:
         * `{id, type, pattern, ...}`) under [mode]; null when the request carries no id. The permission name is what
         * [OpenCodePermissions] matches and what the denial notice names.
         */
        internal fun permissionDecision(props: JsonObject, mode: ToolApproval): PermissionDecision? {
            val id = props.requestId() ?: return null
            val permission = props["permission"].str() ?: props["type"].str()
            // BUG-PERM-5: a bash ask whose EVERY pattern is a plain agenthq command is answered `once`, in every mode.
            // MCP-1: and so is a call to one of the session's own AgentHQ MCP tools.
            val agentHq = isAgentHqAsk(props) || AgentHqMcp.isOpenCodeAgentHqTool(permission)
            val allowed = agentHq || OpenCodePermissions.allows(mode, permission)
            return PermissionDecision(
                requestId = id,
                replyBody = if (agentHq) OpenCodePermissions.serveReplyBody(ToolApproval.ALLOW_ALL, permission) else OpenCodePermissions.serveReplyBody(mode, permission),
                deniedTool = if (allowed) null else permission?.takeIf { it.isNotBlank() } ?: "unknown",
            )
        }

        /** BUG-PERM-5: a bash ask whose EVERY pattern is a plain agenthq command. */
        private fun isAgentHqAsk(props: JsonObject): Boolean =
            (props["permission"].str() ?: props["type"].str())?.trim()?.lowercase() == "bash" &&
                bashCommands(props).let { c -> c.isNotEmpty() && c.all(OpenCodePermissions::isAgentHqCommand) }

        /** PROMPT-4: under [mode] `ask`, this ask goes to the owner; an agenthq command never does (BUG-PERM-5). */
        internal fun needsOwner(props: JsonObject, mode: ToolApproval): Boolean =
            mode == ToolApproval.ASK && !isAgentHqAsk(props) &&
                !AgentHqMcp.isOpenCodeAgentHqTool(props["permission"].str() ?: props["type"].str()) // MCP-1

        /** The permission an ask names (what the card and the denial notice show). */
        internal fun permissionName(props: JsonObject): String =
            (props["permission"].str() ?: props["type"].str())?.takeIf { it.isNotBlank() } ?: "unknown"

        /** PROMPT-4: what the call is, for the card: the command, else the file, else the patterns (PendingPrompts shortens it). */
        internal fun approvalInput(props: JsonObject): String {
            val metadata = props["metadata"] as? JsonObject
            metadata?.get("command").str()?.let { return it }
            (metadata?.get("filepath").str() ?: metadata?.get("filePath").str())?.let { return it }
            (props["patterns"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { it.str() }?.takeIf { it.isNotEmpty() }
                ?.let { return it.joinToString(", ") }
            val pattern = props["pattern"]
            if (pattern is kotlinx.serialization.json.JsonArray) return pattern.mapNotNull { it.str() }.joinToString(", ")
            return pattern.str() ?: metadata?.get("url").str() ?: ""
        }

        /** The command text(s) of a bash ask: 1.x `patterns`, else the older `pattern`, else `metadata.command`. */
        private fun bashCommands(props: JsonObject): List<String> {
            (props["patterns"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { it.str() }?.takeIf { it.isNotEmpty() }?.let { return it }
            val pattern = props["pattern"]
            if (pattern is kotlinx.serialization.json.JsonArray) pattern.mapNotNull { it.str() }.takeIf { it.isNotEmpty() }?.let { return it }
            pattern.str()?.let { return listOf(it) }
            return listOfNotNull((props["metadata"] as? JsonObject)?.get("command").str())
        }

        /** `provider/model` -> (provider, model), split at the FIRST slash; null when there is none. */
        internal fun splitModel(model: String): Pair<String, String>? {
            val slash = model.indexOf('/')
            if (slash <= 0 || slash == model.length - 1) return null
            return model.substring(0, slash) to model.substring(slash + 1)
        }

        private fun freeLoopbackPort(): Int = ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { it.localPort }

        private fun randomPassword(): String =
            ByteArray(24).also { SecureRandom().nextBytes(it) }.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

        private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

        private fun JsonObject.requestId(): String? = this["id"].str() ?: this["requestID"].str()
    }
}
