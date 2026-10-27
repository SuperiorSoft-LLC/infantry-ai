package com.superiorsoftllc.agenthq.ui.plugin

/** The web compiles its modules in and installs them ([CompiledModules.install]); there is nothing to discover. */
internal actual fun discoverModuleSources(): List<ModuleSource> = emptyList()
