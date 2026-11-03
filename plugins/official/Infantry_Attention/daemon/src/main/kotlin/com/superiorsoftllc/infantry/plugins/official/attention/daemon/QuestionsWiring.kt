// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.attention.daemon

import com.superiorsoftllc.infantry.daemon.api.Caller
import com.superiorsoftllc.infantry.daemon.api.ConnectedHook
import com.superiorsoftllc.infantry.daemon.api.DaemonApi
import com.superiorsoftllc.infantry.daemon.api.Frame
import com.superiorsoftllc.infantry.daemon.api.attention.OwnerAsks
import com.superiorsoftllc.infantry.daemon.api.attention.QuestionsOpen
import com.superiorsoftllc.infantry.daemon.api.sessions.SessionView
import com.superiorsoftllc.infantry.daemon.api.sessions.SessionsResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * ATTN-2c: the owner questions, wired onto the plugin API. Storage = the adopted `owner_questions` table; the sweep runs on
 * `api.schedule`; each change is pushed through `api.events.broadcast`; the answer mail goes through `api.sessionNotifier`; the
 * questions are replayed on connect through `hooks.connected`; a closed session orphans its open questions.
 */
internal object QuestionsWiring {
    const val SWEEP_MS = 15_000L
    private val ORPHANING_CLOSE_REASONS = setOf("session killed", "session removed", "session restarted")
    private const val MAX_OPEN_KEY = "questions.maxOpenPerAgent"
    private const val MAX_PER_HOUR_KEY = "questions.maxPerHour"
    private const val TIMEOUT_SEC_KEY = "prompts.timeoutSec"
    private const val DEFAULT_TIMEOUT_SEC = 600
    private const val TTL_KEY = "limits.questions.ttlMinutes"
    private val LIMIT_RANGE = 1..100
    private val OWNER_CALLER = Caller(owner = true)

    fun install(api: DaemonApi): OwnerQuestions {
        fun session(id: String): SessionView? = (api.sessionReads.find(id, OWNER_CALLER) as? SessionsResult.Ok)?.value
        fun limit(key: String, default: Int): Int =
            api.settings.stored(key)?.trim()?.toIntOrNull()?.takeIf { it in LIMIT_RANGE } ?: default

        val questions = OwnerQuestions(
            repository = StorageOwnerQuestionRepository(api.storage),
            notify = api.sessionNotifier::notifySession,
            maxOpen = { limit(MAX_OPEN_KEY, OwnerQuestions.DEFAULT_MAX_OPEN) },
            maxPerHour = { limit(MAX_PER_HOUR_KEY, OwnerQuestions.DEFAULT_MAX_PER_HOUR) },
            sessionExists = { session(it) != null },
            sessionBusy = { session(it)?.live == true },
            ttlMinutes = { api.settings.limit(TTL_KEY) ?: 0L },
            // ASK-TIMER: a question with no `--default-after` auto-picks its recommendation when the prompt timer runs out.
            // The global `prompts.timeoutSec` only: the per-session override is core state a plugin cannot read.
            defaultAfterMinFor = {
                val sec = api.settings.stored(TIMEOUT_SEC_KEY)?.trim()?.toIntOrNull() ?: DEFAULT_TIMEOUT_SEC
                (sec * 1_000L).takeIf { it > 0 }?.let { ((it + 59_999) / 60_000).toInt() }
            },
        )
        // PLUGIN-MCP-1: OwnerAsks listeners hear each resolution (answered / cancelled); the push to clients is unchanged.
        val resolvedListeners = java.util.concurrent.CopyOnWriteArrayList<(String, String?) -> Unit>()
        questions.sink = { q ->
            api.events.broadcast("question", QuestionJson.questionText(q))
            if (q.state == QuestionStates.ANSWERED || q.state == QuestionStates.CANCELLED) {
                for (l in resolvedListeners) runCatching { l(q.id, if (q.state == QuestionStates.ANSWERED) q.answer else null) }
            }
        }
        api.services.provide(OwnerAsks::class, object : OwnerAsks {
            override fun ask(sessionId: String, question: String, options: List<Pair<String, String>>, recommended: String, reason: String): String {
                val record = session(sessionId) ?: throw IllegalArgumentException("unknown session '${sessionId.take(80)}'")
                return questions.ask(
                    Asker(sessionId, record.agentId, record.role, record.projectId),
                    QuestionAsk(question = question, options = options.map { QuestionOption(it.first, it.second) }, recommended = recommended, reason = reason),
                ).id
            }

            override fun onResolved(listener: (questionId: String, answer: String?) -> Unit): AutoCloseable {
                resolvedListeners += listener
                return AutoCloseable { resolvedListeners -= listener }
            }
        })

        api.services.provide(QuestionsOpen::class, object : QuestionsOpen {
            override fun hasOpen(sessionId: String): Boolean =
                questions.list().any { it.sessionId == sessionId && it.state == QuestionStates.OPEN }
        })
        api.hooks.connected(ConnectedHook { listOf(Frame("questions", QuestionJson.listText(questions.list()))) })

        // A session that is killed, removed or restarted can no longer read the answer: its open questions are orphaned.
        api.processes.onSessionClosed { c ->
            if (c.reason in ORPHANING_CLOSE_REASONS) questions.orphanSession(c.sessionId, c.at)
        }
        // Auto-picks are minute-grained; a 15 s sweep (and one at once, for questions due across a restart).
        runCatching { questions.sweep() }
        api.schedule("question-sweep", SWEEP_MS) { questions.sweep() }

        api.frames.handle("question_ask") { frame, _ -> refuse("question_ask") { ask(api, questions, frame, ::session) } }
        api.frames.handle("question_list") { _, _ -> refuse("question_list") { Frame("questions", QuestionJson.listText(questions.list())) } }
        // The owner's pick (or an override of an auto-pick). Only the owner answers: a frame naming a session (an agent) is refused.
        api.frames.handle("question_answer") { frame, _ ->
            refuse("question_answer") {
                require(frame.sessionId.isNullOrBlank()) { "only the owner answers a question; an agent cannot" }
                questions.answer(QuestionJson.decodeAnswer(frame.data))
                null
            }
        }
        // The owner (no sessionId) or the asking session itself withdraws an open question.
        api.frames.handle("question_cancel") { frame, _ ->
            refuse("question_cancel") {
                questions.cancel(QuestionJson.decodeCancel(frame.data), frame.sessionId?.trim()?.takeIf { it.isNotEmpty() })
                null
            }
        }
        return questions
    }

    private fun ask(api: DaemonApi, questions: OwnerQuestions, frame: Frame, session: (String) -> SessionView?): Frame {
        val askerId = frame.sessionId?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("no session: the envelope's sessionId names the asking session")
        val record = session(askerId) ?: throw IllegalArgumentException("unknown session '${askerId.take(80)}'")
        val created = questions.ask(Asker(askerId, record.agentId, record.role, record.projectId), QuestionJson.decodeAsk(frame.data))
        return Frame("question_asked", JsonObject(mapOf("questionId" to JsonPrimitive(created.id))).toString(), askerId)
    }

    /** The core's error shapes: `<frame> refused: ...` for a bad request, `<frame> failed: ...` for anything else. */
    private fun refuse(type: String, block: () -> Frame?): Frame? = try {
        block()
    } catch (e: IllegalArgumentException) {
        Frame("error", "$type refused: ${e.message ?: "invalid request"}")
    } catch (e: Exception) {
        Frame("error", "$type failed: ${e.message ?: e::class.simpleName}")
    }
}
