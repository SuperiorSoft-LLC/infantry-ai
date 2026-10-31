package com.superiorsoftllc.agenthq.daemon

/**
 * DP-5h-3: the daemon's one session-event sink as the board plugin sees it across a load or a reload. Core owns the sessions
 * and keeps emitting while the board plugin is absent (before it loads, or between an unload and the next load). Events that
 * arrive then are QUEUED in arrival order, and [attach] hands them to the new target before it receives anything live, so a
 * swap loses none. [detach] returns to queueing. The queue is bounded: past [limit] the OLDEST are dropped (counted in
 * [dropped]) so a plugin that never comes back cannot grow the daemon's memory.
 */
class SwapFeed<E : Any>(private val limit: Int = 10_000) {
    private val lock = Any()
    private val queue = ArrayDeque<E>()
    private var target: ((E) -> Unit)? = null

    /** Events discarded because the queue was full while no target was attached. */
    @Volatile
    var dropped: Long = 0
        private set

    /** Queued events not yet delivered. */
    val pending: Int get() = synchronized(lock) { queue.size }

    /** The core side: deliver to the attached target now, else queue. Delivery order is arrival order. */
    fun emit(event: E) {
        synchronized(lock) {
            val t = target
            if (t == null) {
                if (queue.size >= limit) { queue.removeFirst(); dropped++ }
                queue.addLast(event)
                return
            }
            t(event)
        }
    }

    /** Attach a target: it first receives every queued event in order, then live ones. A throwing target skips that event. */
    fun attach(newTarget: (E) -> Unit) {
        synchronized(lock) {
            while (queue.isNotEmpty()) runCatching { newTarget(queue.removeFirst()) }
            target = newTarget
        }
    }

    /** The target is gone (plugin unloading): later events queue again. */
    fun detach() = synchronized(lock) { target = null }
}
