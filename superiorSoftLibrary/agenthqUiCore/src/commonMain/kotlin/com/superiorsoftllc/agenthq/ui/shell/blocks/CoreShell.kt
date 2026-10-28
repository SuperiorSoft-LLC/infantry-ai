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
import com.superiorsoftllc.agenthq.ui.block.layout.Show
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
        "infantry.shell.serverUpdate", "infantry.shell.config", "infantry.shell.configEntry",
        // S6: the drop-in consent dialog and the safe-mode banner: what a plugin asks for, and the way back from a bad one.
        DROP_CONSENT_NODE, SAFE_MODE_NODE,
    ) + ShellConfigTrust.map { it.id }

    /**
     * S4 (drop-in path): the shell's empty slots, the nodes the app UI draws. Each is an empty [PlaceholderBlock] that
     * draws nothing until a core module fills it (module.json `fills`, [TabFactory.baseRoot]); the fill is a tree of the
     * module's with the slot's id, and goes in before overrides and injections, so their node ids stay addressable.
     * Since S4 the tab trees (Git, Run, Notes, Changes) are module contributions too ([TabFactory.tabTreeRoots]). Since S4c
     * the Config dialog's open settings group (`infantry.shell.config.settings`, [CONFIG_SETTINGS]) is a slot too, inside
     * the locked dialog: without the app UI the dialog shows its trust sections only.
     */
    val slots: List<String> = listOf(
        "infantry.shell.header", "infantry.shell.left", "infantry.shell.center", "infantry.shell.right",
        "infantry.shell.drawer", "infantry.shell.footer",
        "infantry.shell.assistant", "infantry.shell.toast", "infantry.shell.board", "infantry.shell.diff",
        "infantry.shell.sound", "infantry.shell.voice", "infantry.shell.questions",
        "infantry.shell.config.settings", "infantry.shell.roles",
    )

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
                                    slot(HEADER_SLOT),
                                    StackBlock(
                                        "infantry.shell.body", measure = true, place = Place(weight = 1f, size = Size.FillWidth),
                                        children = listOf(
                                            RowBlock(
                                                "infantry.shell.panes", place = Place(size = Size.FillMax),
                                                children = listOf(
                                                    slot("infantry.shell.left", Place(show = Show.Wide)),
                                                    // S6: the empty centre is the drop box, until the app UI fills it.
                                                    slot("infantry.shell.center", Place(weight = 1f), empty = ShellDropBox),
                                                    slot("infantry.shell.right", Place(show = Show.Wide)),
                                                ),
                                            ).asPlaceholder(),
                                            slot("infantry.shell.drawer", Place(show = Show.Compact)),
                                        ),
                                    ).asPlaceholder(),
                                    slot("infantry.shell.footer"),
                                ),
                            ).asPlaceholder(),
                            // Outside the replaceable frame, so a frame replace cannot remove the locked way into Configuration.
                            ShellConfigEntry,
                            ShellSafeMode,
                            // The overlays, over the whole dock; they align themselves.
                            slot("infantry.shell.assistant"), slot("infantry.shell.toast"), slot("infantry.shell.board"), slot("infantry.shell.diff"),
                            slot("infantry.shell.sound"), slot("infantry.shell.voice"), slot("infantry.shell.questions"),
                        ),
                    ),
                ),
            ),
            // The dialogs, outside the dock and the image viewer.
            GroupBlock(
                "infantry.shell.windowLayer",
                listOf(
                    ShellConfig, ShellServerUpdate, slot("infantry.shell.roles"), ShellServers,
                    ShellSwitchError, ShellConnect, ShellDropConsent,
                ),
            ).asPlaceholder(),
        ),
    )
}

/** S4: an empty slot [id] of [CoreShell.slots] (the place is the one its usual fill has; an empty slot draws nothing). */
private fun slot(id: String, place: Place = Place(), empty: Block? = null) = PlaceholderBlock(id, null, place, empty)

/** `imageViewerHost` (core): IMG-VIEW-1's viewer host; its children are its content. */
class ImageViewerHostBlock(id: String, children: List<Block> = emptyList(), place: Place = Place()) :
    Block("imageViewerHost", id, place, children) {
    @Composable
    override fun Render(scope: BlockScope) = ImageViewerHost { RenderPlain(children) }

    override fun withChildren(children: List<Block>) = ImageViewerHostBlock(id, children, place)
}

