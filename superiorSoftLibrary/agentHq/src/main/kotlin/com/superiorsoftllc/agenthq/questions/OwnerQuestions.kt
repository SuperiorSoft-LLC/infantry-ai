package com.superiorsoftllc.agenthq.questions

import com.superiorsoftllc.agenthq.db.AgentHQDatabase
import com.superiorsoftllc.agenthq.mailbox.MailResult
import com.superiorsoftllc.agenthq.mailbox.MailSendRequest
import com.superiorsoftllc.agenthq.persistence.AgentHqStore
import com.superiorsoftllc.agenthq.persistence.SqlAgentHqStore
import com.superiorsoftllc.agenthq.protocol.OwnerQuestion
import com.superiorsoftllc.agenthq.protocol.PromptOption
import com.superiorsoftllc.agenthq.protocol.QuestionAnswer
import com.superiorsoftllc.agenthq.protocol.QuestionAsk
import com.superiorsoftllc.agenthq.protocol.QuestionResolvedBy
import com.superiorsoftllc.agenthq.protocol.QuestionStates
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

/** ASK-1: the durable owner questions (41.sqm). Rows are never deleted: resolved ones are the history. */
interface OwnerQuestionRepository {
    fun insert(question: OwnerQuestion)
    /** Writes the resolution columns of [question] (state, answer, otherText, resolvedBy, resolvedAt, overridden). */
    fun update(question: OwnerQuestion)
    fun get(id: String): OwnerQuestion?
    /** Oldest first. */
    fun all(): List<OwnerQuestion>

    companion object {
        /** The daemon's SQL table when [store] is the SQL store; an in-memory list otherwise (a test double store). */
        fun forStore(store: AgentHqStore): OwnerQuestionRepository =
            (store as? SqlAgentHqStore)?.let { SqlOwnerQuestionRepository(it.handle.database) } ?: InMemoryOwnerQuestionRepository()
    }
}

class InMemoryOwnerQuestionRepository : OwnerQuestionRepository {
    private val rows = LinkedHashMap<String, OwnerQuestion>()

    @Synchronized override fun insert(question: OwnerQuestion) {
        require(question.id !in rows) { "question '${question.id}' exists" }
        rows[question.id] = question
    }

    @Synchronized override fun update(question: OwnerQuestion) {
        if (question.id in rows) rows[question.id] = question
    }

    @Synchronized override fun get(id: String) = rows[id]

    @Synchronized override fun all() = rows.values.sortedWith(compareBy({ it.createdAt }, { it.id }))
}

class SqlOwnerQuestionRepository(database: AgentHQDatabase) : OwnerQuestionRepository {
    private val q = database.owner_questionsQueries

    override fun insert(question: OwnerQuestion) {
        q.insertOwnerQuestion(
            id = question.id,
            project_id = question.projectId,
            session_id = question.sessionId,
            agent_id = question.agentId,
            role = question.role,
            question = question.question,
            options = json.encodeToString(OPTIONS, question.options),
            recommended = question.recommended,
            reason = question.reason,
            allow_other = if (question.allowOther) 1 else 0,
            default_after_min = question.defaultAfterMin?.toLong(),
            state = question.state,
            answer = question.answer,
            other_text = question.otherText,
            resolved_by = question.resolvedBy,
            created_at = question.createdAt,
            resolved_at = question.resolvedAt,
            overridden = if (question.overridden) 1 else 0,
        )
    }

    override fun update(question: OwnerQuestion) {
        q.resolveOwnerQuestion(
            state = question.state,
            answer = question.answer,
            otherText = question.otherText,
            resolvedBy = question.resolvedBy,
            resolvedAt = question.resolvedAt,
            overridden = if (question.overridden) 1 else 0,
            id = question.id,
        )
    }

    override fun get(id: String) = q.getOwnerQuestionById(id).executeAsOneOrNull()?.toQuestion()

    override fun all() = q.getAllOwnerQuestions().executeAsList().map { it.toQuestion() }

    private fun com.superiorsoftllc.agenthq.db.Owner_questions.toQuestion() = OwnerQuestion(
        id = id,
        projectId = project_id,
        sessionId = session_id,
        agentId = agent_id,
        role = role,
        question = question,
        options = json.decodeFromString(OPTIONS, options),
        recommended = recommended,
        reason = reason,
        allowOther = allow_other != 0L,
        defaultAfterMin = default_after_min?.toInt(),
        state = state,
        answer = answer,
        otherText = other_text,
        resolvedBy = resolved_by,
        createdAt = created_at,
        resolvedAt = resolved_at,
        overridden = overridden != 0L,
    )

    private companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val OPTIONS = ListSerializer(PromptOption.serializer())
    }
}

/** ASK-1: who asks, frozen on the question so the owner sees it after the session is gone. */
data class Asker(val sessionId: String, val agentId: String? = null, val role: String? = null, val projectId: String? = null) {
    /** The rate-limit key and the mail address: the agent outlives its sessions, so its id wins. */
    val address: String get() = agentId?.takeIf { it.isNotBlank() } ?: sessionId
}

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
    private val send: (MailSendRequest) -> MailResult,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val maxOpen: () -> Int = { DEFAULT_MAX_OPEN },
    private val maxPerHour: () -> Int = { DEFAULT_MAX_PER_HOUR },
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
        val options = request.options.map { PromptOption(it.label.trim(), it.description.trim()) }
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
                allowOther = request.allowOther,
                defaultAfterMin = request.defaultAfterMin,
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
            require(other == null || current.allowOther) { "this question takes no otherText" }
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

    /** Auto-pick every open question whose `defaultAfterMin` has run out at [now]. Returns the questions it picked. */
    fun sweep(now: Long = clock()): List<OwnerQuestion> {
        val picked = synchronized(lock) {
            repository.all()
                .filter { it.state == QuestionStates.OPEN && it.defaultAfterMin != null && now >= it.createdAt + it.defaultAfterMin * MINUTE_MS }
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
        runCatching { sink(question) }.onFailure { System.err.println("[agentHq] question push failed: ${it.message}") }
    }

    /** A mail failure never undoes the stored answer: it is logged, and the answer stays visible in the history. */
    private fun mail(question: OwnerQuestion, content: Pair<String, String>) {
        val request = MailSendRequest(
            from = OWNER,
            to = Asker(question.sessionId, question.agentId).address,
            subject = content.first,
            body = content.second,
            threadId = threadOf(question.id),
        )
        runCatching { send(request) }
            .onSuccess { if (it is MailResult.Refused) System.err.println("[agentHq] question ${question.id} mail refused: ${it.reason}") }
            .onFailure { System.err.println("[agentHq] question ${question.id} mail failed: ${it.message}") }
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
            val choice = question.answer ?: "other"
            val subject = "Answer to question ${question.id}: ${choice.take(80)}"
            val body = buildString {
                appendLine("Question ${question.id}: ${question.question}")
                question.answer?.let { label ->
                    val description = question.options.firstOrNull { it.label == label }?.description.orEmpty()
                    appendLine("Chosen option: $label" + if (description.isNotEmpty()) " ($description)" else "")
                }
                question.otherText?.let { appendLine("Owner's answer: $it") }
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
