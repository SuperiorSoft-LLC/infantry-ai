package com.superiorsoftllc.agenthq.ui

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.agenthq.plugin.api.DependencyDeclaration
import com.superiorsoftllc.agenthq.plugin.api.ModuleManifest
import com.superiorsoftllc.agenthq.plugin.api.ModulesConfig
import com.superiorsoftllc.agenthq.plugin.api.Tier
import com.superiorsoftllc.agenthq.plugin.host.ModuleChange
import com.superiorsoftllc.agenthq.plugin.host.ModuleState
import com.superiorsoftllc.agenthq.plugin.host.ModuleStatus
import com.superiorsoftllc.agenthq.ui.model.UiFlags
import com.superiorsoftllc.agenthq.ui.plugin.ModuleChanges
import com.superiorsoftllc.agenthq.ui.plugin.TabFactory
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/** Sample module statuses for the Settings > Plugins tests and screenshots: the core set plus one community module. */
internal object SampleModules {
    val core = listOf("Cli", "Epics", "Git", "Notes", "Pods", "Profiles", "Prompts", "Review", "Roles", "Run", "Skills", "Stats", "Triggers", "Usage")
        .map { ModuleStatus("Infantry_$it", Tier.CORE, ModuleState.LOADED, manifest = ModuleManifest("Infantry_$it", "1.0.0", author = "SuperiorSoft LLC", license = "Proprietary")) }
    val hello = ModuleStatus(
        "Acme_Hello", Tier.COMMUNITY, ModuleState.DISABLED, reason = "off in modules.json",
        manifest = ModuleManifest("Acme_Hello", "1.0.0", author = "Acme", license = "MIT", description = "Says hello in a right panel tab.", dependencies = listOf(DependencyDeclaration("Infantry_Cli", ">=1.0 <2"))),
    )
    val all = core + hello
    val changes = ModuleChanges { ModuleChange.Applied(ModulesConfig(), listOf(it.module), "${it.module} changed. Restart to apply.") }
    fun factory() = TabFactory(emptyList(), statuses = all, changes = changes)
}

/** Before/after screenshots of the configuration modal (ticket cb1ceb93); written under build/config-shots, asserts nothing. */
@OptIn(ExperimentalTestApi::class)
class ConfigDialogShots {
    private fun shoot(name: String, w: Int, h: Int, scrolled: Boolean = false) = runDesktopComposeUiTest(w, h) {
        setContent { AgentHqTheme { ConfigDialog(UiFlags(), onFlags = {}, onDismiss = {}, modules = SampleModules.factory(), initialTab = ConfigTab.PLUGINS) } }
        waitForIdle()
        // Scrolled: the pointer stays over the body, so the hover thumb shows too.
        if (scrolled) { onNodeWithTag(CONFIG_TAB_BODY_TAG).performMouseInput { moveTo(center); scroll(200f) }; mainClock.advanceTimeBy(500) }
        val dir = File("build/config-shots").apply { mkdirs() }
        onAllNodes(isRoot()).fetchSemanticsNodes().indices.forEach { i ->
            runCatching { onAllNodes(isRoot())[i].captureToImage().toAwtImage() }.onSuccess { ImageIO.write(it, "png", File(dir, "$name-$i.png")) }
        }
    }

    @Test fun wide() = shoot("wide", 1640, 1150)
    @Test fun wideScrolled() = shoot("wide-scrolled", 1640, 1150, scrolled = true)
    @Test fun narrow() = shoot("narrow", 700, 800)
}
