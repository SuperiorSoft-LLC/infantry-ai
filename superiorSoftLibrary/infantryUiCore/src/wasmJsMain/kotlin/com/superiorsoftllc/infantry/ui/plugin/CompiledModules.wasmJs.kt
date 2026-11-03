// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.plugin

/** The web compiles its modules in and installs them ([CompiledModules.install]); there is nothing to discover. */
internal actual fun discoverModuleSources(): List<ModuleSource> = emptyList()
