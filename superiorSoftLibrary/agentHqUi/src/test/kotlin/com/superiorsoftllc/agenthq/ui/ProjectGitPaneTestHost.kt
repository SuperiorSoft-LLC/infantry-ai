package com.superiorsoftllc.agenthq.ui

import androidx.compose.runtime.Composable
import com.superiorsoftllc.agenthq.plugins.official.git.GitBaseSection
import com.superiorsoftllc.agenthq.plugins.official.git.GitBranchesSection
import com.superiorsoftllc.agenthq.plugins.official.git.GitDiffSection
import com.superiorsoftllc.agenthq.plugins.official.git.GitDirectorySection
import com.superiorsoftllc.agenthq.plugins.official.git.GitHistorySection
import com.superiorsoftllc.agenthq.plugins.official.git.GitPaneFrame
import com.superiorsoftllc.agenthq.plugins.official.git.GitPipelineSection
import com.superiorsoftllc.agenthq.plugins.official.git.GitTreeSection
import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.net.Controller
import com.superiorsoftllc.agenthq.ui.shell.CoreGit
import com.superiorsoftllc.agenthq.ui.shell.CoreLink
import com.superiorsoftllc.agenthq.ui.shell.CoreSession

/**
 * RUNTIME-6: the whole Git pane over the window's [state] and [controller], for the pane tests. It lived in Infantry_Git;
 * the official jar reads only the plugin API contexts, so the AppState / Controller adapter is the tests' own now.
 */
@Composable
fun ProjectGitPane(state: AppState, controller: Controller) {
    GitPaneFrame(CoreLink(state), CoreSession(state), CoreGit(state, controller)) { git ->
        GitBaseSection(git)
        GitDirectorySection(git)
        GitTreeSection(git)
        GitDiffSection(git)
        GitBranchesSection(git)
        GitPipelineSection(git)
        GitHistorySection(git)
    }
}
