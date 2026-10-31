package com.superiorsoftllc.agenthq.daemon.wiring

import com.superiorsoftllc.agenthq.persistence.AgentHqStore
import com.superiorsoftllc.agenthq.prompts.ApprovalRules
import com.superiorsoftllc.agenthq.prompts.PendingPrompts
import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.session.SessionManager
import com.superiorsoftllc.agenthq.settings.SettingsService
import kotlinx.coroutines.channels.Channel

/** Pending prompts and approval rules wiring (CORE-SPLIT-4a2). Moved verbatim from DaemonMain, same construction order. */
object PromptsWiring {
    fun build(store: AgentHqStore, settings: SettingsService, manager: SessionManager, clients: List<Channel<Envelope>>): PendingPrompts {
        // PROMPT-1: `prompts.timeoutSec` is read at every open; every request / resolution goes to every client.
        val pendingPrompts = PendingPrompts(
            timeoutMillis = { settings.promptTimeoutSec() * 1000L },
            // ASK-TIMER (1.139.0): global `prompts.timeoutSec` / `prompts.onTimeout`, the session's spawn override over it.
            policyFor = { id -> manager.promptTimeoutFor(id, settings.promptTimeoutPolicy()) },
            // PROMPT-6: "Always allow" rules live in the settings table (`toolApproval.rules`), scoped by project.
            rules = ApprovalRules.forStore(store),
            projectOf = { id -> manager.get(id)?.projectId },
        )
        pendingPrompts.sink = { envelope -> for (c in clients.toList()) c.trySend(envelope) }
        // PROMPT-6: a rule-answered call is one `[agenthq]` stderr line in that session's transcript (UI-BOOT-1's
        // convention), through the session stream so it reaches every client like any other session line.
        pendingPrompts.notice = { sessionId, text ->
            manager.eventSink?.invoke(com.superiorsoftllc.agenthq.session.SessionEvent.Stderr(sessionId, "[agenthq] $text"))
        }
        // PROMPT-3: a session adapter (Claude in `ask`) relays its CLI's approval / question requests here.
        // PROMPT-4: harness adapters (opencode serve) relay their tool approvals here.
        manager.promptAsker = pendingPrompts
        return pendingPrompts
    }
}
