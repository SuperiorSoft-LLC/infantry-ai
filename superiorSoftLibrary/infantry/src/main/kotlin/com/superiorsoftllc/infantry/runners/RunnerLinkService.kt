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
import com.superiorsoftllc.infantry.settings.LimitDef
import com.superiorsoftllc.infantry.settings.LimitRegistry
import com.superiorsoftllc.infantry.settings.RunnerLinkSettings
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import com.superiorsoftllc.infantry.runners.RunnerLinkFrames as F

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
        if (!RunnerLinkStore.exists(stateDir)) return
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
        if (store.runners().isNotEmpty()) {
            peers(store).rotateDue(limit(LimitRegistry.RUNNERS_CREDENTIAL_ROTATE_DAYS)).forEach { log("[infantry] $it") }
        }
    }

    private fun serve(store: RunnerLinkStore, t: RunnerLinkTransport) {
        val server = RunnerLinkServer(store, RunnerAudit(File(dir, RunnerAudit.FILE)), codes = null, settings = settings, runner = { runner })
        val plain = settings().allowPlainTailnet
        val s = t.serve(handler(server), host, linkPort(), if (plain) null else tls(store), callTimeoutMs())
        serving = s
        log("[infantry] runner link: serving ${if (plain) "PLAIN ws (runners.allowPlainTailnet)" else "wss"} on $host:${s.port} for orchestrator ${store.orchestrator()?.name}")
    }

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
            "pair_cancel" -> { closePairing(); ok() }
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
