// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.runners

import com.superiorsoftllc.infantry.daemon.api.orchestrator.OrchestratorReply
import com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerLink
import com.superiorsoftllc.infantry.daemon.api.runners.LinkAnswer
import com.superiorsoftllc.infantry.daemon.api.runners.LinkFailure
import com.superiorsoftllc.infantry.daemon.api.runners.LinkHandler
import com.superiorsoftllc.infantry.daemon.api.runners.LinkServing
import com.superiorsoftllc.infantry.daemon.api.runners.LinkTlsMaterial
import com.superiorsoftllc.infantry.daemon.api.runners.RunnerLinkTransport
import com.superiorsoftllc.infantry.daemon.api.runners.RunnerLinks
import com.superiorsoftllc.infantry.settings.LimitDef
import com.superiorsoftllc.infantry.settings.LimitRegistry
import com.superiorsoftllc.infantry.settings.RunnerLinkSettings
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import com.superiorsoftllc.infantry.daemon.api.runners.RunnerLinkWire as F

/**
 * RUNNER-POOL-5: the daemon's share of the runner link, run every `limits.runners.linkCheckSeconds` by [tick]:
 *
 * * Runner side: once this daemon is paired (by `infantry runner pair` on its console), it serves the link (wss on
 *   `limits.runners.linkPort`); after a detach from either side it stops serving it.
 * * Orchestrator side: rekeys every paired runner whose credential is `limits.runners.credentialRotateDays` old.
 *
 * A daemon that was never paired opens nothing: no listener, no link keystore ([RunnerLinkStore.exists]).
 *
 * RUNNER-POOL-6a: the transport is a plugin's ([transport], `hooks.runnerLinkTransport`, read at each use so a hot load or unload
 * is seen). With none loaded the daemon serves no link and [admin] refuses the operations that need one ([NO_TRANSPORT]). The
 * `infantry runner pair|add|rotate|detach <name>` commands reach the daemon as the owner-only [ADMIN] frame ([admin]), so only
 * the daemon loads the plugin; the pairing listener `runner pair` used to open in the CLI process is opened here.
 *
 * RUNNER-POOL-6: [links] is the orchestrator side's [RunnerLinks] (`api.runnerLinks`): one frame to a paired runner by name, with
 * the url, pin and credential this store keeps; [starts] is the runner side's clone-and-push for starts with a git remote.
 */
class RunnerLinkService(
    private val stateDir: File,
    private val limit: (LimitDef) -> Long,
    private val settings: () -> RunnerLinkSettings.Values,
    private val runner: RunnerLink?,
    private val transport: () -> RunnerLinkTransport?,
    private val host: String = ANY_HOST,
    private val log: (String) -> Unit = { System.err.println(it) },
    private val clock: () -> Long = System::currentTimeMillis,
    private val starts: RemoteStarts? = null,
    /** RUNNER-POOL-7 (runner side): the agent frames this runner forwards to its orchestrator. */
    private val forwarder: FrameForwarder? = null,
    /** RUNNER-POOL-7 (orchestrator side): pulls and answers the frames the paired runners forward; [tick] keeps it on every paired runner. */
    private val pump: ForwardPump? = null,
) : AutoCloseable {
    private var serving: LinkServing? = null
    private var pairing: Pairing? = null
    private var toldNoTransport = false
    private var closed = false

    /** A `runner pair` in progress: its listener and its one-time code. */
    private class Pairing(val serving: LinkServing, val codes: PairCodes, val name: String)

    private val dir: File get() = RunnerLinkStore.dir(stateDir)

    @Synchronized
    fun tick() {
        // A closed service never re-opens the link listener: its ticker may fire once more while the daemon shuts down, and the port must be free for the restart.
        if (closed) return
        pairing?.let { p -> if (RunnerLinkStore.open(stateDir).orchestrator() != null || p.codes.liveName(clock()) == null) closePairing() }
        if (!RunnerLinkStore.exists(stateDir)) {
            pump?.sync(emptyList())
            return
        }
        val store = RunnerLinkStore.open(stateDir)
        val paired = store.orchestrator() != null
        val t = transport()
        if (t == null) {
            if (serving != null) {
                closeServing()
                log("[infantry] runner link: the transport plugin was unloaded; the link listener is stopped")
            }
            if (paired && !toldNoTransport) log("[infantry] runner link: paired, but not served: $NO_TRANSPORT")
            toldNoTransport = true
            return
        }
        toldNoTransport = false
        if (paired && serving == null && pairing == null) {
            runCatching { serve(store, t) }.onFailure { log("[infantry] runner link: could not serve the link: ${it.message}") }
        } else if (!paired && serving != null) {
            closeServing()
            log("[infantry] runner link: detached; the link listener is stopped")
        }
        pump?.sync(store.runners().map { it.name })
        if (store.runners().isNotEmpty()) {
            peers(store).rotateDue(limit(LimitRegistry.RUNNERS_CREDENTIAL_ROTATE_DAYS)).forEach { log("[infantry] $it") }
        }
    }

    private fun serve(store: RunnerLinkStore, t: RunnerLinkTransport) {
        val server = RunnerLinkServer(
            store, RunnerAudit(File(dir, RunnerAudit.FILE)), codes = null, settings = settings, runner = { runner }, starts = starts,
            forwarder = forwarder, forwardWaitCapMs = ::forwardWaitMs,
            limit = limit, cloneRoot = ::cloneRootDir,
        )
        val plain = settings().allowPlainTailnet
        val s = t.serve(handler(server), host, linkPort(), if (plain) null else tls(store), callTimeoutMs())
        serving = s
        log("[infantry] runner link: serving ${if (plain) "PLAIN ws (runners.allowPlainTailnet)" else "wss"} on $host:${s.port} for orchestrator ${store.orchestrator()?.name}")
    }

    /** RUNNER-POOL-6: the paired runners by name, for a runner binding plugin (credentials stay here). */
    val links: RunnerLinks = object : RunnerLinks {
        override fun paired(): List<String> =
            if (RunnerLinkStore.exists(stateDir)) RunnerLinkStore.open(stateDir).runners().map { it.name }.sorted() else emptyList()

        override fun call(runner: String, type: String, data: String): LinkAnswer {
            if (!RunnerLinkStore.exists(stateDir)) throw LinkFailure("no paired runner '$runner'")
            val store = RunnerLinkStore.open(stateDir)
            val r = store.runner(runner) ?: throw LinkFailure("no paired runner '$runner'")
            val answer = clientCall(limit, settings, transport).call(r.url, r.fingerprint.ifBlank { null }, store.selfId(), r.credential, type, data, limit(LimitRegistry.RUNNERS_LINK_CALL_SECONDS))
            return LinkAnswer(answer.type, answer.data)
        }
    }

    /** The port the paired link is served on, or null while it is not served. */
    @Synchronized
    fun servingPort(): Int? = serving?.port

    /** The orchestrator-side operations over the loaded transport, with the daemon's settings. */
    fun peers(store: RunnerLinkStore): RunnerPeers = RunnerPeers(
        store, clientCall(limit, settings, transport), clock,
        callTimeoutSec = { limit(LimitRegistry.RUNNERS_LINK_CALL_SECONDS) },
        hostTimeoutSec = { limit(LimitRegistry.RUNNERS_ADD_HOST_TIMEOUT_SEC) },
    )

    /**
     * RUNNER-POOL-6a: one owner `runner_admin` request (`{"op": ..., ...}`) -> its answer object: `{"ok": true, ...}`, or
     * `{"ok": false, "error": <reason>, "presentedFingerprint": <fp or null>}`. Ops: `pair {name}`, `pair_status`, `pair_cancel`,
     * `add {name, url, code, fingerprint?, orchestratorName}` or `add {link, name?, orchestratorName}` (RUNNER-ADD-LINK-1),
     * `rotate {name}`, `detach {name}`.
     */
    @Synchronized
    fun admin(request: JsonObject): JsonObject = try {
        fun str(key: String) = (request[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        fun need(key: String) = str(key)?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("$key is required")
        val op = need("op")
        if (op in NEEDS_TRANSPORT && transport() == null) throw IllegalArgumentException(NO_TRANSPORT)
        when (op) {
            "pair" -> pair(need("name"), str("allowProjects"), str("allowHarnesses"), str("hosts"))
            "pair_preview" -> JsonObject(ok() + ("installedHarnesses" to JsonArray(runner?.hello()?.let { (it as? OrchestratorReply.Ok)?.value }?.harnesses.orEmpty().map { JsonPrimitive(it.id) })))
            "pair_status" -> pairStatus()
            "list" -> list()
            "pair_cancel" -> { closePairing(); ok() }
            "pause", "resume" -> {
                val name = need("name")
                val known = name == "local" || (RunnerLinkStore.exists(stateDir) && RunnerLinkStore.open(stateDir).runner(name) != null)
                require(known) { "no runner '${name.filter { it in ' '..'~' }.take(SHOWN_OP_SIZE)}'" }
                setPaused(name, op == "pause")
                ok("name" to name, "paused" to (op == "pause").toString())
            }
            "add" -> {
                // RUNNER-ADD-LINK-1: a pasted link (the one-string flow) or the older name/url/code/fingerprint form; both keep it.
                val store = RunnerLinkStore.open(stateDir)
                val text = str("link")
                val outcome = if (text != null) {
                    val parsed = when (val p = RunnerPairLink.parse(text)) {
                        is RunnerPairLink.Parsed.Ok -> p.link
                        is RunnerPairLink.Parsed.Bad -> throw IllegalArgumentException(p.reason)
                    }
                    val name = str("name")?.takeIf { it.isNotBlank() } ?: parsed.name
                    peers(store).addLink(parsed, name, str("orchestratorName").orEmpty(), settings().allowPlainTailnet)
                } else {
                    val r = peers(store).add(need("name"), need("url"), need("code"), str("fingerprint"), str("orchestratorName").orEmpty())
                    AddOutcome(r, emptyList())
                }
                // Adding a runner IS the owner's consent (`runner_admin` is owner-only), so the first add turns the feature on too.
                val turnedOn = !enabledNow() && runCatching { setEnabled(true) }.isSuccess
                val r = outcome.runner
                ok(
                    "name" to r.name, "url" to r.url, "runnerId" to r.runnerId,
                    "skipped" to outcome.skipped.joinToString("; ").takeIf { it.isNotEmpty() },
                    "enabledTurnedOn" to turnedOn.toString(),
                )
            }
            "rotate" -> ok("name" to peers(RunnerLinkStore.open(stateDir)).rotate(need("name")).name)
            // RUNNER-CHECK-1: the health check (link, contract, sign-in, grants, git, disk) with the fix for each failing row.
            "check" -> check(need("name"), str("projectId"), str("harness"))
            "detach" -> {
                if (!RunnerLinkStore.exists(stateDir)) throw IllegalArgumentException("this machine never paired")
                ok("remote" to peers(RunnerLinkStore.open(stateDir)).detach(need("name")))
            }
            else -> throw IllegalArgumentException("unknown runner_admin op '${op.filter { it in ' '..'~' }.take(SHOWN_OP_SIZE)}'")
        }
    } catch (e: LinkFailure) {
        failed(e.message, e.presentedFingerprint)
    } catch (e: IllegalArgumentException) {
        failed(e.message, null)
    }

    /** RUNNER-CHECK-1: where this runner keeps its clones (the check's disk row and its `ls-remote` folder). */
    private fun cloneRootDir(): File = RemoteStarts.cloneRootDir(runCatching { settings().cloneRoot }.getOrDefault(""), stateDir)

    /**
     * RUNNER-CHECK-1 (docs/wiki/RunnerPairing-design.md 4, step 4): one health check of runner [name], from this side.
     *
     * The runner answers its own rows ([F.CHECK]); this adds the two only the orchestrator can decide:
     *
     * * `link`: it was reached, in how many ms (or the transport error, as a failing row instead of an exception).
     * * `git:origin`: the project's checkout has an `origin` to give the runner at all; without one the remote start would fail
     *   (when a `projectId` is named and this daemon can look the project up).
     *
     * A runner that does not know [F.CHECK] answers an error: one `contract` row says it is too old.
     */
    private fun check(name: String, projectId: String?, harness: String?): JsonObject {
        val store = RunnerLinkStore.open(stateDir)
        val r = store.runner(name) ?: throw IllegalArgumentException("no paired runner '${name.filter { it in ' '..'~' }.take(SHOWN_OP_SIZE)}'")
        val project = projectId?.takeIf { it.isNotBlank() }
        val remote = project?.let { runCatching { projectRemoteOf(it) }.getOrNull() }?.takeIf { it.isNotBlank() }
        val rows = mutableListOf<F.CheckRow>()
        if (project != null && remote == null) {
            rows += F.CheckRow(
                "git:origin", false, "project '$project' has no git remote: its checkout has no `origin`, so the runner has nothing to clone",
                "git -C <the project's folder on this machine> remote add origin <a url the runner can reach>",
            )
        }
        val request = F.CheckRequest(projectId = project, harness = harness?.takeIf { it.isNotBlank() }, gitRemote = remote, contract = F.CONTRACT)
        val data = F.json.encodeToString(F.CheckRequest.serializer(), request)
        // The runner may spend limits.runners.checkGitSec in its git row: give the call that on top of a normal link call.
        val timeout = limit(LimitRegistry.RUNNERS_LINK_CALL_SECONDS) + limit(LimitRegistry.RUNNERS_CHECK_GIT_SEC)
        val started = clock()
        val answer = try {
            clientCall(limit, settings, transport).call(r.url, r.fingerprint.ifBlank { null }, store.selfId(), r.credential, F.CHECK, data, timeout)
        } catch (e: LinkFailure) {
            rows += F.CheckRow(
                "link", false, "cannot reach runner '$name' at ${r.url}: ${e.message}",
                "check the runner is running and its link port is reachable from this machine (a firewall or tailnet rule)",
            )
            return rowsJson(rows)
        }
        rows += F.CheckRow("link", true, "reached ${r.url} in ${clock() - started} ms")
        if (!answer.ok) {
            rows += F.CheckRow(
                "contract", false,
                "runner too old for checks; update it (it answered: ${answer.data.filter { it in ' '..'~' }.take(SHOWN_OP_SIZE * 4)})",
                "update the runner to the same release as this daemon (restart it with the current image or install), then check again",
            )
            return rowsJson(rows)
        }
        val remoteRows = runCatching { F.json.decodeFromString(F.CheckAnswer.serializer(), answer.data) }.getOrNull()
            ?: return rowsJson(rows + F.CheckRow("contract", false, "the runner's check answer could not be read", "update the runner to the same release as this daemon"))
        return rowsJson(rows + remoteRows.rows)
    }

    private fun rowsJson(rows: List<F.CheckRow>): JsonObject = JsonObject(
        ok() + mapOf("rows" to JsonArray(rows.map { F.json.encodeToJsonElement(F.CheckRow.serializer(), it) })),
    )

    /**
     * RUNNER-POOL-10: what the Runners card shows. The pool fills health, workers and caps through [rowStatus] (one runner name -> its
     * fields); this adds the paired url and pinned fingerprint. `enabled` is [enabledNow]. Read only: pause is the setting
     * `runners.<name>.paused`, detach and rotate are the ops above.
     */
    @Volatile var rowStatus: (String) -> Map<String, String> = { emptyMap() }
    @Volatile var enabledNow: () -> Boolean = { false }
    /** RUNNER-POOL-11: writes `runners.<name>.paused` for the `pause` / `resume` ops; the daemon wires the setting. */
    @Volatile var setPaused: (String, Boolean) -> Unit = { _, _ -> }
    /** RUNNER-ADD-LINK-1: writes `runners.enabled`; the first successful add turns it on so one paste is enough. */
    @Volatile var setEnabled: (Boolean) -> Unit = { }
    /**
     * RUNNER-CHECK-1: the git remote of a project (`origin` of its checkout), the same lookup a remote start uses; null = the
     * project has none, which the check reports as its `git:origin` row.
     */
    @Volatile var projectRemoteOf: (String?) -> String? = { null }

    private fun list(): JsonObject {
        val paired = if (RunnerLinkStore.exists(stateDir)) RunnerLinkStore.open(stateDir).runners() else emptyList()
        val rows = (listOf("local") + paired.map { it.name }).map { name ->
            val peer = paired.firstOrNull { it.name == name }
            JsonObject((rowStatus(name) + listOfNotNull("name" to name, peer?.let { "url" to it.url }, peer?.let { "fingerprint" to it.fingerprint })).mapValues { JsonPrimitive(it.value) })
        }
        return JsonObject(ok().plus(mapOf("enabled" to JsonPrimitive(enabledNow()), "runners" to JsonArray(rows))))
    }

    /** RUNNER-DEFAULTS-1: stores the owner's explicit grant (`runner.projects` / `runner.harnesses`); the daemon wires the setting. */
    @Volatile var grant: (key: String, ids: List<String>) -> Unit = { _, _ -> }

    private fun pair(name: String, allowProjects: String? = null, allowHarnesses: String? = null, hosts: String? = null): JsonObject {
        require(RunnerLinkStore.NAME.matches(name)) { "runner name '$name' must be 1-32 of a-z, 0-9 and '-' (starting with a letter or digit)" }
        val t = transport() ?: throw IllegalArgumentException(NO_TRANSPORT)
        val store = RunnerLinkStore.open(stateDir)
        store.orchestrator()?.let {
            throw IllegalArgumentException("this runner is already paired with orchestrator ${it.name}; one orchestrator only. `infantry runner detach` first.")
        }
        // A new `runner pair` replaces one still open: its code dies with its listener.
        closePairing()
        // The grant is the owner's flag or console answer; null = untouched (the settings keep their values).
        fun ids(csv: String?) = csv?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
        ids(allowProjects)?.let { grant(RunnerLinkSettings.PROJECTS, it) }
        ids(allowHarnesses)?.let { grant(RunnerLinkSettings.HARNESSES, it) }
        val values = settings()
        val plain = values.allowPlainTailnet
        val tls = if (plain) null else tls(store)
        val fingerprint = tls?.let(t::fingerprint)
        val codes = PairCodes(
            File(dir, PairCodes.ATTEMPTS_FILE),
            expiryMs = { limit(LimitRegistry.RUNNERS_PAIR_CODE_MIN) * MS_PER_MINUTE },
            attemptsPerHour = { limit(LimitRegistry.RUNNERS_PAIR_ATTEMPTS_PER_HOUR) },
        )
        val server = RunnerLinkServer(store, RunnerAudit(File(dir, RunnerAudit.FILE)), codes, settings = { values }, runner = { null }, clock = clock)
        val s = try {
            t.serve(handler(server), host, linkPort(), tls, callTimeoutMs())
        } catch (e: Exception) {
            throw IllegalArgumentException("cannot serve the runner link on port ${linkPort()} (${e.message}); is the daemon already serving it, or set limits.runners.linkPort")
        }
        val code = codes.issue(name, clock())
        pairing = Pairing(s, codes, name)
        // RUNNER-LINK-1: the one link; `code`, `fingerprint` and `port` stay for older CLIs.
        val candidates = RunnerPairLink.candidates(listOfNotNull(hosts), values.advertiseHosts, limit(LimitRegistry.RUNNERS_ADVERTISE_HOSTS_MAX).toInt())
        val link = candidates.takeIf { it.isNotEmpty() }?.let { RunnerPairLink(name, it, s.port, code, fingerprint, plain).toUri() }
        return ok(
            "code" to code, "link" to link, "fingerprint" to fingerprint, "port" to s.port.toString(), "plain" to plain.toString(),
            "expiryMin" to limit(LimitRegistry.RUNNERS_PAIR_CODE_MIN).toString(),
        ).let { JsonObject(it + mapOf("projects" to JsonArray(values.projects.map(::JsonPrimitive)), "harnesses" to JsonArray(values.harnesses.map(::JsonPrimitive)))) }
    }

    /** `paired` (with the orchestrator), `waiting` (the code is live) or `expired`; a finished pairing's listener is closed. */
    private fun pairStatus(): JsonObject {
        val paired = if (RunnerLinkStore.exists(stateDir)) RunnerLinkStore.open(stateDir).orchestrator() else null
        val live = pairing?.codes?.liveName(clock()) != null
        if (paired != null || !live) closePairing()
        return when {
            paired != null -> ok("state" to "paired", "orchestratorName" to paired.name, "orchestratorId" to paired.id)
            live -> ok("state" to "waiting")
            else -> ok("state" to "expired")
        }
    }

    /** RUNNER-POOL-7: how long one forward pull waits on the runner: `limits.runners.forwardWaitSec`, kept below the link call timeout. */
    fun forwardWaitMs(): Long =
        minOf(limit(LimitRegistry.RUNNERS_FORWARD_WAIT_SEC), limit(LimitRegistry.RUNNERS_LINK_CALL_SECONDS) / 2).coerceAtLeast(1) * MS_PER_SECOND

    private fun tls(store: RunnerLinkStore) = LinkTlsMaterial(dir.also { it.mkdirs() }, store.tlsPassword())
    private fun linkPort() = limit(LimitRegistry.RUNNERS_LINK_PORT).toInt()
    private fun callTimeoutMs() = (limit(LimitRegistry.RUNNERS_LINK_CALL_SECONDS) * MS_PER_SECOND).toInt()

    private fun closePairing() {
        pairing?.let { runCatching { it.serving.close() } }
        pairing = null
    }

    private fun closeServing() {
        serving?.let { runCatching { it.close() } }
        serving = null
    }

    @Synchronized
    override fun close() {
        closed = true
        pump?.close()
        closePairing()
        closeServing()
    }

    companion object {
        const val ANY_HOST = "0.0.0.0"
        /** RUNNER-POOL-6a: the owner frame `infantry runner pair|add|rotate|detach <name>` send ([FrameGate.OWNER_ONLY]). */
        const val ADMIN = com.superiorsoftllc.infantry.policy.ActionCatalog.RUNNER_ADMIN
        const val NO_TRANSPORT = "the runner link needs the Infantry_RemoteRunner plugin (no runner link transport is loaded)"
        private val NEEDS_TRANSPORT = setOf("pair", "add", "rotate", "detach")
        private const val MS_PER_SECOND = 1000L
        private const val MS_PER_MINUTE = 60_000L
        private const val SHOWN_OP_SIZE = 32

        /** [LinkCall] over the loaded transport with the link call timeout and the plain-ws switch read at each call. */
        fun clientCall(limit: (LimitDef) -> Long, settings: () -> RunnerLinkSettings.Values, transport: () -> RunnerLinkTransport?) = LinkCall { url, fp, id, cred, type, data, timeoutSeconds ->
            val t = transport() ?: throw LinkFailure(NO_TRANSPORT)
            t.call(url, fp, settings().allowPlainTailnet, id, cred, type, data, timeoutSeconds)
                .let { RunnerLinkServer.Answer(it.type, it.data) }
        }

        /** [server] as the transport's handler. */
        fun handler(server: RunnerLinkServer): LinkHandler = object : LinkHandler {
            override fun handle(type: String, data: String, orchestratorId: String?, credential: String?, from: String?): LinkAnswer =
                server.handle(type, data, orchestratorId, credential, from).let { LinkAnswer(it.type, it.data) }

            override fun malformed(): LinkAnswer = LinkAnswer(F.ERROR, "malformed frame")
        }

        private fun ok(vararg fields: Pair<String, String?>): JsonObject =
            JsonObject(mapOf("ok" to JsonPrimitive(true)) + fields.associate { (k, v) -> k to (v?.let(::JsonPrimitive) ?: JsonNull) })

        private fun failed(message: String?, presented: String?): JsonObject = JsonObject(
            mapOf("ok" to JsonPrimitive(false), "error" to JsonPrimitive(message ?: "failed"), "presentedFingerprint" to (presented?.let(::JsonPrimitive) ?: JsonNull)),
        )

        /** A string field of an admin answer, or null. */
        fun field(answer: JsonObject, key: String): String? = (answer[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull
    }
}
