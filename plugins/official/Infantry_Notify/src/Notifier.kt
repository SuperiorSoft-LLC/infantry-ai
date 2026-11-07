// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.notify

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.Flow
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * NOTIFY-1: one platform's system notification: the Windows tray toast (desktop), the Android "Milestones" channel, the
 * browser Notification API (web). Each actual lives in jvmSrc / androidSrc / wasmSrc.
 */
interface Notifier {
    /** `desktop`, `android` or `web`: matched against a notification's `target`. */
    val platform: String

    /** Shows [p] as a system notification; false when this platform cannot (no tray, permission refused). */
    fun show(p: NotifyPayload): Boolean

    /** Plays one sound file ([type] audio/wav, audio/mpeg or audio/ogg); false when this platform cannot play it. */
    fun play(type: String, bytes: ByteArray): Boolean

    /** The ticket ids of the notifications the owner clicked (the listener opens each one on the board). */
    val opened: Flow<String>

    /** NOTIFY-PUSH-1: the opt-in background connection of this platform (Android's foreground service); null where there is none. */
    val backgroundPush: BackgroundPush? get() = null
}

/** NOTIFY-PUSH-1: a switch for the platform's background notification connection. */
interface BackgroundPush {
    var enabled: Boolean
}

@Composable
internal expect fun rememberNotifier(): Notifier

@OptIn(ExperimentalEncodingApi::class)
internal fun decodeSound(base64: String): ByteArray? = runCatching { Base64.decode(base64) }.getOrNull()
