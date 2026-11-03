package com.superiorsoftllc.infantry.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.superiorsoftllc.infantry.plugins.official.run.RunConfirmSection
import com.superiorsoftllc.infantry.plugins.official.run.RunEditorSection
import com.superiorsoftllc.infantry.plugins.official.run.RunEmptySection
import com.superiorsoftllc.infantry.plugins.official.run.RunNoticesSection
import com.superiorsoftllc.infantry.plugins.official.run.RunOutputSection
import com.superiorsoftllc.infantry.plugins.official.run.RunPaneFrame
import com.superiorsoftllc.infantry.plugins.official.run.RunPillsSection
import com.superiorsoftllc.infantry.plugins.official.run.RunToolbarSection
import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.net.Controller
import com.superiorsoftllc.infantry.ui.shell.CoreLink
import com.superiorsoftllc.infantry.ui.shell.CoreRun

/**
 * RUNTIME-7: the whole Run pane over the window's [state] and [controller], for the pane tests. It lived in Infantry_Run;
 * the official jar reads only the plugin API contexts, so the AppState / Controller adapter is the tests' own now.
 */
@Composable
fun RunPane(state: AppState, controller: Controller) {
    RunPaneFrame(CoreLink(state), CoreRun(state, controller, LocalRunConsole.current)) { run ->
        RunEmptySection(run)
        RunToolbarSection(run)
        RunPillsSection(run)
        RunConfirmSection(run)
        RunEditorSection(run, Modifier.weight(1f))
        RunNoticesSection(run)
        RunOutputSection(run, Modifier.weight(1f))
    }
}
