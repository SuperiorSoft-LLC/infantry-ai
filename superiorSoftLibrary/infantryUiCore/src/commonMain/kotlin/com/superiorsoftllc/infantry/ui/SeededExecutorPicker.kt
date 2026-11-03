// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.superiorsoftllc.infantry.ui.model.ExecutorPickerModel
import com.superiorsoftllc.infantry.ui.model.PickedModel
import com.superiorsoftllc.infantry.ui.protocol.ProviderInfo
import com.superiorsoftllc.infantry.ui.protocol.ProviderModels

/** The config pane's model rows, tagged "catalog-model-<harness>|<exact id>" (unchanged from PICKER 2/2). */
const val CATALOG_MODEL_TAG_PREFIX = "catalog-model-"

/** The profile forms' model search box (the old dropdown's tag, kept). */
const val MODEL_PICKER_TAG = "model-picker"

/** The profile forms' provider search box (the old sub-provider dropdown's tag, kept). */
const val MODEL_SUB_PROVIDER_TAG = "model-sub-provider"

/** One model row of a profile form, tagged with its exact wire id ("model-option-opencode-go/gpt-6-luna"). */
const val MODEL_OPTION_TAG_PREFIX = "model-option-"

/** The profile forms' tags: both forms (Profiles pane, Roles pane) share them, so they cannot drift. */
val PROFILE_EXECUTOR_TAGS = ExecutorPickerTags(
    harness = { "model-harness-$it" },
    providerSearch = MODEL_SUB_PROVIDER_TAG,
    providerAll = "$MODEL_SUB_PROVIDER_TAG-all",
    provider = { "$MODEL_SUB_PROVIDER_TAG-$it" },
    modelSearch = MODEL_PICKER_TAG,
    model = { _, id -> MODEL_OPTION_TAG_PREFIX + id },
    freeText = "model-freetext",
)

/** The config pane's tags: models keep their "catalog-model-<harness>|<id>" form. */
val CONFIG_EXECUTOR_TAGS = ExecutorPickerTags(model = { harness, id -> "$CATALOG_MODEL_TAG_PREFIX$harness|$id" })

/**
 * PICKER-ALL-2: an [ExecutorPicker] over a value the CALLER stores ([stored]). Until the user touches it, it is
 * re-seeded from [stored] on every frame (so late `providers` / `models` frames fold in), and [onPick] is never
 * called: a Save then sends the stored value unchanged. After a touch it keeps the user's own state, re-seeding only
 * when the frames change, and reports each new harness/model through [onPick]. Typing in a search box changes no
 * value, so it reports nothing. [key] resets the draft (a different profile, a reopened form).
 */
@Composable
fun SeededExecutorPicker(
    harnesses: List<ProviderInfo>,
    catalog: List<ProviderModels>,
    stored: PickedModel?,
    key: Any?,
    tags: ExecutorPickerTags,
    onPick: (PickedModel?) -> Unit,
    modifier: Modifier = Modifier,
    textColor: androidx.compose.ui.graphics.Color = Palette.Text,
    /**
     * PICKER-UX: a harness whose catalog lists one model has it picked and reported through [onPick] (the New agent
     * spawn row); off for the editors, where a Save must send the stored value unchanged.
     */
    preselectSole: Boolean = false,
) {
    val full = ExecutorPickerModel.catalogWithProviderLists(harnesses, catalog)
    var touched by remember(key) { mutableStateOf(false) }
    var draft by remember(key) { mutableStateOf(ExecutorPickerModel.seeded(harnesses, full, stored)) }
    val shown = when {
        !touched -> ExecutorPickerModel.seeded(harnesses, full, stored)
        // New frames: re-seed from the user's pick, keeping the vendor they narrowed to (a late `models` frame must not undo it).
        draft.harnesses != harnesses || draft.catalog != full ->
            ExecutorPickerModel.seeded(harnesses, full, draft.picked).let { s -> draft.group?.let { g -> s.withGroup(g) } ?: s }
        else -> draft
    }
    if (preselectSole) {
        val withSole = shown.withSoleModel()
        androidx.compose.runtime.LaunchedEffect(withSole.picked) {
            if (withSole.picked != shown.picked) {
                if (touched) draft = withSole
                onPick(withSole.picked)
            }
        }
    }
    ExecutorPicker(
        shown,
        { next ->
            touched = true
            draft = next
            if (next.picked != shown.picked) onPick(next.picked)
        },
        modifier = modifier, tags = tags, textColor = textColor,
    )
}
