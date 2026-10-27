package com.superiorsoftllc.agenthq.ui.shell.blocks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import com.superiorsoftllc.agenthq.ui.ChangesBranchFileRow
import com.superiorsoftllc.agenthq.ui.ChangesBranchFilesSection
import com.superiorsoftllc.agenthq.ui.ChangesBranchSection
import com.superiorsoftllc.agenthq.ui.ChangesCommitRow
import com.superiorsoftllc.agenthq.ui.ChangesEmptySection
import com.superiorsoftllc.agenthq.ui.ChangesFileItem
import com.superiorsoftllc.agenthq.ui.ChangesFileRow
import com.superiorsoftllc.agenthq.ui.ChangesFilesSection
import com.superiorsoftllc.agenthq.ui.ChangesHeaderSection
import com.superiorsoftllc.agenthq.ui.ChangesHintSection
import com.superiorsoftllc.agenthq.ui.ChangesHistorySection
import com.superiorsoftllc.agenthq.ui.ChangesModeSection
import com.superiorsoftllc.agenthq.ui.ChangesNotGitSection
import com.superiorsoftllc.agenthq.ui.ChangesPaneFrame
import com.superiorsoftllc.agenthq.ui.ChangesPaneHost
import com.superiorsoftllc.agenthq.ui.ChangesTitleSection
import com.superiorsoftllc.agenthq.ui.ChangesUnsupportedSection
import com.superiorsoftllc.agenthq.ui.ChangesWorktreeSection
import com.superiorsoftllc.agenthq.ui.shell.LocalShellContext
import com.superiorsoftllc.agenthq.ui.block.layout.Block
import com.superiorsoftllc.agenthq.ui.block.layout.BlockScope
import com.superiorsoftllc.agenthq.ui.block.layout.Place
import com.superiorsoftllc.agenthq.ui.block.layout.RenderPlaced
import com.superiorsoftllc.agenthq.ui.block.layout.RenderPlain
import com.superiorsoftllc.agenthq.ui.model.DiffTarget
import com.superiorsoftllc.agenthq.ui.protocol.BranchChangedFile
import com.superiorsoftllc.agenthq.ui.protocol.GitCommit

private const val CHANGES = "infantry.shell.changes"

/** What the tab hands the pane: the file the diff modal shows and how to open one; null outside [ChangesTab]. */
private class ChangesTabDiff(val openDiff: DiffTarget?, val onOpenDiff: ((DiffTarget) -> Unit)?)

private val LocalChangesTabDiff = compositionLocalOf<ChangesTabDiff?> { null }

/** The Changes tab's host; null outside [ChangesPaneBlock] (a child moved elsewhere draws nothing). */
private val LocalChangesPaneHost = compositionLocalOf<ChangesPaneHost?> { null }

/** The file a file row is drawn for; null outside [ChangesFilesBlock] (a file row moved elsewhere draws nothing). */
private val LocalChangesFile = compositionLocalOf<ChangesFileItem?> { null }

/** The file a branch file row is drawn for; null outside [ChangesBranchFilesBlock]. */
private val LocalChangesBranchFile = compositionLocalOf<BranchChangedFile?> { null }

/** The commit a commit row is drawn for; null outside [ChangesHistoryBlock]. */
private val LocalChangesCommit = compositionLocalOf<GitCommit?> { null }

/**
 * BLOCKS-LAYOUT L18: the Changes tab's content (Infantry_Changes), a container of child blocks
 * `infantry.shell.changes.<name>` drawn straight into the tab's scrolled column, 6 dp apart, top to bottom. The frame
 * (column, the Working tree | History mode, the polling) stays its own. It is not in [CoreShell.root]: the tab draws it
 * ([ChangesTab]), and overrides address it by id the same way ([CoreShell.tabs]).
 */
class ChangesPaneBlock(children: List<Block>, place: Place = Place()) : Block("changesPane", CHANGES, place, children) {
    @Composable
    override fun Render(scope: BlockScope) {
        val ctx = LocalShellContext.current ?: return
        val diff = LocalChangesTabDiff.current
        ChangesPaneFrame(ctx.link, ctx.session, ctx.git, ctx.changes, openDiff = diff?.openDiff, onOpenDiff = diff?.onOpenDiff, modifier = scope.modifier) { changes ->
            CompositionLocalProvider(LocalChangesPaneHost provides changes) { RenderPlaced(children) }
        }
    }

    override fun withChildren(children: List<Block>) = ChangesPaneBlock(children, place)
}

/** "Select a project to see its changes.", with no project; its placement goes on the line. */
object ChangesEmpty : Block("changesEmpty", "$CHANGES.empty") {
    @Composable
    override fun Render(scope: BlockScope) {
        ChangesEmptySection(LocalChangesPaneHost.current ?: return, scope.modifier)
    }
}

/** "CHANGES · project" and the refresh icon; its placement goes on the row. */
object ChangesTitle : Block("changesTitle", "$CHANGES.title") {
    @Composable
    override fun Render(scope: BlockScope) {
        ChangesTitleSection(LocalChangesPaneHost.current ?: return, scope.modifier)
    }
}

/** The line for a daemon older than 1.36.0; its placement goes on the line. */
object ChangesUnsupported : Block("changesUnsupported", "$CHANGES.unsupported") {
    @Composable
    override fun Render(scope: BlockScope) {
        ChangesUnsupportedSection(LocalChangesPaneHost.current ?: return, scope.modifier)
    }
}

/** "Not a git repository: ..."; its placement goes on the line. */
object ChangesNotGit : Block("changesNotGit", "$CHANGES.notGit") {
    @Composable
    override fun Render(scope: BlockScope) {
        ChangesNotGitSection(LocalChangesPaneHost.current ?: return, scope.modifier)
    }
}

/** The worktree picker and why the worktrees are unknown; a placement other than the default gets a Column. */
object ChangesWorktree : Block("changesWorktree", "$CHANGES.worktree") {
    @Composable
    override fun Render(scope: BlockScope) {
        ChangesWorktreeSection(LocalChangesPaneHost.current ?: return, scope.modifier)
    }
}

/** The shown worktree's branch, ahead/behind, base and uncommitted count; its placement goes on the row. */
object ChangesHeader : Block("changesHeader", "$CHANGES.header") {
    @Composable
    override fun Render(scope: BlockScope) {
        ChangesHeaderSection(LocalChangesPaneHost.current ?: return, scope.modifier)
    }
}

/** The Working tree | History toggle; its placement goes on the row. */
object ChangesModeToggle : Block("changesMode", "$CHANGES.mode") {
    @Composable
    override fun Render(scope: BlockScope) {
        ChangesModeSection(LocalChangesPaneHost.current ?: return, scope.modifier)
    }
}

/** The branch line as a drop-down; its placement goes on its box. */
object ChangesBranch : Block("changesBranch", "$CHANGES.branch") {
    @Composable
    override fun Render(scope: BlockScope) {
        ChangesBranchSection(LocalChangesPaneHost.current ?: return, scope.modifier)
    }
}

/** "N commits ahead" over a clean worktree; its placement goes on the line. */
object ChangesHint : Block("changesHint", "$CHANGES.hint") {
    @Composable
    override fun Render(scope: BlockScope) {
        ChangesHintSection(LocalChangesPaneHost.current ?: return, scope.modifier)
    }
}

/**
 * The working tree's files (unknown and clean wear its placement; with files a placement other than the default gets a
 * Column). Its children are drawn once per file straight into the tab's column, with that file in a composition local:
 * `infantry.shell.changes.file` is the file template, so an override of it applies to every file.
 */
class ChangesFilesBlock(children: List<Block>, place: Place = Place()) : Block("changesFiles", "$CHANGES.files", place, children) {
    @Composable
    override fun Render(scope: BlockScope) {
        ChangesFilesSection(LocalChangesPaneHost.current ?: return, scope.modifier) { file ->
            CompositionLocalProvider(LocalChangesFile provides file) { RenderPlain(children) }
        }
    }

    override fun withChildren(children: List<Block>) = ChangesFilesBlock(children, place)
}

/** The file template: one working-tree file's badge, path and status, a click opens its diff; its placement goes on the row. */
object ChangesFile : Block("changesFile", "$CHANGES.file") {
    @Composable
    override fun Render(scope: BlockScope) {
        ChangesFileRow(LocalChangesPaneHost.current ?: return, LocalChangesFile.current ?: return, scope.modifier)
    }
}

/**
 * What the picked branch changed (unknown wears its placement; with files a placement other than the default gets a
 * Column). Its children are drawn once per file, with that file in a composition local: `infantry.shell.changes.branchFile`
 * is the branch file template.
 */
class ChangesBranchFilesBlock(children: List<Block>, place: Place = Place()) : Block("changesBranchFiles", "$CHANGES.branchFiles", place, children) {
    @Composable
    override fun Render(scope: BlockScope) {
        ChangesBranchFilesSection(LocalChangesPaneHost.current ?: return, scope.modifier) { file ->
            CompositionLocalProvider(LocalChangesBranchFile provides file) { RenderPlain(children) }
        }
    }

    override fun withChildren(children: List<Block>) = ChangesBranchFilesBlock(children, place)
}

/** The branch file template: one file the branch changed, a click opens its diff; its placement goes on the row. */
object ChangesBranchFile : Block("changesBranchFile", "$CHANGES.branchFile") {
    @Composable
    override fun Render(scope: BlockScope) {
        ChangesBranchFileRow(LocalChangesPaneHost.current ?: return, LocalChangesBranchFile.current ?: return, scope.modifier)
    }
}

/**
 * The History mode's commits (unknown and empty wear its placement; with commits a placement other than the default gets
 * a Column). Its children are drawn once per commit, with that commit in a composition local:
 * `infantry.shell.changes.commit` is the commit template.
 */
class ChangesHistoryBlock(children: List<Block>, place: Place = Place()) : Block("changesHistory", "$CHANGES.history", place, children) {
    @Composable
    override fun Render(scope: BlockScope) {
        ChangesHistorySection(LocalChangesPaneHost.current ?: return, scope.modifier) { commit ->
            CompositionLocalProvider(LocalChangesCommit provides commit) { RenderPlain(children) }
        }
    }

    override fun withChildren(children: List<Block>) = ChangesHistoryBlock(children, place)
}

/** The commit template: short hash, subject, author and time; its placement goes on the row. */
object ChangesCommit : Block("changesCommit", "$CHANGES.commit") {
    @Composable
    override fun Render(scope: BlockScope) {
        LocalChangesPaneHost.current ?: return
        ChangesCommitRow(LocalChangesCommit.current ?: return, scope.modifier)
    }
}

val ShellChanges = ChangesPaneBlock(
    listOf(
        ChangesEmpty, ChangesTitle, ChangesUnsupported, ChangesNotGit, ChangesWorktree, ChangesHeader, ChangesModeToggle,
        ChangesBranch, ChangesHint, ChangesFilesBlock(listOf(ChangesFile)), ChangesBranchFilesBlock(listOf(ChangesBranchFile)),
        ChangesHistoryBlock(listOf(ChangesCommit)),
    ),
)

/**
 * The Changes tab's content: [ShellChanges] as the window resolved it (a module's override in), filling the tab. [openDiff]
 * is the file the diff modal shows; a click on a file calls [onOpenDiff].
 */
@Composable
fun ChangesTab(openDiff: DiffTarget?, onOpenDiff: ((DiffTarget) -> Unit)?) {
    CompositionLocalProvider(LocalChangesTabDiff provides ChangesTabDiff(openDiff, onOpenDiff)) {
        (LocalShellContext.current?.layout?.tabTree(CHANGES) ?: ShellChanges).Render(BlockScope(Modifier, null))
    }
}
