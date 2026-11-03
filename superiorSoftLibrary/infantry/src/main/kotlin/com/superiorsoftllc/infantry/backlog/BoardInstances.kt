package com.superiorsoftllc.infantry.backlog

import com.superiorsoftllc.infantry.daemon.api.board.BoardRoleInstance
import com.superiorsoftllc.infantry.daemon.api.board.BoardTrait
import com.superiorsoftllc.infantry.protocol.TraitCheck
import com.superiorsoftllc.infantry.session.RoleInstance
import com.superiorsoftllc.infantry.session.TraitSnapshot

internal fun RoleInstance.toBoard() = BoardRoleInstance(
    agentId, role, instructions,
    traits.map { BoardTrait(it.id, it.version, it.name, it.body, it.check?.kind, it.check?.reportTo, it.check?.fields.orEmpty(), it.check?.reprompt ?: true) },
    skillIds,
)
