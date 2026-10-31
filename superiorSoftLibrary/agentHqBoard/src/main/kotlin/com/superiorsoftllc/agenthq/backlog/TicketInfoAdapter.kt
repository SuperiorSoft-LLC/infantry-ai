package com.superiorsoftllc.agenthq.backlog

import com.superiorsoftllc.agenthq.persistence.TicketLinkRow
import com.superiorsoftllc.agenthq.persistence.TicketRow
import com.superiorsoftllc.agenthq.ticketfiles.TicketInfo
import com.superiorsoftllc.agenthq.ticketfiles.TicketLinkInfo

fun TicketRow.toInfo() = TicketInfo(id, title, description, status, projectId, branch, base)

fun TicketLinkRow.toInfo() = TicketLinkInfo(ticketId, relatedId)
