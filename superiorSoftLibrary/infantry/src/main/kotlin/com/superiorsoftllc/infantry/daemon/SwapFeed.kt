// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

/**
 * DP-5h-3: the daemon's one session-event sink as the board plugin sees it across a load or a reload. Core owns the sessions
 * and keeps emitting while the board plugin is absent (before it loads, or between an unload and the next load). Events that
 * arrive then are QUEUED in arrival order, and [attach] hands them to the new target before it receives anything live, so a
 * swap loses none. [detach] returns to queueing. The queue is bounded: past [limit] the OLDEST are dropped (counted in
 * [dropped]) so a plugin that never comes back cannot grow the daemon's memory.
 *
 * DEADLOCK-1 (ticket f3389c81): the target is NEVER called with [lock] held. The target (the board) takes its own lock, and a
 * board hand-out emits session events while holding the board lock; delivering under [lock] made the two locks a cycle
 * (an event thread held [lock] and waited for the board, the hand-out held the board and waited for [lock]). [lock] guards
 * only the queue and the target reference. Events emitted while an [attach] drains are queued behind the drain, so queued
 * events still reach the new target before live ones; one thread's events keep their order.
 */
class SwapFeed<E : Any>(private val limit: Int = 10_000) {
    private val lock = Any()
    /** Serialises [attach] calls only; [emit] never takes it, so it cannot join a cycle with the target's lock. */
    private val attachLock = Any()
    private val queue = ArrayDeque<E>()
    private var target: ((E) -> Unit)? = null

    /** Events discarded because the queue was full while no target was attached. */
    @Volatile
    var dropped: Long = 0
        private set

    /** Queued events not yet delivered. */
    val pending: Int get() = synchronized(lock) { queue.size }

    /** The core side: deliver to the attached target now (outside the lock), else queue. */
    fun emit(event: E) {
        val t = synchronized(lock) {
            val t = target
            if (t == null) {
                if (queue.size >= limit) { queue.removeFirst(); dropped++ }
                queue.addLast(event)
                return
            }
            t
        }
        t(event)
    }

    /** Attach a target: it first receives every queued event in order, then live ones. A throwing target skips that event. */
    fun attach(newTarget: (E) -> Unit) {
        synchronized(attachLock) {
            while (true) {
                val next = synchronized(lock) {
                    val e = queue.removeFirstOrNull()
                    if (e == null) target = newTarget
                    e
                } ?: return
                runCatching { newTarget(next) }
            }
        }
    }

    /** The target is gone (plugin unloading): later events queue again. */
    fun detach() = synchronized(lock) { target = null }
}
