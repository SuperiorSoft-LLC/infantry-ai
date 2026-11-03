// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.model.Notice

// S4c: the refusal read stays in core (the project picker uses it); the sidebar slot that shows it is app UI.

/**
 * The newest daemon refusal of a project / workspace request, owned by [AppState.projectRefusal]: it clears on a
 * successful save, on a project switch and when the user dismisses it, so it never stays pinned.
 */
fun AppState.projectsRefusal(): Notice? = projectRefusal
