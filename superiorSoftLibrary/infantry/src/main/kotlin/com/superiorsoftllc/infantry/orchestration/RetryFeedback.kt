package com.superiorsoftllc.infantry.orchestration

import com.superiorsoftllc.infantry.persistence.RunEvent
import com.superiorsoftllc.infantry.persistence.RunRecord
import com.superiorsoftllc.infantry.persistence.StoredEvent

/** Composes the prompt of a retry attempt from the immediately previous attempt only. */
object RetryFeedback {
    const val MARKER = "INFANTRY RETRY FEEDBACK"
    const val MAX_ERRORS = 10
    const val MAX_OUTPUT_CHARS = 2_000

    fun compose(
        originalPrompt: String,
        nextAttempt: Int,
        maxAttempts: Int,
        previousRun: RunRecord,
        previousEvents: List<StoredEvent>,
    ): String {
        val errors = previousEvents.mapNotNull { (it.event as? RunEvent.Error)?.message }.takeLast(MAX_ERRORS)
        val output = previousEvents.mapNotNull { (it.event as? RunEvent.TextDelta)?.text }
            .joinToString("").takeLast(MAX_OUTPUT_CHARS)
        return buildList {
            add(originalPrompt)
            add("")
            add("---")
            add("$MARKER (attempt $nextAttempt of $maxAttempts)")
            add("The previous attempt (attempt ${nextAttempt - 1}) did not succeed.")
            add("exit code: ${previousRun.exitCode?.toString() ?: "none (the run could not be started)"}")
            add("daemon error: ${previousRun.error ?: "none"}")
            add("provider errors:")
            if (errors.isEmpty()) add("- none") else errors.forEach { add("- $it") }
            add("last output:")
            add(output.ifEmpty { "(none)" })
            add("---")
            add("Address the failure above, then complete the original task.")
        }.joinToString("\n")
    }
}
