// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.demo.banner

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.infantry.ui.IconActionTooltipBox
import com.superiorsoftllc.infantry.ui.block.layout.LocalBlockSize
import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.plugin.api.ShellOverrideDeclaration
import com.superiorsoftllc.infantry.ui.Palette
import com.superiorsoftllc.infantry.plugins.demo.lock.LocalDemoLock
import com.superiorsoftllc.infantry.ui.block.layout.BlockOverride
import com.superiorsoftllc.infantry.ui.plugin.InfantryModule
import com.superiorsoftllc.infantry.ui.plugin.ModuleSource
import com.superiorsoftllc.infantry.ui.plugin.UiPlugin
import com.superiorsoftllc.infantry.ui.plugin.UiRegistrar

const val DEFAULT_TEXT = "DEMO BUILD - sample data, nothing here is live"

/** The turn count in [text] ("You have 10 turns"), shown alone when the card shrinks to an icon and the count; null when there is none. */
fun turnCount(text: String): String? = Regex("""(\d+)\s+turns?""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)

/** DEMO-BANNER-CARD-1: below this header width the card is the info icon and the turn count only. */
private val COMPACT_BELOW = 600.dp

/** The demo notice as a compact card in the top bar (the old full-width strip is gone); the full text is its tooltip. */
val demoBanner = BlockOverride { scope, _ ->
    // Demo_Lock's notice (INFANTRY_DEMO_NOTICE from `ready`, or the turn-limit notice) wins; offline/wasm demo has none and says the default.
    val text = LocalDemoLock.current.notice ?: DEFAULT_TEXT
    val width = LocalBlockSize.current.width
    val compact = width != Dp.Unspecified && width < COMPACT_BELOW
    val shape = RoundedCornerShape(6.dp)
    IconActionTooltipBox(text, modifier = scope.modifier.padding(horizontal = 6.dp).testTag("demo-banner")) { m ->
        Row(
            m.widthIn(max = if (compact) 80.dp else 340.dp).clip(shape).background(Palette.Accent.copy(alpha = 0.14f))
                .border(1.dp, Palette.Accent.copy(alpha = 0.5f), shape).padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(14.dp).clip(CircleShape).border(1.dp, Palette.Accent, CircleShape), contentAlignment = Alignment.Center) {
                Text("i", color = Palette.Accent, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
            val shown = if (compact) turnCount(text) else text
            if (shown != null) {
                Spacer(Modifier.width(6.dp))
                Text(shown, color = Palette.Text, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** OVERRIDE-1: a demo-only module; its single override is declared in module.json and here (same name). */
val DemoBannerModule = InfantryModule(
    ModuleManifest(
        name = "Demo_Banner", version = "1.0.0", author = "Infantry AI",
        description = "Demo build only: a notice card in the top bar.",
        shell = listOf(ShellOverrideDeclaration("infantry.shell.header.lead", "banner", "after", priority = 10)),
    ),
    shell = mapOf("banner" to demoBanner),
)

class DemoBannerPlugin : UiPlugin, ModuleSource {
    override fun register(registrar: UiRegistrar) = registrar.shell("banner", demoBanner)

    override fun modules(): List<InfantryModule> = listOf(DemoBannerModule)
}
