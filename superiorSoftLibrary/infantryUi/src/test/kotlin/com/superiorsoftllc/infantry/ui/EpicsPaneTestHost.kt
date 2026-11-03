// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.runtime.Composable
import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.net.Controller
import com.superiorsoftllc.infantry.ui.shell.CoreEpics
import com.superiorsoftllc.infantry.plugins.official.epics.EpicsPane as OfficialEpicsPane

/**
 * RUNTIME-11: the Epics pane over the window's [state] and [controller], for the pane tests. It lived in infantryUiCore; the
 * official jar reads only the plugin API's epics context, so the Controller adapter is the tests' own.
 */
@Composable
fun EpicsPane(state: AppState, controller: Controller) = OfficialEpicsPane(state, CoreEpics(controller))
