package com.superiorsoftllc.agenthq.ui

import androidx.compose.runtime.Composable
import com.superiorsoftllc.agenthq.ui.model.PickedModel
import com.superiorsoftllc.agenthq.ui.shell.ExecutorContext

/**
 * PICKER-ALL-2: the harness + model of a profile form, through the shared [SeededExecutorPicker]. [provider] null is the
 * daemon's default harness and is kept null on Save until the user picks one; [model] "" is the harness default.
 * S4c: out of core, reading [executor] in place of the app state; the Profiles and Roles panes use it.
 */
@Composable
internal fun ProfileExecutorField(executor: ExecutorContext, key: Any?, provider: String?, model: String, onChange: (String?, String) -> Unit) {
    val stored = (provider ?: executor.providers.firstOrNull { it.default }?.id)?.let { PickedModel(it, model.ifEmpty { null }) }
    SeededExecutorPicker(
        executor.providers, executor.modelCatalog, stored, key, PROFILE_EXECUTOR_TAGS,
        onPick = { picked -> onChange(picked?.provider ?: provider, picked?.model.orEmpty()) },
    )
}
