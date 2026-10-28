package com.superiorsoftllc.agenthq.ui.model

enum class ToastKind { INFO, SUCCESS, WARN, ERROR }

/**
 * One global bottom-right toast. [sessionId] / [ticketId] / [projectId] say where a click goes; [durationMs] is how long it
 * stays (the clock pauses while hovered). [id] dedupes: posting an id that is already queued is a no-op.
 */
data class ToastEvent(
    val id: String,
    val at: Long,
    val kind: ToastKind,
    val title: String,
    val body: String? = null,
    val ticketId: String? = null,
    val sessionId: String? = null,
    val projectId: String? = null,
    val durationMs: Long = DEFAULT_TOAST_MS,
)

const val DEFAULT_TOAST_MS = 6_000L

/** At most this many toasts show at once; the newest win and sit on top. */
const val MAX_VISIBLE_TOASTS = 3

private const val MAX_QUEUED_TOASTS = 20

fun NoticeKind.toToastKind(): ToastKind = when (this) {
    NoticeKind.OK -> ToastKind.SUCCESS
    NoticeKind.HEADS_UP -> ToastKind.WARN
    NoticeKind.ERROR -> ToastKind.ERROR
}

/** A cross-project [Notice] drawn as a toast: the project is the title, the text the body. */
fun Notice.toToast(projectName: String?): ToastEvent =
    ToastEvent("notice-$at-${text.hashCode()}", at, kind.toToastKind(), projectName ?: projectId.orEmpty(), text, projectId = projectId)

/** BUG f422b3a1: how long a prompt toast stays if nobody answers (it is removed at once when the prompt resolves). */
const val PROMPT_TOAST_MS = 10 * 60_000L

fun promptToastId(promptId: String): String = "prompt-$promptId"

/**
 * BUG f422b3a1: the global toast for a prompt opened by a session that is not the selected one: who asks and what for.
 * A click opens that session (its project first), where the card is docked above the input.
 */
fun promptToast(s: AppState, p: com.superiorsoftllc.agenthq.ui.protocol.PromptRequestView, now: Long): ToastEvent {
    val agent = s.agents[p.sessionId]
    val who = agent?.let { s.agentTitle(it) } ?: p.sessionId.take(8)
    val what = if (p.tool != null) "wants to run ${p.tool}" + (p.input?.takeIf { it.isNotBlank() }?.let { ": ${it.take(120)}" } ?: "")
    else p.question?.takeIf { it.isNotBlank() }?.let { "asks: ${it.take(160)}" } ?: "has a question"
    val title = if (p.tool != null) "Approval needed" else "Question for you"
    return ToastEvent(promptToastId(p.promptId), now, ToastKind.WARN, title, "$who $what", sessionId = p.sessionId,
        projectId = agent?.session?.projectId, durationMs = PROMPT_TOAST_MS)
}

/** Post [toast]; an id already queued is ignored. Keeps the newest few. */
fun AppState.withToast(toast: ToastEvent): AppState =
    if (toasts.any { it.id == toast.id }) this else copy(toasts = (toasts + toast).takeLast(MAX_QUEUED_TOASTS))

/**
 * What to draw: toasts newer than [baseline] (what was there when the host first composed is history) that are not
 * [dismissed], the newest [max], NEWEST FIRST (top of the stack).
 */
fun visibleToasts(events: List<ToastEvent>, baseline: Long, dismissed: Set<String>, max: Int = MAX_VISIBLE_TOASTS): List<ToastEvent> =
    events.filter { it.at > baseline && it.id !in dismissed }.takeLast(max).reversed()

/** Notices the chat of [sessionId] shows: global ones (no session) and that session's own, never another session's. */
fun AppState.noticesForSession(sessionId: String?): List<Notice> =
    localNotices().filter { it.sessionId == null || it.sessionId == sessionId }
