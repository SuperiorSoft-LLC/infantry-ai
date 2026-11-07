// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.remoterunner.daemon

import com.superiorsoftllc.infantry.daemon.api.orchestrator.MailItem
import com.superiorsoftllc.infantry.daemon.api.orchestrator.MailResult
import com.superiorsoftllc.infantry.daemon.api.orchestrator.OrchestratorReply
import com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerEvent
import com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerHarness
import com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerHealth
import com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerInfo
import com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerLink
import com.superiorsoftllc.infantry.daemon.api.orchestrator.SessionRef
import com.superiorsoftllc.infantry.daemon.api.orchestrator.StartSpec
import com.superiorsoftllc.infantry.daemon.api.runners.LinkFailure
import com.superiorsoftllc.infantry.daemon.api.runners.RunnerLinkWire as F
import com.superiorsoftllc.infantry.daemon.api.runners.RunnerLinks
import kotlinx.serialization.KSerializer

/**
 * RUNNER-POOL-6 (docs/RUNNER-POOL.md 2.1, 4): the remote runner binding. Each [RunnerLink] command becomes one `runner_link_*`
 * frame sent to runner [name] through core's [RunnerLinks] (core holds the url, the pinned fingerprint and the credential). No
 * answer ([LinkFailure]) is [OrchestratorReply.Unavailable]; a runner's `runner_link_error` is the refusal it names (for a start)
 * or Unavailable (for every other command). A start never carries a runner path or an env value: the runner clones [StartSpec.gitRemote]
 * and fills [StartSpec.envNames] itself.
 */
class RemoteRunnerLink(private val name: String, private val links: RunnerLinks) : RunnerLink {
    override val contractVersion: String get() = F.CONTRACT

    /** The orchestrator's session index answers which ticket a session works on; the runner is not asked. */
    override fun ticketIdOf(sessionId: String): String? = null

    override fun hello(): OrchestratorReply<RunnerInfo> = call(F.HELLO, "{}", F.HelloAnswer.serializer()).map { a ->
        RunnerInfo(
            name = a.runnerName.ifBlank { name },
            contract = a.contract,
            harnesses = a.harnesses.map { RunnerHarness(it.id, it.models, it.loggedIn) },
            projects = a.projects,
            capacity = a.capacity,
            os = a.os,
        )
    }

    override fun startSession(spec: StartSpec): OrchestratorReply<SessionRef> {
        val request = F.StartRequest(
            startId = spec.startId,
            projectId = spec.projectId,
            provider = spec.provider,
            model = spec.model,
            role = spec.role,
            agentId = spec.agentId,
            effort = spec.effort,
            ticketId = spec.ticketId,
            contextLimit = spec.contextLimit,
            prompt = spec.prompt,
            title = spec.title,
            gitRemote = spec.gitRemote,
            base = spec.base,
            branch = spec.branch,
            envNames = spec.envNames,
            pushOnCommit = spec.pushOnCommit,
        )
        val answer = try {
            links.call(name, F.START, F.json.encodeToString(F.StartRequest.serializer(), request))
        } catch (e: LinkFailure) {
            return OrchestratorReply.Unavailable("runner '$name': ${e.message}")
        }
        if (answer.type == F.ERROR) return OrchestratorReply.Ok(SessionRef(refusal = answer.data.ifBlank { "refused by runner '$name'" }))
        return decode(answer.data, F.StartAnswer.serializer()).map { SessionRef(it.sessionId, it.refusal, it.branch, it.base) }
    }

    override fun stopSession(sessionId: String, reason: String?, force: Boolean): OrchestratorReply<Boolean> =
        call(F.STOP, F.json.encodeToString(F.StopRequest.serializer(), F.StopRequest(sessionId, reason, force)), F.StopAnswer.serializer()).map { it.stopped }

    override fun deliverMail(sessionId: String, mail: MailItem): OrchestratorReply<MailResult> =
        call(
            F.DELIVER,
            F.json.encodeToString(F.DeliverRequest.serializer(), F.DeliverRequest(sessionId, mail.text, mail.origin, mail.wakeReason, mail.messageIds)),
            F.DeliverAnswer.serializer(),
        ).map { MailResult(it.kind, it.reason) }

    override fun health(): OrchestratorReply<RunnerHealth> =
        call(F.HEALTH, "{}", F.HealthAnswer.serializer()).map { RunnerHealth(it.state, it.running, it.freeSlots, it.load) }

    override fun events(sinceSeq: Long): OrchestratorReply<List<RunnerEvent>> =
        call(F.EVENTS, F.json.encodeToString(F.EventsRequest.serializer(), F.EventsRequest(sinceSeq)), F.EventsAnswer.serializer())
            .map { a -> a.events.map { RunnerEvent(it.sessionId, it.seq, it.kind, it.payload) } }

    /** RUNNER-POOL-8: acknowledge [acked] and read what the runner's outbox still keeps. */
    override fun events(acked: Map<String, Long>): OrchestratorReply<List<RunnerEvent>> =
        call(F.EVENTS, F.json.encodeToString(F.EventsRequest.serializer(), F.EventsRequest(acked = acked)), F.EventsAnswer.serializer())
            .map { a -> a.events.map { RunnerEvent(it.sessionId, it.seq, it.kind, it.payload) } }

    override fun readTranscript(sessionId: String, fromLine: Int, maxLines: Int): OrchestratorReply<List<String>> =
        call(F.TRANSCRIPT, F.json.encodeToString(F.TranscriptRequest.serializer(), F.TranscriptRequest(sessionId, fromLine, maxLines)), F.TranscriptAnswer.serializer())
            .map { it.lines }

    private fun <T> call(type: String, data: String, serializer: KSerializer<T>): OrchestratorReply<T> {
        val answer = try {
            links.call(name, type, data)
        } catch (e: LinkFailure) {
            return OrchestratorReply.Unavailable("runner '$name': ${e.message}")
        }
        if (answer.type == F.ERROR) return OrchestratorReply.Unavailable("runner '$name': ${answer.data}")
        return decode(answer.data, serializer)
    }

    private fun <T> decode(data: String, serializer: KSerializer<T>): OrchestratorReply<T> =
        try {
            OrchestratorReply.Ok(F.json.decodeFromString(serializer, data))
        } catch (e: Exception) {
            OrchestratorReply.Unavailable("runner '$name' answered an unreadable frame: ${e.message}")
        }

    private inline fun <T, R> OrchestratorReply<T>.map(f: (T) -> R): OrchestratorReply<R> = when (this) {
        is OrchestratorReply.Ok -> OrchestratorReply.Ok(f(value))
        is OrchestratorReply.Unavailable -> this
    }
}
