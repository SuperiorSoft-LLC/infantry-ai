// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.ui.protocol.AgentProfile

// RUNTIME-13: kit, out of Infantry_Board, since the official Infantry_Triggers jar names a trigger's target the same way.

/** What a ticket's assignee reads as: a profile's name when the id is one, else the raw `role:<role>` / id. */
fun assigneeLabel(profiles: List<AgentProfile>, agentId: String?): String = when {
    agentId == null -> "unassigned"
    else -> profiles.firstOrNull { it.id == agentId }?.name ?: agentId
}
