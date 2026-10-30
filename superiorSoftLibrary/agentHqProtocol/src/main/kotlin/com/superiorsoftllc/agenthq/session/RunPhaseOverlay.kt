package com.superiorsoftllc.agenthq.session

/** The sessions whose run is in a named phase right now (`compacting`), kept by the run tracker, read when a record goes on the wire. */
object RunPhaseOverlay {
    private val phases = java.util.concurrent.ConcurrentHashMap<String, String>()
    fun phaseOf(sessionId: String): String? = phases[sessionId]
    fun set(sessionId: String, phase: String?) { if (phase == null) phases.remove(sessionId) else phases[sessionId] = phase }
}
