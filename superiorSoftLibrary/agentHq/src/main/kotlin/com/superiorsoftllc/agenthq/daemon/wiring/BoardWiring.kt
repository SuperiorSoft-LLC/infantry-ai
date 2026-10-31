package com.superiorsoftllc.agenthq.daemon.wiring

import com.superiorsoftllc.agenthq.agents.AgentProfiles
import com.superiorsoftllc.agenthq.agents.KnowledgeCounter
import com.superiorsoftllc.agenthq.agents.SkillsSource
import com.superiorsoftllc.agenthq.backlog.TicketEventLog
import com.superiorsoftllc.agenthq.backlog.TicketImages
import com.superiorsoftllc.agenthq.daemon.BoardPorts
import com.superiorsoftllc.agenthq.daemon.DoneWorkerReaper
import com.superiorsoftllc.agenthq.daemon.TicketServices
import com.superiorsoftllc.agenthq.daemon.plugins.LocalBoardHost
import com.superiorsoftllc.agenthq.persistence.AgentHqStore
import com.superiorsoftllc.agenthq.session.SessionManager
import com.superiorsoftllc.agenthq.settings.SettingsService
import com.superiorsoftllc.agenthq.ticketfiles.RelatedDescription
import com.superiorsoftllc.agenthq.ticketfiles.RelatedWorkSource
import com.superiorsoftllc.agenthq.triggers.TriggerService
import com.superiorsoftllc.agenthq.worktree.TicketBaseRef
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Board-port wiring (CORE-SPLIT-4a2): the core's ticket services and the core-built [LocalBoardHost]. Moved verbatim from DaemonMain. */
object BoardWiring {
    /** What [buildTicketServices] builds. */
    class Core(val events: TicketEventLog, val services: TicketServices)

    /** The typed durable ticket-event log, the ticket files and the three core event consumers over it; null without a board. */
    fun buildTicketServices(
        withBoard: Boolean, store: AgentHqStore, settings: SettingsService, triggers: TriggerService, doneWorkerReaper: DoneWorkerReaper,
        relatedRef: AtomicReference<RelatedWorkSource>, relatedDescriptionRef: AtomicReference<RelatedDescription>, boardPorts: BoardPorts,
    ): Core? {
        val ticketEvents: TicketEventLog? = if (!withBoard) null else TicketServices.eventLog(store)
        return ticketEvents?.let { events ->
            Core(events, TicketServices.create(store, events, settings, triggers, doneWorkerReaper, relatedRef, relatedDescriptionRef) { projectId -> boardPorts.cutRepo(projectId)?.let(::File) })
        }
    }

    /** The core-built host over the event log (the plugin builds its TicketBoard on it through the board runtime). */
    fun buildHost(
        events: TicketEventLog?, store: AgentHqStore, manager: SessionManager, baseRef: TicketBaseRef,
        projectDirectory: (String?) -> String?, repo: File?, agents: AgentProfiles, knowledge: KnowledgeCounter, skills: SkillsSource,
        ticketImages: TicketImages,
    ): LocalBoardHost? = events?.let { log ->
        LocalBoardHost(
            store = store, sessionManager = { manager }, baseRef = baseRef, projectDirectory = projectDirectory,
            defaultRepo = repo, eventLog = { log }, agentProfiles = agents, knowledge = knowledge, skills = skills, ticketImages = ticketImages,
        )
    }
}
