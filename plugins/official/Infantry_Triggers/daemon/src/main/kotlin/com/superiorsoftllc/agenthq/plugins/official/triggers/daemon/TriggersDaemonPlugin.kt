package com.superiorsoftllc.agenthq.plugins.official.triggers.daemon

import com.superiorsoftllc.agenthq.daemon.api.DaemonApi
import com.superiorsoftllc.agenthq.daemon.api.DaemonPlugin

/** TRIG-2b: adopts the trigger tables and offers [StorageTriggerStore]; nothing is registered yet (TRIG-2c moves the service in). */
class TriggersDaemonPlugin : DaemonPlugin {
    override fun start(api: DaemonApi) {
        StorageTriggerStore(api.storage)
    }
}
