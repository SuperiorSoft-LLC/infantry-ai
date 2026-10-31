package com.superiorsoftllc.agenthq.daemon.wiring

import com.superiorsoftllc.agenthq.daemon.api.board.PromptStart
import com.superiorsoftllc.agenthq.daemon.api.board.StartResult
import com.superiorsoftllc.agenthq.persistence.TriggerRepository
import com.superiorsoftllc.agenthq.session.SessionRecord
import com.superiorsoftllc.agenthq.triggers.PromptDelivery
import com.superiorsoftllc.agenthq.triggers.TriggerNudge
import com.superiorsoftllc.agenthq.triggers.TriggerService

/** Triggers wiring (CORE-SPLIT-4a1). */
object TriggersWiring {
    /** The trigger service over the store's trigger repo. Not auto-started: the daemon starts it after the mailbox and the sessions are loaded. */
    fun build(
        repo: TriggerRepository,
        attention: com.superiorsoftllc.agenthq.daemon.api.attention.Attention,
        startNext: (String?) -> StartResult?,
        startPrompt: (String, String) -> PromptStart,
        sessions: (String) -> SessionRecord?,
        deliverPrompt: (TriggerNudge) -> PromptDelivery,
        mailPending: (String) -> Boolean,
    ): TriggerService = TriggerService(
        repo = repo,
        attention = attention,
        startNext = startNext,
        startPrompt = startPrompt,
        sessions = sessions,
        deliverPrompt = deliverPrompt,
        mailPending = mailPending,
        autoStart = false, // started by the daemon, after the mailbox and the sessions are loaded
    )
}
