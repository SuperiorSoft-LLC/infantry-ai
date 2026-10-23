package com.superiorsoftllc.agenthq.ui.pair

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.Dialog

@Composable
actual fun ScannerModal(onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismissRequest) { content() }
}
