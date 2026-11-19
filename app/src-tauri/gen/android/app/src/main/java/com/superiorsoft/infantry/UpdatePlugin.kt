// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoft.infantry

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import app.tauri.annotation.Command
import app.tauri.annotation.InvokeArg
import app.tauri.annotation.TauriPlugin
import app.tauri.plugin.Invoke
import app.tauri.plugin.JSObject
import app.tauri.plugin.Plugin
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

@InvokeArg
class FetchArgs {
    lateinit var url: String
}

@InvokeArg
class DownloadArgs {
    lateinit var url: String
    lateinit var sha256: String
    var size: Long? = null
    lateinit var version: String
}

/**
 * RUST-UPD-2: the phone's half of the in-app update (after Kotlin's AndroidSelfUpdate). Rust (`android_update.rs`) reads the
 * release feed and decides what is offered; this plugin does the three things only Android can do: fetch the feed text,
 * download the APK (progress polled by the WebView through Rust), and hand it to the system package installer.
 *
 *  - The APK is downloaded WITHOUT any token (the address is a public release asset) into the app cache.
 *  - A size or SHA-256 mismatch deletes the file and nothing is installed.
 *  - The installer (PackageInstaller) shows Android's own confirmation, and the first time asks the user to allow
 *    installs from this app (REQUEST_INSTALL_PACKAGES). Android refuses an APK not signed with the installed app's key.
 * Called from Rust, never from the WebView. States: idle, downloading, verifying, installing, done, error.
 */
@TauriPlugin
class UpdatePlugin(private val activity: Activity) : Plugin(activity) {
    @Volatile private var state = "idle"
    @Volatile private var done = 0L
    @Volatile private var total = 0L
    @Volatile private var message = ""

    private fun set(s: String, msg: String = "") {
        state = s
        message = msg
    }

    @Command
    fun abi(invoke: Invoke) {
        val a = Build.SUPPORTED_ABIS.firstOrNull() ?: ""
        val name = when {
            a.startsWith("arm64") -> "aarch64"
            a.startsWith("armeabi") -> "armv7"
            a == "x86_64" -> "x86_64"
            a == "x86" -> "x86"
            else -> a
        }
        invoke.resolve(JSObject().put("abi", name))
    }

    @Command
    fun fetch(invoke: Invoke) {
        val args = invoke.parseArgs(FetchArgs::class.java)
        Thread({
            try {
                val c = URL(args.url).openConnection() as HttpURLConnection
                c.connectTimeout = TIMEOUT_MS
                c.readTimeout = TIMEOUT_MS
                c.instanceFollowRedirects = true
                val code = c.responseCode
                if (code !in 200..299) {
                    invoke.reject("the update feed answered HTTP $code")
                    return@Thread
                }
                invoke.resolve(JSObject().put("text", c.inputStream.bufferedReader().use { it.readText() }))
            } catch (e: Exception) {
                invoke.reject("the update feed could not be read: ${e.message ?: e::class.simpleName}")
            }
        }, "infantry-update-fetch").apply { isDaemon = true }.start()
    }

    @Command
    fun progress(invoke: Invoke) {
        invoke.resolve(JSObject().put("state", state).put("done", done).put("total", total).put("message", message))
    }

    /** Starts the download, verify and install in the background; the WebView polls [progress]. */
    @Command
    fun download(invoke: Invoke) {
        val args = invoke.parseArgs(DownloadArgs::class.java)
        if (state == "downloading" || state == "verifying" || state == "installing") {
            invoke.reject("an update is already in progress")
            return
        }
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            set("error", "Allow Infantry AI to install apps in the screen that just opened, then press Install again.")
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            invoke.resolve()
            return
        }
        done = 0
        total = args.size ?: 0
        set("downloading")
        invoke.resolve()
        Thread({
            try {
                val apk = fetchApk(args.url, args.version)
                set("verifying")
                val got = sha256(apk)
                if (got != args.sha256.lowercase() || (args.size != null && apk.length() != args.size)) {
                    apk.delete()
                    set("error", "The download does not match its checksum; nothing was installed.")
                    return@Thread
                }
                set("installing")
                install(apk)
            } catch (e: Exception) {
                set("error", "Update failed: ${e.message ?: e::class.simpleName}")
            }
        }, "infantry-update").apply { isDaemon = true }.start()
    }

    private fun fetchApk(url: String, version: String): File {
        val dir = File(activity.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val out = File(dir, "InfantryAI-$version.apk")
        var next = url
        repeat(MAX_REDIRECTS) {
            val c = URL(next).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = TIMEOUT_MS
            c.readTimeout = TIMEOUT_MS
            when (val code = c.responseCode) {
                in 300..399 -> {
                    next = c.getHeaderField("Location") ?: error("redirect without a location")
                    c.disconnect()
                }
                in 200..299 -> {
                    if (c.contentLengthLong > 0) total = c.contentLengthLong
                    c.inputStream.use { input ->
                        out.outputStream().use { o ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                o.write(buf, 0, n)
                                done += n
                            }
                        }
                    }
                    return out
                }
                else -> error("the download answered HTTP $code")
            }
        }
        error("too many redirects")
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = i.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun install(apk: File) {
        val installer = activity.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply { setAppPackageName(activity.packageName) }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val action = "${activity.packageName}.UPDATE_INSTALL_$id"
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                        PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                            @Suppress("DEPRECATION")
                            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                            confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { c.startActivity(it) }
                            return
                        }
                        PackageInstaller.STATUS_SUCCESS -> set("done")
                        else -> set("error", intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "The installer refused the update.")
                    }
                    runCatching { c.unregisterReceiver(this) }
                }
            }
            ContextCompat.registerReceiver(activity, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(activity, id, Intent(action).setPackage(activity.packageName), flags)
            session.commit(pending.intentSender)
        }
    }

    private companion object {
        const val TIMEOUT_MS = 30_000
        const val MAX_REDIRECTS = 8
    }
}
