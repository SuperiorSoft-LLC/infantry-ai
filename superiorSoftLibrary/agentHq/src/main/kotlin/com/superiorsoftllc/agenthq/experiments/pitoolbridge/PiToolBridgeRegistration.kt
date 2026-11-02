package com.superiorsoftllc.agenthq.experiments.pitoolbridge

import com.superiorsoftllc.agenthq.harness.HarnessLaunchExtras
import com.superiorsoftllc.agenthq.stats.SessionStatsService

/**
 * THE ONE REGISTRATION POINT. The daemon calls [register] once at startup; that call (one line in `DaemonMain`) is the only
 * place outside this package that names it. Registering installs hooks only: with the switch off they return nothing.
 */
object PiToolBridgeRegistration {
    fun register(projectSwitch: (projectId: String?) -> Boolean, stats: SessionStatsService) {
        PiToolBridge.projectSwitch = projectSwitch
        HarnessLaunchExtras.add("pi", PiToolBridge::launchArgs)
        stats.addTagger(PiToolBridge::tagOf)
    }
}
