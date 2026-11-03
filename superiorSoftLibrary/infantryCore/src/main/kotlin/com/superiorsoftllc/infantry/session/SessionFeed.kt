package com.superiorsoftllc.infantry.session

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.ConcurrentHashMap

/**
 * SCRIPT-SESSION-1: the chat of each session as a script may read it, and its activity as named events.
 * Lines (assistant text, tool calls, errors) go into a small bounded ring per session (memory only); the events
 * `session.tool_call`, `session.output` and `session.error` are published through [sink], rate limited per session and
 * name, so a chatty session cannot flood the bus. [sink] is called on the stream thread: it must only hand off.
 */
object SessionFeed {
    const val MAX_LINES = 200
    const val MAX_LINE_CHARS = 300
    /** Smallest gap between two events of the same name for one session, millis. */
    private val GAP = mapOf("session.tool_call" to 500L, "session.output" to 2_000L, "session.error" to 1_000L)

    @Volatile var sink: ((name: String, payload: String) -> Unit)? = null
    @Volatile var clock: () -> Long = System::currentTimeMillis

    private class Ring { val lines = ArrayDeque<String>(); val open = StringBuilder(); val last = HashMap<String, Long>() }
    private val rings = ConcurrentHashMap<String, Ring>()

    /** One tool call line, e.g. "Bash: gradlew test". */
    fun tool(sessionId: String, text: String) { add(sessionId, "[tool] $text"); emit(sessionId, "session.tool_call", "tool", text) }

    fun error(sessionId: String, text: String) { add(sessionId, "[error] $text"); emit(sessionId, "session.error", "text", text) }

    /** A streamed assistant text delta; deltas join into lines at each newline. */
    fun text(sessionId: String, delta: String) {
        val r = rings.computeIfAbsent(sessionId) { Ring() }
        val tail = synchronized(r) {
            r.open.append(delta)
            while (true) {
                val i = r.open.indexOf("\n")
                if (i < 0) break
                r.open.substring(0, i).takeIf { it.isNotBlank() }?.let { push(r, it) }
                r.open.delete(0, i + 1)
            }
            if (r.open.length > MAX_LINE_CHARS * 4) { push(r, r.open.toString()); r.open.setLength(0) }
            r.lines.lastOrNull().orEmpty().let { last -> r.open.toString().takeLast(MAX_LINE_CHARS).ifBlank { last } }
        }
        if (tail.isNotBlank()) emit(sessionId, "session.output", "text", tail)
    }

    /** The last [max] lines (capped at [MAX_LINES]) of [sessionId], oldest first, the unfinished line last. */
    fun recent(sessionId: String, max: Int): List<String> {
        val r = rings[sessionId] ?: return emptyList()
        return synchronized(r) {
            (r.lines.toList() + listOfNotNull(r.open.toString().takeIf { it.isNotBlank() }?.take(MAX_LINE_CHARS))).takeLast(max.coerceIn(1, MAX_LINES))
        }
    }

    fun forget(sessionId: String) { rings.remove(sessionId) }
    fun clear() { rings.clear(); sink = null; clock = System::currentTimeMillis }

    private fun add(sessionId: String, line: String) { val r = rings.computeIfAbsent(sessionId) { Ring() }; synchronized(r) { push(r, line) } }
    private fun push(r: Ring, line: String) { r.lines.addLast(line.take(MAX_LINE_CHARS)); while (r.lines.size > MAX_LINES) r.lines.removeFirst() }

    private fun emit(sessionId: String, name: String, key: String, value: String) {
        val out = sink ?: return
        val r = rings[sessionId] ?: return
        val now = clock()
        synchronized(r) { if (now - (r.last[name] ?: Long.MIN_VALUE / 2) < (GAP[name] ?: 0L)) return; r.last[name] = now }
        val payload = JsonObject(mapOf("sessionId" to JsonPrimitive(sessionId), key to JsonPrimitive(value.take(MAX_LINE_CHARS)))).toString()
        runCatching { out(name, payload) }
    }
}
