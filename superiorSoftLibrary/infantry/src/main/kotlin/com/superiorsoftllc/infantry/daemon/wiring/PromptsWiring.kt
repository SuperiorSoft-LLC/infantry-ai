package com.superiorsoftllc.infantry.daemon.wiring

import com.superiorsoftllc.infantry.prompts.PendingPrompts
import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.session.SessionManager
import com.superiorsoftllc.infantry.settings.SettingsService
import kotlinx.coroutines.channels.Channel

/** Pending prompts and approval rules wiring (CORE-SPLIT-4a2). Moved verbatim from DaemonMain, same construction order. */
object PromptsWiring {
    fun build(rules: com.superiorsoftllc.infantry.daemon.api.ApprovalRulesHook, settings: SettingsService, manager: SessionManager, clients: List<Channel<Envelope>>): PendingPrompts {
        // PROMPT-1: `prompts.timeoutSec` is read at every open; every request / resolution goes to every client.
        val pendingPrompts = PendingPrompts(
            timeoutMillis = { settings.promptTimeoutSec() * 1000L },
            // ASK-TIMER (1.139.0): global `prompts.timeoutSec` / `prompts.onTimeout`, the session's spawn override over it.
            policyFor = { id -> manager.promptTimeoutFor(id, settings.promptTimeoutPolicy()) },
            // DP-10b: "Always allow" rules are the approval-rules port (Infantry_Approvals fills it), scoped by project.
            rules = rules,
            projectOf = { id -> manager.get(id)?.projectId },
        )
        pendingPrompts.sink = { envelope -> for (c in clients.toList()) c.trySend(envelope) }
        // PROMPT-6: a rule-answered call is one `[infantry]` stderr line in that session's transcript (UI-BOOT-1's
        // convention), through the session stream so it reaches every client like any other session line.
        pendingPrompts.notice = { sessionId, text ->
            manager.eventSink?.invoke(com.superiorsoftllc.infantry.session.SessionEvent.Stderr(sessionId, "[infantry] $text"))
        }
        // PROMPT-3: a session adapter (Claude in `ask`) relays its CLI's approval / question requests here.
        // PROMPT-4: harness adapters (opencode serve) relay their tool approvals here.
        manager.promptAsker = pendingPrompts
        return pendingPrompts
    }
}
