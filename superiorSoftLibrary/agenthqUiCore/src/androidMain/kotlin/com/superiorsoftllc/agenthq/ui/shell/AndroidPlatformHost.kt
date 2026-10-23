package com.superiorsoftllc.agenthq.ui.shell

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
    override val speaker: com.superiorsoftllc.agenthq.ui.voice.Speaker? = null,
    /** PAIR-1: the Android store (URL in prefs, token sealed by the Keystore); one slot, so it keeps the latest server. */
    override val savedServers: com.superiorsoftllc.agenthq.ui.pair.SavedServers? = null,
) : PlatformHost {
    /** PAIR-1: the phone camera, CameraX into ML Kit's bundled model. */
    override val qrScanner: (@androidx.compose.runtime.Composable (onCode: (String) -> Unit, onClose: () -> Unit) -> Unit)? =
        { onCode, onClose -> com.superiorsoftllc.agenthq.ui.pair.CameraQrScanner(onCode, onClose) }

    override val defaultDaemonUrl: String = "ws://10.0.2.2:8787/ws"
    override fun setting(key: String): String? = settings[key]
    override fun readTextFile(path: String): String? = runCatching { File(path).readText() }.getOrNull()
    override fun readBytes(path: String): ByteArray? = runCatching { File(path).takeIf { it.isFile }?.readBytes() }.getOrNull()

    override fun fileExists(path: String): Boolean? = runCatching { File(path).isFile }.getOrNull()
}

/** Context-free default (no configured sources): the emulator default URL and no token. The activity builds a real host. */
actual fun defaultPlatformHost(): PlatformHost = AndroidPlatformHost(LayeredSettings())
