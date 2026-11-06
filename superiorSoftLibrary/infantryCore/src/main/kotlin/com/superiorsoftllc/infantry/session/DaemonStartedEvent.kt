// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.session

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * BRIDGE-EVENTS-1: the named event `daemon.started`, published once when the daemon's port is bound after a start.
 *
 * Core only DETECTS (RESUME-NOTIFY-1's [ReconcileReport]): which sessions were mid-turn when the previous daemon stopped
 * ([ReconcileReport.cutTurns]: at rest now, their turn cut) and which kept running because their child outlived the daemon
 * ([ReconcileReport.orphanedIds]). What happens next (prompt them, tell someone) is a script's business: the Scripting plugin ships
 * `resume-after-restart` bound to this event; a developer turns it off or binds their own.
 *
 * Payload: `cutTurns` and `orphaned` are lists of [session] objects; `orphanedIds` is the plain id list. A session that is gone
 * from the store is left out. [starter] names who waits on a session (its ticket's starter, else its spawner), or null.
 */
object DaemonStartedEvent {
    const val NAME = "daemon.started"

    fun payload(report: ReconcileReport, get: (String) -> SessionRecord?, starter: (SessionRecord) -> String? = { null }): String {
        val cut = report.cutTurns.distinct().mapNotNull(get)
        val orphaned = report.orphanedIds.distinct().mapNotNull(get)
        return buildJsonObject {
            put("cutTurns", JsonArray(cut.map { session(it, starter(it)) }))
            put("orphaned", JsonArray(orphaned.map { session(it, starter(it)) }))
            put("orphanedIds", buildJsonArray { orphaned.forEach { add(JsonPrimitive(it.id)) } })
        }.toString()
    }

    /** One session as the event names it: sessionId, role, agentId, ticketId, projectId, lastAction, lastOutputAt, starter (absent = unknown). */
    fun session(s: SessionRecord, starter: String? = null): JsonObject = buildJsonObject {
        put("sessionId", JsonPrimitive(s.id))
        s.role?.let { put("role", JsonPrimitive(it)) }
        s.agentId?.let { put("agentId", JsonPrimitive(it)) }
        s.ticketId?.let { put("ticketId", JsonPrimitive(it)) }
        s.projectId?.let { put("projectId", JsonPrimitive(it)) }
        s.lastAction?.let { put("lastAction", JsonPrimitive(it)) }
        s.lastOutputAt?.let { put("lastOutputAt", JsonPrimitive(it)) }
        starter?.takeIf { it.isNotBlank() && it != s.id }?.let { put("starter", JsonPrimitive(it)) }
    }
}
