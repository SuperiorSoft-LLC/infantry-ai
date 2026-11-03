package com.superiorsoftllc.infantry.plugins.official.header

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.superiorsoftllc.infantry.ui.ATTENTION_TAB_ID
import com.superiorsoftllc.infantry.ui.AssistantEntry
import com.superiorsoftllc.infantry.ui.ServerSwitcherChip
import com.superiorsoftllc.infantry.ui.shell.HeaderContext
import com.superiorsoftllc.infantry.ui.shell.LocalShellContext
import com.superiorsoftllc.infantry.ui.shell.NoHeader
import com.superiorsoftllc.infantry.ui.LocalWindowChrome
import com.superiorsoftllc.infantry.plugin.api.FillDeclaration
import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.ui.plugin.InfantryModule
import com.superiorsoftllc.infantry.ui.plugin.ModuleSource
import com.superiorsoftllc.infantry.ui.plugin.UiPlugin
import com.superiorsoftllc.infantry.ui.plugin.UiRegistrar
import com.superiorsoftllc.infantry.ui.block.layout.Block
import com.superiorsoftllc.infantry.ui.block.layout.BlockScope
import com.superiorsoftllc.infantry.ui.block.layout.Place
import com.superiorsoftllc.infantry.ui.block.layout.RenderPlaced
import com.superiorsoftllc.infantry.ui.block.layout.Show
import com.superiorsoftllc.infantry.ui.model.PanelSide
import com.superiorsoftllc.infantry.ui.model.needsInput

/**
 * BLOCKS-LAYOUT L6 (docs/BLOCKS-LAYOUT-DESIGN.md section 8): the window's title bar as a `titleBar` layout block. Its
 * frame is the 36dp Panel row plus the divider; its items are children, left to right, ids `infantry.shell.header.<name>`.
 * Each item owns the gap next to it, so a replaced child keeps the bar's spacing.
 */
class TitleBarBlock(id: String, children: List<Block> = emptyList(), place: Place = Place()) :
    Block("titleBar", id, place, children) {
    @Composable
    override fun Render(scope: BlockScope) = TitleBarFrame(scope.modifier) { RenderPlaced(children) }

    override fun withChildren(children: List<Block>) = TitleBarBlock(id, children, place)
}

/** The shell's title bar slot, which this module fills. */
const val HEADER_FILL_ID = "infantry.shell.header"

private const val HEADER = HEADER_FILL_ID

/** The window's header domain; [NoHeader] (no state, nothing drawn) where the shell has no window. */
private val header: HeaderContext @Composable get() = LocalShellContext.current?.header ?: NoHeader

@Composable
private fun Picker() {
    val h = header
    ProjectPicker(h.state ?: return, h.controller ?: return)
}

private const val LEAD = "$HEADER.lead"

/**
 * BLOCKS-LAYOUT L7: the bar's left and middle, a container of child blocks `infantry.shell.header.lead.<name>`. Its
 * children, the drag area's weight included, go straight into the bar's row ([BlockScope.row]); drawn outside a row they
 * get a row of their own.
 */
class HeaderLeadBlock(children: List<Block>, place: Place = Place()) : Block("titleBarLead", LEAD, place, children) {
    @Composable
    override fun Render(scope: BlockScope) {
        val row = scope.row
        if (row == null) Row(scope.modifier, verticalAlignment = Alignment.CenterVertically) { RenderPlaced(children) } else with(row) { RenderPlaced(children) }
    }

    override fun withChildren(children: List<Block>) = HeaderLeadBlock(children, place)
}

/** PANEL-TOGGLE-1: the agent list toggle at the bar's left edge, with the 6dp before it. */
object HeaderToggleLeft : Block("panelToggle", "$LEAD.toggleLeft") {
    @Composable
    override fun Render(scope: BlockScope) {
        val h = header
        TitleBarToggleLeft(h.state ?: return, scope.modifier) { h.togglePanel(PanelSide.LEFT) }
    }
}

/** The logo + name, a drag area (a double-click toggles maximize), with the 6dp after the left toggle and 10dp after it. */
object HeaderBrand : Block("brand", "$LEAD.brand") {
    @Composable
    override fun Render(scope: BlockScope) = TitleBarBrand(LocalWindowChrome.current, modifier = scope.modifier)
}

/** M2-7: the project drop-down and the "+" New Project box; wide only (a compact bar lists it in the overflow). */
object HeaderProject : Block("projectSlot", "$LEAD.project") {
    @Composable
    override fun Render(scope: BlockScope) {
        TitleBarProject({ Picker() }, scope.modifier)
    }
}

/**
 * The bar's free middle, a drag area with `weight(1f)` in the bar's row: wide, the avatar strip with the Assistant last;
 * compact, the plain drag area and the Assistant (the overflow button after it is [HeaderOverflowBlock], L8).
 */
object HeaderAgents : Block("agentStrip", "$LEAD.agents") {
    @Composable
    override fun Render(scope: BlockScope) {
        val row = scope.row
        if (row == null) Row(verticalAlignment = Alignment.CenterVertically) { Agents(scope.modifier) } else row.Agents(scope.modifier)
    }

    @Composable
    private fun RowScope.Agents(modifier: Modifier) {
        val h = header
        val state = h.state ?: return
        TitleBarAgents(
            state, h.agentScope,
            chrome = LocalWindowChrome.current,
            // ASSISTANT-1: the last member of the team strip; a tap toggles the popup (the daemon makes the session on the first open).
            assistantSlot = {
                if (h.hasAssistant) {
                    AssistantEntry(
                        h.assistantOpen,
                        state.assistant?.let { needsInput(it, state.waiting, state.openPrompts, state.openQuestionSessions) } == true,
                    ) { h.toggleAssistant() }
                }
            },
            modifier = modifier,
        )
    }
}

private const val OVERFLOW = "$LEAD.overflow"

/**
 * BLOCKS-LAYOUT L8 (design section 5): the compact bar's "More" button, `show Compact`. Its menu entries are its own
 * children, top to bottom, `infantry.shell.header.lead.overflow.<name>`; each entry owns its gap (the connection entry
 * the divider before it). A plugin header item that should overflow adds one node `show Wide` in the bar and one here.
 */
class HeaderOverflowBlock(children: List<Block>, place: Place = Place(show = Show.Compact)) : Block("titleBarOverflow", OVERFLOW, place, children) {
    @Composable
    override fun Render(scope: BlockScope) = TitleBarOverflow(scope.modifier) { RenderPlaced(children) }

    override fun withChildren(children: List<Block>) = HeaderOverflowBlock(children, place)
}

/** M2-7: the project picker, the overflow's first entry. */
object HeaderOverflowProject : Block("projectSlot", "$OVERFLOW.project") {
    @Composable
    override fun Render(scope: BlockScope) {
        TitleBarOverflowProject({ Picker() }, scope.modifier)
    }
}

/** "N need attention", while any is open; a tap opens Attention. */
object HeaderOverflowAttention : Block("attentionEntry", "$OVERFLOW.attention") {
    @Composable
    override fun Render(scope: BlockScope) {
        val h = header
        TitleBarOverflowAttention(h.state ?: return, { h.showTab(ATTENTION_TAB_ID) }, scope.modifier)
    }
}

/** The connection URL the compact chip has no room for, after a divider; a tap opens the connection. */
object HeaderOverflowConnection : Block("connectionEntry", "$OVERFLOW.connection") {
    @Composable
    override fun Render(scope: BlockScope) {
        val h = header
        TitleBarOverflowConnection(h.state ?: return, h::connect, scope.modifier)
    }
}

val HeaderOverflow = HeaderOverflowBlock(listOf(HeaderOverflowProject, HeaderOverflowAttention, HeaderOverflowConnection))

val HeaderLead = HeaderLeadBlock(listOf(HeaderToggleLeft, HeaderBrand, HeaderProject, HeaderAgents, HeaderOverflow))

/** M2-6: minimize / maximize-restore / close where the platform has a window, else the bar's 12dp end. */
object HeaderWindowControls : Block("windowControls", "$HEADER.windowControls") {
    @Composable
    override fun Render(scope: BlockScope) = TitleBarWindowControls(LocalWindowChrome.current, scope.modifier)
}

/** The connection chip; a tap opens the connection (or the host's own connect screen, MOBILE-CONNCHIP-1). */
object HeaderConnection : Block("connectionChip", "$HEADER.connection") {
    @Composable
    override fun Render(scope: BlockScope) {
        val h = header
        TitleBarConnection(h.state ?: return, h::connect, scope.modifier)
    }
}

/** SWITCH-1: the server dropdown, where the host has a server switcher; its menu also opens Servers. */
object HeaderServer : Block("serverSwitcher", "$HEADER.server") {
    @Composable
    override fun Render(scope: BlockScope) {
        val h = header
        val switcher = h.switcher
        if (switcher != null) ServerSwitcherChip(switcher, onSelect = h::switchTo, onManage = h::manageServers, modifier = scope.modifier)
    }
}

/** PANEL-TOGGLE-1: the side panel toggle, in view at every width. */
object HeaderToggleRight : Block("panelToggle", "$HEADER.toggleRight") {
    @Composable
    override fun Render(scope: BlockScope) {
        val h = header
        TitleBarRightToggle(h.state ?: return, scope.modifier) { h.togglePanel(PanelSide.RIGHT) }
    }
}

/** Contract 1.9.0: the open attention count (wide only; a compact bar lists it in the overflow); a tap opens Attention. */
object HeaderAttention : Block("attentionChip", "$HEADER.attention") {
    @Composable
    override fun Render(scope: BlockScope) {
        val h = header
        TitleBarAttention(h.state ?: return, { h.showTab(ATTENTION_TAB_ID) }, scope.modifier)
    }
}

/** BLOCKS-SHELL-1: the window's title bar, on top. */
val ShellHeader = TitleBarBlock(HEADER, listOf(HeaderLead, HeaderAttention, HeaderToggleRight, HeaderServer, HeaderConnection, HeaderWindowControls))

/** RUNTIME-20: Infantry_Header (official), the window's title bar, compiled in on web and Android (D9). */
val InfantryHeader = InfantryModule(
    ModuleManifest(
        name = "Infantry_Header", version = "1.0.0", author = "Infantry AI",
        description = "The window's title bar: logo, project picker, agent strip, attention and connection chips, window controls.",
        fills = listOf(FillDeclaration(HEADER_FILL_ID)),
    ),
    fills = mapOf(HEADER_FILL_ID to ShellHeader),
)

/**
 * RUNTIME-20: the entry of Infantry_Header (module.json `entry`), an official module. It fills the shell's header slot
 * (formerly filled by Infantry_AppUi). Without it the window has no title bar.
 *  - Desktop loads it at run time from its signed jar (`official/Infantry_Header`): the loader takes it as a [UiPlugin].
 *  - Web and Android compile it in from the generated module list, which takes it as a [ModuleSource].
 */
class HeaderPlugin : UiPlugin, ModuleSource {
    override fun register(registrar: UiRegistrar) {
        registrar.fill(HEADER_FILL_ID, ShellHeader)
    }

    override fun modules(): List<InfantryModule> = listOf(InfantryHeader)
}
