package com.superiorsoftllc.agenthq.ui.shell.blocks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import com.superiorsoftllc.agenthq.ui.GitBaseSection
import com.superiorsoftllc.agenthq.ui.GitBranchesSection
import com.superiorsoftllc.agenthq.ui.GitDiffSection
import com.superiorsoftllc.agenthq.ui.GitDirectorySection
import com.superiorsoftllc.agenthq.ui.GitHistorySection
import com.superiorsoftllc.agenthq.ui.GitPaneFrame
import com.superiorsoftllc.agenthq.ui.GitPaneHost
import com.superiorsoftllc.agenthq.ui.GitPipelineSection
import com.superiorsoftllc.agenthq.ui.GitTreeSection
import com.superiorsoftllc.agenthq.ui.LocalShell
import com.superiorsoftllc.agenthq.ui.shell.LocalShellContext
import com.superiorsoftllc.agenthq.ui.block.layout.Block
import com.superiorsoftllc.agenthq.ui.block.layout.BlockScope
import com.superiorsoftllc.agenthq.ui.block.layout.Place
import com.superiorsoftllc.agenthq.ui.block.layout.RenderPlaced

private const val GIT = "infantry.shell.git"

/** The Git pane's host; null outside [GitPaneBlock] (a child moved elsewhere draws nothing). */
private val LocalGitPaneHost = compositionLocalOf<GitPaneHost?> { null }

/**
 * BLOCKS-LAYOUT L14: the Git tab's content (Infantry_Git), a container of child blocks `infantry.shell.git.<name>` drawn
 * straight into the pane's scrolling column, top to bottom. The frame (column, title, the tree load, the picked file, the
 * last refusal) stays its own. It is not in [CoreShell.root]: the tab draws it ([GitTab]), and overrides address it by id
 * the same way ([CoreShell.tabs]).
 */
class GitPaneBlock(children: List<Block>, place: Place = Place()) : Block("gitPane", GIT, place, children) {
    @Composable
    override fun Render(scope: BlockScope) {
        val ctx = LocalShellContext.current ?: return
        GitPaneFrame(ctx.link, ctx.session, ctx.git, scope.modifier) { git ->
            CompositionLocalProvider(LocalGitPaneHost provides git) { RenderPlaced(children) }
        }
    }

    override fun withChildren(children: List<Block>) = GitPaneBlock(children, place)
}

/** The branch new tickets are cut from; its placement goes on the heading. */
object GitBase : Block("gitBase", "$GIT.base") {
    @Composable
    override fun Render(scope: BlockScope) {
        GitBaseSection(LocalGitPaneHost.current ?: return, scope.modifier)
    }
}

/** The project's directory and the last refusal; its placement goes on the directory line. */
object GitDirectory : Block("gitDirectory", "$GIT.directory") {
    @Composable
    override fun Render(scope: BlockScope) {
        GitDirectorySection(LocalGitPaneHost.current ?: return, scope.modifier)
    }
}

/** The working tree and its changed files (or the older-daemon line); its placement goes on the heading row. */
object GitTree : Block("gitTree", "$GIT.tree") {
    @Composable
    override fun Render(scope: BlockScope) {
        GitTreeSection(LocalGitPaneHost.current ?: return, scope.modifier)
    }
}

/** The picked file's diff (nothing while none is picked); its placement goes on the heading. */
object GitDiff : Block("gitDiff", "$GIT.diff") {
    @Composable
    override fun Render(scope: BlockScope) {
        GitDiffSection(LocalGitPaneHost.current ?: return, scope.modifier)
    }
}

/** The local branches, Switch and New branch from current; its placement goes on the heading. */
object GitBranches : Block("gitBranches", "$GIT.branches") {
    @Composable
    override fun Render(scope: BlockScope) {
        GitBranchesSection(LocalGitPaneHost.current ?: return, scope.modifier)
    }
}

/** The verification pipeline; its placement goes on the heading row. */
object GitPipeline : Block("gitPipeline", "$GIT.pipeline") {
    @Composable
    override fun Render(scope: BlockScope) {
        GitPipelineSection(LocalGitPaneHost.current ?: return, scope.modifier)
    }
}

/** The history; its placement goes on the heading. */
object GitHistory : Block("gitHistory", "$GIT.history") {
    @Composable
    override fun Render(scope: BlockScope) {
        GitHistorySection(LocalGitPaneHost.current ?: return, scope.modifier)
    }
}

val ShellGit = GitPaneBlock(listOf(GitBase, GitDirectory, GitTree, GitDiff, GitBranches, GitPipeline, GitHistory))

/** The Git tab's content: [ShellGit] as the window resolved it (a module's override in), filling the tab. */
@Composable
fun GitTab() {
    (LocalShell.current.tabs.tabTree(GIT) ?: ShellGit).Render(BlockScope(Modifier, null))
}
