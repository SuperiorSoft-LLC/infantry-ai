// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.news

import com.superiorsoftllc.infantry.plugin.api.ImplementationDeclaration
import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.ui.CHAT_CARDS
import com.superiorsoftllc.infantry.ui.plugin.InfantryModule
import com.superiorsoftllc.infantry.ui.plugin.ModuleSource
import com.superiorsoftllc.infantry.ui.plugin.UiPlugin
import com.superiorsoftllc.infantry.ui.plugin.UiRegistrar

/** NEWS-1: the card type this module draws (```card:news). */
const val NEWS_CARD = "news"

/** NEWS-1: Infantry_News as a compiled-in module (web and Android, D9; the manifest mirrors module.json's UI part). */
val InfantryNews = InfantryModule(
    ModuleManifest(
        name = "Infantry_News", version = "1.0.0", author = "Infantry AI", license = "Apache-2.0", description = "News headline cards.",
        implementations = listOf(ImplementationDeclaration(CHAT_CARDS, NEWS_CARD)),
    ),
    implementations = mapOf(CHAT_CARDS to mapOf(NEWS_CARD to NewsCardRenderer)),
)

/** NEWS-1: the entry of Infantry_News' UI half (module.json `entry`): desktop loads it from its jar, web and Android compile it in. */
class NewsPlugin : UiPlugin, ModuleSource {
    override fun register(registrar: UiRegistrar) {
        registrar.implementation(CHAT_CARDS, NEWS_CARD, NewsCardRenderer)
    }

    override fun modules(): List<InfantryModule> = listOf(InfantryNews)
}
