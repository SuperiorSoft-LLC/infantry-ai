package com.superiorsoftllc.agenthq.ui.plugin

import java.util.ServiceLoader

internal actual fun discoverModuleSources(): List<ModuleSource> =
    ServiceLoader.load(ModuleSource::class.java, ModuleSource::class.java.classLoader).toList()
