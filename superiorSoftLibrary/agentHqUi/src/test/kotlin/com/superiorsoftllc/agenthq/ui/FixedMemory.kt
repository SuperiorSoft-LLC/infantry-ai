package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.session.MemorySource
import com.superiorsoftllc.agenthq.session.ProcMem

/**
 * A fixed 8 GB free of 16 GB for every UI test that boots the real daemon through `AgentHqDaemon.build`, so a loaded host
 * (< 4 GB free) never pushes the ROGUE-1 "low memory" notice row into the chat pane. Pass it as `memory = FixedMemory`.
 */
object FixedMemory : MemorySource {
    override fun freeMb(): Long = 8192
    override fun totalMb(): Long = 16384
    override fun processes(): List<ProcMem> = emptyList()
}
