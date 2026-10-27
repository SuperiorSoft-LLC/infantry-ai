package com.superiorsoftllc.agenthq.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.model.ToastEvent
import com.superiorsoftllc.agenthq.ui.model.ToastKind
import com.superiorsoftllc.agenthq.ui.model.crossProjectNotices
import com.superiorsoftllc.agenthq.ui.model.toToast
import kotlin.time.Clock

/**
 * TOAST-1: what a block toasts through. The shell provides one at the root ([LocalToast]); any block may provide its
 * own around a subtree (a wrap override, or a toast block of its own), and the nearest provider wins. The default is
 * the controller's ([com.superiorsoftllc.agenthq.ui.net.Controller.toasts]): it posts into `AppState.toasts`, which the
 * core toast block (`infantry.shell.toast`) draws.
 */
interface ToastService {
    /** Post [toast]; an id already queued is ignored. */
    fun post(toast: ToastEvent)

    /** Take toast [id] down (unknown ids are ignored). */
    fun dismiss(id: String)

    /** Post a one-line [message] (a [title] makes it the body); the answer is the new toast's id, for [dismiss]. */
    fun post(message: String, kind: ToastKind = ToastKind.INFO, title: String? = null): String {
        val now = Clock.System.now().toEpochMilliseconds()
        val id = "toast-$now-${nextToastSeq++}"
        post(ToastEvent(id, now, kind, title ?: message, message.takeIf { title != null }))
        return id
    }
}

private var nextToastSeq = 0L

/** No toast system above: posting is a safe no-op, never a crash. */
object NoToastService : ToastService {
    override fun post(toast: ToastEvent) {}
    override fun dismiss(id: String) {}
}

/** TOAST-1: the nearest [ToastService]; [NoToastService] when nothing provides one. */
val LocalToast = staticCompositionLocalOf<ToastService> { NoToastService }

// S4c: the toast list stays in core (CoreShellContext serves it as ToastContext.events); the host that draws it is app UI.
/** S2: the state's toasts plus its notices about OTHER projects, oldest first: what [ToastHost] shows. */
fun toastEvents(state: AppState): List<ToastEvent> =
    (state.toasts + state.crossProjectNotices().map { n -> n.toToast(state.projects.firstOrNull { it.id == n.projectId }?.name) })
        .sortedBy { it.at }
