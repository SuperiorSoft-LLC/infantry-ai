package com.superiorsoftllc.agenthq.ui

import androidx.compose.runtime.Composable
import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.net.Controller
import com.superiorsoftllc.agenthq.ui.shell.CoreEpics
import com.superiorsoftllc.agenthq.plugins.official.epics.EpicsPane as OfficialEpicsPane

/**
 * RUNTIME-11: the Epics pane over the window's [state] and [controller], for the pane tests. It lived in agenthqUiCore; the
 * official jar reads only the plugin API's epics context, so the Controller adapter is the tests' own.
 */
@Composable
fun EpicsPane(state: AppState, controller: Controller) = OfficialEpicsPane(state, CoreEpics(controller))
