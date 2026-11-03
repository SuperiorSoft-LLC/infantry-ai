// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.model

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * RUN-CONSOLE (contract 1.134.0): orders one run's `run_bytes` for a terminal emulator, whatever the platform's
 * emulator is (JediTerm on the desktop; a later xterm.js on the web). No Compose, no platform APIs.
 *
 * The console asks for a replay (`run_attach`) when it opens. Live chunks that arrive before the replay are held;
 * the replay is written, then the held and later chunks with a higher sequence, in order. A duplicate is dropped.
 * A hole (a chunk missed, e.g. across a reconnect) asks for a fresh replay through [reattach]; that replay
 * starts with a terminal reset, so the screen is redrawn from the scrollback instead of patched.
 */
class RunConsoleFeed(
    private val runId: String,
    private val write: (ByteArray) -> Unit,
    private val reattach: () -> Unit,
) {
    private var waiting = true
    private var replays = 0
    private var last = 0L
    private val held = mutableListOf<RunBytesPayload>()

    @OptIn(ExperimentalEncodingApi::class)
    private fun bytes(p: RunBytesPayload): ByteArray = runCatching { Base64.decode(p.data) }.getOrDefault(ByteArray(0))

    fun accept(p: RunBytesPayload) {
        if (p.runId != runId) return
        if (p.replay) {
            if (replays > 0) write(RESET)
            replays++
            write(bytes(p))
            last = p.sequence
            waiting = false
            val later = held.filter { it.sequence > last }.sortedBy { it.sequence }
            held.clear()
            later.forEach { live(it) }
            return
        }
        if (waiting) held += p else live(p)
    }

    private fun live(p: RunBytesPayload) {
        when {
            p.sequence <= last -> {} // already in the replay, or a duplicate
            p.sequence == last + 1 -> { write(bytes(p)); last = p.sequence }
            else -> { waiting = true; held += p; reattach() }
        }
    }

    companion object {
        /** ESC c: the terminal's full reset (RIS), before a second replay redraws the screen. */
        val RESET: ByteArray = byteArrayOf(0x1b, 'c'.code.toByte())
    }
}
