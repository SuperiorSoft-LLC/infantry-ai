// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.session

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable

/**
 * WAITING-FLAG-1: the flag a session sets on ITSELF to say "I am still working, I am waiting on a process". Generic mechanism:
 * a reason, a start time and an expected end; the board shows a flagged ticket as "active but waiting" and a watcher script
 * leaves a flagged session alone. All times are epoch millis. Memory only in effect (a daemon start clears it, like progress).
 */
@Serializable
data class SessionWaiting(
    val reason: String,
    val since: Long,
    /** When the wait was expected to end (`since` + the asked minutes, else the `limits.waiting.defaultMinutes`). */
    val untilAt: Long,
    /** The flag counts as set until this time (`untilAt` + `limits.waiting.graceMinutes`); after it the session counts as silent again. */
    val expiresAt: Long,
    /** True when `limits.waiting.clearOnOutput` is on: the session's first output after [since] + the output grace clears the flag, not only the next turn. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val clearOnOutput: Boolean = false,
    /** Output within this many ms after [since] is the flagging command's own answer, not a sign of work. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val outputGraceMs: Long = 0,
) {
    /** In force at [now]: set, not past its expiry. */
    fun active(now: Long): Boolean = now <= expiresAt
}

/** `infantry waiting on|off|get` / `infantry_waiting` / the `waiting` frame: one request for the frame's session. */
@Serializable
data class WaitingRequest(
    /** `on`, `off` or `get`. */
    val op: String,
    val reason: String? = null,
    /** Expected wait in minutes; omitted = the default setting. */
    val minutes: Int? = null,
)

/** The pure rules of the waiting flag. No clock, no I/O. */
object WaitingRules {
    val OPS = setOf("on", "off", "get")

    /** The settings the rules read, resolved by the caller (named limits `limits.waiting.*`). */
    data class Config(val defaultMinutes: Long, val graceMinutes: Long, val clearOnOutput: Boolean, val outputGraceSeconds: Long, val maxMinutes: Long, val reasonChars: Int)

    /** The next value (null = clear). Throws [IllegalArgumentException] for a malformed request. */
    fun apply(previous: SessionWaiting?, req: WaitingRequest, now: Long, cfg: Config): SessionWaiting? {
        require(req.op in OPS) { "unknown waiting op '${req.op.take(40)}' (${OPS.joinToString(", ")})" }
        return when (req.op) {
            "get" -> previous
            "off" -> null
            else -> {
                val reason = req.reason?.trim().orEmpty()
                require(reason.isNotEmpty()) { "waiting on needs a reason (what you are waiting for)" }
                val minutes = req.minutes?.toLong() ?: cfg.defaultMinutes
                require(minutes in 1..cfg.maxMinutes) { "minutes must be 1..${cfg.maxMinutes} (limits.waiting.maxMinutes)" }
                val until = now + minutes * 60_000L
                SessionWaiting(
                    reason = reason.take(cfg.reasonChars), since = now, untilAt = until, expiresAt = until + cfg.graceMinutes * 60_000L,
                    clearOnOutput = cfg.clearOnOutput, outputGraceMs = cfg.outputGraceSeconds * 1000L,
                )
            }
        }
    }

    /** [w] given the session's [lastOutputAt]: null once cleared by output. Expiry is left to [SessionWaiting.active]. */
    fun overlay(w: SessionWaiting?, lastOutputAt: Long?): SessionWaiting? {
        if (w == null) return null
        if (w.clearOnOutput && lastOutputAt != null && lastOutputAt > w.since + w.outputGraceMs) return null
        return w
    }

    /** One line for the CLI / tool answer. */
    fun line(w: SessionWaiting?, now: Long): String = when {
        w == null -> "not waiting"
        !w.active(now) -> "waiting EXPIRED ${(now - w.untilAt) / 60_000} min past the expected end: ${w.reason}"
        else -> "waiting ${(now - w.since) / 60_000} min of ~${(w.untilAt - w.since) / 60_000}: ${w.reason}"
    }
}
