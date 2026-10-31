package com.superiorsoftllc.agenthq.backlog

import com.superiorsoftllc.agenthq.daemon.api.board.BoardRoleInstance
import com.superiorsoftllc.agenthq.daemon.api.board.BoardTrait
import com.superiorsoftllc.agenthq.protocol.TraitCheck
import com.superiorsoftllc.agenthq.session.RoleInstance
import com.superiorsoftllc.agenthq.session.TraitSnapshot

internal fun RoleInstance.toBoard() = BoardRoleInstance(
    agentId, role, instructions,
    traits.map { BoardTrait(it.id, it.version, it.name, it.body, it.check?.kind, it.check?.reportTo, it.check?.fields.orEmpty(), it.check?.reprompt ?: true) },
    skillIds,
)
