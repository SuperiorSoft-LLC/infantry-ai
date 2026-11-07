// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.agents.AgentProfiles
import com.superiorsoftllc.infantry.daemon.api.orchestrator.LocalRunnerServices
import com.superiorsoftllc.infantry.daemon.api.orchestrator.MailItem
import com.superiorsoftllc.infantry.daemon.api.orchestrator.MailResult
import com.superiorsoftllc.infantry.daemon.api.orchestrator.OrchestratorReply
import com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerEvent
import com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerHealth
import com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerInfo
import com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerLink
import com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerPool
import com.superiorsoftllc.infantry.daemon.api.orchestrator.StartSpec
import com.superiorsoftllc.infantry.protocol.MessageOrigin
import com.superiorsoftllc.infantry.protocol.SpawnOptions
import com.superiorsoftllc.infantry.session.RunInFlightException
import com.superiorsoftllc.infantry.session.SessionManager
import com.superiorsoftllc.infantry.session.SessionRecord
import com.superiorsoftllc.infantry.session.isBusy
import com.superiorsoftllc.infantry.usage.LaunchOrigin
import com.superiorsoftllc.infantry.worktree.WorktreeRecord
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * RUNNER-POOL-2 (docs/RUNNER-POOL.md 2.1): the runner-side functions the local [com.superiorsoftllc.infantry.daemon.api.orchestrator.LocalRunnerLink]
 * answers from. Each wraps what the daemon called before, unchanged: a start is [bindSessionOnSpawn], a stop is
 * [SessionManager.kill], a mail turn is [SessionManager.resume].
 *
 * RUNNER-POOL-6: a spec that carries a worktree the runner cut (a start its orchestrator sent with a git remote) starts the
 * session in it with the composed first turn, as a ticket start does ([SessionManager.startNew] with `preWorktree`), unattended.
 */
object LocalRunner {
    private val originJson = Json { ignoreUnknownKeys = true }

    fun services(
        manager: SessionManager,
        agents: AgentProfiles,
        info: () -> RunnerInfo,
        capacity: () -> Int,
        events: (sinceSeq: Long) -> List<RunnerEvent> = { emptyList() },
        transcript: (sessionId: String, fromLine: Int, maxLines: Int) -> List<String> = { _, _, _ -> emptyList() },
    ) = LocalRunnerServices(
        ticketIdOf = { sessionId -> manager.get(sessionId)?.ticketId },
        info = info,
        start = { spec ->
            val wt = spec.worktree
            if (wt == null) {
                bindSessionOnSpawn(manager, agents, spawnOptions(spec), spec.repo?.let(::File))?.id
            } else {
                manager.startNew(
                    prompt = spec.prompt.orEmpty(),
                    providerId = spec.provider, model = spec.model, effort = spec.effort,
                    agentId = spec.agentId, role = spec.role, ticketId = spec.ticketId, projectId = spec.projectId,
                    contextLimit = spec.contextLimit,
                    unattended = true, launchOrigin = LaunchOrigin.UNATTENDED,
                    preWorktree = WorktreeRecord(sessionId = "", repoRoot = wt.repoRoot, path = wt.path, branch = wt.branch, baseRef = wt.base, createdAt = System.currentTimeMillis()),
                ).id
            }
        },
        stop = { sessionId, reason, force -> if (manager.get(sessionId) == null) false else { manager.kill(sessionId, force = force, reason = reason); true } },
        deliver = { sessionId, mail ->
            try {
                manager.resume(sessionId, mail.text, decodeOrigin(mail.origin), mail.wakeReason, mail.messageIds.joinToString(","))
                MailResult.Delivered
            } catch (e: RunInFlightException) {
                MailResult(MailResult.QUEUED)
            } catch (e: Exception) {
                MailResult(MailResult.FAILED, e.message ?: e::class.simpleName.orEmpty())
            }
        },
        health = {
            val running = manager.list().count { it.isBusy() }
            val cap = capacity()
            RunnerHealth(RunnerHealth.UP, running, if (cap <= 0) -1 else maxOf(0, cap - running))
        },
        events = events,
        transcript = transcript,
    )

    /** A pool with only the local runner over [manager] (a connection handler's default when the daemon passes none). */
    fun pool(manager: SessionManager, agents: AgentProfiles): RunnerPool = com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerHub(
        com.superiorsoftllc.infantry.daemon.api.orchestrator.LocalRunnerLink(
            services(manager, agents, info = { RunnerInfo(RunnerPool.LOCAL, RunnerLink.CONTRACT_VERSION) }, capacity = { 0 }),
        ),
    )

    /** A spawn's options as a start spec: the fields a local spawn resolves (agent, role, project, executor, context limit). */
    fun startSpec(options: SpawnOptions?, repo: File?, startId: String = UUID.randomUUID().toString(), parentSessionId: String? = null) = StartSpec(
        startId = startId,
        agentId = options?.agentId, role = options?.role, projectId = options?.projectId,
        provider = options?.provider, model = options?.model, effort = options?.effort, contextLimit = options?.contextLimit,
        parentSessionId = parentSessionId, repo = repo?.path,
    )

    /** The inverse of [startSpec]: exactly the options [bindSessionOnSpawn] reads. */
    fun spawnOptions(spec: StartSpec) = SpawnOptions(
        provider = spec.provider, model = spec.model, agentId = spec.agentId, projectId = spec.projectId,
        effort = spec.effort, role = spec.role, contextLimit = spec.contextLimit,
    )

    fun encodeOrigin(origin: MessageOrigin): String = originJson.encodeToString(MessageOrigin.serializer(), origin)
    fun decodeOrigin(origin: String): MessageOrigin = originJson.decodeFromString(MessageOrigin.serializer(), origin)

    /**
     * Start a session for [options] (working copy from [repo]) on the pool's runner and return its record, null when the options name no agent and no role.
     * A refused spec throws [IllegalArgumentException] with the runner's reason (the callers' existing refusal path); an unreachable
     * runner throws [IllegalStateException]. Placement (RUNNER-POOL-3) picks the runner later; today it is always `local`.
     */
    fun start(pool: RunnerPool, manager: SessionManager, options: SpawnOptions?, repo: File?): SessionRecord? {
        val link = pool.link(RunnerPool.LOCAL) ?: throw IllegalStateException("no '${RunnerPool.LOCAL}' runner")
        return when (val r = link.startSession(startSpec(options, repo))) {
            is OrchestratorReply.Ok -> {
                r.value.refusal?.let { throw IllegalArgumentException(it) }
                r.value.sessionId?.let(manager::get)
            }
            is OrchestratorReply.Unavailable -> throw IllegalStateException("runner unavailable: ${r.reason}")
        }
    }

    /** MailRouter's resume edge through the session's runner; an unreachable runner keeps the mail queued (it is persisted). */
    fun deliverVia(pool: RunnerPool): (String, String, MessageOrigin, String, List<String>) -> MailResult = { sessionId, turn, origin, wakeReason, ids ->
        val link: RunnerLink? = pool.link(pool.runnerOf(sessionId))
        when (val r = link?.deliverMail(sessionId, MailItem(turn, encodeOrigin(origin), wakeReason, ids))) {
            null -> MailResult(MailResult.QUEUED, "runner '${pool.runnerOf(sessionId)}' is unknown")
            is OrchestratorReply.Ok -> r.value
            is OrchestratorReply.Unavailable -> MailResult(MailResult.QUEUED, "runner unavailable: ${r.reason}")
        }
    }
}
