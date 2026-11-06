// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.session

/**
 * RESUME-NOTIFY-1: after a daemon restart, a session whose turn the stop cut ([ReconcileReport.cutTurns]) is prompted to go on,
 * and the people who wait on it are told.
 *
 * Generic: it knows sessions, not tickets or roles. The caller supplies how to prompt a session ([resume], normally
 * [SessionManager.resume]), who hears about one session ([notify]: the daemon wiring sends it to the session's starter or its
 * work item's notify list) and the one summary for the owner ([raise]: an attention item). A session at rest between turns is
 * never in [ReconcileReport.cutTurns], so it is never prompted. A session whose child outlived the daemon
 * ([ReconcileReport.orphanedIds]) is still running: it is named in the summary, never prompted.
 *
 * [enabled] off = nothing is prompted, but the notices still go out (a cut turn is never silent).
 */
class RestartResume(
    private val get: (sessionId: String) -> SessionRecord?,
    private val resume: (sessionId: String, prompt: String) -> Unit,
    private val notify: (session: SessionRecord, line: String) -> Unit,
    private val raise: (summary: String, sessionIds: List<String>) -> Unit,
    private val enabled: () -> Boolean = { true },
    private val prompt: () -> String = { DEFAULT_PROMPT },
) {
    /** What [run] did: the sessions prompted, the ones it could not prompt (with why), the ones left as they were. */
    data class Outcome(
        val resumed: List<String>,
        val failed: Map<String, String>,
        val notResumed: List<String>,
        val orphaned: List<String>,
    ) {
        val total: Int get() = resumed.size + failed.size + notResumed.size + orphaned.size
        override fun toString(): String = "resumed=${resumed.size} failed=${failed.size} notResumed=${notResumed.size} orphaned=${orphaned.size}"
    }

    fun run(report: ReconcileReport): Outcome {
        val on = enabled()
        val text = prompt().ifBlank { DEFAULT_PROMPT }
        val resumed = ArrayList<String>()
        val failed = LinkedHashMap<String, String>()
        val notResumed = ArrayList<String>()
        for (id in report.cutTurns.distinct()) {
            if (get(id) == null) continue
            if (!on) { notResumed += id; continue }
            runCatching { resume(id, text) }
                .onSuccess { resumed += id }
                .onFailure { failed[id] = it.message ?: it.javaClass.simpleName }
        }
        val orphaned = report.orphanedIds.distinct().filter { get(it) != null }
        val outcome = Outcome(resumed, failed, notResumed, orphaned)
        if (outcome.total == 0) return outcome

        fun each(ids: Collection<String>, line: (SessionRecord) -> String) = ids.forEach { id ->
            get(id)?.let { s -> runCatching { notify(s, line(s)) }.onFailure { System.err.println("[infantry] restart notice for $id failed: ${it.message}") } }
        }
        each(resumed) { "${label(it)}: the daemon restarted and cut its turn; it was prompted to continue where it left off." }
        each(failed.keys) { "${label(it)}: the daemon restarted and cut its turn; it could NOT be resumed (${failed[it.id]}). It needs a prompt." }
        each(notResumed) { "${label(it)}: the daemon restarted and cut its turn; auto-resume is off ($ENABLED_SETTING), so it is idle until prompted." }
        each(orphaned) { "${label(it)}: the daemon restarted while its turn ran; its process is still alive, so it was not prompted." }

        val lines = buildList {
            resumed.forEach { add("- ${describe(it)}: resumed") }
            failed.forEach { (id, why) -> add("- ${describe(id)}: NOT resumed ($why)") }
            notResumed.forEach { add("- ${describe(it)}: not resumed (auto-resume off)") }
            orphaned.forEach { add("- ${describe(it)}: process still running, not prompted") }
        }
        val head = "${outcome.total} session(s) had their turn cut by a daemon restart; ${resumed.size} resumed."
        runCatching { raise(head + "\n" + lines.joinToString("\n"), resumed + failed.keys + notResumed + orphaned) }
            .onFailure { System.err.println("[infantry] restart attention failed: ${it.message}") }
        return outcome
    }

    private fun describe(id: String): String = get(id)?.let(::label) ?: id

    private fun label(s: SessionRecord): String =
        listOfNotNull(s.role ?: "-", s.agentId ?: s.id.take(8)).joinToString("/") + " (session ${s.id.take(8)}" + (s.ticketId?.let { ", ticket $it" } ?: "") + ")"

    companion object {
        /** Setting: auto-resume a cut turn after a restart (default true). */
        const val ENABLED_SETTING = "session.resumeAfterRestart"

        /** Setting: the prompt a cut session gets ("" = [DEFAULT_PROMPT]). */
        const val PROMPT_SETTING = "session.resumeAfterRestartPrompt"
        const val DEFAULT_PROMPT = "The daemon restarted and your last turn was cut. Continue where you left off."

        /** The attention kind of the summary. */
        const val ATTENTION_KIND = com.superiorsoftllc.infantry.daemon.api.attention.AttentionKinds.SESSION_RESUMED

        /** The wake reason a resumed turn carries (scripts see it on `session woken`). */
        const val WAKE_REASON = "daemon-restart"
    }
}
