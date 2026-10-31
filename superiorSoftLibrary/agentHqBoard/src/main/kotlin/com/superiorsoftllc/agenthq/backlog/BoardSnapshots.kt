package com.superiorsoftllc.agenthq.backlog

import com.superiorsoftllc.agenthq.daemon.api.board.BoardRoleInstance
import com.superiorsoftllc.agenthq.protocol.TraitCheck
import com.superiorsoftllc.agenthq.session.TraitSnapshot

/** The host's trait snapshots as the first-turn composer reads them. */
fun BoardRoleInstance.toSnapshots() =
    traits.map { t -> TraitSnapshot(t.id, t.version, t.name, t.body, t.checkKind?.let { TraitCheck(it, t.checkReportTo ?: TraitCheck.DEFAULT_REPORT_TO, t.checkFields, t.checkReprompt) }) }
