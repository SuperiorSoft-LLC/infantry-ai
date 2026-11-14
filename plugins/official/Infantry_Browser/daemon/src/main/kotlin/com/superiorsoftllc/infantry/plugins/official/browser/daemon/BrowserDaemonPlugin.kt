// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.browser.daemon

import com.superiorsoftllc.infantry.daemon.api.DaemonApi
import com.superiorsoftllc.infantry.daemon.api.DaemonPlugin

/**
 * BROWSER-1: the browser for agents. The plugin's whole content is the MCP server it declares in module.json (`daemon.mcpServers`):
 * the host lists it in the MCP registry (source plugin) and starts nothing; an agent gets it only through a grant with a purpose.
 */
class BrowserDaemonPlugin : DaemonPlugin {
    override fun start(api: DaemonApi) = Unit
}
