package com.superiorsoftllc.agenthq.session

import com.superiorsoftllc.agenthq.protocol.TraitCheck
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * TRAITS-T6 (contract 1.52.0, docs/AGENTHQ-ROLE-TRAITS.md section 5): where a session stands on its REPORT BACK
 * obligation. Persisted with the record (`sessions.json`) and carried on the `session`/`sessions` frames.
 *
 * - [status]: `pending` from spawn, `reported` once a qualifying mail is seen, `unreported` after a checked run that
 *   left none. A later qualifying mail flips `unreported` to `reported`.
 * - [traitId]: the trait whose `report` check this is.
 * - [reprompted]: the one `system` re-prompt was sent (D10).
 * - [checkedRun]: the last run number checked, so one run is never checked twice (a restart cannot raise twice).
 * - [reportMessageId]: the mail that satisfied the check.
 * - [attentionId]: the `unreported` attention item raised for this session, at most one per instance (D9).
 */
@Serializable
data class SessionReport(
    val status: ReportStatus = ReportStatus.PENDING,
    val traitId: String,
    val reprompted: Boolean = false,
    val checkedRun: Int = 0,
    val reportMessageId: String? = null,
    val attentionId: String? = null,
)

@Serializable
enum class ReportStatus {
    @SerialName("pending") PENDING,
    @SerialName("reported") REPORTED,
    @SerialName("unreported") UNREPORTED,
}

/** TRAITS-T6: the pure rules of REPORT BACK -- which trait obliges a session, and whether a mail body is a report. */
object ReportBack {

    /**
     * The first trait of [instance] carrying a `report` check, with that check; null when there is none. Only an
     * [unattended] session is ever obliged (D9): an interactive console is never checked.
     */
    fun obligation(instance: RoleInstance?, unattended: Boolean): Pair<TraitSnapshot, TraitCheck>? {
        if (!unattended || instance == null) return null
        for (trait in instance.traits) {
            val check = trait.check ?: continue
            if (check.kind == TraitCheck.KIND_REPORT && check.fields.isNotEmpty()) return trait to check
        }
        return null
    }

    /** The `pending` state a spawn starts with, or null when the session has no obligation. */
    fun initial(instance: RoleInstance?, unattended: Boolean): SessionReport? =
        obligation(instance, unattended)?.let { (trait, _) -> SessionReport(traitId = trait.id) }

    /**
     * The required [fields] [body] does NOT carry as a filled `label: value` line, in [fields] order (empty = a full
     * report). A label matches ignoring case and may be preceded by `-` or `*`. The value must be non-blank and not an
     * unfilled `<placeholder>`; `NOT RUN`, `none` and `unknown` are valid values.
     */
    fun missingFields(body: String, fields: List<String>): List<String> =
        fields.filter { field -> !hasFilledLine(body, field) }

    private fun hasFilledLine(body: String, field: String): Boolean {
        val line = Regex("^\\s*[-*]?\\s*${Regex.escape(field.trim())}\\s*:(.*)$", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
        return line.findAll(body).any { match -> isFilled(match.groupValues[1]) }
    }

    private val PLACEHOLDER = Regex("^<[^<>]*>$")

    private fun isFilled(raw: String): Boolean {
        val value = raw.trim()
        return value.isNotEmpty() && !PLACEHOLDER.matches(value)
    }
}
