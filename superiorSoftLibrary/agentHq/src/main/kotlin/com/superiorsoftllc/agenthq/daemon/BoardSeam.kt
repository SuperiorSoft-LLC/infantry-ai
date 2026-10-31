package com.superiorsoftllc.agenthq.daemon

import java.util.concurrent.atomic.AtomicReference

/**
 * DP-5h-3c: the seam between the daemon core and a board that may arrive AFTER the plugin host loads (and leave on a reload).
 * Services built for the host take [host] lazily instead of a board built first; session events go through [feed], which queues
 * (bounded, in order) while no board is attached. [attach] gives the board its queued events, then re-adopts its running work
 * ([readopt]: the worker sweep and the queue-mark clear). [detach] returns to queueing.
 */
class BoardSeam<H : Any, E : Any>(private val deliver: (H, E) -> Unit, limit: Int = 10_000) {
    private val ref = AtomicReference<H?>(null)
    private val feed = SwapFeed<E>(limit)

    /** The attached board host, or null before attach / after detach. */
    val host: H? get() = ref.get()

    /** Events queued while no board is attached. */
    val pending: Int get() = feed.pending

    /** Events dropped because the queue was full. */
    val dropped: Long get() = feed.dropped

    /** The core side: a session event, delivered now or queued. */
    fun emit(event: E) = feed.emit(event)

    /** The host the services resolve at call time; a clear error (not an NPE) if used before attach. */
    fun require(): H = ref.get() ?: error("the board is not attached yet")

    /** Attach: queued events first, in order, then live; then [readopt] runs once (worker sweep, queue marks). */
    fun attach(newHost: H, readopt: () -> Unit = {}) {
        ref.set(newHost)
        feed.attach { e -> deliver(newHost, e) }
        runCatching(readopt).onFailure { System.err.println("[agentHq] board re-adopt failed: ${it.message}") }
    }

    /** The board is going away (plugin reload / unload): events queue again. */
    fun detach() {
        feed.detach()
        ref.set(null)
    }
}
