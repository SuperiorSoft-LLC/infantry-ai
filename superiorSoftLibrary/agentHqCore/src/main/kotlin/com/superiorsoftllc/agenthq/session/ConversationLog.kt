package com.superiorsoftllc.agenthq.session

import com.superiorsoftllc.agenthq.harness.CarriedTurn
import com.superiorsoftllc.agenthq.protocol.EventNormalizer

/**
 * HARNESS-CARRY-1: each session's conversation as AgentHQ saw it -- every turn's prompt and the visible text of its
 * reply -- independent of which CLI produced it. It is what a provider switch carries to the next harness
 * ([com.superiorsoftllc.agenthq.harness.ConversationCarry]); the provider's own conversation store belongs to that CLI.
 *
 * In memory, like the daemon's replay buffer (the transcript a client can see), so it lives as long as this daemon
 * process. Bounded: the last [MAX_TURNS] turns per session, [MAX_REPLY_CHARS] of each reply.
 */
class ConversationLog {
    private class Turn(val prompt: String) {
        val reply = StringBuilder()
    }

    private val turns = HashMap<String, ArrayDeque<Turn>>()

    /** GAP 855db590: per session, how many assistant text / tool-call events it produced in all (not bounded by [MAX_TURNS]). */
    private val workEvents = HashMap<String, Int>()

    /** A turn was accepted on [sessionId] with [prompt] (what the user asked, as the transcript shows it). */
    @Synchronized
    fun turn(sessionId: String, prompt: String) {
        val log = turns.getOrPut(sessionId) { ArrayDeque() }
        log.addLast(Turn(prompt))
        while (log.size > MAX_TURNS) log.removeFirst()
    }

    /** One provider stdout [line] of [sessionId]'s current turn: its visible assistant text and tool use are kept. */
    fun output(sessionId: String, line: String) {
        val visible = EventNormalizer.normalise(line).mapNotNull {
            when (it) {
                is EventNormalizer.Line.Text -> it.delta
                is EventNormalizer.Line.Tool -> "\n[tool] ${it.activity ?: it.name}\n"
                else -> null
            }
        }
        if (visible.isEmpty()) return
        synchronized(this) {
            workEvents.merge(sessionId, visible.size, Int::plus)
            val reply = turns[sessionId]?.lastOrNull()?.reply ?: return
            // Text deltas append as streamed (a delta may be one token); a tool line stands on its own line.
            for (text in visible) {
                if (reply.length >= MAX_REPLY_CHARS) break
                reply.append(text.take(MAX_REPLY_CHARS - reply.length))
            }
        }
    }

    /** [sessionId]'s exchanges so far, oldest first. */
    @Synchronized
    fun history(sessionId: String): List<CarriedTurn> =
        turns[sessionId].orEmpty().map { CarriedTurn(it.prompt, it.reply.toString().replace(Regex("\n{2,}"), "\n").trim()) }

    /** GAP 855db590: how many assistant outputs and tool calls [sessionId] produced while this daemon watched it (0 = silent). */
    @Synchronized
    fun workEvents(sessionId: String): Int = workEvents[sessionId] ?: 0

    @Synchronized
    fun drop(sessionId: String) {
        turns.remove(sessionId)
        workEvents.remove(sessionId)
    }

    companion object {
        const val MAX_TURNS = 200
        const val MAX_REPLY_CHARS = 20_000
    }
}
