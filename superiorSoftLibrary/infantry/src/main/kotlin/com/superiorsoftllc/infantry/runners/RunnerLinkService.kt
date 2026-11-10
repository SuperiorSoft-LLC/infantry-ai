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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID
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
    /** RUNNER-UP-1: this daemon's deployment mode id (`both` | `runner` | `orchestrator`), so `runner up` can refuse a board-only daemon. */
    private val mode: () -> String = { BOTH_MODE },
) : AutoCloseable {
    private var serving: LinkServing? = null
    private var pairing: Pairing? = null
    private var toldNoTransport = false
    private var closed = false

    /**
     * A `runner pair` in progress: its one-time code and its listener.
     *
     * RUNNER-GRANT-AT-PAIR-1: [answering] guards the listener while a PAIR is being answered. Since the code is spent at the
     * start of the answer and the grants are written to the runner's settings before the answer goes out, a `pair_status` poll
     * (the runner console polls every 500 ms) saw "expired" in that window and closed the listener under the answer, so the
     * orchestrator lost the pairing it had in fact made. The flag makes the poll report its last known state instead.
     */
    private class Pairing(val codes: PairCodes, val name: String) {
        @Volatile var serving: LinkServing? = null
        @Volatile var answering = false
    }

    private val dir: File get() = RunnerLinkStore.dir(stateDir)

    @Synchronized
    fun tick() {
        // A closed service never re-opens the link listener: its ticker may fire once more while the daemon shuts down, and the port must be free for the restart.
        if (closed) return
        pairing?.let { p ->
            if (!p.answering && (RunnerLinkStore.open(stateDir).orchestrator() != null || p.codes.liveName(clock()) == null)) closePairing()
        }
        if (!RunnerLinkStore.exists(stateDir)) {
            pump?.sync(emptyList())
            return
        }
        val store = settle() ?: return
        pump?.sync(store.runners().map { it.name })
        if (store.runners().isNotEmpty()) {
            peers(store).rotateDue(limit(LimitRegistry.RUNNERS_CREDENTIAL_ROTATE_DAYS)).forEach { log("[infantry] $it") }
        }
    }

    /**
     * Opens or closes the paired link listener to match the store; null when no transport is loaded (nothing more to do).
     * RUNNER-LINK-PORT-1: [pairStatus] runs it too the moment a pairing ends, so the paired listener takes the port over at once
     * instead of up to `limits.runners.linkCheckSeconds` later: in that gap nothing listened, so a reused code got a connection
     * refusal instead of the runner's refusal, and a detach from the orchestrator could not reach the runner.
     */
    private fun settle(): RunnerLinkStore? {
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
            return null
        }
        toldNoTransport = false
        if (paired && serving == null && pairing == null) {
            runCatching { serve(store, t) }.onFailure { log("[infantry] runner link: could not serve the link: ${it.message}") }
        } else if (!paired && serving != null) {
            closeServing()
            log("[infantry] runner link: detached; the link listener is stopped")
        }
        return store
    }

    private fun pairedServer(store: RunnerLinkStore) = RunnerLinkServer(
        store, RunnerAudit(File(dir, RunnerAudit.FILE)), codes = null, settings = settings, runner = { runner }, starts = starts,
        forwarder = forwarder, forwardWaitCapMs = ::forwardWaitMs,
        limit = limit, cloneRoot = ::cloneRootDir,
    )

    private fun serve(store: RunnerLinkStore, t: RunnerLinkTransport) {
        val server = pairedServer(store)
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
     * `{"ok": false, "error": <reason>, "presentedFingerprint": <fp or null>}`. Ops: `pair {name, allowProjects?, allowHarnesses?,
     * hosts?, grantOnPair?, harnessCeiling?}`, `pair_status`, `pair_cancel`, `add {name, url, code, fingerprint?, orchestratorName}`
     * or `add {link, name?, orchestratorName, grant?, projectId?}` (RUNNER-ADD-LINK-1; RUNNER-GRANT-AT-PAIR-1 adds `grant`),
     * `rotate {name}`, `detach {name}`, `check {name, projectId?, harness?}` (RUNNER-CHECK-1) and `test {name, harness?, model?}`
     * (RUNNER-TEST-1: one throwaway turn there, its reply, harness, seconds and session id).
     */
    @Synchronized
    fun admin(request: JsonObject): JsonObject = try {
        fun str(key: String) = (request[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        fun flag(key: String) = (request[key] as? JsonPrimitive)?.content in setOf("true", "1")
        fun need(key: String) = str(key)?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("$key is required")
        val op = need("op")
        if (op in NEEDS_TRANSPORT && transport() == null) throw IllegalArgumentException(NO_TRANSPORT)
        when (op) {
            "pair" -> pair(need("name"), str("allowProjects"), str("allowHarnesses"), str("hosts"), flag("grantOnPair"), str("harnessCeiling"), str("port"))
            "pair_preview" -> JsonObject(
                ok() +
                    ("mode" to JsonPrimitive(mode())) +
                    ("installedHarnesses" to JsonArray(installedHarnesses().map(::JsonPrimitive))) +
                    ("grantsAtPairing" to JsonPrimitive(limit(LimitRegistry.RUNNERS_GRANT_PROJECTS_MAX).toString())),
            )
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
                // RUNNER-GRANT-AT-PAIR-1: the owner confirmed the one-time grant in the CLI/UI; this side only carries it.
                val grantAsked = flag("grant")
                val outcome = if (text != null) {
                    val parsed = when (val p = RunnerPairLink.parse(text)) {
                        is RunnerPairLink.Parsed.Ok -> p.link
                        is RunnerPairLink.Parsed.Bad -> throw IllegalArgumentException(p.reason)
                    }
                    if (grantAsked && !parsed.grant) {
                        throw IllegalArgumentException(
                            "that link carries no grant authority: its code was not issued with `--grant-on-pair` on the runner, so this " +
                                "pairing may not widen its grants; run `infantry runner pair --grant-on-pair` there, or grant by hand on the runner",
                        )
                    }
                    val project = str("projectId")?.takeIf { it.isNotBlank() }
                    if (grantAsked && project == null) {
                        throw IllegalArgumentException("granting at pairing needs the project to grant: run `infantry runner add ... --grant --project <id>` (or set INFANTRY_PROJECT_ID)")
                    }
                    val name = str("name")?.takeIf { it.isNotBlank() } ?: parsed.name
                    val grants = if (grantAsked) PairGrants(listOf(project!!), installedHarnesses()) else null
                    peers(store).addLink(parsed, name, str("orchestratorName").orEmpty(), settings().allowPlainTailnet, grants)
                } else {
                    if (grantAsked) throw IllegalArgumentException("`--grant` works with a pairing link (`infantry runner add '<infantry://runner?...>' --grant`); the by-hand form has no link to check the grant authority against")
                    val r = peers(store).add(need("name"), need("url"), need("code"), str("fingerprint"), str("orchestratorName").orEmpty())
                    AddOutcome(r, emptyList())
                }
                // Adding a runner IS the owner's consent (`runner_admin` is owner-only), so the first add turns the feature on too.
                val turnedOn = !enabledNow() && runCatching { setEnabled(true) }.isSuccess
                val r = outcome.runner
                JsonObject(
                    ok(
                        "name" to r.name, "url" to r.url, "runnerId" to r.runnerId,
                        "skipped" to outcome.skipped.joinToString("; ").takeIf { it.isNotEmpty() },
                        "enabledTurnedOn" to turnedOn.toString(),
                        "grantRefusal" to outcome.grantRefusal,
                    ) + mapOf(
                        "grantedProjects" to JsonArray(outcome.grantedProjects.map(::JsonPrimitive)),
                        "grantedHarnesses" to JsonArray(outcome.grantedHarnesses.map(::JsonPrimitive)),
                    ),
                )
            }
            "rotate" -> ok("name" to peers(RunnerLinkStore.open(stateDir)).rotate(need("name")).name)
            // RUNNER-CHECK-1: the health check (link, contract, sign-in, grants, git, disk) with the fix for each failing row.
            "check" -> check(need("name"), str("projectId"), str("harness"))
            // RUNNER-TEST-1: one throwaway turn on the runner, so the owner sees a real answer (and not only green check rows).
            "test" -> test(need("name"), str("harness"), str("model"))
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
     * RUNNER-GRANT-AT-PAIR-1: the harness ids this daemon can run (its local runner's hello), without the no-harness placeholder.
     * On the runner they are the installed harnesses a pairing grant is intersected with (and the ceiling's default); on the
     * orchestrator they are what it asks to grant there (the runner keeps only the ones it has).
     */
    private fun installedHarnesses(): List<String> =
        runner?.hello()?.let { (it as? OrchestratorReply.Ok)?.value }?.harnesses.orEmpty()
            .map { it.id }
            .filter { it != com.superiorsoftllc.infantry.harness.NoHarnessProvider.ID }

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
        // RUNNER-GIT-IDENT-1: the identity a start for this project would carry, so the runner's `git:identity` row is the real answer.
        val identity = project?.let { runCatching { projectIdentityOf(it) }.getOrNull() }
        val request = F.CheckRequest(
            projectId = project, harness = harness?.takeIf { it.isNotBlank() }, gitRemote = remote, contract = F.CONTRACT,
            gitName = identity?.first, gitEmail = identity?.second,
        )
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
     * RUNNER-TEST-1 (docs/wiki/RunnerPairing-design.md 4, step 6): "test this runner": one throwaway turn there, so the owner sees a
     * real answer from that machine and not only green check rows (RUNNER-E2E-3 found a start that "ran" and returned no output).
     *
     * The start is a start the orchestrator already sends ([F.START]): a provider, a model and a one-line prompt, with no ticket, no
     * git remote and no project, so the runner clones nothing and touches no project checkout. Its own grants still decide: a harness
     * not in its `runner.harnesses` is refused there, and that refusal is the answer's error, verbatim.
     *
     * The harness is the one asked for, else `echo` when the runner lists it (a test with it spends nothing), else the first harness
     * the runner reports signed IN; a runner with none refuses here, before anything runs. A test on a real model harness spends one
     * small turn of that account (docs/RUNNER-POOL.md section 5 says so, and the Runners card says it beside its Test button).
     *
     * The answer is read with [F.TRANSCRIPT] every `limits.runners.testPollMs` until a turn answer is there or
     * `limits.runners.testTurnSec` passes; then the session is removed for good ([F.REMOVE], which ends a live session first), so
     * neither side keeps a worker that did nothing. Answer: `{ok, harness, model, seconds, ms, sessionId, reply}`, or
     * `{ok: false, error, lastLines}` when no answer came back.
     *
     * This op holds the admin lock while it waits (one `test` at a time); a `runner pair` polling `pair_status` elsewhere waits for it.
     */
    private fun test(name: String, harness: String?, model: String?): JsonObject {
        val shown = name.filter { it in ' '..'~' }.take(SHOWN_OP_SIZE)
        if (!RunnerLinkStore.exists(stateDir) || RunnerLinkStore.open(stateDir).runner(name) == null) {
            throw IllegalArgumentException("no paired runner '$shown'")
        }
        val helloAnswer = links.call(name, F.HELLO, "{}")
        if (helloAnswer.type != F.REPLY) return testFailure("runner '$shown' answered its hello with: ${helloAnswer.data.ifBlank { "no reason" }}", emptyList(), "", null, 0)
        val hello = runCatching { F.json.decodeFromString(F.HelloAnswer.serializer(), helloAnswer.data) }.getOrNull()
            ?: return testFailure("runner '$shown' answered a hello this daemon cannot read", emptyList(), "", null, 0)
        val provider = harness?.takeIf { it.isNotBlank() } ?: run {
            // "none" is the no-harness placeholder, never a pick; an empty list means the runner reports no harness at all.
            val installed = hello.harnesses.filter { it.id != com.superiorsoftllc.infantry.harness.NoHarnessProvider.ID }
            if (installed.any { it.id == TEST_HARNESS }) TEST_HARNESS
            else installed.firstOrNull { it.loggedIn }?.id
                ?: throw IllegalArgumentException(
                    "no logged-in harness on '$shown'; run `infantry runner check $shown` to see what it can run and sign one in there",
                )
        }
        val chosenModel = model?.takeIf { it.isNotBlank() }
        val startedAt = clock()
        val start = links.call(
            name, F.START,
            F.json.encodeToString(
                F.StartRequest.serializer(),
                F.StartRequest(startId = "test-" + UUID.randomUUID(), provider = provider, model = chosenModel, prompt = TEST_PROMPT),
            ),
        )
        val startAnswer = if (start.type == F.REPLY) runCatching { F.json.decodeFromString(F.StartAnswer.serializer(), start.data) }.getOrNull() else null
        if (start.type != F.REPLY) return testFailure(start.data.ifBlank { "runner '$shown' refused the start" }, emptyList(), provider, chosenModel, clock() - startedAt)
        if (startAnswer == null) return testFailure("runner '$shown' answered a start this daemon cannot read", emptyList(), provider, chosenModel, clock() - startedAt)
        val sessionId = startAnswer.sessionId
        if (sessionId == null) return testFailure(startAnswer.refusal ?: "runner '$shown' started no session", emptyList(), provider, chosenModel, clock() - startedAt)

        val deadline = startedAt + limit(LimitRegistry.RUNNERS_TEST_TURN_SEC) * MS_PER_SECOND
        val pollMs = limit(LimitRegistry.RUNNERS_TEST_POLL_MS)
        var lines: List<String> = emptyList()
        var reply: String? = null
        while (true) {
            transcriptOf(name, sessionId)?.let { lines = it }
            reply = lines.asReversed().firstOrNull(::isTestAnswer)?.trim()?.let(::resultText)?.takeIf { it.isNotEmpty() }
            if (reply != null || clock() >= deadline) break
            sleepUntilNextPoll(pollMs, deadline)
        }
        val turnMillis = maxOf(0, clock() - startedAt)
        val cleaned = cleanUp(name, sessionId)
        val chars = limit(LimitRegistry.RUNNERS_TEST_REPLY_CHARS).toInt()
        return if (reply == null) {
            val out = testFailure("no answer from '$provider' on '$shown' within ${secondsOf(turnMillis)} s (limits.runners.testTurnSec)", lines, provider, chosenModel, turnMillis)
            if (cleaned) out else JsonObject(out + ("cleanup" to JsonPrimitive(LEFT_BEHIND)))
        } else {
            val out = buildJsonObject {
                put("ok", true)
                put("harness", provider)
                put("model", chosenModel ?: "")
                put("seconds", secondsOf(turnMillis))
                put("ms", turnMillis)
                put("sessionId", sessionId)
                put("reply", reply.take(chars))
            }
            if (cleaned) out else JsonObject(out + ("cleanup" to JsonPrimitive(LEFT_BEHIND)))
        }
    }

    /** RUNNER-TEST-1: the shape a test that got no answer answers: what failed, and the transcript it did have. */
    private fun testFailure(error: String, lines: List<String>, harness: String, model: String?, millis: Long): JsonObject {
        val budget = limit(LimitRegistry.RUNNERS_TEST_REPLY_CHARS).toInt()
        val tail = ArrayList<String>()
        var used = 0
        for (line in lines.asReversed()) {
            if (tail.isNotEmpty() && used + line.length > budget) break
            tail.add(0, line.take(budget))
            used += line.length
        }
        return buildJsonObject {
            put("ok", false)
            put("error", error)
            put("harness", harness)
            put("model", model ?: "")
            put("seconds", secondsOf(millis))
            put("ms", millis)
            put("lastLines", JsonArray(tail.map(::JsonPrimitive)))
        }
    }

    /** One greeting at the test session's transcript, or null when the runner did not answer it. */
    private fun transcriptOf(name: String, sessionId: String): List<String>? {
        val answer = runCatching {
            links.call(name, F.TRANSCRIPT, F.json.encodeToString(F.TranscriptRequest.serializer(), F.TranscriptRequest(sessionId)))
        }.getOrNull() ?: return null
        if (answer.type != F.REPLY) return null
        return runCatching { F.json.decodeFromString(F.TranscriptAnswer.serializer(), answer.data).lines }.getOrNull()
    }

    /**
     * RUNNER-TEST-1: whether [line] is a turn answer (what the harness said), not the request or a failure: the runner's transcript
     * marks the turn it was asked `[turn] ...` and its errors `error: ...`.
     */
    /** RUNNER-CLAUDE-PROOF-1: claude's stream-json `result` event carries the reply in its `result` field; show that text, not the event. */
    private fun resultText(line: String): String {
        if (!line.startsWith("{\"type\":\"result\"")) return line
        val text = runCatching { (F.json.parseToJsonElement(line) as? JsonObject)?.get("result") as? JsonPrimitive }.getOrNull()?.takeIf { it.isString }?.content
        return text ?: line
    }

    private fun isTestAnswer(line: String): Boolean {
        val t = line.trim()
        // RUNNER-TEST-ANSWER-1: a failed turn ("[infantry] turn failed: ...") and a stream-json system event (claude's init line)
        // are not what the harness said: the first made a signed-out claude "answer", the second a reply of only {"type":"system"...}.
        return t.isNotEmpty() && !t.startsWith(TURN_LINE) && !t.startsWith(ERROR_LINE) &&
            !t.startsWith(FAILED_TURN_LINE) && !t.startsWith(SYSTEM_EVENT_LINE) && !t.startsWith(SESSION_NOTE_LINE) &&
            !isHarnessNoise(t)
    }

    /**
     * RUNNER-TEST-ANSWER-2: banner and protocol lines a real harness writes before it answers (opencode "server listening on ...",
     * pi `extension_ui_request` / other non-reply JSON events) are not the model's reply; the test keeps waiting past them.
     */
    private fun isHarnessNoise(t: String): Boolean {
        if (t.contains("server listening", ignoreCase = true)) return true
        if (!t.startsWith("{")) return false
        val type = runCatching { ((F.json.parseToJsonElement(t) as? JsonObject)?.get("type") as? JsonPrimitive)?.content }.getOrNull()
        return type != null && type != "result"
    }

    /**
     * RUNNER-TEST-1: end the test session for good and remove it, so no worker that did nothing stays on either session list. One
     * frame does both ([F.REMOVE] ends a live session first); an older runner that does not know it is still stopped ([F.STOP],
     * force) so no turn keeps running, and the answer then carries [LEFT_BEHIND]. True = nothing is left on the runner's list.
     */
    private fun cleanUp(name: String, sessionId: String): Boolean {
        val removed = runCatching {
            links.call(name, F.REMOVE, F.json.encodeToString(F.RemoveRequest.serializer(), F.RemoveRequest(sessionId)))
        }.getOrNull()
        // `removed = false` is "the runner no longer had it": nothing is left behind either.
        if (removed?.type == F.REPLY) return true
        runCatching { links.call(name, F.STOP, F.json.encodeToString(F.StopRequest.serializer(), F.StopRequest(sessionId, TEST_STOP_REASON, force = true))) }
        return false
    }

    /** Wait [pollMs], but never past [deadline]. */
    private fun sleepUntilNextPoll(pollMs: Long, deadline: Long) {
        val wait = minOf(pollMs, deadline - clock()).coerceAtLeast(0)
        if (wait > 0) Thread.sleep(wait)
    }

    /** Whole seconds a test turn took, at least 1 (a sub-second answer reads as "1 s", never "0 s"). */
    private fun secondsOf(millis: Long): Long = ((millis + MS_PER_SECOND - 1) / MS_PER_SECOND).coerceAtLeast(1)

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
    /** RUNNER-CARD-STALE-1b: runs one heartbeat round after a list-changing op, before clients are told, so a new runner is not listed `down` until the next period. */
    @Volatile var settle: () -> Unit = { }
    /**
     * RUNNER-CHECK-1: the git remote of a project (`origin` of its checkout), the same lookup a remote start uses; null = the
     * project has none, which the check reports as its `git:origin` row.
     */
    @Volatile var projectRemoteOf: (String?) -> String? = { null }

    /** RUNNER-GIT-IDENT-1: the git identity (name, email) of a project's checkout, the same lookup a remote start uses; null = none. */
    @Volatile var projectIdentityOf: (String?) -> Pair<String, String>? = { null }

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

    private fun pair(
        name: String,
        allowProjects: String? = null,
        allowHarnesses: String? = null,
        hosts: String? = null,
        grantOnPair: Boolean = false,
        harnessCeiling: String? = null,
        /** RUNNER-LINK-HOST-1: the port the link advertises when it differs from the listener (a Docker `-p 18921:8790`). */
        advertisePort: String? = null,
    ): JsonObject {
        val advertised = advertisePort?.takeIf { it.isNotBlank() }?.let { p ->
            p.toIntOrNull()?.takeIf { it in 1..65535 } ?: throw IllegalArgumentException("--port must be a port number (1-65535), the one the orchestrator reaches this runner on")
        }
        require(RunnerLinkStore.NAME.matches(name)) { "runner name '$name' must be 1-32 of a-z, 0-9 and '-' (starting with a letter or digit)" }
        val t = transport() ?: throw IllegalArgumentException(NO_TRANSPORT)
        val store = RunnerLinkStore.open(stateDir)
        store.orchestrator()?.let {
            throw IllegalArgumentException("this runner is already paired with orchestrator ${it.name}; one orchestrator only. `infantry runner detach` first.")
        }
        // A new `runner pair` replaces one still open: its code dies with its listener.
        closePairing()
        // RUNNER-LINK-PORT-1: `runner detach` on this machine unpairs the store from the CLI process, so the paired listener still
        // holds the port until the next tick ("Address already in use" on an immediate re-pair). It serves nobody now: free the port.
        if (serving != null) {
            closeServing()
            log("[infantry] runner link: detached; the link listener is stopped")
        }
        // The grant is the owner's flag or console answer; null = untouched (the settings keep their values).
        fun ids(csv: String?) = csv?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
        // RUNNER-GRANT-AT-PAIR-1: `--grant-harnesses-max` is the ceiling of the one grant this code carries (empty = installed here).
        val ceiling = ids(harnessCeiling).orEmpty().filter { it != RunnerLinkSettings.ANY }
        if (grantOnPair) {
            // A grant at pairing is ONE project, chosen on the orchestrator: names it here would grant everything blindly.
            require(allowProjects == null) {
                "--grant-on-pair is the alternative to --allow-projects: the project is granted once, from the orchestrator, at pairing. Use one or the other"
            }
            require(ceiling.all { RunnerLinkSettings.isId(it) }) { "--grant-harnesses-max takes harness ids (comma separated)" }
        }
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
        // The pairing server does not reach the local runner (`runner = { null }`): it only needs what is installed here and the
        // add-to-setting write a granted code makes.
        val server = RunnerLinkServer(
            store, RunnerAudit(File(dir, RunnerAudit.FILE)), codes, settings = { values }, runner = { null }, clock = clock,
            limit = limit, installedHarnesses = ::installedHarnesses,
            grantAdd = { key, add ->
                val live = settings()
                val current = if (key == RunnerLinkSettings.PROJECTS) live.projects else live.harnesses
                grant(key, (current + add).distinct())
            },
        )
        val pending = Pairing(codes, name)
        pairing = pending
        val s = try {
            t.serve(pairingHandler(server, pending), host, linkPort(), tls, callTimeoutMs())
        } catch (e: Exception) {
            // RUNNER-LINK-PORT-1: a pairing that never listened is no pairing; [tick] must not keep it (it blocks serving).
            pairing = null
            throw IllegalArgumentException("cannot serve the runner link on port ${linkPort()} (${e.message}); is the daemon already serving it, or set limits.runners.linkPort")
        }
        val code = codes.issue(name, clock(), grant = grantOnPair, harnessCeiling = ceiling)
        pending.serving = s
        // RUNNER-LINK-1: the one link; `code`, `fingerprint` and `port` stay for older CLIs.
        val candidates = RunnerPairLink.candidates(listOfNotNull(hosts), values.advertiseHosts, limit(LimitRegistry.RUNNERS_ADVERTISE_HOSTS_MAX).toInt())
        val link = candidates.takeIf { it.isNotEmpty() }?.let { RunnerPairLink(name, it, advertised ?: s.port, code, fingerprint, plain, grantOnPair).toUri() }
        return ok(
            "code" to code, "link" to link, "fingerprint" to fingerprint, "port" to s.port.toString(), "plain" to plain.toString(),
            "expiryMin" to limit(LimitRegistry.RUNNERS_PAIR_CODE_MIN).toString(), "grant" to grantOnPair.toString(),
        ).let {
            JsonObject(
                it + mapOf(
                    "projects" to JsonArray(values.projects.map(::JsonPrimitive)),
                    "harnesses" to JsonArray(values.harnesses.map(::JsonPrimitive)),
                    "harnessCeiling" to JsonArray(ceiling.map(::JsonPrimitive)),
                    "installedHarnesses" to JsonArray(installedHarnesses().map(::JsonPrimitive)),
                ),
            )
        }
    }

    /**
     * `paired` (with the orchestrator), `waiting` (the code is live, or its PAIR is being answered right now) or `expired`; a
     * finished pairing's listener is closed. RUNNER-GRANT-AT-PAIR-1: an in-flight PAIR reads as `waiting`, never as `expired`,
     * because the code is spent before the grants are written and the answer is built; the console polls this every 500 ms.
     */
    private fun pairStatus(): JsonObject {
        val paired = if (RunnerLinkStore.exists(stateDir)) RunnerLinkStore.open(stateDir).orchestrator() else null
        val state = pairing
        val live = state?.codes?.liveName(clock()) != null
        val answering = state?.answering == true
        if (!answering && (paired != null || !live)) {
            closePairing()
            // RUNNER-LINK-PORT-1: paired: the paired listener takes the port over now, not on the next tick.
            if (paired != null && !closed) settle()
        }
        return when {
            paired != null -> ok("state" to "paired", "orchestratorName" to paired.name, "orchestratorId" to paired.id)
            live || answering -> ok("state" to "waiting")
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
        pairing?.let { p -> p.serving?.let { runCatching { it.close() } } }
        pairing = null
    }

    /**
     * RUNNER-GRANT-AT-PAIR-1: [handler] with the pairing's "answering" flag around a PAIR, so `pair_status` / [tick] never close
     * the listener under an answer that is still being built (a grant is written to the runner's settings before the answer).
     */
    private fun pairingHandler(server: RunnerLinkServer, p: Pairing): LinkHandler {
        val inner = handler(server)
        return object : LinkHandler {
            override fun handle(type: String, data: String, orchestratorId: String?, credential: String?, from: String?): LinkAnswer {
                if (type != F.PAIR) {
                    // RUNNER-CHECK-STALE-1: the pairing listener keeps the port until the next tick; once paired, a frame (a check
                    // right after `runner add`) goes to the paired server (live settings, the local runner), never to this one's
                    // `runner = { null }` and its settings snapshot.
                    val store = RunnerLinkStore.open(stateDir)
                    if (store.orchestrator() != null) return handler(pairedServer(store)).handle(type, data, orchestratorId, credential, from)
                    return inner.handle(type, data, orchestratorId, credential, from)
                }
                p.answering = true
                try {
                    return inner.handle(type, data, orchestratorId, credential, from)
                } finally {
                    p.answering = false
                }
            }

            override fun malformed(): LinkAnswer = inner.malformed()
        }
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
        /** RUNNER-UP-1: the default [mode] when the daemon does not pass one (a bare `both` daemon pairs fine). */
        const val BOTH_MODE = "both"
        /** RUNNER-POOL-6a: the owner frame `infantry runner pair|add|rotate|detach <name>` send ([FrameGate.OWNER_ONLY]). */
        const val ADMIN = com.superiorsoftllc.infantry.policy.ActionCatalog.RUNNER_ADMIN
        /**
         * RUNNER-CARD-STALE-1: the push frame the daemon sends every client when the runner list may read differently: a heartbeat
         * changed a runner's state (`up` / `degraded` / `down`), or an [ADMIN] op added, removed, paused or resumed one. The data is
         * `{}` (no runner names: a guest connection receives it too); a client re-lists with an [ADMIN] `list` of its own.
         */
        const val STATE_FRAME = "runners_changed"
        /** RUNNER-CARD-STALE-1: the [ADMIN] ops whose success changes what `list` answers, so a [STATE_FRAME] follows them. */
        val LIST_CHANGING_OPS = setOf("add", "detach", "pause", "resume")
        const val NO_TRANSPORT = "the runner link needs the Infantry_RemoteRunner plugin (no runner link transport is loaded)"
        private val NEEDS_TRANSPORT = setOf("pair", "add", "rotate", "detach", "test")
        private const val MS_PER_SECOND = 1000L
        private const val MS_PER_MINUTE = 60_000L
        private const val SHOWN_OP_SIZE = 32

        /**
         * RUNNER-TEST-1: the one-line turn a test sends. A harness that answers anything proves it ran; the word makes the expected
         * answer obvious in the reply the owner reads.
         */
        const val TEST_PROMPT = "Reply with the single word: ready"

        /** RUNNER-TEST-1: the harness a test picks first when the runner lists it: it spends nothing and answers at once. */
        private const val TEST_HARNESS = "echo"

        /** RUNNER-TEST-1: why a test session was stopped, so the runner's session history says who ended it if the removal is late. */
        private const val TEST_STOP_REASON = "runner test finished"

        /** RUNNER-TEST-1: what the answer says when the test session was stopped but not removed on the runner. */
        const val LEFT_BEHIND = "the test session was stopped but not removed on the runner (it may still show there until it ends)"
        private const val TURN_LINE = "[turn]"
        private const val ERROR_LINE = "error:"
        private const val FAILED_TURN_LINE = "[infantry] turn failed"
        /** RUNNER-CLAUDE-PROOF-1: the session's own `[infantry] * session started (...)` note is bookkeeping, not an answer. */
        private const val SESSION_NOTE_LINE = "[infantry] *"
        private const val SYSTEM_EVENT_LINE = "{\"type\":\"system\""

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
