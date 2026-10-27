package com.superiorsoftllc.agenthq.ui.shell.blocks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.superiorsoftllc.agenthq.ui.ImageViewerHost
import com.superiorsoftllc.agenthq.ui.block.DockScope
import com.superiorsoftllc.agenthq.ui.block.LocalDockSize
import com.superiorsoftllc.agenthq.ui.block.layout.Block
import com.superiorsoftllc.agenthq.ui.block.layout.BlockScope
import com.superiorsoftllc.agenthq.ui.block.layout.ColumnBlock
import com.superiorsoftllc.agenthq.ui.block.layout.GroupBlock
import com.superiorsoftllc.agenthq.ui.block.layout.LocalBlockSize
import com.superiorsoftllc.agenthq.ui.block.layout.Place
import com.superiorsoftllc.agenthq.ui.block.layout.RenderPlain
import com.superiorsoftllc.agenthq.ui.block.layout.RowBlock
import com.superiorsoftllc.agenthq.ui.block.layout.Size
import com.superiorsoftllc.agenthq.ui.block.layout.StackBlock
import com.superiorsoftllc.agenthq.ui.block.Block as DockBlock

/**
 * BLOCKS-LAYOUT L3 (docs/BLOCKS-LAYOUT-DESIGN.md section 2): the window is one tree of blocks, placed by the layout
 * primitives. Every node maps to the Compose call the old dock emitted, with the same modifiers in the same order.
 * Node ids are the override address (BLOCKS-1); never rename one.
 */
object CoreShell {
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
                                                    Legacy(ShellRightPanel),
                                                ),
                                            ),
                                            ShellCompactDrawer,
                                        ),
                                    ),
                                    Legacy(ShellStatusBar),
                                ),
                            ),
                            // The overlays, over the whole dock; they align themselves.
                            Legacy(ShellAssistant), Legacy(ShellToast), Legacy(ShellBoard), Legacy(ShellDiff),
                            Legacy(ShellSound), Legacy(ShellVoice), Legacy(ShellQuestionPill),
                        ),
                    ),
                ),
            ),
            // The dialogs, outside the dock and the image viewer.
            GroupBlock(
                "infantry.shell.windowLayer",
                listOf(
                    Legacy(ShellConfig), Legacy(ShellServerUpdate), Legacy(ShellRoles), Legacy(ShellServers),
                    Legacy(ShellSwitchError), Legacy(ShellConnect),
                ),
            ),
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

/** L3 migration bridge: a dock-era block in the new tree, placed by its parent; it goes once every block has moved. */
private class Legacy(val block: DockBlock, place: Place = Place()) : Block(block.blockType, block.id, place) {
    @Composable
    override fun Render(scope: BlockScope) =
        CompositionLocalProvider(LocalDockSize provides LocalBlockSize.current) { block.Render(DockScope(scope.modifier, scope.box)) }
}
