// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.runtime.Composable
import com.superiorsoftllc.infantry.ui.model.PickedModel
import com.superiorsoftllc.infantry.ui.shell.ExecutorContext

/**
 * PICKER-ALL-2: the harness + model of a profile form, through the shared [SeededExecutorPicker]. [provider] null is the
 * daemon's default harness and is kept null on Save until the user picks one; [model] "" is the harness default.
 * S4c: out of core, reading [executor] in place of the app state; the Profiles and Roles panes use it.
 * RUNTIME-12: in the kit (public), since the official Infantry_Profiles and Infantry_Roles jars both draw it.
 */
@Composable
fun ProfileExecutorField(executor: ExecutorContext, key: Any?, provider: String?, model: String, onChange: (String?, String) -> Unit) {
    val stored = (provider ?: executor.providers.firstOrNull { it.default }?.id)?.let { PickedModel(it, model.ifEmpty { null }) }
    SeededExecutorPicker(
        executor.providers, executor.modelCatalog, stored, key, PROFILE_EXECUTOR_TAGS,
        onPick = { picked -> onChange(picked?.provider ?: provider, picked?.model.orEmpty()) },
    )
}
