// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

/**
 * POPUP-ROUND-1: the one dropdown menu every plugin and the shell open: the material menu drawn in
 * [Palette.PopupShape] on [Palette.Raised], so every menu shares one corner radius and one change restyles them all.
 */
@Composable
fun InfantryDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    content: @Composable ColumnScope.() -> Unit,
) = DropdownMenu(
    expanded = expanded, onDismissRequest = onDismissRequest, modifier = modifier, offset = offset,
    shape = Palette.PopupShape, containerColor = Palette.Raised, content = content,
)
