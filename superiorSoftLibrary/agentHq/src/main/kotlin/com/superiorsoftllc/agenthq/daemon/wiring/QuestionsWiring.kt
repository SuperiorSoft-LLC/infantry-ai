package com.superiorsoftllc.agenthq.daemon.wiring

import com.superiorsoftllc.agenthq.mailbox.MailResult
import com.superiorsoftllc.agenthq.mailbox.MailSendRequest
import com.superiorsoftllc.agenthq.persistence.AgentHqStore
import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.protocol.OwnerQuestion
import com.superiorsoftllc.agenthq.questions.OwnerQuestionRepository
import com.superiorsoftllc.agenthq.questions.OwnerQuestions
import com.superiorsoftllc.agenthq.session.SessionManager
import com.superiorsoftllc.agenthq.settings.LimitRegistry
import com.superiorsoftllc.agenthq.settings.SettingsService
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.Json

/** Owner-questions wiring (CORE-SPLIT-4a1). ASK-1 (contract 1.97.0): non-blocking owner questions, stored, pushed to every client, answered by mail. */
object QuestionsWiring {
    fun build(
        store: AgentHqStore,
        send: (MailSendRequest) -> MailResult,
        clock: () -> Long,
        settings: SettingsService,
        manager: SessionManager,
        clients: List<Channel<Envelope>>,
        json: Json,
    ): OwnerQuestions {
        val ownerQuestions = OwnerQuestions(
            repository = OwnerQuestionRepository.forStore(store),
            send = send,
            clock = clock,
            maxOpen = settings::questionsMaxOpenPerAgent,
            maxPerHour = settings::questionsMaxPerHour,
            sessionExists = { manager.get(it) != null },
            sessionBusy = { manager.get(it)?.live == true },
            ttlMinutes = { settings.limit(LimitRegistry.QUESTION_TTL_MINUTES) },
            // ASK-TIMER: a question with no `--default-after` auto-picks its recommendation when the prompt timer runs out.
            defaultAfterMinFor = { id ->
                manager.promptTimeoutFor(id, settings.promptTimeoutPolicy()).timeoutMillis.takeIf { it > 0 }
                    ?.let { ((it + 59_999) / 60_000).toInt() }
            },
        )
        ownerQuestions.sink = { question ->
            val frame = Envelope(Envelope.QUESTION, json.encodeToString(OwnerQuestion.serializer(), question))
            for (c in clients.toList()) c.trySend(frame)
        }
        return ownerQuestions
    }
}
