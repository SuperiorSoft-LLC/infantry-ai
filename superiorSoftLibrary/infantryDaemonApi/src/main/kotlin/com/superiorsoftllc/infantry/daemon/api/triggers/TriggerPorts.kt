// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.api.triggers

import com.superiorsoftllc.infantry.daemon.api.board.PromptStart
import com.superiorsoftllc.infantry.protocol.TriggerEventView
import com.superiorsoftllc.infantry.protocol.TriggerView

/*
 * TRIG-1 (DaemonApi 1.28.0, additive): everything the triggers service needs from the daemon, as ports. The service
 * (stored triggers, coalescing, schedules, ship, webhook ingress, prompt nudges) compiles against these and the protocol
 * only, so it can move out of core. A daemon without triggers answers [NoTriggerHost].
 */

/** The durable trigger tables (rows, events, webhook secrets, the pending nudge mail). */
interface TriggerStore {
    fun upsert(trigger: TriggerView, intervalMs: Long?)
    fun get(id: String): TriggerView?
    fun all(): List<TriggerView>
    fun intervals(): Map<String, Long>
    fun delete(id: String): Boolean
    fun insertEvent(event: TriggerEventView, keep: Long = 500)
    fun events(triggerId: String): List<TriggerEventView>
    fun setWebhookSecret(triggerId: String, secret: String)
    fun webhookSecret(triggerId: String): String?
    fun deleteWebhookSecret(triggerId: String)
    fun pendingMail(triggerId: String): String?
    fun setPendingMail(triggerId: String, mailId: String?)
}

/** One session as a trigger sees it. [reachable]: mail can still reach it (in flight, resumable, or bound and never run). */
data class TriggerSession(
    val id: String,
    val agentId: String?,
    val role: String?,
    val projectId: String?,
    val spawnedAt: Long,
    val live: Boolean,
    val reachable: Boolean,
    /** The session state's name (`IDLE`, `FAILED`, ...). */
    val state: String,
    val runCount: Int = 0,
    val lastExitCode: Int? = null,
)

/** A run's end as the trigger sees it. */
sealed interface TriggerRunEvent {
    /** The run of [sessionId] ended; [killed] when a kill ended it. */
    data class Exit(val sessionId: String, val killed: Boolean) : TriggerRunEvent
    /** [sessionId] failed before it ever ran (its start was refused). */
    data class StartFailed(val sessionId: String) : TriggerRunEvent
}

data class TriggerMailRequest(val from: String, val to: String, val subject: String, val body: String, val threadId: String)

sealed interface TriggerMailResult {
    data class Sent(val mailId: String) : TriggerMailResult
    data class Refused(val reason: String) : TriggerMailResult
}

/**
 * The host side of triggers. [startPrompt] is the spawn-with-binding: a role/agent session started in [projectId] with
 * [prompt] as its first turn; its refusals (depth, pod cap, launch guards) are the host's, unchanged.
 */
interface TriggerHost {
    val store: TriggerStore
    fun session(sessionId: String): TriggerSession?
    fun sessions(): List<TriggerSession>
    fun sendMail(request: TriggerMailRequest): TriggerMailResult
    fun startPrompt(agentId: String, prompt: String, projectId: String?): PromptStart
    /** True while the mail [mailId] still waits for its recipient. */
    fun mailPending(mailId: String): Boolean
    /** Calls [listener] for every run end; close the handle to stop. */
    fun onRunEvent(listener: (TriggerRunEvent) -> Unit): AutoCloseable
}
