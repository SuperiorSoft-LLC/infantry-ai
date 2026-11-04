// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.opencode.harness

import com.superiorsoftllc.infantry.harness.api.ApprovalReply
import com.superiorsoftllc.infantry.harness.api.ConfigParts
import com.superiorsoftllc.infantry.harness.api.HarnessHost
import com.superiorsoftllc.infantry.harness.api.HarnessProcess
import com.superiorsoftllc.infantry.harness.api.HarnessRequest
import com.superiorsoftllc.infantry.harness.api.HarnessSession
import com.superiorsoftllc.infantry.harness.api.SessionConnection
import com.superiorsoftllc.infantry.harness.api.SessionOutput
import com.superiorsoftllc.infantry.harness.api.SessionTurnEnd
import com.superiorsoftllc.infantry.harness.api.ToolApproval
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
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
 * HARNESS H8b-2 (API 2.12): the `opencode serve` PROTOCOL as a [HarnessSession], and nothing else. The lifecycle (one process per session, reuse, the
 * restart after a crash, close, shutdown, the chat boot line) is the daemon's session host's. The core `OpenCodeServeAdapter` stays as it was.
 *
 *  - [launch]: `opencode serve --port <free> --hostname 127.0.0.1` with a random basic-auth password and the inline config (permissions, MCP,
 *    watch plugin) in its environment; the host starts it. The process prints `listening on <url>`; [Connection.onStdout] reads that line,
 *    opens the `/event` stream and reports ready;
 *  - [Connection.sendTurn]: waits for ready, resumes the request's `ses_...` id or creates one (`POST /session`, reported through
 *    [SessionOutput.providerSession]), then `POST /session/{ses}/prompt_async`, the model in the body (per turn, so not in [fingerprint]);
 *  - the server's `message.part.updated` events are re-emitted ([SessionOutput.output]) as the exact lines `opencode run --format json` prints
 *    (`step_start` / `text` / `tool_use` / `step_finish`), so [OpenCodeTranslator] reads one format; `session.idle` ends the turn;
 *  - [Connection.abortTurn] is `POST /session/{ses}/abort`, the process lives on; [Connection.close] answers false (opencode has no graceful exit).
 *
 * BUG-PERM-2: a permission request is answered from the turn's Tool approval ([OpenCodePermissions.serveReply]); every reject also emits the denial
 * line. A question is rejected: nobody can answer it. PROMPT-4: under `ask` each `permission.asked` goes to the owner ([SessionOutput.requestApproval])
 * on a thread of its own; `ask` is part of the [fingerprint]. `infantry *` and the session's own MCP tools are never prompted.
 */
class OpenCodeServeSession(
    private val host: HarnessHost?,
    private val executable: () -> String,
    private val portPicker: () -> Int = ::freeLoopbackPort,
    override val bootTimeoutMs: Long = 30_000,
    private val modelFor: (String?) -> String = { OpenCodeCommand.effectiveModel(it) },
    private val http: HttpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build(),
    private val processEnv: (String) -> String? = System::getenv,
) : HarnessSession {
    override val label: String = "opencode"

    /** cwd + env + whether the ask rules are in its config: what the server process is bound to. The model is per turn. */
    override fun fingerprint(request: HarnessRequest, env: Map<String, String>): String =
        request.project + "\u0000" + env.toSortedMap() + (if (request.toolApproval == ToolApproval.ASK) "\u0000ask" else "")

    override fun launch(request: HarnessRequest, env: Map<String, String>): HarnessProcess {
        val port = portPicker()
        val password = randomPassword()
        val command = listOf(executable(), "serve", "--port", port.toString(), "--hostname", "127.0.0.1")
        // BUG-PERM-5: the infantry allow rides the inline config, merged into whatever the child would inherit. MCP-1: + mcp.infantry.
        val inherited = env[OpenCodePermissions.CONFIG_CONTENT_ENV] ?: processEnv(OpenCodePermissions.CONFIG_CONTENT_ENV)
        val served = OpenCodePermissions.serveConfig(request.toolApproval, inherited, host?.mcpLaunch(env))
        // STALL-2: + the watch plugin (allowAll only: a rewrite must never widen what the session may run).
        val config = if (request.toolApproval == ToolApproval.ALLOW_ALL) {
            val plugin = host?.takeIf { it.watchLauncher(env) != null }?.let { installWatchPlugin(it.stateDir) }
            OpenCodeConfig.merge(served, ConfigParts(watchPlugin = plugin))
        } else served
        return HarnessProcess(command, env = mapOf(OpenCodePermissions.CONFIG_CONTENT_ENV to config, PASSWORD_ENV to password))
    }

    override fun connect(out: SessionOutput): SessionConnection = error("opencode serve needs the launched process (connect(out, launched))")

    override fun connect(out: SessionOutput, launched: HarnessProcess): SessionConnection =
        Connection(out, launched.env[PASSWORD_ENV].orEmpty())

    // --- one `opencode serve` process -----------------------------------------------------------------

    private inner class Connection(private val out: SessionOutput, password: String) : SessionConnection {
        private val auth = "Basic " + Base64.getEncoder().encodeToString("opencode:$password".toByteArray())
        private val urlFuture = CompletableFuture<String>()
        private val ready = CompletableFuture<Unit>()
        private val recentStderr = Collections.synchronizedList(mutableListOf<String>())
        private val stopped = AtomicBoolean(false)
        private val subscribed = CountDownLatch(1)
        private val started = AtomicBoolean(false)
        @Volatile private var sse: Thread? = null
        @Volatile private var active: Turn? = null
        @Volatile private var toolApproval: ToolApproval = ToolApproval.DENY
        @Volatile private var providerSessionId: String? = null
        @Volatile private var url: String = ""
        private val prompting = ConcurrentHashMap<Thread, AtomicBoolean>()

        override fun onStdout(line: String) {
            val found = LISTENING.find(line) ?: return
            if (!started.compareAndSet(false, true)) return
            url = found.groupValues[1].trimEnd('/')
            urlFuture.complete(url)
            sse = Thread({ readEvents() }, "infantry-opencode-sse").apply { isDaemon = true; start() }
            Thread({
                if (!subscribed.await(bootTimeoutMs, TimeUnit.MILLISECONDS) || stopped.get()) ready.completeExceptionally(IllegalStateException("opencode serve at $url never opened its event stream"))
                else ready.complete(Unit)
            }, "infantry-opencode-boot").apply { isDaemon = true; start() }
        }

        override fun onStderr(line: String) {
            recentStderr += line
            while (recentStderr.size > 10) recentStderr.removeAt(0)
        }

        override fun sendTurn(request: HarnessRequest) {
            val model = modelFor(request.model)
            val turn = Turn(out, model, maxRetries = maxRetries(), giveUp = { abortProviderTurn() }) { recentStderr.toList() }.also { active = it }
            toolApproval = request.toolApproval
            // The boot wait and the HTTP calls run off the host's thread; the turn ends exactly once either way.
            Thread({
                try {
                    try { ready.get(bootTimeoutMs, TimeUnit.MILLISECONDS) } catch (e: Exception) {
                        return@Thread turn.end(SessionTurnEnd.FAILED, 1, "opencode serve did not start within ${bootTimeoutMs}ms" + recentStderr.toList().takeIf { it.isNotEmpty() }?.joinToString(" | ", prefix = ": ").orEmpty())
                    }
                    val ses = providerSessionId ?: establishSession(request.sessionId)
                    val body = buildJsonObject {
                        put("parts", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", request.prompt) }) })
                        splitModel(model)?.let { (providerId, modelId) ->
                            put("model", buildJsonObject { put("providerID", providerId); put("modelID", modelId) })
                        }
                    }
                    post("/session/$ses/prompt_async", body.toString())
                } catch (e: Exception) {
                    out.diagnostic("[infantry] opencode serve refused the turn: ${e.message}")
                    turn.end(SessionTurnEnd.FAILED, 1, "opencode serve refused the turn")
                }
            }, "infantry-opencode-turn").apply { isDaemon = true; start() }
        }

        /** Resume the harness session [resume] or create one; reported once. */
        private fun establishSession(resume: String?): String {
            val resumed = resume?.takeIf { it.isNotBlank() }
            val ses = resumed ?: createSession()
            providerSessionId = ses
            out.detail("opencode serve on $url, " + if (resumed != null) "resuming provider session $ses" else "provider session $ses")
            out.providerSession(ses)
            return ses
        }

        private fun createSession(): String {
            val created = post("/session", "{}")
            return (Json.parseToJsonElement(created) as JsonObject)["id"].str() ?: error("opencode serve created a session without an id: ${created.take(200)}")
        }

        /** Stops the TURN (`/abort`); the server lives on. The turn ends now, so a late idle for it is ignored. */
        private fun maxRetries(): Int = host?.setting(MAX_RETRIES_SETTING)?.trim()?.toIntOrNull()?.takeIf { it >= 0 } ?: DEFAULT_MAX_RETRIES

        private fun abortProviderTurn() {
            providerSessionId?.let { ses ->
                Thread({ runCatching { post("/session/$ses/abort", "{}") } }, "infantry-opencode-abort").apply { isDaemon = true; start() }
            }
        }

        override fun abortTurn(): Boolean {
            withdrawPrompts()
            val turn = active ?: return true
            abortProviderTurn()
            turn.end(SessionTurnEnd.INTERRUPTED, 130, null)
            return true
        }

        override fun close(): Boolean = false

        private fun withdrawPrompts() { for (cancelled in prompting.values) cancelled.set(true) }

        /** PROMPT-4: ask the owner on a thread of its own, then reply; a non-approval is announced once. */
        private fun askOwner(requestId: String, sessionId: String, tool: String, input: String) {
            val cancelled = AtomicBoolean(false)
            val thread = Thread({
                try {
                    val decision = out.requestApproval(tool, input)
                    if (cancelled.get() || stopped.get()) return@Thread
                    runCatching { post("/permission/$requestId/reply", OpenCodePermissions.serveReplyBody(decision)) }
                    if (decision == ApprovalReply.DENY) out.output(OpenCodePermissions.denialLine(sessionId, tool, prompted = true))
                } finally {
                    prompting.remove(Thread.currentThread())
                }
            }, "infantry-opencode-prompt")
            thread.isDaemon = true
            prompting[thread] = cancelled
            thread.start()
        }

        private fun post(path: String, body: String): String {
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
                // The stream ends when the server dies or is stopped; the host hears of a death through the process exit.
            } finally {
                stopped.set(true)
                withdrawPrompts()
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
                "permission.asked", "permission.updated" -> if (mine) {
                    val mode = toolApproval
                    val id = props.requestId()
                    if (id != null && needsOwner(props, mode)) askOwner(id, sessionId!!, permissionName(props), approvalInput(props))
                    else permissionDecision(props, mode)?.let { d ->
                        runCatching { post("/permission/${d.requestId}/reply", d.replyBody) }
                        d.deniedTool?.let { out.output(OpenCodePermissions.denialLine(sessionId!!, it)) }
                    }
                }
                "question.asked" -> if (mine) props.requestId()?.let { runCatching { post("/question/$it/reject", "{}") } }
                else -> if (mine) active?.onEvent(type, props, part, sessionId!!)
            }
        }
    }

    // --- one turn -------------------------------------------------------------------------------------

    class Turn(private val out: SessionOutput, private val model: String, private val maxRetries: Int = DEFAULT_MAX_RETRIES, private val giveUp: () -> Unit = {}, private val stderrTail: () -> List<String> = { emptyList() }) {
        private val done = AtomicBoolean(false)
        private val userMessages = ConcurrentHashMap.newKeySet<String>()
        @Volatile private var replied = false
        private val emitted = ConcurrentHashMap.newKeySet<String>()
        @Volatile private var sawBusy = false
        @Volatile private var errored = false

        fun end(end: SessionTurnEnd, exitCode: Int?, reason: String?) {
            if (done.compareAndSet(false, true)) out.turnEnded(end, exitCode, reason)
        }

        private fun idle() = when {
            errored -> end(SessionTurnEnd.FAILED, 1, "opencode reported an error")
            // An idle with nothing said is not a success: the owner would see a finished turn and no answer.
            !replied -> end(SessionTurnEnd.FAILED, null, noReply(model, stderrTail()))
            else -> end(SessionTurnEnd.COMPLETED, 0, null)
        }

        /** opencode retries a refusing provider (quota, 429) forever and sends no error: show it, and give up after [maxRetries] (0 = never). */
        private fun onRetry(status: JsonObject) {
            val attempt = (status["attempt"] as? JsonPrimitive)?.longOrNull ?: 0
            val message = status["message"].str() ?: "provider error"
            sawBusy = true
            out.diagnostic("[infantry] opencode: $message, retry $attempt")
            if (maxRetries > 0 && attempt > maxRetries) {
                giveUp()
                end(SessionTurnEnd.FAILED, 1, "opencode: $message (gave up after $maxRetries retries)")
            }
        }

        fun onEvent(type: String, props: JsonObject, part: JsonObject?, sessionId: String) {
            if (done.get()) return
            when (type) {
                "session.status" -> when ((props["status"] as? JsonObject)?.get("type").str()) {
                    "retry" -> onRetry(props["status"] as JsonObject)
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
                    // The prompt's own echo (a user message part) is no sign the model started.
                    if (part["messageID"].str()?.let { it in userMessages } == true) return
                    sawBusy = true
                    val lineType = runLineType(part) ?: return
                    val partId = part["id"].str()
                    // A tool part is seen twice (running, then completed): the start is its own line, deduped apart.
                    if (lineType == "tool_start" && partId != null && partId in emitted) return
                    if (partId != null && !emitted.add(if (lineType == "tool_start") "start:$partId" else partId)) return
                    val startReported = lineType == "tool_use" && partId != null && "start:$partId" in emitted
                    if (lineType == "text" || lineType == "tool_use") replied = true
                    emit(buildJsonObject {
                        put("type", lineType)
                        put("timestamp", (props["time"] as? JsonPrimitive)?.longOrNull ?: System.currentTimeMillis())
                        put("sessionID", sessionId)
                        put("part", part)
                        // BUG-PERM-4: this session already emitted `permission_denied` for its own rejects.
                        put(OpenCodeTranslator.DENIAL_REPORTED, true)
                        if (startReported) put(OpenCodeTranslator.START_REPORTED, true)
                    })
                }
            }
        }

        private fun emit(line: JsonObject) = out.output(line.toString())
    }

    companion object {
        const val MAX_RETRIES_SETTING = "harness.opencode.maxRetries"
        const val DEFAULT_MAX_RETRIES = 2

        /** `opencode serve` reads its basic-auth password from here; set per child so no other local process can drive it. */
        const val PASSWORD_ENV = "OPENCODE_SERVER_PASSWORD"

        const val WATCH_PLUGIN = "infantry-watch.js"

        private val LISTENING = Regex("""listening on (https?://\S+)""")

        /** The "no reply" reason: names the model asked and the raw stderr, so a silent turn is never a plain success. */
        fun noReply(model: String, stderr: List<String> = emptyList()): String =
            "no reply from $model: the turn ended with no assistant output" +
                stderr.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.joinToString(" | ", prefix = " (opencode stderr: ", postfix = ")").orEmpty()

        /** Writes the watch plugin into [dir] (only when missing or different) and returns the file. */
        fun installWatchPlugin(dir: File): File {
            val text = requireNotNull(OpenCodeServeSession::class.java.getResource("/opencode/$WATCH_PLUGIN")) { "missing resource /opencode/$WATCH_PLUGIN" }.readText()
            val file = File(dir, WATCH_PLUGIN)
            if (!file.isFile || file.readText() != text) { dir.mkdirs(); file.writeText(text) }
            return file
        }

        /** The `opencode run --format json` line type for a finished server part, or null when run would print nothing. */
        fun runLineType(part: JsonObject): String? = when (part["type"].str()) {
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
        data class PermissionDecision(val requestId: String, val replyBody: String, val deniedTool: String?)

        /** The answer to a `permission.asked` / `permission.updated` under [mode]; null when the request carries no id. */
        fun permissionDecision(props: JsonObject, mode: ToolApproval): PermissionDecision? {
            val id = props.requestId() ?: return null
            val permission = props["permission"].str() ?: props["type"].str()
            // BUG-PERM-5: a bash ask whose EVERY pattern is a plain infantry command is answered `once`, in every mode. MCP-1: and so is one of the session's own tools.
            val infantry = isInfantryAsk(props) || OpenCodeConfig.isInfantryTool(permission)
            val allowed = infantry || OpenCodePermissions.allows(mode, permission)
            return PermissionDecision(
                requestId = id,
                replyBody = if (infantry) OpenCodePermissions.serveReplyBody(ToolApproval.ALLOW_ALL, permission) else OpenCodePermissions.serveReplyBody(mode, permission),
                deniedTool = if (allowed) null else permission?.takeIf { it.isNotBlank() } ?: "unknown",
            )
        }

        private fun isInfantryAsk(props: JsonObject): Boolean =
            (props["permission"].str() ?: props["type"].str())?.trim()?.lowercase() == "bash" &&
                bashCommands(props).let { c -> c.isNotEmpty() && c.all(OpenCodePermissions::isInfantryCommand) }

        /** PROMPT-4: under [mode] `ask`, this ask goes to the owner; an infantry command never does (BUG-PERM-5). */
        fun needsOwner(props: JsonObject, mode: ToolApproval): Boolean =
            mode == ToolApproval.ASK && !isInfantryAsk(props) && !OpenCodeConfig.isInfantryTool(props["permission"].str() ?: props["type"].str())

        fun permissionName(props: JsonObject): String =
            (props["permission"].str() ?: props["type"].str())?.takeIf { it.isNotBlank() } ?: "unknown"

        /** PROMPT-4: what the call is, for the card: the command, else the file, else the patterns. */
        fun approvalInput(props: JsonObject): String {
            val metadata = props["metadata"] as? JsonObject
            metadata?.get("command").str()?.let { return it }
            (metadata?.get("filepath").str() ?: metadata?.get("filePath").str())?.let { return it }
            (props["patterns"] as? JsonArray)?.mapNotNull { it.str() }?.takeIf { it.isNotEmpty() }?.let { return it.joinToString(", ") }
            val pattern = props["pattern"]
            if (pattern is JsonArray) return pattern.mapNotNull { it.str() }.joinToString(", ")
            return pattern.str() ?: metadata?.get("url").str() ?: ""
        }

        private fun bashCommands(props: JsonObject): List<String> {
            (props["patterns"] as? JsonArray)?.mapNotNull { it.str() }?.takeIf { it.isNotEmpty() }?.let { return it }
            val pattern = props["pattern"]
            if (pattern is JsonArray) pattern.mapNotNull { it.str() }.takeIf { it.isNotEmpty() }?.let { return it }
            pattern.str()?.let { return listOf(it) }
            return listOfNotNull((props["metadata"] as? JsonObject)?.get("command").str())
        }

        /** `provider/model` -> (provider, model), split at the FIRST slash; null when there is none. */
        fun splitModel(model: String): Pair<String, String>? {
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
