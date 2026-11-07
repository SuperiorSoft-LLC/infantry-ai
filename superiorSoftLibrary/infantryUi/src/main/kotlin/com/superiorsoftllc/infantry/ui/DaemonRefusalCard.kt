// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** FIRSTRUN-ERR-1: what the first screen says when this computer's daemon refuses to start. */
internal object DaemonRefusal {
    const val TITLE = "Infantry AI could not start its background service"

    /** The refusal [reason], where to look ([logsDir] null = dev, no file log) and the README section to read. */
    fun details(reason: String, logsDir: String?): String = buildString {
        append(reason)
        append("\n\nLog folder: ").append(logsDir ?: "none (a dev run logs to the console)")
        append(" (also Configuration > Logs).")
        append("\nSee the README, \"Headless server\".")
    }
}

@Composable
internal fun DaemonRefusalCard(reason: String, logsDir: String?, onQuit: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(DaemonRefusal.TITLE, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.error)
        SelectionContainer { Text(DaemonRefusal.details(reason, logsDir), style = MaterialTheme.typography.bodyMedium) }
        Button(onClick = onQuit) { Text("Quit") }
    }
}
