// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.backlog

import com.superiorsoftllc.infantry.persistence.TicketLinkRow
import com.superiorsoftllc.infantry.persistence.TicketRow
import com.superiorsoftllc.infantry.ticketfiles.TicketInfo
import com.superiorsoftllc.infantry.ticketfiles.TicketLinkInfo

fun TicketRow.toInfo() = TicketInfo(id, title, description, status, projectId, branch, base)

fun TicketLinkRow.toInfo() = TicketLinkInfo(ticketId, relatedId)
