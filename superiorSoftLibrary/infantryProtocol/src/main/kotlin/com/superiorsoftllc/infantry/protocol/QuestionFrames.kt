package com.superiorsoftllc.infantry.protocol

import kotlinx.serialization.Serializable

/**
 * Contract 1.97.0 (ASK-1): owner questions, the NON-BLOCKING way an agent asks the owner to choose. Unlike PROMPT-5's
 * `prompt_ask` (which holds the asker until an answer), `question_ask` returns a question id at once; the owner's pick
 * reaches the asking agent later as mailbox mail. Questions are stored, so they survive a daemon restart.
 */
object QuestionStates {
    const val OPEN = "open"
    /** The owner picked (or overrode an auto-pick: [OwnerQuestion.overridden]). */
    const val ANSWERED = "answered"
    /** `defaultAfterMin` ran out with no answer: the daemon picked the recommended option. Still overridable. */
    const val AUTO = "auto"
    const val CANCELLED = "cancelled"
    val ALL: List<String> = listOf(OPEN, ANSWERED, AUTO, CANCELLED)
}

/** Who ended a question ([OwnerQuestion.resolvedBy]). */
object QuestionResolvedBy {
    const val OWNER = "owner"
    const val AUTO = "auto"
    /** The asking agent withdrew its own question. */
    const val ASKER = "asker"
    /** The daemon closed it: the asking session was removed, killed, restarted or is gone, or the question outlived its TTL. */
    const val ORPHANED = "orphaned"
}

/**
 * `question_ask` (agent -> daemon, envelope sessionId = the asking session). Exactly one [recommended] option, named by
 * its label, with a short [reason]. [defaultAfterMin] (1..1440, null = off, the default) auto-picks [recommended] when
 * the owner has not answered by then. Answered by `question_asked`; a refusal is an `error` "question_ask refused: ...".
 */
@Serializable
data class QuestionAsk(
    val question: String,
    val options: List<PromptOption>,
    val recommended: String,
    val reason: String,
    /** 1.129.0: true (default) = the owner may type an answer of their own ("Other"); false = options only. */
    val allowOther: Boolean = true,
    val defaultAfterMin: Int? = null,
) {
    companion object {
        fun decode(text: String): QuestionAsk = StrictJson.decode(serializer(), text, "question_ask payload")

        /** ASK-2: the canned question `infantry ask --test` and the Config "Send a test question" action raise. */
        fun sample() = QuestionAsk(
            question = "TEST: how should this sample question be answered?",
            options = listOf(
                PromptOption("Looks good", "Nothing happens: this is only a test"),
                PromptOption("Try the keyboard", "Press 1-9 to pick, Enter to confirm"),
                PromptOption("A deliberately long option label to check that wrapping works on a narrow phone screen", "Also nothing happens"),
            ),
            recommended = "Looks good",
            reason = "a test question, no real decision",
            allowOther = true,
        )

        const val QUESTION_MAX = 2_000
        const val REASON_MAX = 200
        const val OPTIONS_MAX = 8
        val DEFAULT_AFTER_MIN_RANGE: IntRange = 1..1_440
    }
}

/** `question_asked` (daemon -> the asking connection): the new question's id. The agent carries on; the answer is mail. */
@Serializable
data class QuestionAsked(val questionId: String, val note: String = NON_BLOCKING_NOTE) {
    companion object {
        const val NON_BLOCKING_NOTE =
            "non-blocking: carry on with other work; the owner's answer arrives as mail in thread question:<id>"
    }
}

/**
 * One owner question as the daemon stores and pushes it (`question`, `questions`). [answer] is the chosen label (null
 * when the owner typed [otherText] only, or while open / cancelled). Times are epoch milliseconds.
 */
@Serializable
data class OwnerQuestion(
    val id: String,
    val projectId: String? = null,
    val sessionId: String,
    val agentId: String? = null,
    val role: String? = null,
    val question: String,
    val options: List<PromptOption>,
    val recommended: String,
    val reason: String,
    val allowOther: Boolean = false,
    val defaultAfterMin: Int? = null,
    val state: String = QuestionStates.OPEN,
    val answer: String? = null,
    val otherText: String? = null,
    val resolvedBy: String? = null,
    val createdAt: Long,
    val resolvedAt: Long? = null,
    /** True once the owner replaced an auto-pick. */
    val overridden: Boolean = false,
)

/**
 * `question_answer` (owner client -> daemon): pick [answer] (an option label) and/or type [otherText] (only when the
 * question allows it). Allowed on an `open` question, and on an `auto` one (an override). A refusal is an `error`
 * "question_answer refused: ...".
 */
@Serializable
data class QuestionAnswer(val questionId: String, val answer: String? = null, val otherText: String? = null) {
    companion object {
        fun decode(text: String): QuestionAnswer = StrictJson.decode(serializer(), text, "question_answer payload")
    }
}

/** `question_cancel` (owner client, or the asking session -> daemon): withdraw an open question. */
@Serializable
data class QuestionCancel(val questionId: String) {
    companion object {
        fun decode(text: String): QuestionCancel = StrictJson.decode(serializer(), text, "question_cancel payload")
    }
}
