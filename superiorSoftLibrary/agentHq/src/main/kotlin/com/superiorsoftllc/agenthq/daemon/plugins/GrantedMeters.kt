package com.superiorsoftllc.agenthq.daemon.plugins

import com.superiorsoftllc.agenthq.daemon.api.usage.MeterReading
import com.superiorsoftllc.agenthq.daemon.api.usage.Meters

/**
 * PACE-TREND-1: the plan meters behind one plugin's grants. Official / core read freely; a community plugin needs the grant
 * `usage.read` (default deny: the call throws, and the plugin's own guard logs it). [may] answers whether a grant is held.
 */
class GrantedMeters(private val source: () -> Meters, private val may: (String) -> Boolean) : Meters {
    override fun read(): List<MeterReading> {
        check(may(GRANT)) { "missing grant '$GRANT' in modules.json" }
        return source().read()
    }

    companion object { const val GRANT = "usage.read" }
}
