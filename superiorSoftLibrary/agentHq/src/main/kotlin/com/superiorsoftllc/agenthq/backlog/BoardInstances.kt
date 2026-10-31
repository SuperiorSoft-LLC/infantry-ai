package com.superiorsoftllc.agenthq.backlog

import com.superiorsoftllc.agenthq.daemon.api.board.BoardRoleInstance
import com.superiorsoftllc.agenthq.daemon.api.board.BoardTrait
import com.superiorsoftllc.agenthq.protocol.TraitCheck
import com.superiorsoftllc.agenthq.session.RoleInstance
import com.superiorsoftllc.agenthq.session.TraitSnapshot

/** DP-5f-3b2: the host's plain role instance as the composers and the session record still read it (until part c). */
internal fun BoardRoleInstance.toCore() = RoleInstance(
    agentId, role, instructions,
    traits.map { t -> TraitSnapshot(t.id, t.version, t.name, t.body, t.checkKind?.let { TraitCheck(it, t.checkReportTo ?: TraitCheck.DEFAULT_REPORT_TO, t.checkFields, t.checkReprompt) }) },
    skillIds,
)

internal fun RoleInstance.toBoard() = BoardRoleInstance(
    agentId, role, instructions,
    traits.map { BoardTrait(it.id, it.version, it.name, it.body, it.check?.kind, it.check?.reportTo, it.check?.fields.orEmpty(), it.check?.reprompt ?: true) },
    skillIds,
)
