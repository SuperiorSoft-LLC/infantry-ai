// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.runners

import com.superiorsoftllc.infantry.daemon.FrameGate
import com.superiorsoftllc.infantry.daemon.api.orchestrator.MailItem
import com.superiorsoftllc.infantry.daemon.api.orchestrator.OrchestratorReply
import com.superiorsoftllc.infantry.daemon.api.orchestrator.PromptReply
import com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerHarness
import com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerLink
import com.superiorsoftllc.infantry.daemon.api.orchestrator.StartSpec
import com.superiorsoftllc.infantry.settings.LimitDef
import com.superiorsoftllc.infantry.settings.LimitRegistry
import com.superiorsoftllc.infantry.settings.RunnerLinkSettings
import com.superiorsoftllc.infantry.worktree.GitCli
import com.superiorsoftllc.infantry.worktree.SystemGitCli
import kotlinx.serialization.KSerializer
import java.io.File
import kotlinx.serialization.json.JsonArray
import com.superiorsoftllc.infantry.daemon.api.runners.RunnerLinkWire as F

/**
 * RUNNER-POOL-5 (docs/RUNNER-POOL.md section 7): the RUNNER side of the link, with no transport: one request in, one answer out.
 * The transport's served link ([com.superiorsoftllc.infantry.daemon.api.runners.RunnerLinkTransport.serve]) feeds it; tests call
 * it directly.
 *
 * * Without a credential only [F.PAIR] is accepted, and only with the live one-time code ([codes]); a runner already paired with
 *   another orchestrator refuses (`infantry runner detach` on the runner first). It accepts link frames from exactly ONE
 *   orchestrator id.
 * * With the paired orchestrator's id and live credential, the caller seat is `orchestrator:<id>` and every frame first passes
 *   [FrameGate.refusal] for that seat: only the `runner_link_*` family; owner frames (settings, plugins, keystore, updater,
 *   remote) are refused.
 * * Remote [F.START] / [F.STOP] then pass the runner-local allow-lists `runner.projects` and `runner.harnesses` (EMPTY = NONE) and
 *   [policy] (the runner's own say) before reaching [runner], the runner's local link.
 * * Every operation, refused ones included, is appended to [audit].
 * * RUNNER-POOL-6: [F.HELLO] reports what this runner can run (harnesses and whether each is signed in, `runner.projects`,
 *   capacity) from [runner]'s hello; [F.HEALTH], [F.DELIVER], [F.EVENTS] and [F.TRANSCRIPT] reach [runner] too. A [F.START] with a
 *   git remote is checked out by [starts] (clone or fetch, then the ticket worktree) before the session starts in it.
 * * RUNNER-POOL-7: [F.FORWARD_PULL] hands the orchestrator the agent frames [forwarder] holds (waiting a little for the first one),
 *   [F.FORWARD_ANSWER] completes one of them. Each frame taken is audited as `forward:<type>` with its session.
 * * RUNNER-CHECK-1: [F.CHECK] answers the orchestrator's health check rows (contract, harness sign-in, grants, git, disk) with the
 *   fix for each failing one, all computed here ([check]). Read only, nothing started or written.
 * * RUNNER-GRANT-AT-PAIR-1: [F.PAIR] grants what the orchestrator asks ([F.PairRequest.grantProjects] / `.grantHarnesses`) ONLY when
 *   the redeemed code was issued with `--grant-on-pair` ([PairCodes.Authority]); the ids are ADDED to `runner.projects` /
 *   `runner.harnesses` (never replacing them), harnesses must be installed here AND inside the console's ceiling, a `*` is refused,
 *   and the grant is audited as [PAIR_GRANT]. A used or expired code grants nothing.
 */
class RunnerLinkServer(
    private val store: RunnerLinkStore,
    private val audit: RunnerAudit,
    private val codes: PairCodes?,
    private val settings: () -> RunnerLinkSettings.Values,
    private val runner: () -> RunnerLink?,
    /** The runner's own policy for a remote start/stop: (seat, frame type, ticket id) -> refusal or null. */
    private val policy: (String, String, String?) -> String? = { _, _, _ -> null },
    private val clock: () -> Long = System::currentTimeMillis,
    /** RUNNER-POOL-6: the clone, cut and push of starts that carry a git remote; null = such a start is refused. */
    private val starts: RemoteStarts? = null,
    /** RUNNER-POOL-7: the agent frames this runner's workers forward to the orchestrator; null = a pull answers none. */
    private val forwarder: FrameForwarder? = null,
    /** RUNNER-POOL-7: the longest a pull may wait here (below the orchestrator's link call timeout). */
    private val forwardWaitCapMs: () -> Long = { 0L },
    /** RUNNER-CHECK-1: `limits.*` by key, for the check's git timeout and minimum free disk. */
    private val limit: (LimitDef) -> Long = { it.default },
    /** RUNNER-CHECK-1: the git seam the check's `ls-remote` runs through, built with that call's timeout in ms. */
    private val git: (timeoutMs: Long) -> GitCli = { ms -> SystemGitCli(ms) },
    /** RUNNER-CRED-1: the same seam with the extra env a lent git credential adds ([LentCredentials.gitEnv]), for the check's `git` row. */
    private val authedGit: (timeoutMs: Long, env: Map<String, String>) -> GitCli = { ms, env -> SystemGitCli(ms, extraEnv = env) },
    /** RUNNER-CHECK-1: folder the check's disk row looks at and its `ls-remote` runs in (`runners.cloneRoot`). */
    private val cloneRoot: () -> File = { File(System.getProperty("user.home")) },
    /** RUNNER-GRANT-AT-PAIR-1: the harness ids installed on this runner (the ceiling's default and the intersection's bound). */
    private val installedHarnesses: () -> List<String> = { emptyList() },
    /** RUNNER-GRANT-AT-PAIR-1: adds ids to `runner.projects` / `runner.harnesses` (a grant at pairing ADDS, never replaces). */
    private val grantAdd: (key: String, ids: List<String>) -> Unit = { _, _ -> },
    /**
     * RUNNER-GIT-IDENT-1: the identity this runner's user already has in its own git config (global), for the `git:identity` row;
     * null = none (a fresh container image).
     */
    private val ownGitIdentity: () -> Pair<String, String>? = { userGitIdentity(File(System.getProperty("user.home"))) },
    /**
     * RUNNER-PAIR-1: the runner's long-lived pairing bearer ([RunnerBearer]); a PAIR whose code is its secret pairs like a live
     * one-time code (no grant authority) and may pair again: the same orchestrator gets a fresh credential. RUNNER-REPAIR-1:
     * holding the live bearer IS the owner's authority, so a different orchestrator takes the runner over (the old link's
     * credential is replaced, audited as [PAIR_TAKEOVER] and logged); only a one-time code is refused while another is paired.
     */
    private val bearer: RunnerBearer? = null,
    /**
     * RUNNER-CRED-1: where the secrets an orchestrator lends a start are kept (memory only) for renders in that session's project;
     * null = a lent secret is dropped. The harness key goes into the start spec ([StartSpec.credentials], the session's launches),
     * the git credential to [starts]. `runner.acceptCredentials` false ignores every loan.
     */
    private val lent: LentCredentials? = null,
    /**
     * RUNNER-CRED-1: the runner's owner chose its own login for this harness here (`harness.<id>.authMode` =
     * `subscription-login` on the runner), so a lent key must not replace it. A positive choice only: a harness's "signed in"
     * in [RunnerInfo] also means "no sign-in problem known", which every key-only harness (no probe) reports.
     */
    private val ownLogin: (harnessId: String) -> Boolean = { false },
    /** RUNNER-REPAIR-1: the runner's log (its container log): a bearer takeover is said there. */
    private val log: (String) -> Unit = {},
) {
    /** One answer: [type] is [F.REPLY] or [F.ERROR]; [data] is the answer JSON or the reason. */
    data class Answer(val type: String, val data: String) {
        val ok: Boolean get() = type == F.REPLY
    }

    fun handle(type: String, data: String, orchestratorId: String?, credential: String?, from: String? = null): Answer {
        if (credential.isNullOrBlank()) {
            if (type != F.PAIR) return refuse(ANONYMOUS, type, null, from, "$type refused: this runner link needs a pair credential")
            return pair(data, from)
        }
        if (!store.accepts(orchestratorId, credential)) {
            return refuse(ANONYMOUS, type, null, from, "$type refused: not the paired orchestrator or not its live credential")
        }
        val seat = FrameGate.orchestratorSeat(orchestratorId!!)
        FrameGate.refusal(type, seat)?.let { return refuse(seat, type, null, from, it) }
        return when (type) {
            F.PAIR -> refuse(seat, type, null, from, "$type refused: already paired; rotate with ${F.REKEY}")
            F.HELLO -> store.orchestrator()!!.let { p ->
                val info = runner()?.hello()?.let { (it as? OrchestratorReply.Ok)?.value }
                ok(
                    seat, type, null, from, F.HelloAnswer.serializer(),
                    F.HelloAnswer(
                        store.selfId(), p.runnerName, issuedAt = p.issuedAt,
                        harnesses = info?.harnesses.orEmpty().map { F.HarnessWire(it.id, it.models, it.loggedIn) },
                        // What the orchestrator may place here is this runner's own allow-list, not its whole project table.
                        projects = settings().projects,
                        capacity = info?.capacity ?: 0,
                        os = info?.os.orEmpty(),
                    ),
                )
            }
            F.REKEY -> {
                val p = store.orchestrator()!!
                val now = clock()
                val fresh = store.issue(p.id, p.name, p.runnerName, now)
                ok(seat, type, null, from, F.RekeyAnswer.serializer(), F.RekeyAnswer(fresh, now))
            }
            F.DETACH -> {
                store.unpair()
                audit.record(seat, type, "ok", from = from)
                Answer(F.REPLY, "{}")
            }
            F.START -> start(seat, data, from)
            F.CHECK -> check(seat, data, from)
            F.STOP -> stop(seat, data, from)
            F.REMOVE -> remove(seat, data, from)
            F.HEALTH -> read(seat, type, from) { link ->
                when (val h = link.health()) {
                    is OrchestratorReply.Ok -> F.json.encodeToString(F.HealthAnswer.serializer(), h.value.let { F.HealthAnswer(it.state, it.running, it.freeSlots, it.load) })
                    is OrchestratorReply.Unavailable -> null
                }
            }
            F.DELIVER -> {
                val d = decode(F.DeliverRequest.serializer(), data) ?: return refuse(seat, type, null, from, "$type refused: malformed request")
                if (starts?.isLinked(d.sessionId) != true) return refuse(seat, type, null, from, "$type refused: session ${d.sessionId.take(SHOWN_TYPE_SIZE)} was not started by this orchestrator")
                read(seat, type, from) { link ->
                    when (val m = link.deliverMail(d.sessionId, MailItem(d.text, d.origin, d.wakeReason, d.messageIds))) {
                        is OrchestratorReply.Ok -> F.json.encodeToString(F.DeliverAnswer.serializer(), F.DeliverAnswer(m.value.kind, m.value.reason))
                        is OrchestratorReply.Unavailable -> null
                    }
                }
            }
            F.EVENTS -> {
                val e = decode(F.EventsRequest.serializer(), data) ?: return refuse(seat, type, null, from, "$type refused: malformed request")
                // RUNNER-POOL-8: acknowledge, then answer what is still kept (an older orchestrator sends no acks: a plain read).
                val kept = if (e.acked.isNotEmpty()) starts?.events(e.acked) else starts?.events(e.sinceSeq)
                val events = kept.orEmpty().map { F.EventWire(it.sessionId, it.seq, it.kind, it.payload) }
                Answer(F.REPLY, F.json.encodeToString(F.EventsAnswer.serializer(), F.EventsAnswer(events)))
            }
            F.TRANSCRIPT -> {
                val t = decode(F.TranscriptRequest.serializer(), data) ?: return refuse(seat, type, null, from, "$type refused: malformed request")
                if (starts?.isLinked(t.sessionId) != true) return refuse(seat, type, null, from, "$type refused: session ${t.sessionId.take(SHOWN_TYPE_SIZE)} was not started by this orchestrator")
                read(seat, type, from) { link ->
                    when (val lines = link.readTranscript(t.sessionId, t.fromLine, t.maxLines)) {
                        is OrchestratorReply.Ok -> F.json.encodeToString(F.TranscriptAnswer.serializer(), F.TranscriptAnswer(lines.value))
                        is OrchestratorReply.Unavailable -> null
                    }
                }
            }
            // RUNNER-PROMPT-RELAY-1: the owner's answer to a prompt one of this orchestrator's sessions raised here.
            F.PROMPT_ANSWER -> {
                val r = decode(F.PromptAnswerRequest.serializer(), data) ?: return refuse(seat, type, null, from, "$type refused: malformed request")
                if (starts?.isLinked(r.sessionId) != true) return refuse(seat, type, null, from, "$type refused: session ${r.sessionId.take(SHOWN_TYPE_SIZE)} was not started by this orchestrator")
                read(seat, type, from) { link ->
                    when (val a = link.answerPrompt(r.sessionId, PromptReply(r.promptId, r.decision, r.answers, r.otherText))) {
                        is OrchestratorReply.Ok -> F.json.encodeToString(F.PromptAnswerAck.serializer(), F.PromptAnswerAck(a.value))
                        is OrchestratorReply.Unavailable -> null
                    }
                }
            }
            F.FORWARD_PULL -> {
                val r = decode(F.ForwardPullRequest.serializer(), data) ?: return refuse(seat, type, null, from, "$type refused: malformed request")
                val frames = forwarder?.pull(r.waitMs.coerceIn(0, forwardWaitCapMs().coerceAtLeast(0))).orEmpty()
                for (f in frames) audit.record(seat, "forward:${shownType(f.type)}", "taken session ${f.sessionId.take(SHOWN_TYPE_SIZE)}", null, from)
                Answer(F.REPLY, F.json.encodeToString(F.ForwardPullAnswer.serializer(), F.ForwardPullAnswer(frames)))
            }
            F.FORWARD_ANSWER -> {
                val r = decode(F.ForwardAnswerRequest.serializer(), data) ?: return refuse(seat, type, null, from, "$type refused: malformed request")
                Answer(F.REPLY, F.json.encodeToString(F.ForwardAnswerAck.serializer(), F.ForwardAnswerAck(forwarder?.answer(r) == true)))
            }
            else -> refuse(seat, type, null, from, "$type refused: not a runner link operation this runner offers (contract ${F.CONTRACT})")
        }
    }

    /**
     * RUNNER-CHECK-1 (docs/wiki/RunnerPairing-design.md 4, step 4): the read-only health check the orchestrator runs right after
     * pairing, so the manual server steps (agent CLI sign-in, grants, git access, disk) are loud and early instead of failing the
     * first ticket start. Every row is computed HERE, on the runner, and carries the fix for a failing one:
     *
     * * `contract`: this runner's [F.CONTRACT] against the orchestrator's ([F.CheckRequest.contract]); a mismatch says to update one.
     * * `harness:<id>`: installed, and signed in (the same yes/no [F.HELLO] reports). For the requested harness, or every installed
     *   one.
     * * `grant:project` / `grant:harness`: the id is in `runner.projects` / `runner.harnesses` (`*` counts); the fix is the exact
     *   `infantry settings set` line that ADDS the id to the current list.
     * * `git`: `git ls-remote --heads <remote>` through the git seam with `limits.runners.checkGitSec` and no terminal prompt (the
     *   seam sets GIT_TERMINAL_PROMPT=0), so a blocked port or a missing key fails instead of hanging; any `user:token@` in what it
     *   says is stripped. No clone, no write. Only when the orchestrator sent a remote.
     * * `disk`: free space of the clone root against `limits.runners.checkMinFreeMb`.
     */
    private fun check(seat: String, data: String, from: String?): Answer {
        val r = decode(F.CheckRequest.serializer(), data) ?: return refuse(seat, F.CHECK, null, from, "${F.CHECK} refused: malformed request")
        val allowed = settings()
        val rows = mutableListOf<F.CheckRow>()
        rows += contractRow(r.contract)
        val wanted = r.harness?.takeIf { it.isNotBlank() }
        val installed = (runner()?.hello() as? OrchestratorReply.Ok)?.value?.harnesses.orEmpty()
        val lentKey = { id: String -> id in r.lentKeys }
        if (wanted != null) rows += harnessRow(wanted, installed.firstOrNull { it.id == wanted }, lentKey(wanted), allowed.acceptCredentials)
        else installed.forEach { rows += harnessRow(it.id, it, lentKey(it.id), allowed.acceptCredentials) }
        r.projectId?.takeIf { it.isNotBlank() }?.let { rows += grantRow("grant:project", RunnerLinkSettings.PROJECTS, it, allowed.projects) }
        if (wanted != null) rows += grantRow("grant:harness", RunnerLinkSettings.HARNESSES, wanted, allowed.harnesses)
        r.gitRemote?.takeIf { it.isNotBlank() }?.let { rows += gitRow(it, r.git?.takeIf { allowed.acceptCredentials }) }
        rows += identityRow(allowed, identity(r.gitName, r.gitEmail))
        rows += diskRow()
        return ok(seat, F.CHECK, null, from, F.CheckAnswer.serializer(), F.CheckAnswer(rows))
    }

    private fun contractRow(peer: String?): F.CheckRow = when {
        peer == null || peer == F.CONTRACT -> F.CheckRow("contract", true, "this runner speaks contract ${F.CONTRACT}")
        else -> F.CheckRow(
            "contract", false, "this runner speaks contract ${F.CONTRACT}, the orchestrator $peer",
            "update the runner to the same release as the orchestrator (restart it with the current image or install), then check again",
        )
    }

    /** RUNNER-CRED-1: this runner's owner chose [harness]'s own login here, so a lent key does not replace it. */
    private fun keepsOwnLogin(harness: String?): Boolean {
        val id = harness?.takeIf { it.isNotBlank() } ?: return false
        return runCatching { ownLogin(id) }.getOrDefault(false)
    }

    private fun harnessRow(id: String, harness: RunnerHarness?, lentKey: Boolean = false, accepts: Boolean = true): F.CheckRow = when {
        harness == null -> F.CheckRow(
            "harness:$id", false, "harness '$id' is not installed on this runner",
            "install the $id CLI on this runner (use the runner image, which carries it)",
        )
        // RUNNER-CRED-1: the orchestrator lends its API key with each start, so no login is needed here.
        lentKey && accepts && keepsOwnLogin(id) ->
            F.CheckRow("harness:$id", true, "harness '$id' is installed; this runner keeps its own login (${com.superiorsoftllc.infantry.harness.credential.HarnessCredential.MODE_SUBSCRIPTION}), not the key the app lends")
        lentKey && accepts && harness.loggedIn ->
            F.CheckRow("harness:$id", true, "harness '$id' is installed; the app lends its API key with each start")
        !harness.loggedIn && lentKey && accepts ->
            F.CheckRow("harness:$id", true, "harness '$id' is installed; not signed in here, the app lends its API key with each start")
        !harness.loggedIn && lentKey -> F.CheckRow(
            "harness:$id", false, "harness '$id' is installed but not signed in, and this runner ignores the key the app lends (${RunnerLinkSettings.ACCEPT_CREDENTIALS} is false)",
            "on this runner: infantry settings set ${RunnerLinkSettings.ACCEPT_CREDENTIALS} true (or sign $id in there)",
        )
        !harness.loggedIn -> F.CheckRow(
            "harness:$id", false,
            "harness '$id' is installed but not signed in; a session would start and the model could not answer " +
                "(hint: in a container, prepend `docker exec -it <container>` to the login)",
            "log $id in on this runner: run `$id` there once and sign in",
        )
        else -> F.CheckRow("harness:$id", true, "harness '$id' is installed and signed in")
    }

    /** `*` counts as a grant of every id; otherwise the fix ADDS [id] to the runner's current list. */
    private fun grantRow(row: String, key: String, id: String, current: List<String>): F.CheckRow {
        if (RunnerLinkSettings.ANY in current || id in current) {
            return F.CheckRow(row, true, "$key allows '$id'")
        }
        val next = (current + id).distinct()
        val value = JsonArray(next.map { kotlinx.serialization.json.JsonPrimitive(it) }).toString()
        val shown = if (current.isEmpty()) "empty = none" else current.joinToString(", ")
        return F.CheckRow(
            row, false, "$id is not in this runner's $key ($shown)",
            "on this runner: infantry settings set $key '$value'",
        )
    }

    private fun gitRow(remote: String, lent: com.superiorsoftllc.infantry.daemon.api.orchestrator.GitCredential? = null): F.CheckRow {
        val timeoutMs = limit(LimitRegistry.RUNNERS_CHECK_GIT_SEC) * MS_PER_SECOND
        val fix = "give the runner read+push access to ${scrubCredentials(remote)} (a deploy key or a token), then run this check again"
        val dir = cloneRoot().let { it.takeIf { d -> d.isDirectory } ?: it.absoluteFile.parentFile } ?: File(".")
        // RUNNER-CRED-1: with a lent credential, the check proves the access a worker will have (header via env, memory only).
        val seam = if (lent == null) git(timeoutMs) else authedGit(timeoutMs, LentCredentials.gitEnv(lent))
        val via = if (lent == null) "" else " with the git credential the app lends"
        val result = runCatching { seam.run(dir, listOf("ls-remote", "--heads", remote)) }.getOrElse {
            return F.CheckRow("git", false, "git could not run on this runner: ${scrubCredentials(it.message.orEmpty())}", fix)
        }
        if (result.ok) {
            val heads = result.out().lines().count { it.isNotBlank() }
            return F.CheckRow("git", true, "${scrubCredentials(remote)} is reachable$via ($heads branch heads read)")
        }
        val reason = scrubCredentials(result.err().lines().firstOrNull { it.isNotBlank() }.orEmpty())
        return F.CheckRow("git", false, "git ls-remote$via exited ${result.exitCode}${if (reason.isEmpty()) "" else ": $reason"}", fix)
    }

    /**
     * RUNNER-GIT-IDENT-1: who a worker here commits as: this runner's `runner.gitName` / `runner.gitEmail`, else the orchestrator's
     * identity ([sent]), else the runner user's own git config. None of them = a failing row, since the worker's first commit would
     * fail with "Author identity unknown" (and an agent would guess a name).
     */
    private fun identityRow(allowed: RunnerLinkSettings.Values, sent: Pair<String, String>?): F.CheckRow {
        allowed.gitIdentity?.let { (n, e) -> return F.CheckRow(IDENTITY_ROW, true, "workers commit as $n <$e> (${RunnerLinkSettings.GIT_NAME} / ${RunnerLinkSettings.GIT_EMAIL})") }
        sent?.let { (n, e) -> return F.CheckRow(IDENTITY_ROW, true, "workers commit as $n <$e> (the orchestrator's git identity, sent with each start)") }
        runCatching(ownGitIdentity).getOrNull()?.let { (n, e) -> return F.CheckRow(IDENTITY_ROW, true, "workers commit as $n <$e> (this runner user's git config)") }
        return F.CheckRow(
            IDENTITY_ROW, false,
            "no git identity: the orchestrator sent none, ${RunnerLinkSettings.GIT_NAME} / ${RunnerLinkSettings.GIT_EMAIL} are unset and this runner's user has no git config, so a worker's commit would fail",
            "on this runner: infantry settings set ${RunnerLinkSettings.GIT_NAME} '\"<name>\"' and infantry settings set ${RunnerLinkSettings.GIT_EMAIL} '\"<email>\"' " +
                "(or set user.name / user.email in the orchestrator's project checkout)",
        )
    }

    private fun diskRow(): F.CheckRow {
        val dir = cloneRoot().let { root ->
            generateSequence(root.absoluteFile) { it.parentFile }.firstOrNull { it.isDirectory } ?: root.absoluteFile
        }
        val minMb = limit(LimitRegistry.RUNNERS_CHECK_MIN_FREE_MB)
        val freeMb = runCatching { dir.usableSpace / MB }.getOrNull()
            ?: return F.CheckRow("disk", false, "could not read the free space of ${dir.path}", "check that the runner can read ${dir.path}")
        val detail = "$freeMb MB free in ${dir.path} (${LimitRegistry.RUNNERS_CHECK_MIN_FREE_MB.key} = $minMb)"
        return if (freeMb >= minMb) F.CheckRow("disk", true, detail)
        else F.CheckRow("disk", false, detail, "free disk space on this runner: it needs room for clones and worktrees")
    }

    private fun pair(data: String, from: String?): Answer {
        val request = decode(F.PairRequest.serializer(), data) ?: return refuse(ANONYMOUS, F.PAIR, null, from, "${F.PAIR} refused: malformed request")
        // RUNNER-GRANT-AT-PAIR-1: a grant request is checked BEFORE the code is redeemed: a refused request must not burn the code.
        val wantedProjects = request.grantProjects.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val wantedHarnesses = request.grantHarnesses.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (RunnerLinkSettings.ANY in wantedProjects || RunnerLinkSettings.ANY in wantedHarnesses) {
            return refuse(
                ANONYMOUS, F.PAIR, null, from,
                "${F.PAIR} refused: a grant at pairing may not name '*' (name the ids; `infantry runner pair --allow-projects *` on the runner grants every one)",
            )
        }
        val maxProjects = limit(LimitRegistry.RUNNERS_GRANT_PROJECTS_MAX).toInt()
        if (wantedProjects.size > maxProjects) {
            return refuse(
                ANONYMOUS, F.PAIR, null, from,
                "${F.PAIR} refused: at most $maxProjects project(s) may be granted at pairing (${LimitRegistry.RUNNERS_GRANT_PROJECTS_MAX.key}); this request names ${wantedProjects.size}",
            )
        }
        val bad = (wantedProjects + wantedHarnesses).firstOrNull { !RunnerLinkSettings.isId(it) }
        if (bad != null) return refuse(ANONYMOUS, F.PAIR, null, from, "${F.PAIR} refused: '${bad.filter { it in ' '..'~' }.take(SHOWN_TYPE_SIZE)}' is not an id")
        // Everything a grant needs is read BEFORE the code is redeemed: what is installed here is a slow local call, and the console
        // polls `pair_status` (a spent code reads as expired) while an answer is being built.
        val current = settings()
        val installed = installedHarnesses()
        val now = clock()
        // RUNNER-PAIR-1: the long-lived bearer pairs without spending a code (and carries no grant authority).
        val byBearer = bearer?.read()?.takeIf { bearer.accepts(request.code) }
        val runnerName = byBearer?.name ?: codes?.liveName(now)
        val redeemed = when {
            byBearer != null -> PairCodes.Redeem.Ok(PairCodes.Authority())
            codes == null -> PairCodes.Redeem.Refused(if (bearer?.exists() == true) WRONG_BEARER else NO_CODE)
            else -> codes.redeem(request.code, now).let { r ->
                if (r is PairCodes.Redeem.Refused && bearer?.exists() == true && r.reason == NO_CODE) PairCodes.Redeem.Refused(WRONG_BEARER) else r
            }
        }
        if (redeemed is PairCodes.Redeem.Refused) return refuse(ANONYMOUS, F.PAIR, null, from, "${F.PAIR} refused: ${redeemed.reason}")
        val authority = (redeemed as PairCodes.Redeem.Ok).authority
        if (request.orchestratorId.isBlank()) return refuse(ANONYMOUS, F.PAIR, null, from, "${F.PAIR} refused: no orchestrator id")
        val previous = store.orchestrator()?.takeIf { it.id != request.orchestratorId }
        if (previous != null && byBearer == null) {
            return refuse(
                ANONYMOUS, F.PAIR, null, from,
                "${F.PAIR} refused: this runner is paired with another orchestrator (${previous.name}) and a one-time code cannot move it. " +
                    "Paste the runner's bearer instead (`infantry runner bearer` on the runner prints it): the bearer moves the runner to this app",
            )
        }
        val seat = FrameGate.orchestratorSeat(request.orchestratorId)
        // RUNNER-REPAIR-1: the live bearer moves the runner; the store holds one orchestrator, so issuing below replaces the old one.
        if (previous != null) {
            audit.record(seat, PAIR_TAKEOVER, "replaced orchestrator ${previous.name} (${previous.id})", null, from)
            log("[infantry] runner link: paired with orchestrator ${request.orchestratorName.ifBlank { request.orchestratorId }} by this runner's bearer; it replaces ${previous.name}, whose link stops working")
        }
        // Only the runner's own record of the code lets a pairing widen its grants; the request can ask, never decide.
        val grantedProjects = if (!authority.grant) emptyList() else wantedProjects.filter { it !in current.projects }
        val ceiling = authority.harnessCeiling.takeIf { it.isNotEmpty() }
        val grantedHarnesses = if (!authority.grant) emptyList() else wantedHarnesses.filter { h ->
            h in installed && h !in current.harnesses && (ceiling == null || h in ceiling)
        }
        if (grantedProjects.isNotEmpty()) grantAdd(RunnerLinkSettings.PROJECTS, grantedProjects)
        if (grantedHarnesses.isNotEmpty()) grantAdd(RunnerLinkSettings.HARNESSES, grantedHarnesses)
        if (authority.grant && (wantedProjects.isNotEmpty() || wantedHarnesses.isNotEmpty())) {
            audit.record(
                seat, PAIR_GRANT,
                "granted projects=[${grantedProjects.joinToString(",")}] harnesses=[${grantedHarnesses.joinToString(",")}]", null, from,
            )
        }
        val grantRefusal = when {
            authority.grant -> null
            wantedProjects.isEmpty() && wantedHarnesses.isEmpty() -> null
            else -> GRANT_NOT_ALLOWED
        }
        val credential = store.issue(request.orchestratorId, request.orchestratorName, runnerName.orEmpty(), now)
        val answer = F.PairAnswer(
            store.selfId(), runnerName.orEmpty(), credential,
            grantedProjects = grantedProjects, grantedHarnesses = grantedHarnesses, grantRefusal = grantRefusal,
            replacedOrchestrator = previous?.name,
        )
        return ok(seat, F.PAIR, null, from, F.PairAnswer.serializer(), answer)
    }

    private fun start(seat: String, data: String, from: String?): Answer {
        val r = decode(F.StartRequest.serializer(), data) ?: return refuse(seat, F.START, null, from, "${F.START} refused: malformed request")
        val allowed = settings()
        // RUNNER-TEST-1: a start that names no project and takes no remote and no ticket is a health test turn (`infantry runner test`):
        // it touches no project checkout, so the project grant has nothing to cover. A start naming a project, or taking a remote,
        // keeps the grant gate below unchanged.
        val projectless = r.projectId.isNullOrBlank() && r.gitRemote.isNullOrBlank() && r.ticketId.isNullOrBlank()
        val why = when {
            !projectless && !allowed.allowsProject(r.projectId) ->
                "project '${r.projectId.orEmpty()}' is not in this runner's ${RunnerLinkSettings.PROJECTS} (empty = none). Grant it on the runner: infantry settings set ${RunnerLinkSettings.PROJECTS} '[\"${r.projectId.orEmpty()}\"]' (list every allowed id; * = every project)"
            !allowed.allowsHarness(r.provider) ->
                "harness '${r.provider.orEmpty()}' is not in this runner's ${RunnerLinkSettings.HARNESSES} (empty = none). Grant it on the runner: infantry settings set ${RunnerLinkSettings.HARNESSES} '[\"${r.provider.orEmpty()}\"]' (list every allowed id; * = every harness)"
            else -> policy(seat, F.START, r.ticketId)
        }
        if (why != null) return refuse(seat, F.START, r.ticketId, from, "${F.START} refused: $why")
        val link = runner() ?: return refuse(seat, F.START, r.ticketId, from, "${F.START} refused: this runner has no local runner")
        // RUNNER-CRED-1: what the orchestrator lent, screened (env names a harness may use, user secret names, one-line git token).
        val screened = r.credentials?.takeIf { allowed.acceptCredentials && !it.isEmpty }
            ?.let { LentCredentials.screen(it, limit(LimitRegistry.RUNNERS_LEND_SECRETS_MAX).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) }
            ?.let { sc -> if (sc.credentials.env.isNotEmpty() && keepsOwnLogin(r.provider)) sc.ownLogin() else sc }
        val gitCredential = screened?.credentials?.git
        // RUNNER-POOL-6: a start with a git remote runs in a ticket worktree this runner cuts in its own clone of that remote.
        var ticketlessRepo: java.io.File? = null
        val worktree = r.gitRemote?.takeIf { it.isNotBlank() }?.let { remote ->
            val s = starts ?: return refuse(seat, F.START, r.ticketId, from, "${F.START} refused: this runner takes no start with a git remote")
            val ticket = r.ticketId?.takeIf { it.isNotBlank() }
            if (ticket == null) {
                // RUNNER-AGENT-1: a spawn start (no ticket) runs in the project clone, no ticket branch.
                try { ticketlessRepo = s.cloneDir(remote, r.projectId, gitCredential) } catch (e: IllegalArgumentException) {
                    return refuse(seat, F.START, null, from, "${F.START} refused: ${e.message}")
                }
                null
            } else try {
                s.checkout(remote, r.projectId, ticket, r.title ?: ticket, r.base, r.branch, credential = gitCredential)
            } catch (e: IllegalArgumentException) {
                return refuse(seat, F.START, ticket, from, "${F.START} refused: ${e.message}")
            }
        }
        // RUNNER-GIT-IDENT-1: the clone the worker commits in gets a defined identity: this runner's own setting, else the orchestrator's.
        (worktree?.repoRoot?.let(::File) ?: ticketlessRepo)?.let { repo -> starts?.applyIdentity(repo, allowed.gitIdentity ?: identity(r.gitName, r.gitEmail)) }
        val spec = StartSpec(
            startId = r.startId, agentId = r.agentId, role = r.role, projectId = r.projectId, provider = r.provider,
            model = r.model, effort = r.effort, ticketId = r.ticketId, contextLimit = r.contextLimit,
            // Never a path from the wire: the runner cuts the working copy from its own clone (or its own project directory).
            repo = ticketlessRepo?.path,
            prompt = r.prompt, title = r.title, envNames = r.envNames, worktree = worktree, toolApproval = r.toolApproval,
            promptRelay = r.promptRelay,
            // RUNNER-CRED-1: only the harness key travels in the spec (the session's launches); secrets and git stay here.
            credentials = screened?.credentials?.env?.takeIf { it.isNotEmpty() }?.let { com.superiorsoftllc.infantry.daemon.api.orchestrator.SessionCredentials(env = it) },
        )
        return when (val reply = link.startSession(spec)) {
            is OrchestratorReply.Ok -> {
                // RUNNER-CRED-1: the audit names what was lent (names only, never a value).
                val lentNote = screened?.summary()?.takeIf { it.isNotEmpty() }?.let { " (lent: $it)" }.orEmpty()
                val result = (reply.value.refusal?.let { "refused: $it" } ?: "ok ${reply.value.sessionId.orEmpty()}".trim()) + lentNote
                audit.record(seat, F.START, result, r.ticketId, from)
                val sessionId = reply.value.sessionId
                if (sessionId != null && screened != null) lent?.lend(sessionId, r.projectId, screened.credentials)
                if (sessionId != null && worktree != null) starts?.started(sessionId, worktree, r.pushOnCommit, gitCredential)
                if (sessionId != null) starts?.linked(sessionId)
                Answer(
                    F.REPLY,
                    F.json.encodeToString(F.StartAnswer.serializer(), F.StartAnswer(sessionId, reply.value.refusal, worktree?.branch, worktree?.base, worktree?.path)),
                )
            }
            is OrchestratorReply.Unavailable -> refuse(seat, F.START, r.ticketId, from, "${F.START} unavailable: ${reply.reason}")
        }
    }

    private fun stop(seat: String, data: String, from: String?): Answer {
        val r = decode(F.StopRequest.serializer(), data) ?: return refuse(seat, F.STOP, null, from, "${F.STOP} refused: malformed request")
        policy(seat, F.STOP, r.ticketId)?.let { return refuse(seat, F.STOP, r.ticketId, from, "${F.STOP} refused: $it") }
        val link = runner() ?: return refuse(seat, F.STOP, r.ticketId, from, "${F.STOP} refused: this runner has no local runner")
        return when (val reply = link.stopSession(r.sessionId, r.reason, r.force)) {
            is OrchestratorReply.Ok -> ok(seat, F.STOP, r.ticketId, from, F.StopAnswer.serializer(), F.StopAnswer(reply.value))
            is OrchestratorReply.Unavailable -> refuse(seat, F.STOP, r.ticketId, from, "${F.STOP} unavailable: ${reply.reason}")
        }
    }

    /**
     * RUNNER-TEST-1 (contract 1.5.0): forget a session this orchestrator started, so a throwaway turn (`infantry runner test`) leaves
     * nothing on this runner's session list. Only a session of this orchestrator may be removed; a live one is ended first, which is
     * what the owner's `session_remove --force` does. One call, so the caller cannot lose the session between a stop and a remove
     * (a stopped session is no longer "started by this orchestrator", so a second gate would refuse it).
     */
    private fun remove(seat: String, data: String, from: String?): Answer {
        val r = decode(F.RemoveRequest.serializer(), data) ?: return refuse(seat, F.REMOVE, null, from, "${F.REMOVE} refused: malformed request")
        if (starts?.isLinked(r.sessionId) != true) return refuse(seat, F.REMOVE, null, from, "${F.REMOVE} refused: session ${r.sessionId.take(SHOWN_TYPE_SIZE)} was not started by this orchestrator")
        policy(seat, F.REMOVE, null)?.let { return refuse(seat, F.REMOVE, null, from, "${F.REMOVE} refused: $it") }
        val link = runner() ?: return refuse(seat, F.REMOVE, null, from, "${F.REMOVE} refused: this runner has no local runner")
        return when (val reply = link.removeSession(r.sessionId)) {
            is OrchestratorReply.Ok -> {
                if (reply.value) { starts?.forget(r.sessionId); lent?.release(r.sessionId) }
                ok(seat, F.REMOVE, null, from, F.RemoveAnswer.serializer(), F.RemoveAnswer(reply.value))
            }
            is OrchestratorReply.Unavailable -> refuse(seat, F.REMOVE, null, from, "${F.REMOVE} unavailable: ${reply.reason}")
        }
    }

    /** A read-like frame answered by [answer] over the local runner; null = the runner had no answer. */
    private fun read(seat: String, type: String, from: String?, answer: (RunnerLink) -> String?): Answer {
        val link = runner() ?: return refuse(seat, type, null, from, "$type refused: this runner has no local runner")
        val data = answer(link) ?: return refuse(seat, type, null, from, "$type unavailable on this runner")
        // Health is polled every heartbeat: only the frames that act or read a session are audited.
        if (type != F.HEALTH) audit.record(seat, type, "ok", null, from)
        return Answer(F.REPLY, data)
    }

    private fun <T> ok(seat: String, type: String, ticket: String?, from: String?, serializer: KSerializer<T>, value: T): Answer {
        audit.record(seat, type, "ok", ticket, from)
        return Answer(F.REPLY, F.json.encodeToString(serializer, value))
    }

    private fun refuse(seat: String, type: String, ticket: String?, from: String?, why: String): Answer {
        audit.record(seat, shownType(type), "refused: $why", ticket, from)
        return Answer(F.ERROR, why)
    }

    /** A wire identity: both halves present, one line each (anything else is dropped, never half-written). */
    private fun identity(name: String?, email: String?): Pair<String, String>? {
        val n = name?.trim()?.takeIf { it.isNotEmpty() && it.none(Char::isISOControl) } ?: return null
        val e = email?.trim()?.takeIf { it.isNotEmpty() && it.none(Char::isISOControl) } ?: return null
        return n to e
    }

    private fun <T> decode(serializer: KSerializer<T>, data: String): T? = runCatching { F.json.decodeFromString(serializer, data) }.getOrNull()

    private fun shownType(type: String) = type.filter { it in ' '..'~' }.take(SHOWN_TYPE_SIZE)

    companion object {
        const val ANONYMOUS = "anonymous"

        /** RUNNER-GIT-IDENT-1: the check row that says who a worker on this runner commits as. */
        const val IDENTITY_ROW = "git:identity"

        /** RUNNER-GIT-IDENT-1: `user.name` / `user.email` from the git config seen in [dir] (global for a non-repo), or null. */
        fun userGitIdentity(dir: File, git: GitCli = SystemGitCli()): Pair<String, String>? {
            fun read(key: String) = runCatching { git.run(dir, listOf("config", "--get", key)) }.getOrNull()?.takeIf { it.ok }?.out()?.takeIf { it.isNotBlank() }
            val name = read("user.name") ?: return null
            val email = read("user.email") ?: return null
            return name to email
        }

        /** RUNNER-GRANT-AT-PAIR-1: the audit operation of a pairing that granted ids (`infantry runner audit` prints it). */
        const val PAIR_GRANT = "runner_link_pair_grant"

        /** RUNNER-REPAIR-1: the audit operation of a bearer pairing that replaced another orchestrator's link. */
        const val PAIR_TAKEOVER = "runner_link_pair_takeover"

        /** RUNNER-GRANT-AT-PAIR-1: what the orchestrator reads when its code carries no grant authority. */
        const val GRANT_NOT_ALLOWED =
            "grant not allowed by this code; set it on the runner (issue the code there with `infantry runner pair --grant-on-pair`, " +
                "or grant by hand: infantry settings set ${RunnerLinkSettings.PROJECTS} '[\"<project id>\"]' and " +
                "infantry settings set ${RunnerLinkSettings.HARNESSES} '[\"<harness id>\"]')"

        private const val NO_CODE = PairCodes.NO_LIVE_CODE
        /** RUNNER-PAIR-1: a PAIR whose code is not this runner's bearer (rotated, or mistyped). */
        const val WRONG_BEARER = "wrong bearer: it is not this runner's live bearer (it was rotated, or copied wrong); copy the 'bearer:' line the runner prints again (`infantry runner bearer` there shows it)"
        private const val SHOWN_TYPE_SIZE = 64
        private const val MS_PER_SECOND = 1_000L
        private const val MB = 1L shl 20

        /**
         * RUNNER-CHECK-1: [text] with any credential a URL carries taken out (`https://user:token@host/x` -> `https://host/x`). Git
         * echoes the remote it tried in its errors, and that text crosses the link and is printed by the CLI.
         */
        fun scrubCredentials(text: String): String = CREDENTIALS.replace(text, "$1")

        private val CREDENTIALS = Regex("""([a-zA-Z][a-zA-Z0-9+.-]*://)[^/@\s]*@""")
    }
}
