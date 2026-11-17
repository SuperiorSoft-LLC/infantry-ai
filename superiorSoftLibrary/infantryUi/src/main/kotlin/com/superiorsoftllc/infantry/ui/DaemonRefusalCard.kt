// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import com.superiorsoftllc.infantry.ui.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
internal fun DaemonRefusalCard(reason: String, logsDir: String?, onQuit: () -> Unit, onRetry: (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(DaemonRefusal.TITLE, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.error)
        SelectionContainer { Text(DaemonRefusal.details(reason, logsDir), style = MaterialTheme.typography.bodyMedium) }
        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (onRetry != null) Button(onClick = onRetry) { Text("Retry") }
            Button(onClick = onQuit) { Text("Quit") }
        }
    }
}

/**
 * SPLASH-STUCK-1: runs [start] (off the UI thread) and shows [content] under the splash with its url; a refusal or ANY
 * thrown failure replaces the splash with [DaemonRefusalCard] (message, log path, Retry / Quit), never an endless splash.
 */
@Composable
internal fun DaemonStartGate(
    initialUrl: String?,
    start: () -> String?,
    refusalOf: () -> String?,
    logsDir: String?,
    onQuit: () -> Unit,
    content: @Composable (url: String?) -> Unit,
) {
    var url by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(initialUrl) }
    var refusal by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var attempt by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(attempt) {
        if (url != null) return@LaunchedEffect
        refusal = null
        try {
            val started = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { start() }
            if (started != null) url = started
            else refusal = refusalOf() ?: "This build has no daemon and no server is configured."
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Throwable) {
            System.err.println("[infantry-ui] daemon start failed: $e")
            refusal = e.message ?: e.toString()
        }
    }
    val r = refusal
    if (r != null) DaemonRefusalCard(r, logsDir, onQuit, onRetry = { attempt++ })
    else SplashHost { content(url) }
}
