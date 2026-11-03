package com.superiorsoftllc.infantry.plugins.official.assistant

import com.superiorsoftllc.infantry.plugin.api.FillDeclaration
import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.ui.plugin.InfantryModule
import com.superiorsoftllc.infantry.ui.plugin.ModuleSource
import com.superiorsoftllc.infantry.ui.plugin.UiPlugin
import com.superiorsoftllc.infantry.ui.plugin.UiRegistrar

private const val ASSISTANT_FILL_ID = "infantry.shell.assistant"

/** Infantry_Assistant as a compiled-in module (web and Android, D9): the manifest mirrors module.json. */
val InfantryAssistant = InfantryModule(
    ModuleManifest(
        name = "Infantry_Assistant", version = "1.0.0", author = "Infantry AI",
        description = "The global Assistant: a popup docked under the title bar that hosts the shared chat panel.",
        fills = listOf(FillDeclaration(ASSISTANT_FILL_ID)),
    ),
    fills = mapOf(ASSISTANT_FILL_ID to ShellAssistant),
)

/**
 * RUNTIME-24 (F8): the entry of Infantry_Assistant (module.json `entry`), an official module. The popup reads the window through the additive `assistant` context
 * (`AssistantContext`); the chat inside it is the shared ChatPanel that Infantry_Chat publishes (no Chat jar, no chat in the popup).
 *  - Desktop loads it at run time from its signed jar; the loader takes it as a [UiPlugin].
 *  - Web and Android compile it in from the generated module list, which takes it as a [ModuleSource].
 */
class AssistantPlugin : UiPlugin, ModuleSource {
    override fun register(registrar: UiRegistrar) {
        registrar.fill(ASSISTANT_FILL_ID, ShellAssistant)
    }

    override fun modules(): List<InfantryModule> = listOf(InfantryAssistant)
}
