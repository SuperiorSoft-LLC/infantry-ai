// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.ticker

import com.superiorsoftllc.infantry.plugin.api.ImplementationDeclaration
import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.ui.CHAT_CARDS
import com.superiorsoftllc.infantry.ui.plugin.InfantryModule
import com.superiorsoftllc.infantry.ui.plugin.ModuleSource
import com.superiorsoftllc.infantry.ui.plugin.UiPlugin
import com.superiorsoftllc.infantry.ui.plugin.UiRegistrar

/** CARD-UPDATE-1: the card type this module draws (```card:ticker). */
const val TICKER_CARD = "ticker"

/** CARD-UPDATE-1: Infantry_Ticker as a compiled-in module (web and Android, D9; the manifest mirrors module.json's UI part). */
val InfantryTicker = InfantryModule(
    ModuleManifest(
        name = "Infantry_Ticker", version = "1.0.0", author = "Infantry AI", license = "Apache-2.0", description = "Ticker cards: a value over time.",
        implementations = listOf(ImplementationDeclaration(CHAT_CARDS, TICKER_CARD)),
    ),
    implementations = mapOf(CHAT_CARDS to mapOf(TICKER_CARD to TickerCardRenderer)),
)

/** CARD-UPDATE-1: the entry of Infantry_Ticker (module.json `entry`): desktop loads it from its jar, web and Android compile it in. */
class TickerPlugin : UiPlugin, ModuleSource {
    override fun register(registrar: UiRegistrar) {
        registrar.implementation(CHAT_CARDS, TICKER_CARD, TickerCardRenderer)
    }

    override fun modules(): List<InfantryModule> = listOf(InfantryTicker)
}
