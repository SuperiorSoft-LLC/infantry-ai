package com.superiorsoftllc.agenthq.daemon

import com.superiorsoftllc.agenthq.harness.pi.PiHarness
import com.superiorsoftllc.agenthq.harness.pi.PiLaunchExtras
import com.superiorsoftllc.agenthq.harness.pi.PiTodoExtension
import java.io.File

/**
 * HARNESS H6b: the daemon's own implementations of the harness contributions (launch extras, watch, approval exemption),
 * registered in ONE place. DaemonMain names no harness; a harness id appears here only through its module's constant.
 */
object CoreHarnessContributions {
    fun register(stateDir: File) {
        val dir = File(stateDir, PiHarness.HARNESS_ID)
        val todo = PiTodoExtension.launchArgs(dir)
        val watch = WatchHooks.piLaunchArgs(dir)
        PiLaunchExtras.builtIn = { d, env -> todo(d, env) + watch(d, env) }
        // The pi watch hook runs whatever the approval mode (pi has no approval prompt of its own).
        WatchHooks.approvalExempt = setOf(PiHarness.HARNESS_ID)
    }
}
