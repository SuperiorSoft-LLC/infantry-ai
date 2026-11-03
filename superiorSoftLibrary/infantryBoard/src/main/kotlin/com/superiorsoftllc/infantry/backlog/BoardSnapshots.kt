// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.backlog

import com.superiorsoftllc.infantry.daemon.api.board.BoardRoleInstance
import com.superiorsoftllc.infantry.protocol.TraitCheck
import com.superiorsoftllc.infantry.session.TraitSnapshot

/** The host's trait snapshots as the first-turn composer reads them. */
fun BoardRoleInstance.toSnapshots() =
    traits.map { t -> TraitSnapshot(t.id, t.version, t.name, t.body, t.checkKind?.let { TraitCheck(it, t.checkReportTo ?: TraitCheck.DEFAULT_REPORT_TO, t.checkFields, t.checkReprompt) }) }
