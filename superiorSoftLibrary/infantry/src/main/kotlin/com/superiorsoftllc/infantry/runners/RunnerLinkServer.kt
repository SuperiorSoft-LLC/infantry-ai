// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.runners

import com.superiorsoftllc.infantry.daemon.FrameGate
import com.superiorsoftllc.infantry.daemon.api.orchestrator.OrchestratorReply
import com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerLink
import com.superiorsoftllc.infantry.daemon.api.orchestrator.StartSpec
import com.superiorsoftllc.infantry.settings.RunnerLinkSettings
import kotlinx.serialization.KSerializer
import com.superiorsoftllc.infantry.runners.RunnerLinkFrames as F

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
                ok(seat, type, null, from, F.HelloAnswer.serializer(), F.HelloAnswer(store.selfId(), p.runnerName, issuedAt = p.issuedAt))
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
                "project '${r.projectId.orEmpty()}' is not in this runner's ${RunnerLinkSettings.PROJECTS} (empty = none)"
            !allowed.allowsHarness(r.provider) ->
                "harness '${r.provider.orEmpty()}' is not in this runner's ${RunnerLinkSettings.HARNESSES} (empty = none)"
            else -> policy(seat, F.START, r.ticketId)
        }
        if (why != null) return refuse(seat, F.START, r.ticketId, from, "${F.START} refused: $why")
        val link = runner() ?: return refuse(seat, F.START, r.ticketId, from, "${F.START} refused: this runner has no local runner")
        val spec = StartSpec(
            startId = r.startId, agentId = r.agentId, role = r.role, projectId = r.projectId, provider = r.provider,
            model = r.model, effort = r.effort, ticketId = r.ticketId,
            // Never a path from the wire: the runner cuts the working copy from its own project directory.
            repo = null,
        )
        return when (val reply = link.startSession(spec)) {
            is OrchestratorReply.Ok -> {
                val result = reply.value.refusal?.let { "refused: $it" } ?: "ok ${reply.value.sessionId.orEmpty()}".trim()
                audit.record(seat, F.START, result, r.ticketId, from)
                Answer(F.REPLY, F.json.encodeToString(F.StartAnswer.serializer(), F.StartAnswer(reply.value.sessionId, reply.value.refusal)))
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
