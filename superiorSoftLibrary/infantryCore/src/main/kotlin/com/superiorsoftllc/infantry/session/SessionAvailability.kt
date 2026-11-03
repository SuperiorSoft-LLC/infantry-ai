// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.session

/**
 * ROSTER-1: the shared "is this session busy / available" rules, extracted from
 * [com.superiorsoftllc.infantry.mailbox.MailRouter] so the mail router and the project roster can
 * never disagree about what "at rest" means: a row that says IDLE is exactly a session a queued
 * message can wake.
 */

/** Whether a run is in flight: [SessionState.STARTING] / [SessionState.BUSY] / [SessionState.WAITING_INPUT]. */
fun SessionRecord.isBusy(): Boolean =
    state == SessionState.STARTING ||
        state == SessionState.BUSY ||
        state == SessionState.WAITING_INPUT

/**
 * Display only: a turn is in flight OR background work still runs. Cards, the Running group and roster BUSY use this;
 * queueing, refusal and mail holding use [isBusy] (turn active), so background work alone never blocks input.
 */
fun SessionRecord.isRunning(): Boolean = isBusy() || backgroundWork != null || bgJobs.isNotEmpty()

/** Whether the session is at rest and can be brought back: not busy and [SessionRecord.resumable]. */
fun SessionRecord.isAvailable(): Boolean = !isBusy() && (resumable || neverRun)

/**
 * BUG-ROLE-1: whether the session is one of its project's agents right now -- in flight, resumable, or bound and
 * never run -- i.e. a session mail can still reach. Role mail (MailRouter) and the live roster (`agents --live`)
 * both read this one rule, so what a session SEES is exactly what it can mail.
 */
fun SessionRecord.isInSession(): Boolean = live || resumable || neverRun
