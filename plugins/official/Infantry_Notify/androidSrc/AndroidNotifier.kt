// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.notify

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.util.Consumer
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.io.File

/** The intent extra that carries the ticket of a tapped notification. */
internal const val EXTRA_TICKET = "infantry.notify.ticketId"

@Composable
internal actual fun rememberNotifier(): Notifier {
    val context = LocalContext.current
    val notifier = remember(context) { AndroidNotifier(context) }
    // A tap re-opens the activity with the ticket in its intent: the launch intent, and every new one after.
    DisposableEffect(context) {
        val activity = context as? ComponentActivity
        activity?.intent?.let(notifier::take)
        val listener = Consumer<Intent> { notifier.take(it) }
        activity?.addOnNewIntentListener(listener)
        onDispose { activity?.removeOnNewIntentListener(listener) }
    }
    return notifier
}

/**
 * Android's system notification. Two channels: "Milestones" (info and milestone, default importance) and "Urgent" (high,
 * heads-up). Both channels are silent: the notification's own sound plays through MediaPlayer, so every notification can
 * carry a different sound. On Android 13+ the POST_NOTIFICATIONS permission is asked once, on the first notification.
 * Delivery needs the app connected (shown on the next connect otherwise); background push is a follow-up.
 */
internal class AndroidNotifier(private val context: Context) : Notifier {
    override val platform = "android"
    private val clicks = MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val opened: Flow<String> = clicks
    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private var player: MediaPlayer? = null

    fun take(intent: Intent) {
        val ticket = intent.getStringExtra(EXTRA_TICKET) ?: return
        intent.removeExtra(EXTRA_TICKET)
        clicks.tryEmit(ticket)
    }

    override fun show(p: NotifyPayload): Boolean {
        if (!permitted()) return false
        channels()
        val launch = (context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return false).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            p.ticketId?.let { putExtra(EXTRA_TICKET, it) }
        }
        val tap = if (p.ticketId == null && p.url != null) Intent(Intent.ACTION_VIEW, android.net.Uri.parse(p.url)) else launch
        val id = NotifyRouting.androidId(p.id)
        val pending = PendingIntent.getActivity(context, id, tap, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val channel = NotifyRouting.androidChannel(p.level)
        val n = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, channel) else @Suppress("DEPRECATION") Notification.Builder(context))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(p.title)
            .setContentText(p.body)
            .setStyle(Notification.BigTextStyle().bigText(p.body))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        return runCatching { manager.notify(id, n); true }.getOrDefault(false)
    }

    /** True when notifications may be posted; on Android 13+ asks the owner once (the answer applies to the next one). */
    private fun permitted(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return true
        val prefs = context.getSharedPreferences("infantry.notify", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("asked", false)) {
            (context as? Activity)?.let { a ->
                prefs.edit().putBoolean("asked", true).apply()
                a.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), PERMISSION_REQUEST)
            }
        }
        return false
    }

    private fun channels() {
        if (Build.VERSION.SDK_INT < 26) return
        fun make(id: String, name: String, importance: Int) = NotificationChannel(id, name, importance).apply { setSound(null, null) }
        manager.createNotificationChannel(make(NotifyRouting.CHANNEL_MILESTONES, "Milestones", NotificationManager.IMPORTANCE_DEFAULT))
        manager.createNotificationChannel(make(NotifyRouting.CHANNEL_URGENT, "Urgent", NotificationManager.IMPORTANCE_HIGH))
    }

    override fun play(type: String, bytes: ByteArray): Boolean = runCatching {
        val ext = when (type) { "audio/mpeg" -> "mp3"; "audio/ogg" -> "ogg"; else -> "wav" }
        val file = File(context.cacheDir, "infantry-notify.$ext").apply { writeBytes(bytes) }
        player?.let { runCatching { it.release() } }
        player = MediaPlayer().apply {
            setDataSource(file.path)
            setOnCompletionListener { it.release(); if (player === it) player = null }
            prepare()
            start()
        }
        true
    }.getOrDefault(false)

    private companion object { const val PERMISSION_REQUEST = 4711 }
}
