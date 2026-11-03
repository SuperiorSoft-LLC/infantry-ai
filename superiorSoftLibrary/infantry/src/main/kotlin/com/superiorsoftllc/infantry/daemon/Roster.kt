// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.mailbox.Mailbox
import com.superiorsoftllc.infantry.protocol.AgentProfile
import com.superiorsoftllc.infantry.session.SessionRecord
import com.superiorsoftllc.infantry.session.isAvailable
import com.superiorsoftllc.infantry.session.isBusy
import com.superiorsoftllc.infantry.session.isRunning
import com.superiorsoftllc.infantry.session.isInSession
import kotlinx.serialization.Serializable

/**
 * ROSTER-1: the pure "who's here" computation: one row per live agent of a project.
 *
 * It is deliberately free of the daemon, the wire and the UI: the caller passes the profiles of the
 * project, every live session and the mailbox, and gets back the rows to show. The status rules are
 * the mail router's own (the shared `isBusy` / `isAvailable` helpers in the session package), so a
 * "here" is exactly an agent a queued message can wake.
 */

/** One agent profile together with the project it belongs to. */
data class ProjectAgent(
    val projectId: String,
    val agentId: String,
    val name: String,
    val role: String,
)

/**
 * The project-agent rule, in one named place (BUG-ROLE-1, owner's rule, 2026-10-20).
 *
 * A project's agents are its live, in-session agents: the sessions opened in it ("New Agent", with or without a
 * profile, and ticket workers) that a message can still reach -- in flight, resumable, or bound and never run.
 * This is the same set MailRouter resolves a role among (BUG-MAIL-2), so what a session SEES in `agents --live`
 * is exactly what it can mail. The profile store ("New Profile") is never a source: a profile with no session in
 * the project is not listed, whatever ticket or binding names it.
 *
 * One row per agent: a profile-backed session is keyed by its agent id, a role-only one (no agent id) by its
 * session id, which is also the address mail takes for it. A session carrying a profile bound to ANOTHER project
 * is never this project's agent (CP-3), whatever its own projectId says.
 */
fun projectAgents(
    projectId: String,
    sessions: List<SessionRecord>,
    agentLookup: (String) -> AgentProfile?,
): List<ProjectAgent> = sessions
    .filter { it.projectId == projectId && it.isInSession() }
    .sortedByDescending { it.spawnedAt }
    .mapNotNull { s ->
        val agentId = s.agentId?.takeIf { it.isNotBlank() }
        val profile = agentId?.let(agentLookup)
        if (profile != null && !profile.belongsTo(projectId)) return@mapNotNull null
        val role = s.role?.takeIf { it.isNotBlank() } ?: profile?.role.orEmpty()
        ProjectAgent(projectId, agentId ?: s.id, profile?.name ?: role.ifEmpty { s.id }, role)
    }
    .distinctBy { it.agentId }

/** CP-3: a legacy profile (no project) may sit in any project's session; a bound one only in its own project. */
private fun AgentProfile.belongsTo(projectId: String): Boolean = this.projectId == null || this.projectId == projectId

/** One row of a project's roster; this is the wire shape the `roster` frame lists. */
@Serializable
data class RosterRow(
    val agentId: String,
    val name: String,
    val role: String,
    val status: RosterStatus,
    val sessionId: String? = null,
    val title: String? = null,
    val pending: Int = 0,
)

/**
 * Where an agent is: [BUSY] has a run in flight, [IDLE] is at rest and resumable (mail can wake it),
 * [OFFLINE] has no session to wake -- or none that can come back.
 */
@Serializable
enum class RosterStatus { BUSY, IDLE, OFFLINE }

/**
 * The roster of project [projectId]: one [RosterRow] per [profiles] entry belonging to that project,
 * joined to [sessions] (by agent id, or session id for a role-only session) and to [mailbox] (mail waiting for the agent).
 *
 * An agent's session is the run in flight when it has one, else its most recently spawned session.
 * The row's status then follows the mail router's shared rules ([isBusy] / [isAvailable]): BUSY, or
 * IDLE when a queued message can wake it, else OFFLINE -- no session, or one whose console never
 * came back and so cannot be resumed. [pending] counts the mail queued for the agent id or for its
 * current session, whichever address the sender used.
 *
 * [titles] maps a session id to the title of the work it is on (its ticket's title, or a session
 * title); the row's `title` is null when the caller supplies none.
 */
fun roster(
    projectId: String,
    profiles: List<ProjectAgent>,
    sessions: List<SessionRecord>,
    mailbox: Mailbox,
    titles: Map<String, String> = emptyMap(),
): List<RosterRow> = profiles
    .filter { it.projectId == projectId }
    .map { profile ->
        val session = sessions
            .filter { (it.agentId ?: it.id) == profile.agentId }
            .sortedByDescending { it.spawnedAt }
            .let { mine -> mine.firstOrNull { it.isRunning() } ?: mine.firstOrNull() }
        val status = when {
            session == null -> RosterStatus.OFFLINE
            session.isRunning() -> RosterStatus.BUSY
            session.isAvailable() -> RosterStatus.IDLE
            else -> RosterStatus.OFFLINE
        }
        RosterRow(
            agentId = profile.agentId,
            name = profile.name,
            role = profile.role,
            status = status,
            sessionId = session?.id,
            title = session?.let { titles[it.id] },
            pending = mailbox.pendingCount(profile.agentId) +
                (session?.let { mailbox.pendingCount(it.id) } ?: 0),
        )
    }
