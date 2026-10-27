package com.superiorsoftllc.agenthq.ui.shell.blocks

import androidx.compose.runtime.Composable
import com.superiorsoftllc.agenthq.ui.ImageViewerHost
import com.superiorsoftllc.agenthq.ui.block.layout.Block
import com.superiorsoftllc.agenthq.ui.block.layout.BlockScope
import com.superiorsoftllc.agenthq.ui.block.layout.ColumnBlock
import com.superiorsoftllc.agenthq.ui.block.layout.GroupBlock
import com.superiorsoftllc.agenthq.ui.block.layout.PlaceholderBlock
import com.superiorsoftllc.agenthq.ui.block.layout.Place
import com.superiorsoftllc.agenthq.ui.block.layout.RenderPlain
import com.superiorsoftllc.agenthq.ui.block.layout.RowBlock
import com.superiorsoftllc.agenthq.ui.block.layout.Size
import com.superiorsoftllc.agenthq.ui.block.layout.asPlaceholder
import com.superiorsoftllc.agenthq.ui.block.layout.StackBlock

/**
 * BLOCKS-LAYOUT L3 (docs/BLOCKS-LAYOUT-DESIGN.md section 2): the window is one tree of blocks, placed by the layout
 * primitives. Every node maps to the Compose call the old dock emitted, with the same modifiers in the same order.
 * Node ids are the override address (BLOCKS-1); since L5 they are plugin API: never rename one.
 */
object CoreShell {
    /**
     * BLOCKS-LAYOUT L5 (design section 7): the nodes no module may replace, wrap or inject into (BLOCKS-INJECTORS J1;
     * an unlocked child is an open slot). The true skeleton, and where a token is typed, an update is approved or a
     * plugin is disabled. Since S1 frame, body, panes and windowLayer are [placeholders] instead. Since S3 the config
     * dialog's settings sections are open; its frame and its trust sections ([ShellConfigTrust]) stay locked.
     */
    val locked: Set<String> = setOf(
        "infantry.shell.window", "infantry.shell.images", "infantry.shell.dock",
        "infantry.shell.connect", "infantry.shell.servers", "infantry.shell.switchError",
        "infantry.shell.serverUpdate", "infantry.shell.config",
    ) + ShellConfigTrust.map { it.id }

    /**
     * BLOCKS-LAYOUT L14: core tabs whose content is a tree of its own, drawn by the tab, not in [root] (the Git tab's
     * [ShellGit]; L15 the Run tab's [ShellRun]; L17 the Notes tab's [ShellNotes]; L18 the Changes tab's [ShellChanges]). A shell
     * override addresses their nodes by id the same way; [locked] applies to them too.
     */
    val tabs: List<Block> = listOf(ShellGit, ShellRun, ShellNotes, ShellChanges)

    /**
     * SHELL-EMPTY-DROPIN S1 (docs/BLOCKS-LAYOUT-DESIGN.md section 13): the containers that are open slots
     * ([PlaceholderBlock]): each keeps its id and place and draws its core default (what it drew before) until a module
     * replaces it; a community module still needs `allowOverride` / `allowInject` for it. windowLayer's replace is
     * still refused, as it would remove the locked dialogs in it; it takes injections.
     */
    val placeholders: List<String> = listOf("infantry.shell.frame", "infantry.shell.body", "infantry.shell.panes", "infantry.shell.windowLayer")

    val root: Block = GroupBlock(
        "infantry.shell.window",
        listOf(
            ImageViewerHostBlock(
                "infantry.shell.images",
                listOf(
                    StackBlock(
                        "infantry.shell.dock", place = Place(size = Size.FillMax),
                        children = listOf(
                            ColumnBlock(
                                "infantry.shell.frame", surface = true,
                                children = listOf(
                                    ShellHeader,
                                    StackBlock(
                                        "infantry.shell.body", measure = true, place = Place(weight = 1f, size = Size.FillWidth),
                                        children = listOf(
                                            RowBlock(
                                                "infantry.shell.panes", place = Place(size = Size.FillMax),
                                                children = listOf(
                                                    ShellLeftPanel,
                                                    ShellCenter,
                                                    ShellRightPanel,
                                                ),
                                            ).asPlaceholder(),
                                            ShellCompactDrawer,
                                        ),
                                    ).asPlaceholder(),
                                    CoreStatusBar,
                                ),
                            ).asPlaceholder(),
                            // The overlays, over the whole dock; they align themselves.
                            ShellAssistant, ShellToast, ShellBoard, ShellDiff,
                            ShellSound, ShellVoice, ShellQuestionPill,
                        ),
                    ),
                ),
            ),
            // The dialogs, outside the dock and the image viewer.
            GroupBlock(
                "infantry.shell.windowLayer",
                listOf(
                    ShellConfig, ShellServerUpdate, ShellRoles, ShellServers,
                    ShellSwitchError, ShellConnect,
                ),
            ).asPlaceholder(),
        ),
    )
}

/** `imageViewerHost` (core): IMG-VIEW-1's viewer host; its children are its content. */
class ImageViewerHostBlock(id: String, children: List<Block> = emptyList(), place: Place = Place()) :
    Block("imageViewerHost", id, place, children) {
    @Composable
    override fun Render(scope: BlockScope) = ImageViewerHost { RenderPlain(children) }

    override fun withChildren(children: List<Block>) = ImageViewerHostBlock(id, children, place)
}

