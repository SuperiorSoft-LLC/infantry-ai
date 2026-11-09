// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.mascot

import com.superiorsoftllc.infantry.plugin.api.ImplementationDeclaration
import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.ui.AGENT_AVATAR_RENDERERS
import com.superiorsoftllc.infantry.ui.plugin.InfantryModule
import com.superiorsoftllc.infantry.ui.plugin.ModuleSource
import com.superiorsoftllc.infantry.ui.plugin.UiPlugin
import com.superiorsoftllc.infantry.ui.plugin.UiRegistrar

/** MASCOT-2: the avatar style key this module fills. */
const val MASCOT_STYLE = "mascot"

/** MASCOT-2: Infantry_Mascot as a compiled-in module (web and Android, D9). */
val InfantryMascot = InfantryModule(
    ModuleManifest(
        name = "Infantry_Mascot", version = "1.0.1", author = "Infantry AI", license = "Apache-2.0", description = "Mascot avatars.",
        implementations = listOf(ImplementationDeclaration(AGENT_AVATAR_RENDERERS, MASCOT_STYLE)),
    ),
    implementations = mapOf(AGENT_AVATAR_RENDERERS to mapOf(MASCOT_STYLE to MascotAvatarRenderer)),
)

/** MASCOT-2: the entry of Infantry_Mascot's UI half (module.json `entry`): desktop loads it from its jar, web and Android compile it in. */
class MascotPlugin : UiPlugin, ModuleSource {
    override fun register(registrar: UiRegistrar) {
        registrar.implementation(AGENT_AVATAR_RENDERERS, MASCOT_STYLE, MascotAvatarRenderer)
    }

    override fun modules(): List<InfantryModule> = listOf(InfantryMascot)
}
