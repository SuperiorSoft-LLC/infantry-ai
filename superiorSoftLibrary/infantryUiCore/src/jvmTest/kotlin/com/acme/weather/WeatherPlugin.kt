// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.acme.weather

import androidx.compose.runtime.Composable
import com.superiorsoftllc.infantry.plugin.api.ConfigFieldDeclaration
import com.superiorsoftllc.infantry.ui.block.layout.Block
import com.superiorsoftllc.infantry.ui.block.layout.BlockScope
import com.superiorsoftllc.infantry.ui.block.layout.config
import com.superiorsoftllc.infantry.ui.plugin.UiPlugin
import com.superiorsoftllc.infantry.ui.plugin.UiRegistrar
import com.superiorsoftllc.infantry.ui.plugin.config.FieldType
import com.superiorsoftllc.infantry.ui.shell.ConfigValues
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * CONFIG-7 fixture: the throwaway community plugin of docs/CONFIG-MANIFEST-DESIGN.md section 9. CommunityConfigDecouplingTest
 * packs these classes into a jar in a temp dir; nothing in the shell sources names this package.
 */
class WeatherPlugin : UiPlugin {
    override fun register(registrar: UiRegistrar) {
        registrar.blockType("acme.weather.badge") { id, _ -> WeatherBadge(id) }
        registrar.fieldType("stationPicker", StationPicker())
    }
}

/** The badge: draws its own resolved config, read through [config] like any block. */
class WeatherBadge(id: String) : Block("acme.weather.badge", id) {
    @Composable
    override fun Render(scope: BlockScope) {
        val cfg: ConfigValues = scope.config()
        label(cfg)
    }

    /** CONFIG-7b: names the ConfigValues type in a signature, so the class links only if the community loader lets the jar see it. */
    fun label(cfg: ConfigValues): String = label(cfg.string("city"), cfg.string("unit"), cfg.int("every"))

    /** What the badge shows for the values it was handed (the test calls this with the live resolved values). */
    fun label(city: String?, unit: String?, every: Int?): String = "${city ?: "Oslo"} ${unit ?: "C"} /${every ?: 15}m"
}

class StationPicker : FieldType {
    override fun validate(field: ConfigFieldDeclaration, value: JsonElement?): String? =
        if (value == null || (value is JsonPrimitive && value.isString)) null else "Pick a station"

    @Composable
    override fun Editor(field: ConfigFieldDeclaration, value: JsonElement?, enabled: Boolean, onChange: (JsonElement?) -> Unit) {}
}
