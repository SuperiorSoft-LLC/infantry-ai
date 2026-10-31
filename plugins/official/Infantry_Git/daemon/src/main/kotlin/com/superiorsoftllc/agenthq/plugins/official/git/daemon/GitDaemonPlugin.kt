package com.superiorsoftllc.agenthq.plugins.official.git.daemon

import com.superiorsoftllc.agenthq.daemon.api.DaemonApi
import com.superiorsoftllc.agenthq.daemon.api.DaemonPlugin

/**
 * Infantry_Git's daemon half. The project pipeline tools (RUNTIME-LOAD-8) moved to Infantry_Board with the pipeline (DP-6);
 * it registers nothing today.
 */
class GitDaemonPlugin : DaemonPlugin {
    override fun start(api: DaemonApi) {}
}
