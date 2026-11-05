// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.updater

import com.superiorsoftllc.infantry.plugin.api.ImplementationDeclaration
import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.ui.plugin.InfantryModule
import com.superiorsoftllc.infantry.ui.plugin.ModuleSource
import com.superiorsoftllc.infantry.ui.plugin.UiPlugin
import com.superiorsoftllc.infantry.ui.plugin.UiRegistrar
import com.superiorsoftllc.infantry.ui.shell.SELF_UPDATE_REGISTRY
import com.superiorsoftllc.infantry.ui.shell.SelfUpdateProvider

/** UPDATER-1: the [SELF_UPDATE_REGISTRY] key this module fills: the phone's APK update. */
const val ANDROID_KEY = "android"

/** The platform's self-update: Android asks the daemon's `GET /update/client` and installs the APK; elsewhere none. */
internal expect fun platformSelfUpdate(): SelfUpdateProvider

/** UPDATER-1: Infantry_Updater as a compiled-in module (web and Android, D9). */
val InfantryUpdater = InfantryModule(
    ModuleManifest(
        name = "Infantry_Updater", version = "1.0.0", author = "Infantry AI", description = "Updates.",
        implementations = listOf(ImplementationDeclaration(SELF_UPDATE_REGISTRY, ANDROID_KEY)),
    ),
    implementations = mapOf(SELF_UPDATE_REGISTRY to mapOf(ANDROID_KEY to platformSelfUpdate())),
)

/**
 * UPDATER-1: the entry of Infantry_Updater's UI half (module.json `entry`).
 *  - Desktop loads it from its jar ([UiPlugin]); the installed window keeps its own MSI updater, so this offers nothing there.
 *  - Web and Android compile it in from the generated module list ([ModuleSource]).
 */
class UpdaterPlugin : UiPlugin, ModuleSource {
    override fun register(registrar: UiRegistrar) {
        registrar.implementation(SELF_UPDATE_REGISTRY, ANDROID_KEY, platformSelfUpdate())
    }

    override fun modules(): List<InfantryModule> = listOf(InfantryUpdater)
}
