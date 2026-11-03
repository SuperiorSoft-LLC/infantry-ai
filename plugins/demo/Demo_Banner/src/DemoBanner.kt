package com.superiorsoftllc.infantry.plugins.demo.banner

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

/** The strip the demo build draws between the header and the app body. */
val demoBanner = BlockOverride { scope, _ ->
    // Demo_Lock's notice (INFANTRY_DEMO_NOTICE from `ready`, or the turn-limit notice) wins; offline/wasm demo has none and says the default.
    val text = LocalDemoLock.current.notice ?: DEFAULT_TEXT
    Box(scope.modifier.fillMaxWidth().background(Palette.Accent).padding(vertical = 3.dp).testTag("demo-banner"), contentAlignment = Alignment.Center) {
        Text(text, color = Palette.Bg, fontSize = 11.sp)
    }
}

/** OVERRIDE-1: a demo-only module; its single override is declared in module.json and here (same name). */
val DemoBannerModule = InfantryModule(
    ModuleManifest(
        name = "Demo_Banner", version = "1.0.0", author = "Infantry AI",
        description = "Demo build only: a strip above the app body.",
        shell = listOf(ShellOverrideDeclaration("infantry.shell.frame/infantry.shell.body", "banner", "before", priority = 10)),
    ),
    shell = mapOf("banner" to demoBanner),
)

class DemoBannerPlugin : UiPlugin, ModuleSource {
    override fun register(registrar: UiRegistrar) = registrar.shell("banner", demoBanner)

    override fun modules(): List<InfantryModule> = listOf(DemoBannerModule)
}
