package com.superiorsoftllc.agenthq.daemon.wiring

import com.superiorsoftllc.agenthq.daemon.api.board.PromptStart
import com.superiorsoftllc.agenthq.daemon.api.triggers.TriggerHost
import com.superiorsoftllc.agenthq.daemon.api.triggers.TriggerMailRequest
import com.superiorsoftllc.agenthq.daemon.api.triggers.TriggerMailResult
import com.superiorsoftllc.agenthq.daemon.api.triggers.TriggerRunEvent
import com.superiorsoftllc.agenthq.daemon.api.triggers.TriggerSession
import com.superiorsoftllc.agenthq.daemon.api.triggers.TriggerStore
import com.superiorsoftllc.agenthq.mailbox.MailResult
import com.superiorsoftllc.agenthq.mailbox.MailSendRequest
import com.superiorsoftllc.agenthq.session.SessionEvent
import com.superiorsoftllc.agenthq.session.SessionRecord
import com.superiorsoftllc.agenthq.session.SessionState
import com.superiorsoftllc.agenthq.session.isInSession
import java.util.concurrent.CopyOnWriteArrayList

/**
 * TRIG-1: the in-core [TriggerHost]. Supplies the triggers package with the store, the sessions, the mail layer and the
 * unattended prompt launch, so that package imports only the protocol and the daemon API. Core session events come in
 * through [dispatch]; the spawn refusals (depth, pod cap, guards) stay the launch function's, unchanged.
 */
class LocalTriggerApi(
    override val store: TriggerStore,
    private val getSession: (String) -> SessionRecord?,
    private val listSessions: () -> List<SessionRecord>,
    private val send: (MailSendRequest) -> MailResult,
    private val launch: (agentId: String, prompt: String, projectId: String?) -> PromptStart,
    private val pending: (String) -> Boolean,
) : TriggerHost {
    private val listeners = CopyOnWriteArrayList<(TriggerRunEvent) -> Unit>()

    override fun session(sessionId: String): TriggerSession? = getSession(sessionId)?.let(::view)
    override fun sessions(): List<TriggerSession> = listSessions().map(::view)

    override fun sendMail(request: TriggerMailRequest): TriggerMailResult =
        when (val r = send(MailSendRequest(from = request.from, to = request.to, subject = request.subject, body = request.body, threadId = request.threadId))) {
            is MailResult.Sent -> TriggerMailResult.Sent(r.message.id)
            is MailResult.Refused -> TriggerMailResult.Refused(r.reason)
        }

    override fun startPrompt(agentId: String, prompt: String, projectId: String?): PromptStart = launch(agentId, prompt, projectId)
    override fun mailPending(mailId: String): Boolean = pending(mailId)

    override fun onRunEvent(listener: (TriggerRunEvent) -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    /** Feed every core session event; the ones that end a trigger's run reach the listeners. */
    fun dispatch(event: SessionEvent) {
        val mapped = when (event) {
            is SessionEvent.Exit -> TriggerRunEvent.Exit(event.sessionId, event.killOutcome != null)
            is SessionEvent.Status ->
                if (event.session.state == SessionState.FAILED && event.session.runCount == 0) TriggerRunEvent.StartFailed(event.session.id) else return
            else -> return
        }
        for (l in listeners) l(mapped)
    }

    private fun view(r: SessionRecord) = TriggerSession(
        id = r.id, agentId = r.agentId, role = r.role, projectId = r.projectId, spawnedAt = r.spawnedAt,
        live = r.live, reachable = r.isInSession(), state = r.state.name, runCount = r.runCount, lastExitCode = r.lastExitCode,
    )
}
