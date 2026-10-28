package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.ui.protocol.SessionRecord

/*
 * Ticket a7f33ba2: the composer's three ways to stop something, each with one distinct purpose.
 *  - Stop thinking: interrupt the current turn (the session stays, idle).
 *  - Stop background processes (Ctrl+Shift+K): reap this session's background jobs; the session stays alive.
 *  - End session (Ctrl+Alt+K): the forced kill; history stays and Restart brings it back.
 * Destroy (Remove agent) stays the delete-with-confirmation action.
 */
const val STOP_TURN_LABEL = "Stop thinking (current turn)"
const val STOP_BACKGROUND_LABEL = "Stop background processes"
const val STOP_BACKGROUND_SHORTCUT = "Ctrl+Shift+K"
const val END_SESSION_LABEL = "End session"
const val END_SESSION_SHORTCUT = "Ctrl+Alt+K"

/** The pids of [session]'s background work (its `agenthq bg` jobs and the background work the daemon saw), distinct, 0 dropped. */
fun backgroundProcessPids(session: SessionRecord): List<Long> =
    (listOfNotNull(session.backgroundWork?.pid) + session.bgJobs.map { it.pid }).filter { it > 0 }.distinct()
