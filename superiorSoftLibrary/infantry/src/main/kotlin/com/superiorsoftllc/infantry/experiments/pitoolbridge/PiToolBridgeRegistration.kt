// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.experiments.pitoolbridge

import com.superiorsoftllc.infantry.harness.HarnessLaunchExtras
import com.superiorsoftllc.infantry.stats.SessionStatsService

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
