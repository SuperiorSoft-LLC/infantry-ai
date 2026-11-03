package com.superiorsoftllc.infantry.ui

import androidx.compose.runtime.Composable
import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.net.Controller
import com.superiorsoftllc.infantry.ui.shell.CoreLink
import com.superiorsoftllc.infantry.ui.shell.CorePrompts
import com.superiorsoftllc.infantry.ui.shell.CoreSkills
import com.superiorsoftllc.infantry.plugins.official.prompts.PromptsPane as OfficialPromptsPane
import com.superiorsoftllc.infantry.plugins.official.skills.SkillsPane as OfficialSkillsPane

/**
 * RUNTIME-9: the Prompts and Skills panes over the window's [state] and [controller], for the pane tests. They lived in
 * infantryUiCore; the official jars read only the plugin API contexts, so the AppState / Controller adapter is the tests' own.
 */
@Composable
fun PromptsPane(state: AppState, controller: Controller, onInsert: (String) -> Unit) =
    OfficialPromptsPane(CoreLink(state), CorePrompts(state, controller), onInsert)

@Composable
fun SkillsPane(state: AppState, controller: Controller, onInsert: (String) -> Unit) =
    OfficialSkillsPane(CoreLink(state), CoreSkills(state, controller), onInsert)
