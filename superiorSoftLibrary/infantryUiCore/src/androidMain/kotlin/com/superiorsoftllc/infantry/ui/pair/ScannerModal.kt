// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.pair

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.Dialog

@Composable
actual fun ScannerModal(onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismissRequest) { content() }
}
