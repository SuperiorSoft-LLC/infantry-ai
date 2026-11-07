// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.runners

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

    /** A `runner pair` in progress: its listener and its one-time code. */
    private class Pairing(val serving: LinkServing, val codes: PairCodes, val name: String)

    private val dir: File get() = RunnerLinkStore.dir(stateDir)

    @Synchronized
    fun tick() {
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
            val answer = clientCall(limit, settings, transport).call(r.url, r.fingerprint.ifBlank { null }, store.selfId(), r.credential, type, data)
            return LinkAnswer(answer.type, answer.data)
        }
    }

    /** The port the paired link is served on, or null while it is not served. */
    @Synchronized
    fun servingPort(): Int? = serving?.port

    /** The orchestrator-side operations over the loaded transport, with the daemon's settings. */
    fun peers(store: RunnerLinkStore): RunnerPeers = RunnerPeers(store, clientCall(limit, settings, transport), clock)

    /**
     * RUNNER-POOL-6a: one owner `runner_admin` request (`{"op": ..., ...}`) -> its answer object: `{"ok": true, ...}`, or
     * `{"ok": false, "error": <reason>, "presentedFingerprint": <fp or null>}`. Ops: `pair {name}`, `pair_status`, `pair_cancel`,
     * `add {name, url, code, fingerprint?, orchestratorName}`, `rotate {name}`, `detach {name}`.
     */
    @Synchronized
    fun admin(request: JsonObject): JsonObject = try {
        fun str(key: String) = (request[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        fun need(key: String) = str(key)?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("$key is required")
        val op = need("op")
        if (op in NEEDS_TRANSPORT && transport() == null) throw IllegalArgumentException(NO_TRANSPORT)
        when (op) {
            "pair" -> pair(need("name"))
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
                val r = peers(RunnerLinkStore.open(stateDir)).add(need("name"), need("url"), need("code"), str("fingerprint"), str("orchestratorName").orEmpty())
                ok("name" to r.name, "url" to r.url, "runnerId" to r.runnerId)
            }
            "rotate" -> ok("name" to peers(RunnerLinkStore.open(stateDir)).rotate(need("name")).name)
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

    /**
     * RUNNER-POOL-10: what the Runners card shows. The pool fills health, workers and caps through [rowStatus] (one runner name -> its
     * fields); this adds the paired url and pinned fingerprint. `enabled` is [enabledNow]. Read only: pause is the setting
     * `runners.<name>.paused`, detach and rotate are the ops above.
     */
    @Volatile var rowStatus: (String) -> Map<String, String> = { emptyMap() }
    @Volatile var enabledNow: () -> Boolean = { false }
    /** RUNNER-POOL-11: writes `runners.<name>.paused` for the `pause` / `resume` ops; the daemon wires the setting. */
    @Volatile var setPaused: (String, Boolean) -> Unit = { _, _ -> }

    private fun list(): JsonObject {
        val paired = if (RunnerLinkStore.exists(stateDir)) RunnerLinkStore.open(stateDir).runners() else emptyList()
        val rows = (listOf("local") + paired.map { it.name }).map { name ->
            val peer = paired.firstOrNull { it.name == name }
            JsonObject((rowStatus(name) + listOfNotNull("name" to name, peer?.let { "url" to it.url }, peer?.let { "fingerprint" to it.fingerprint })).mapValues { JsonPrimitive(it.value) })
        }
        return JsonObject(ok().plus(mapOf("enabled" to JsonPrimitive(enabledNow()), "runners" to JsonArray(rows))))
    }

    private fun pair(name: String): JsonObject {
        require(RunnerLinkStore.NAME.matches(name)) { "runner name '$name' must be 1-32 of a-z, 0-9 and '-' (starting with a letter or digit)" }
        val t = transport() ?: throw IllegalArgumentException(NO_TRANSPORT)
        val store = RunnerLinkStore.open(stateDir)
        store.orchestrator()?.let {
            throw IllegalArgumentException("this runner is already paired with orchestrator ${it.name}; one orchestrator only. `infantry runner detach` first.")
        }
        // A new `runner pair` replaces one still open: its code dies with its listener.
        closePairing()
        val values = settings()
        val plain = values.allowPlainTailnet
        val tls = if (plain) null else tls(store)
        val fingerprint = tls?.let(t::fingerprint)
        val codes = PairCodes(
            File(dir, PairCodes.ATTEMPTS_FILE),
            expiryMs = { limit(LimitRegistry.RUNNERS_PAIR_CODE_MIN) * MS_PER_MINUTE },
            attemptsPerHour = { limit(LimitRegistry.RUNNERS_PAIR_ATTEMPTS_PER_HOUR) },
        )
        val server = RunnerLinkServer(store, RunnerAudit(File(dir, RunnerAudit.FILE)), codes, settings = { values }, runner = { null })
        val s = try {
            t.serve(handler(server), host, linkPort(), tls, callTimeoutMs())
        } catch (e: Exception) {
            throw IllegalArgumentException("cannot serve the runner link on port ${linkPort()} (${e.message}); is the daemon already serving it, or set limits.runners.linkPort")
        }
        val code = codes.issue(name, clock())
        pairing = Pairing(s, codes, name)
        return ok(
            "code" to code, "fingerprint" to fingerprint, "port" to s.port.toString(), "plain" to plain.toString(),
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
        fun clientCall(limit: (LimitDef) -> Long, settings: () -> RunnerLinkSettings.Values, transport: () -> RunnerLinkTransport?) = LinkCall { url, fp, id, cred, type, data ->
            val t = transport() ?: throw LinkFailure(NO_TRANSPORT)
            t.call(url, fp, settings().allowPlainTailnet, id, cred, type, data, limit(LimitRegistry.RUNNERS_LINK_CALL_SECONDS))
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
