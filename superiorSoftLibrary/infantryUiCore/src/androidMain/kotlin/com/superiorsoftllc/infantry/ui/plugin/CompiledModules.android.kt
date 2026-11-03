// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.plugin

import java.util.ServiceLoader

internal actual fun discoverModuleSources(): List<ModuleSource> =
    ServiceLoader.load(ModuleSource::class.java, ModuleSource::class.java.classLoader).toList()
