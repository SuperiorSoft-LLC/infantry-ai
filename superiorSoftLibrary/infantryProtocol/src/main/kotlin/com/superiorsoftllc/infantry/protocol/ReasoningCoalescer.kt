// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.protocol

import java.util.concurrent.ConcurrentHashMap

/**
 * Joins a streamed reasoning block into ONE [EventNormalizer.Line.Reasoning].
 *
 * pi streams thinking as arbitrary-sized `thinking_delta`s, often mid-word, and [EventNormalizer] turns each into its own
 * line (flagged `delta`). Every consumer shows one reasoning frame as one line, so the deltas are held here, verbatim, per
 * session, and handed out as a single reasoning line when the block ends: the next line that is not a thinking delta
 * ([process]) or the run/session ending ([flush]). Whole-block reasoning (claude, opencode) is not flagged and passes through.
 */
class ReasoningCoalescer {
    private val pending = ConcurrentHashMap<String, StringBuilder>()

    /** [lines] is what the normaliser made of ONE raw line of [sessionId]; returns what to emit for it, in order. */
    fun process(sessionId: String, lines: List<EventNormalizer.Line>): List<EventNormalizer.Line> {
        val streamed = lines.isNotEmpty() && lines.all { it is EventNormalizer.Line.Reasoning && it.delta }
        if (streamed) {
            val buf = pending.getOrPut(sessionId) { StringBuilder() }
            synchronized(buf) { lines.forEach { buf.append((it as EventNormalizer.Line.Reasoning).text) } }
            return emptyList()
        }
        return listOfNotNull(flush(sessionId)) + lines
    }

    /** The held block of [sessionId] as one reasoning line, or null when nothing is held. */
    fun flush(sessionId: String): EventNormalizer.Line.Reasoning? {
        val buf = pending.remove(sessionId) ?: return null
        val text = synchronized(buf) { buf.toString() }
        return if (text.isEmpty()) null else EventNormalizer.Line.Reasoning(text)
    }
}
