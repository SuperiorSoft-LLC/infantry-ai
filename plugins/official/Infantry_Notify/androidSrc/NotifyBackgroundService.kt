// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.superiorsoftllc.infantry.ui.net.DaemonClient
import com.superiorsoftllc.infantry.ui.protocol.Envelope
import com.superiorsoftllc.infantry.ui.shell.ConnectionStore
import com.superiorsoftllc.infantry.ui.shell.KeystoreTokenCipher
import com.superiorsoftllc.infantry.ui.shell.PrefsKeyValueStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** NOTIFY-PUSH-1: process-wide state shared by the UI listener and the background service (one process). */
internal object NotifyProcess {
    /** True while the activity is started: its own listener shows notifications, so the service stays quiet. */
    @Volatile var uiVisible: Boolean = false
    val dedupe = NotifyDedupe()
    const val PREFS = "infantry.notify"
    const val KEY_BACKGROUND = "background"

    fun enabled(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_BACKGROUND, false)

    /** Turns the background connection on or off (and remembers it); on starts the foreground service, off stops it. */
    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_BACKGROUND, on).apply()
        sync(context)
    }

    /** Starts the service when the setting is on, stops it when off. Safe to call again. */
    fun sync(context: Context) {
        val intent = Intent(context, NotifyBackgroundService::class.java)
        if (!enabled(context)) { context.stopService(intent); return }
        runCatching { if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent) }
    }
}

/**
 * NOTIFY-PUSH-1: keeps one daemon connection while the app is closed or backgrounded and shows every `notify_push` for
 * Android as a system notification (a tap opens the ticket). A foreground service with a silent, minimum-importance
 * notification; no third party. Off by default (setting "Background notifications" in the Notifications card) because it
 * costs a persistent notification and some battery. The daemon keeps a per-device queue, so what arrived while the socket
 * was down is asked for again (`notify_pending`) after every reconnect.
 */
class NotifyBackgroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var client: DaemonClient? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this, FOREGROUND_ID, foregroundNotification(),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        if (client == null) connect()
        return START_STICKY
    }

    private fun connect() {
        val saved = ConnectionStore(
            plain = PrefsKeyValueStore(getSharedPreferences("infantry", Context.MODE_PRIVATE)),
            secure = PrefsKeyValueStore(getSharedPreferences("infantry_secure", Context.MODE_PRIVATE)),
            cipher = KeystoreTokenCipher(),
        ).load() ?: run { stopSelf(); return }
        val c = DaemonClient(scope)
        client = c
        val notifier = AndroidNotifier(this)
        scope.launch {
            c.frames.collect { env ->
                when (env.type) {
                    NotifyFrames.PUSH -> NotifyPayload.parse(env.data)?.let { handle(c, notifier, it) }
                    NotifyFrames.PENDING_LIST -> NotifyPayload.parseList(env.data).forEach { handle(c, notifier, it) }
                }
            }
        }
        // After every ready (first connect and each reconnect): ask for what this device has not shown.
        scope.launch { c.opened.collect { c.send(Envelope(NotifyFrames.PENDING, NotifyFrames.pending(notifier.platform))) } }
        c.connect(saved.url, saved.token)
    }

    private suspend fun handle(c: DaemonClient, notifier: AndroidNotifier, p: NotifyPayload) {
        if (NotifyProcess.uiVisible) return // the activity's listener shows it
        if (!NotifyRouting.shows(p.target, notifier.platform) || !NotifyProcess.dedupe.firstTime(p.id)) return
        if (!runCatching { notifier.show(p) }.getOrDefault(false)) return
        p.soundData?.let { d -> decodeSound(d)?.let { runCatching { notifier.play(p.soundType ?: "audio/wav", it) } } }
        c.send(Envelope(NotifyFrames.ACK, NotifyFrames.ack(p.id, notifier.platform)))
    }

    private fun foregroundNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Background connection", NotificationManager.IMPORTANCE_MIN).apply { setSound(null, null) })
        }
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let { PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE) }
        return (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Infantry AI")
            .setContentText("Listening for milestone notifications")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        client?.disconnect()
        client = null
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val FOREGROUND_ID = 4712
        const val CHANNEL = "infantry.background"
    }
}
