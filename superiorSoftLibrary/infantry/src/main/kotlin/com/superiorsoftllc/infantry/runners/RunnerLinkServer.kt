// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.runners

import com.superiorsoftllc.infantry.daemon.FrameGate
import com.superiorsoftllc.infantry.daemon.api.orchestrator.MailItem
import com.superiorsoftllc.infantry.daemon.api.orchestrator.OrchestratorReply
import com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerLink
import com.superiorsoftllc.infantry.daemon.api.orchestrator.StartSpec
import com.superiorsoftllc.infantry.settings.RunnerLinkSettings
import kotlinx.serialization.KSerializer
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
            F.STOP -> stop(seat, data, from)
            F.HEALTH -> read(seat, type, from) { link ->
                when (val h = link.health()) {
                    is OrchestratorReply.Ok -> F.json.encodeToString(F.HealthAnswer.serializer(), h.value.let { F.HealthAnswer(it.state, it.running, it.freeSlots, it.load) })
                    is OrchestratorReply.Unavailable -> null
                }
            }
            F.DELIVER -> {
                val d = decode(F.DeliverRequest.serializer(), data) ?: return refuse(seat, type, null, from, "$type refused: malformed request")
                if (starts?.isRemote(d.sessionId) != true) return refuse(seat, type, null, from, "$type refused: session ${d.sessionId.take(SHOWN_TYPE_SIZE)} was not started by this orchestrator")
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
                if (starts?.isRemote(t.sessionId) != true) return refuse(seat, type, null, from, "$type refused: session ${t.sessionId.take(SHOWN_TYPE_SIZE)} was not started by this orchestrator")
                read(seat, type, from) { link ->
                    when (val lines = link.readTranscript(t.sessionId, t.fromLine, t.maxLines)) {
                        is OrchestratorReply.Ok -> F.json.encodeToString(F.TranscriptAnswer.serializer(), F.TranscriptAnswer(lines.value))
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

    private fun pair(data: String, from: String?): Answer {
        val request = decode(F.PairRequest.serializer(), data) ?: return refuse(ANONYMOUS, F.PAIR, null, from, "${F.PAIR} refused: malformed request")
        val now = clock()
        val runnerName = codes?.liveName(now)
        val codeRefusal = codes?.redeem(request.code, now) ?: if (codes == null) NO_CODE else null
        if (codeRefusal != null) return refuse(ANONYMOUS, F.PAIR, null, from, "${F.PAIR} refused: $codeRefusal")
        if (request.orchestratorId.isBlank()) return refuse(ANONYMOUS, F.PAIR, null, from, "${F.PAIR} refused: no orchestrator id")
        store.orchestrator()?.takeIf { it.id != request.orchestratorId }?.let {
            return refuse(
                ANONYMOUS, F.PAIR, null, from,
                "${F.PAIR} refused: this runner is paired with another orchestrator (${it.name}); run `infantry runner detach` on the runner first",
            )
        }
        val seat = FrameGate.orchestratorSeat(request.orchestratorId)
        val credential = store.issue(request.orchestratorId, request.orchestratorName, runnerName.orEmpty(), now)
        return ok(seat, F.PAIR, null, from, F.PairAnswer.serializer(), F.PairAnswer(store.selfId(), runnerName.orEmpty(), credential))
    }

    private fun start(seat: String, data: String, from: String?): Answer {
        val r = decode(F.StartRequest.serializer(), data) ?: return refuse(seat, F.START, null, from, "${F.START} refused: malformed request")
        val allowed = settings()
        val why = when {
            !allowed.allowsProject(r.projectId) ->
                "project '${r.projectId.orEmpty()}' is not in this runner's ${RunnerLinkSettings.PROJECTS} (empty = none). Grant it on the runner: infantry settings set ${RunnerLinkSettings.PROJECTS} '[\"${r.projectId.orEmpty()}\"]' (list every allowed id; * = every project)"
            !allowed.allowsHarness(r.provider) ->
                "harness '${r.provider.orEmpty()}' is not in this runner's ${RunnerLinkSettings.HARNESSES} (empty = none). Grant it on the runner: infantry settings set ${RunnerLinkSettings.HARNESSES} '[\"${r.provider.orEmpty()}\"]' (list every allowed id; * = every harness)"
            else -> policy(seat, F.START, r.ticketId)
        }
        if (why != null) return refuse(seat, F.START, r.ticketId, from, "${F.START} refused: $why")
        val link = runner() ?: return refuse(seat, F.START, r.ticketId, from, "${F.START} refused: this runner has no local runner")
        // RUNNER-POOL-6: a start with a git remote runs in a ticket worktree this runner cuts in its own clone of that remote.
        var ticketlessRepo: java.io.File? = null
        val worktree = r.gitRemote?.takeIf { it.isNotBlank() }?.let { remote ->
            val s = starts ?: return refuse(seat, F.START, r.ticketId, from, "${F.START} refused: this runner takes no start with a git remote")
            val ticket = r.ticketId?.takeIf { it.isNotBlank() }
            if (ticket == null) {
                // RUNNER-AGENT-1: a spawn start (no ticket) runs in the project clone, no ticket branch.
                try { ticketlessRepo = s.cloneDir(remote, r.projectId) } catch (e: IllegalArgumentException) {
                    return refuse(seat, F.START, null, from, "${F.START} refused: ${e.message}")
                }
                null
            } else try {
                s.checkout(remote, r.projectId, ticket, r.title ?: ticket, r.base, r.branch)
            } catch (e: IllegalArgumentException) {
                return refuse(seat, F.START, ticket, from, "${F.START} refused: ${e.message}")
            }
        }
        val spec = StartSpec(
            startId = r.startId, agentId = r.agentId, role = r.role, projectId = r.projectId, provider = r.provider,
            model = r.model, effort = r.effort, ticketId = r.ticketId, contextLimit = r.contextLimit,
            // Never a path from the wire: the runner cuts the working copy from its own clone (or its own project directory).
            repo = ticketlessRepo?.path,
            prompt = r.prompt, title = r.title, envNames = r.envNames, worktree = worktree,
        )
        return when (val reply = link.startSession(spec)) {
            is OrchestratorReply.Ok -> {
                val result = reply.value.refusal?.let { "refused: $it" } ?: "ok ${reply.value.sessionId.orEmpty()}".trim()
                audit.record(seat, F.START, result, r.ticketId, from)
                val sessionId = reply.value.sessionId
                if (sessionId != null && worktree != null) starts?.started(sessionId, worktree, r.pushOnCommit)
                if (sessionId != null) starts?.linked(sessionId)
                Answer(
                    F.REPLY,
                    F.json.encodeToString(F.StartAnswer.serializer(), F.StartAnswer(sessionId, reply.value.refusal, worktree?.branch, worktree?.base)),
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

    private fun <T> decode(serializer: KSerializer<T>, data: String): T? = runCatching { F.json.decodeFromString(serializer, data) }.getOrNull()

    private fun shownType(type: String) = type.filter { it in ' '..'~' }.take(SHOWN_TYPE_SIZE)

    companion object {
        const val ANONYMOUS = "anonymous"
        private const val NO_CODE = "no pairing code is live on this runner (run `infantry runner pair` there)"
        private const val SHOWN_TYPE_SIZE = 64
    }
}
