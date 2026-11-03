package com.superiorsoftllc.infantry.plugins.official.run

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import com.superiorsoftllc.infantry.ui.shell.LocalShellContext
import com.superiorsoftllc.infantry.ui.block.layout.Block
import com.superiorsoftllc.infantry.ui.block.layout.BlockScope
import com.superiorsoftllc.infantry.ui.block.layout.Place
import com.superiorsoftllc.infantry.ui.block.layout.RenderPlaced

private const val RUN = "infantry.shell.run"

/** The Run pane's host; null outside [RunPaneBlock] (a child moved elsewhere draws nothing). */
private val LocalRunPaneHost = compositionLocalOf<RunPaneHost?> { null }

/**
 * BLOCKS-LAYOUT L15: the Run tab's content (Infantry_Run), a container of child blocks `infantry.shell.run.<name>` drawn
 * straight into the pane's column, top to bottom. The frame (column, title, the picked command, the editor's draft, the
 * delete confirm, the last refusal) stays its own. It is not in [CoreShell.root]: the tab draws it ([RunTab]), and
 * overrides address it by id the same way ([CoreShell.tabs]).
 */
class RunPaneBlock(children: List<Block>, place: Place = Place()) : Block("runPane", RUN, place, children) {
    @Composable
    override fun Render(scope: BlockScope) {
        val ctx = LocalShellContext.current ?: return
        RunPaneFrame(ctx.link, ctx.run, scope.modifier) { run ->
            CompositionLocalProvider(LocalRunPaneHost provides run) { RenderPlaced(children) }
        }
    }

    override fun withChildren(children: List<Block>) = RunPaneBlock(children, place)
}

/** "No saved commands" and Add command, with none saved and no editor open; its placement goes on the row. */
object RunEmpty : Block("runEmpty", "$RUN.empty") {
    @Composable
    override fun Render(scope: BlockScope) {
        RunEmptySection(LocalRunPaneHost.current ?: return, scope.modifier)
    }
}

/** The picker, Start, Interactive, Stop, Add / Edit / Delete and the run's state; its placement goes on the row. */
object RunToolbar : Block("runToolbar", "$RUN.toolbar") {
    @Composable
    override fun Render(scope: BlockScope) {
        RunToolbarSection(LocalRunPaneHost.current ?: return, scope.modifier)
    }
}

/** One pill per run (nothing with none); its placement goes on the row. */
object RunPills : Block("runPills", "$RUN.pills") {
    @Composable
    override fun Render(scope: BlockScope) {
        RunPillsSection(LocalRunPaneHost.current ?: return, scope.modifier)
    }
}

/** The inline delete confirm, while it is asked; its placement goes on the row. */
object RunConfirm : Block("runConfirm", "$RUN.confirm") {
    @Composable
    override fun Render(scope: BlockScope) {
        RunConfirmSection(LocalRunPaneHost.current ?: return, scope.modifier)
    }
}

/** The add / edit form while it is open, taking the rest of the height; its placement goes on the form. */
object RunEditor : Block("runEditor", "$RUN.editor", Place(weight = 1f)) {
    @Composable
    override fun Render(scope: BlockScope) {
        RunEditorSection(LocalRunPaneHost.current ?: return, scope.modifier)
    }
}

/** The last refusal, an output gap, the hidden-lines count, while no editor is open; its placement goes on the first. */
object RunNotices : Block("runNotices", "$RUN.notices") {
    @Composable
    override fun Render(scope: BlockScope) {
        RunNoticesSection(LocalRunPaneHost.current ?: return, scope.modifier)
    }
}

/** The shown run's lines while no editor is open, taking the rest of the height; its placement goes on the list. */
object RunOutput : Block("runOutput", "$RUN.output", Place(weight = 1f)) {
    @Composable
    override fun Render(scope: BlockScope) {
        RunOutputSection(LocalRunPaneHost.current ?: return, scope.modifier)
    }
}

val ShellRun = RunPaneBlock(listOf(RunEmpty, RunToolbar, RunPills, RunConfirm, RunEditor, RunNotices, RunOutput))

/** The Run tab's content: [ShellRun] as the window resolved it (a module's override in), filling the tab. */
@Composable
fun RunTab() {
    (LocalShellContext.current?.layout?.tabTree(RUN) ?: ShellRun).Render(BlockScope(Modifier, null))
}
