// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.wiring

import com.superiorsoftllc.infantry.agents.AgentProfiles
import com.superiorsoftllc.infantry.agents.KnowledgeCounter
import com.superiorsoftllc.infantry.agents.SkillsSource
import com.superiorsoftllc.infantry.backlog.TicketEventLog
import com.superiorsoftllc.infantry.backlog.TicketImages
import com.superiorsoftllc.infantry.daemon.BoardPorts
import com.superiorsoftllc.infantry.daemon.DoneWorkerReaper
import com.superiorsoftllc.infantry.daemon.TicketServices
import com.superiorsoftllc.infantry.daemon.plugins.LocalBoardHost
import com.superiorsoftllc.infantry.persistence.InfantryStore
import com.superiorsoftllc.infantry.session.SessionManager
import com.superiorsoftllc.infantry.settings.SettingsService
import com.superiorsoftllc.infantry.ticketfiles.RelatedDescription
import com.superiorsoftllc.infantry.ticketfiles.RelatedWorkSource
import com.superiorsoftllc.infantry.triggers.TriggerService
import com.superiorsoftllc.infantry.worktree.TicketBaseRef
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Board-port wiring (CORE-SPLIT-4a2): the core's ticket services and the core-built [LocalBoardHost]. Moved verbatim from DaemonMain. */
object BoardWiring {
    /** What [buildTicketServices] builds. */
    class Core(val events: TicketEventLog, val services: TicketServices)

    /** The typed durable ticket-event log, the ticket files and the three core event consumers over it; null without a board. */
    fun buildTicketServices(
        withBoard: Boolean, store: InfantryStore, settings: SettingsService, triggers: TriggerService, doneWorkerReaper: DoneWorkerReaper,
        relatedRef: AtomicReference<RelatedWorkSource>, relatedDescriptionRef: AtomicReference<RelatedDescription>, boardPorts: BoardPorts,
    ): Core? {
        val ticketEvents: TicketEventLog? = if (!withBoard) null else TicketServices.eventLog(store)
        return ticketEvents?.let { events ->
            Core(events, TicketServices.create(store, events, settings, triggers, doneWorkerReaper, relatedRef, relatedDescriptionRef) { projectId -> boardPorts.cutRepo(projectId)?.let(::File) })
        }
    }

    /** The core-built host over the event log (the plugin builds its TicketBoard on it through the board runtime). */
    fun buildHost(
        events: TicketEventLog?, store: InfantryStore, manager: SessionManager, baseRef: TicketBaseRef,
        projectDirectory: (String?) -> String?, repo: File?, agents: AgentProfiles, knowledge: KnowledgeCounter, skills: SkillsSource,
        ticketImages: TicketImages,
        runners: com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerHub? = null,
        pushOnCommit: () -> Boolean = { false },
        remotePlaced: (String, com.superiorsoftllc.infantry.runners.RemoteSessionMirror.Placed) -> Unit = { _, _ -> },
    ): LocalBoardHost? = events?.let { log ->
        LocalBoardHost(
            store = store, sessionManager = { manager }, baseRef = baseRef, projectDirectory = projectDirectory,
            defaultRepo = repo, eventLog = { log }, agentProfiles = agents, knowledge = knowledge, skills = skills, ticketImages = ticketImages,
            runners = runners, pushOnCommit = pushOnCommit, remotePlaced = remotePlaced,
        )
    }
}
