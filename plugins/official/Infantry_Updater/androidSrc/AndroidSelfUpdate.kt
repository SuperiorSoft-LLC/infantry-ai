// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.updater

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import com.superiorsoftllc.infantry.ui.UpdatesEntry
import com.superiorsoftllc.infantry.ui.net.HttpServerUpdateApi
import com.superiorsoftllc.infantry.ui.shell.AndroidPlatformHost
import com.superiorsoftllc.infantry.ui.shell.SelfUpdateProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/** UPDATER-1: Configuration > Updates on the phone: the daemon's APK offer, downloaded, verified and installed. */
internal actual fun platformSelfUpdate(): SelfUpdateProvider = SelfUpdateProvider { daemonUrl, token, host ->
    val context = (host as? AndroidPlatformHost)?.appContext ?: return@SelfUpdateProvider null
    val current = AndroidSelfUpdate.versionOf(context) ?: return@SelfUpdateProvider null
    UpdatesEntry(current) { AndroidSelfUpdate(context, daemonUrl, token).checkInBackground(current) }
}

/**
 * UPDATER-1: one check.
 *  1. `GET <daemon>/update/client?platform=android&current=<x.y.z>` with the daemon's Bearer token. The daemon holds the
 *     private-repo token (if any) and answers a short-lived token-free download URL plus the APK's size and SHA-256.
 *  2. The APK is downloaded WITHOUT the daemon token (the URL is GitHub's, not the daemon's) into the app cache.
 *  3. A size or SHA-256 mismatch deletes the file and stops.
 *  4. The system installer ([PackageInstaller]) installs it; Android asks the owner to confirm (and, the first time, to
 *     allow installs from this app).
 */
internal class AndroidSelfUpdate(private val context: Context, daemonUrl: String, private val token: String?) {
    private val base = HttpServerUpdateApi.httpBase(daemonUrl)
    private val main = Handler(Looper.getMainLooper())

    fun checkInBackground(current: String) {
        toast("Checking for updates…")
        Thread({ runCatching { run(current) }.onFailure { toast("Update failed: ${it.message ?: it::class.simpleName}") } }, "infantry-self-update").apply { isDaemon = true }.start()
    }

    private fun run(current: String) {
        val offer = offer(current)
        val result = offer.str("result")
        if (result != "available") {
            toast(when (result) {
                "up_to_date" -> "Infantry AI $current is up to date."
                else -> offer.str("message") ?: "No update ($result)."
            })
            return
        }
        val version = offer.str("version") ?: error("the offer has no version")
        val url = offer.str("url") ?: error("the offer has no download URL")
        val sha = offer.str("sha256")?.lowercase() ?: error("the offer has no checksum")
        val size = offer["size"]?.jsonPrimitive?.longOrNull
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            toast("Allow Infantry AI to install apps, then check again.")
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        toast("Downloading Infantry AI $version…")
        val apk = download(url, version)
        val got = sha256(apk)
        if (got != sha || (size != null && apk.length() != size)) {
            apk.delete()
            error("the download does not match its checksum; nothing was installed")
        }
        install(apk, version)
    }

    private fun offer(current: String): JsonObject {
        val c = openUrl("$base/update/client?platform=android&current=${URLEncoder.encode(current, "UTF-8")}")
        token?.takeIf { it.isNotBlank() }?.let { c.setRequestProperty("Authorization", "Bearer $it") }
        c.connectTimeout = TIMEOUT_MS; c.readTimeout = TIMEOUT_MS
        val code = c.responseCode
        if (code == 404) error("this server has no phone updates (no Infantry_Updater)")
        if (code == 401 || code == 403) error("the server refused the token (HTTP $code)")
        val text = (if (code in 200..299) c.inputStream else c.errorStream ?: c.inputStream).bufferedReader().use { it.readText() }
        return Json.parseToJsonElement(text).jsonObject
    }

    /** Downloads [url] (no Authorization header: a token-free signed or public URL) into the cache. */
    private fun download(url: String, version: String): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val out = File(dir, "InfantryAI-$version.apk")
        var next = url
        repeat(MAX_REDIRECTS) {
            val c = openUrl(next)
            c.instanceFollowRedirects = false
            c.connectTimeout = TIMEOUT_MS; c.readTimeout = TIMEOUT_MS
            when (val code = c.responseCode) {
                in 300..399 -> { next = c.getHeaderField("Location") ?: error("redirect without a location"); c.disconnect() }
                in 200..299 -> { c.inputStream.use { input -> out.outputStream().use { input.copyTo(it) } }; return out }
                else -> error("the download answered HTTP $code")
            }
        }
        error("too many redirects")
    }

    private fun install(apk: File, version: String) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply { setAppPackageName(context.packageName) }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out -> apk.inputStream().use { it.copyTo(out) }; session.fsync(out) }
            val action = "${context.packageName}.UPDATER_INSTALL_$id"
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                        PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                            @Suppress("DEPRECATION")
                            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                            confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { c.startActivity(it) }
                            return
                        }
                        PackageInstaller.STATUS_SUCCESS -> toast("Infantry AI $version installed.")
                        else -> toast("Install failed ($status): ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "no reason given"}")
                    }
                    runCatching { c.unregisterReceiver(this) }
                    apk.delete()
                }
            }
            val filter = IntentFilter(action)
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else @Suppress("UnspecifiedRegisterReceiverFlag") context.registerReceiver(receiver, filter)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(context, id, Intent(action).setPackage(context.packageName), flags)
            toast("Installing Infantry AI $version…")
            session.commit(pending.intentSender)
        }
    }

    private fun toast(text: String) = main.post { Toast.makeText(context, text, Toast.LENGTH_LONG).show() }

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.takeIf { it.isString }?.content

    companion object {
        private const val TIMEOUT_MS = 30_000
        private const val MAX_REDIRECTS = 5
        /** PendingIntent.FLAG_MUTABLE (API 31): the installer fills the status extras in. */
        private const val FLAG_MUTABLE = 0x02000000

        /** `x.y.z` of the installed APK (its versionName carries the whole build string `1.0.1+20261023...`). */
        fun versionOf(context: Context): String? = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName?.substringBefore('+')?.trim()?.takeIf { it.isNotEmpty() }
        }.getOrNull()

        private fun sha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val buf = ByteArray(1 shl 16); while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

private fun openUrl(url: String) = URL(url).openConnection() as HttpURLConnection
