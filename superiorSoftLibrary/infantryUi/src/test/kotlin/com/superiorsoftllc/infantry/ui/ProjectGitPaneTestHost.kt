// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.runtime.Composable
import com.superiorsoftllc.infantry.plugins.official.git.GitBaseSection
import com.superiorsoftllc.infantry.plugins.official.git.GitBranchesSection
import com.superiorsoftllc.infantry.plugins.official.git.GitDiffSection
import com.superiorsoftllc.infantry.plugins.official.git.GitDirectorySection
import com.superiorsoftllc.infantry.plugins.official.git.GitHistorySection
import com.superiorsoftllc.infantry.plugins.official.git.GitPaneFrame
import com.superiorsoftllc.infantry.plugins.official.git.GitPipelineSection
import com.superiorsoftllc.infantry.plugins.official.git.GitTreeSection
import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.net.Controller
import com.superiorsoftllc.infantry.ui.shell.CoreGit
import com.superiorsoftllc.infantry.ui.shell.CoreLink
import com.superiorsoftllc.infantry.ui.shell.CoreSession

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
