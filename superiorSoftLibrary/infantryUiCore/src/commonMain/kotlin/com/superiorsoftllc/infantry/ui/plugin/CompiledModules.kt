// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.plugin

/**
 * S4 (drop-in path): what supplies the modules compiled into a build. The shell names none: the app UI module
 * (infantryAppUi, its core modules in plugins/core) provides one. On the JVM and Android it is found through
 * `java.util.ServiceLoader` (`META-INF/services/com.superiorsoftllc.infantry.ui.plugin.ModuleSource`), so the desktop
 * shell needs the app UI only on its run-time classpath; the web entry installs it ([CompiledModules.install]).
 */
fun interface ModuleSource {
    fun modules(): List<InfantryModule>
}

/**
 * S4: the modules compiled into this build, in load order (formerly `CoreModules.all`, which now lives in the app UI).
 * Empty = the empty shell: its slots draw nothing ([com.superiorsoftllc.infantry.ui.shell.blocks.CoreShell.slots]).
 */
object CompiledModules {
    private var installed: List<InfantryModule>? = null

    /** The entry point's modules, before the first window; wins over discovery. */
    fun install(modules: List<InfantryModule>) {
        installed = modules
    }

    private val discovered: List<InfantryModule> by lazy { discoverModuleSources().flatMap { it.modules() } }

    val all: List<InfantryModule> get() = installed ?: discovered

    val factory: TabFactory by lazy { TabFactory(all) }
}

/** The [ModuleSource]s on this platform's class path (none where the platform has no service loader). */
internal expect fun discoverModuleSources(): List<ModuleSource>
