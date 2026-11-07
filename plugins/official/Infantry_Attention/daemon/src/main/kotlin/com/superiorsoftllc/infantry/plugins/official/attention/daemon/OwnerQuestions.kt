// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.attention.daemon

import java.util.UUID

/**
 * ASK-1 (contract 1.97.0): non-blocking owner questions.
 *
 *  - [ask] validates and stores a question and returns it at once; nothing waits on the owner.
 *  - [answer] (the owner) resolves an `open` question, or overrides an `auto` one, and MAILS the pick to the asker
 *    ([Asker.address]) from [OWNER] in thread `question:<id>`.
 *  - [sweep] auto-picks the recommended option of every open question whose `defaultAfterMin` has run out, and mails it
 *    marked "auto-selected". The daemon calls it on a timer; a restart loses nothing (it reads the stored rows).
 *  - [cancel] withdraws an open question: by the owner (the asker is mailed so it stops expecting an answer) or by the
 *    asker itself (no mail).
 *  - Per asker at most [maxOpen] open questions and [maxPerHour] asks in any rolling hour.
 *  - Every change goes to [sink] (the daemon pushes `question` to every client).
 */
class OwnerQuestions(
    private val repository: OwnerQuestionRepository,
    /** Mails [subject] / [body] to the asking session (`api.sessionNotifier.notifySession`); false = not delivered. */
    private val notify: (sessionId: String, subject: String, body: String, threadId: String) -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val maxOpen: () -> Int = { DEFAULT_MAX_OPEN },
    private val maxPerHour: () -> Int = { DEFAULT_MAX_PER_HOUR },
    /** Whether the asking session still has a record; an open question of a missing one is orphaned by [sweep]. */
    private val sessionExists: (String) -> Boolean = { true },
    /** Whether the asking session is mid-turn; a busy asker's question never expires by [ttlMinutes]. */
    private val sessionBusy: (String) -> Boolean = { false },
    /** `limits.questions.ttlMinutes`: an open question older than this, whose asker is not busy, is orphaned; 0 = never. */
    private val ttlMinutes: () -> Long = { 0L },
    /**
     * ASK-TIMER (1.139.0): the `defaultAfterMin` a question of this asking session gets when it names none (the
     * prompt timer, `prompts.timeoutSec` with the session's override, in whole minutes); null = no timer.
     */
    private val defaultAfterMinFor: (sessionId: String) -> Int? = { null },
) {
    @Volatile
    var sink: (OwnerQuestion) -> Unit = {}

    private val lock = Any()

    fun list(): List<OwnerQuestion> = repository.all()

    fun get(id: String): OwnerQuestion? = repository.get(id)

    /** Refused ([IllegalArgumentException]): a malformed question, or the asker is over its rate limit. */
    fun ask(asker: Asker, request: QuestionAsk): OwnerQuestion {
        val question = request.question.trim()
        require(question.isNotEmpty()) { "a question is required" }
        require(question.length <= QuestionAsk.QUESTION_MAX) { "the question is longer than ${QuestionAsk.QUESTION_MAX} characters" }
        val options = request.options.map { QuestionOption(it.label.trim(), it.description.trim()) }
        require(options.size >= 2) { "at least two options are required (a question with one answer is not a choice)" }
        require(options.size <= QuestionAsk.OPTIONS_MAX) { "at most ${QuestionAsk.OPTIONS_MAX} options" }
        require(options.none { it.label.isEmpty() }) { "an option label cannot be blank" }
        val repeated = options.groupBy { it.label }.filterValues { it.size > 1 }.keys.firstOrNull()
        require(repeated == null) { "option '${repeated!!.take(80)}' is given twice" }
        val recommended = request.recommended.trim()
        require(recommended.isNotEmpty()) { "exactly one recommended option is required (recommended = one option's label)" }
        require(options.any { it.label == recommended }) { "recommended '${recommended.take(80)}' is not one of the options" }
        val reason = request.reason.trim()
        require(reason.isNotEmpty()) { "a reason for the recommendation is required" }
        require(reason.length <= QuestionAsk.REASON_MAX) { "the reason is longer than ${QuestionAsk.REASON_MAX} characters" }
        request.defaultAfterMin?.let {
            require(it in QuestionAsk.DEFAULT_AFTER_MIN_RANGE) {
                "defaultAfterMin must be ${QuestionAsk.DEFAULT_AFTER_MIN_RANGE.first}..${QuestionAsk.DEFAULT_AFTER_MIN_RANGE.last} minutes, got $it"
            }
        }
        val created = synchronized(lock) {
            val now = clock()
            val mine = repository.all().filter { Asker(it.sessionId, it.agentId).address == asker.address }
            val open = mine.count { it.state == QuestionStates.OPEN }
            val openCap = maxOpen()
            require(open < openCap) {
                "rate limit: you already have $open open questions for the owner (max $openCap). Do not wait: carry on " +
                    "with other work, and batch further choices into one question once an answer arrives"
            }
            val lastHour = mine.count { it.createdAt > now - HOUR_MS }
            val hourCap = maxPerHour()
            require(lastHour < hourCap) {
                "rate limit: you asked the owner $lastHour questions in the last hour (max $hourCap). Decide yourself where you " +
                    "can, and carry on"
            }
            OwnerQuestion(
                id = newId(),
                projectId = asker.projectId,
                sessionId = asker.sessionId,
                agentId = asker.agentId,
                role = asker.role,
                question = question,
                options = options,
                recommended = recommended,
                reason = reason,
                allowOther = true, // the owner may always type an own answer
                defaultAfterMin = request.defaultAfterMin
                    ?: runCatching { defaultAfterMinFor(asker.sessionId) }.getOrNull()?.coerceIn(QuestionAsk.DEFAULT_AFTER_MIN_RANGE),
                createdAt = now,
            ).also(repository::insert)
        }
        publish(created)
        return created
    }

    /**
     * The owner's pick. Refused: an unknown id, a question that is not `open` or `auto`, an unknown label, other text
     * on a question that takes none, or neither a label nor text.
     */
    fun answer(request: QuestionAnswer): OwnerQuestion {
        val label = request.answer?.trim()?.takeIf { it.isNotEmpty() }
        val other = request.otherText?.trim()?.takeIf { it.isNotEmpty() }
        val (previous, updated) = synchronized(lock) {
            val current = known(request.questionId)
            require(current.state == QuestionStates.OPEN || current.state == QuestionStates.AUTO) {
                "question '${current.id}' is already ${current.state}"
            }
            require(label == null || current.options.any { it.label == label }) { "unknown option '${label!!.take(80)}'" }
            require(label != null || other != null) { "an answer needs 'answer' (an option label) or 'otherText'" }
            current to current.copy(
                state = QuestionStates.ANSWERED,
                answer = label,
                otherText = other,
                resolvedBy = QuestionResolvedBy.OWNER,
                resolvedAt = clock(),
                overridden = current.state == QuestionStates.AUTO,
            ).also(repository::update)
        }
        publish(updated)
        mail(updated, answerMail(updated, previous.takeIf { it.state == QuestionStates.AUTO }))
        return updated
    }

    /** Withdraw an open question. [bySession] null = the owner (the asker is told); else it must be the asker's session. */
    fun cancel(questionId: String, bySession: String?): OwnerQuestion {
        val updated = synchronized(lock) {
            val current = known(questionId)
            require(bySession == null || bySession == current.sessionId) { "only the owner or the asking session can cancel question '${current.id}'" }
            require(current.state == QuestionStates.OPEN) { "question '${current.id}' is already ${current.state}" }
            current.copy(
                state = QuestionStates.CANCELLED,
                resolvedBy = if (bySession == null) QuestionResolvedBy.OWNER else QuestionResolvedBy.ASKER,
                resolvedAt = clock(),
            ).also(repository::update)
        }
        publish(updated)
        if (bySession == null) mail(updated, cancelMail(updated))
        return updated
    }

    /**
     * A session ended (killed, removed, restarted): its open questions can no longer reach the asker, so they are
     * cancelled with [QuestionResolvedBy.ORPHANED] (no mail: nobody is there to read it). Returns the questions closed.
     */
    fun orphanSession(sessionId: String, now: Long = clock()): List<OwnerQuestion> =
        orphan(now) { it.sessionId == sessionId }

    private fun orphan(now: Long, match: (OwnerQuestion) -> Boolean): List<OwnerQuestion> {
        val closed = synchronized(lock) {
            repository.all().filter { it.state == QuestionStates.OPEN && match(it) }.map {
                it.copy(state = QuestionStates.CANCELLED, resolvedBy = QuestionResolvedBy.ORPHANED, resolvedAt = now).also(repository::update)
            }
        }
        closed.forEach(::publish)
        return closed
    }

    /**
     * Orphan every open question whose session is gone, or older than the TTL with an asker that is not busy, then
     * auto-pick every open question whose `defaultAfterMin` has run out at [now]. Returns the questions it picked.
     */
    fun sweep(now: Long = clock()): List<OwnerQuestion> {
        val ttlMs = ttlMinutes().let { if (it <= 0) Long.MAX_VALUE else it * MINUTE_MS }
        orphan(now) { !sessionExists(it.sessionId) || (now - it.createdAt >= ttlMs && !sessionBusy(it.sessionId)) }
        val picked = synchronized(lock) {
            repository.all()
                .filter { it.state == QuestionStates.OPEN && it.defaultAfterMin != null && now >= it.createdAt + it.defaultAfterMin!! * MINUTE_MS }
                .map {
                    it.copy(
                        state = QuestionStates.AUTO,
                        answer = it.recommended,
                        resolvedBy = QuestionResolvedBy.AUTO,
                        resolvedAt = now,
                    ).also(repository::update)
                }
        }
        picked.forEach {
            publish(it)
            mail(it, answerMail(it, null))
        }
        return picked
    }

    private fun known(id: String): OwnerQuestion {
        val trimmed = id.trim()
        require(trimmed.isNotEmpty()) { "questionId is required" }
        return repository.get(trimmed) ?: throw IllegalArgumentException("unknown question '${trimmed.take(80)}'")
    }

    private fun publish(question: OwnerQuestion) {
        runCatching { sink(question) }.onFailure { System.err.println("[infantry] question push failed: ${it.message}") }
    }

    /** A mail failure never undoes the stored answer: it is logged, and the answer stays visible in the history. */
    private fun mail(question: OwnerQuestion, content: Pair<String, String>) {
        // The mail rides on the question thread (it survives a restart); the name also stays in the body for a reader without thread support.
        runCatching { notify(question.sessionId, content.first, content.second + "\n(thread " + threadOf(question.id) + ")", threadOf(question.id)) }
            .onSuccess { if (!it) System.err.println("[infantry] question ${question.id} mail not delivered (the asking session is gone)") }
            .onFailure { System.err.println("[infantry] question ${question.id} mail failed: ${it.message}") }
    }

    companion object {
        /** The sender of every question mail. */
        const val OWNER = "owner"
        const val DEFAULT_MAX_OPEN = 3
        const val DEFAULT_MAX_PER_HOUR = 6
        const val MINUTE_MS = 60_000L
        const val HOUR_MS = 3_600_000L

        fun threadOf(questionId: String) = "question:$questionId"

        /** Subject and body of the mail that tells the asker the pick. [replacedAuto] = the auto-pick an override replaces. */
        fun answerMail(question: OwnerQuestion, replacedAuto: OwnerQuestion?): Pair<String, String> {
            val custom = question.answer == null && question.otherText != null
            val choice = question.answer ?: question.otherText.orEmpty()
            val subject = (if (custom) "Custom answer" else "Answer") + " to question ${question.id}: ${choice.take(80)}"
            val body = buildString {
                appendLine("Question ${question.id}: ${question.question}")
                question.answer?.let { label ->
                    val description = question.options.firstOrNull { it.label == label }?.description.orEmpty()
                    appendLine("Chosen option: $label" + if (description.isNotEmpty()) " ($description)" else "")
                }
                question.otherText?.let { appendLine(if (custom) "Custom answer from the owner: $it" else "Owner's answer: $it") }
                if (custom) appendLine("This is the owner's OWN text, not one of your options; do not read it as a chosen option.")
                when {
                    question.state == QuestionStates.AUTO -> appendLine(
                        "auto-selected after ${question.defaultAfterMin} min, owner did not answer (your recommended option). " +
                            "The owner may still override it; an override arrives as another mail in this thread.",
                    )
                    replacedAuto != null -> appendLine(
                        "OVERRIDE: the owner replaced the earlier auto-selection '${replacedAuto.answer}'. Follow this answer instead.",
                    )
                    else -> appendLine("Chosen by the owner.")
                }
                append("Carry on with this choice; no reply is needed.")
            }
            return subject to body
        }

        fun cancelMail(question: OwnerQuestion): Pair<String, String> =
            "Question ${question.id} cancelled by the owner" to
                "Question ${question.id}: ${question.question}\nThe owner cancelled it: no answer is coming. Decide with your " +
                "own judgment (your recommendation was '${question.recommended}') and carry on."
    }
}
