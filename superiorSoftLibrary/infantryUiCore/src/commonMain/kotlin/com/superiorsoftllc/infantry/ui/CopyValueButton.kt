// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.sp

/**
 * Ticket d0b9bdcf: one small "Copy" button for any value meant to be copied (a token, session id, URL, commit SHA).
 * It copies [value] whole, so the owner never has to select a hash or URL by hand. The same button is used by code
 * blocks, so every copy affordance in the app looks and behaves alike.
 */
@Composable
fun CopyValueButton(value: String, modifier: Modifier = Modifier) {
    val clip = LocalClipboardManager.current
    TextButton(onClick = { clip.setText(AnnotatedString(value)) }, modifier = modifier) {
        Text("Copy", color = Palette.Muted, fontSize = 10.sp)
    }
}
