package com.superiorsoftllc.agenthq.plugins.core

import com.superiorsoftllc.agenthq.ui.net.Controller
import com.superiorsoftllc.agenthq.ui.plugin.TabContext

/**
 * S4 (docs/SHELL-LEAKS.md): the window's controller in a core module's tab. The shell gives it to core-tier contexts
 * only ([TabContext.coreController]); every module here is core tier, so a null is a wiring bug, not a refusal.
 */
internal val TabContext.appController: Controller
    get() = checkNotNull(coreController) { "a core module's tab got a community module's context" }
