// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.shell

import java.io.File

/**
 * Android host. Settings come from ordered sources supplied by the entry point (Intent extras, then saved
 * preferences) under the same keys the CLI uses. The default daemon URL is the emulator's alias for the
 * development machine; a physical device must be given a URL.
 */
class AndroidPlatformHost(
    private val settings: LayeredSettings,
    /** MOBILE-CONNCHIP-1: the title-bar connection chip's action (the gate reopens its ConnectScreen); null = dialog. */
    override val onChangeConnection: (() -> Unit)? = null,
    /** VOICE-1: Android TextToSpeech, built by the entry point that has a Context; null = toast only. */
    override val speaker: com.superiorsoftllc.infantry.ui.voice.Speaker? = null,
    /** PAIR-1: the Android store (URL in prefs, token sealed by the Keystore); one slot, so it keeps the latest server. */
    override val savedServers: com.superiorsoftllc.infantry.ui.pair.SavedServers? = null,
    /** Per-device UI preferences (Stats chart scales); null = not persisted. */
    private val uiPrefs: KeyValueStore? = null,
    /** BUILD-STAMP-1: the APK's versionName, which infantryAndroid/build.gradle.kts sets to the whole build string. */
    override val appBuild: String? = null,
    /** Incoming-comms sound: MediaPlayer, the app-private copy of the owner's mp3, and the system document picker. */
    override val soundPlayer: com.superiorsoftllc.infantry.ui.sound.SoundPlayer? = null,
    override val customSoundStore: com.superiorsoftllc.infantry.ui.sound.CustomSoundStore? = null,
    private val soundPicker: ((onPicked: (PickedImage?) -> Unit) -> Unit)? = null,
    /** ANDROID-ATTACH-1: the system picker (imagesOnly, maxBytes, callback), wired to the Activity result registry. */
    private val attachPicker: ((imagesOnly: Boolean, maxBytes: Long, onPicked: (PickedImage?) -> Unit) -> Unit)? = null,
    /** UPDATER-1: the application context, for a plugin's self-update (download to cache, the system installer); null = none. */
    val appContext: android.content.Context? = null,
) : PlatformHost {
    /** UPDATER-1: a plugin may offer the APK update ([SELF_UPDATE_REGISTRY] key `android`) when there is a context to install with. */
    override val selfUpdateKey: String? get() = if (appContext != null) "android" else null

    /**
     * PATH-PICKER-1: the system document picker answers a content:// URI on the phone, never a path the daemon (on another
     * computer) can open, so path fields have no Browse here; they say to type the daemon computer's path.
     */
    override val pathPickerNote: String get() = PATH_TYPED_NOTE

    override val canPickAttachment: Boolean get() = attachPicker != null
    override fun pickAttachment(title: String, imagesOnly: Boolean, maxBytes: Long, onPicked: (PickedImage?) -> Unit) {
        attachPicker?.invoke(imagesOnly, maxBytes, onPicked) ?: onPicked(null)
    }

    override val canPickSound: Boolean get() = soundPicker != null
    override fun pickSound(onPicked: (PickedImage?) -> Unit) { soundPicker?.invoke(onPicked) ?: onPicked(null) }

    override fun uiPref(key: String): String? = uiPrefs?.get(key)
    override fun saveUiPref(key: String, value: String) { uiPrefs?.put(key, value) }

    /** PAIR-1: the phone camera, CameraX into ML Kit's bundled model. */
    override val qrScanner: (@androidx.compose.runtime.Composable (onCode: (String) -> Unit, onClose: () -> Unit) -> Unit)? =
        { onCode, onClose -> com.superiorsoftllc.infantry.ui.pair.CameraQrScanner(onCode, onClose) }

    /** MODAL-BLUR: Modifier.blur renders only on Android 12+ (API 31); older versions keep the dark scrim. */
    override val supportsBlur: Boolean = android.os.Build.VERSION.SDK_INT >= 31

    override val defaultDaemonUrl: String = "ws://10.0.2.2:8787/ws"
    override fun setting(key: String): String? = settings[key]
    override fun readTextFile(path: String): String? = runCatching { File(path).readText() }.getOrNull()
    override fun readBytes(path: String): ByteArray? = runCatching { File(path).takeIf { it.isFile }?.readBytes() }.getOrNull()

    override fun fileExists(path: String): Boolean? = runCatching { File(path).isFile }.getOrNull()
}

/** Context-free default (no configured sources): the emulator default URL and no token. The activity builds a real host. */
actual fun defaultPlatformHost(): PlatformHost = AndroidPlatformHost(LayeredSettings())
