package com.superiorsoftllc.agenthq.plugins.demo.banner

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.agenthq.plugin.api.ModuleManifest
import com.superiorsoftllc.agenthq.plugin.api.ShellOverrideDeclaration
import com.superiorsoftllc.agenthq.ui.Palette
import com.superiorsoftllc.agenthq.ui.block.layout.BlockOverride
import com.superiorsoftllc.agenthq.ui.plugin.AgentHqModule
import com.superiorsoftllc.agenthq.ui.plugin.ModuleSource
import com.superiorsoftllc.agenthq.ui.plugin.UiPlugin
import com.superiorsoftllc.agenthq.ui.plugin.UiRegistrar

/** The strip the demo build draws between the header and the app body. */
val demoBanner = BlockOverride { scope, _ ->
    Box(scope.modifier.fillMaxWidth().background(Palette.Accent).padding(vertical = 3.dp).testTag("demo-banner"), contentAlignment = Alignment.Center) {
        Text("DEMO BUILD - sample data, nothing here is live", color = Palette.Bg, fontSize = 11.sp)
    }
}

/** OVERRIDE-1: a demo-only module; its single override is declared in module.json and here (same name). */
val DemoBannerModule = AgentHqModule(
    ModuleManifest(
        name = "Demo_Banner", version = "1.0.0", author = "Infantry AI",
        description = "Demo build only: a strip above the app body.",
        shell = listOf(ShellOverrideDeclaration("infantry.shell.frame/infantry.shell.body", "banner", "before", priority = 10)),
    ),
    shell = mapOf("banner" to demoBanner),
)

class DemoBannerPlugin : UiPlugin, ModuleSource {
    override fun register(registrar: UiRegistrar) = registrar.shell("banner", demoBanner)

    override fun modules(): List<AgentHqModule> = listOf(DemoBannerModule)
}
