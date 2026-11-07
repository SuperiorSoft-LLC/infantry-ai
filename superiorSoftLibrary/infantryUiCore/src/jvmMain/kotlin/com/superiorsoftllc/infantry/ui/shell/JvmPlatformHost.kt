// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.shell

import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.awtTransferable
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

/**
 * Desktop/JVM host: settings are environment variables (as the CLI reads them), files are local files.
 *
 * PORT-1: the default daemon URL follows the port the daemon actually bound, recorded in
 * `<stateDir>/daemon.port` (state dir = `INFANTRY_STATE_DIR`, property then environment, else `~/.infantry`, as the daemon reads it). With no
 * file it is the fixed 8787. An explicit `INFANTRY_WS_URL` still wins over both (see `ShellConfig`).
 */
class JvmPlatformHost(
    private val env: (String) -> String? = System::getenv,
    private val home: String? = System.getProperty("user.home"),
    /** BUG 464d7f97: the installed app pins its settings as system properties (InstallProfile.apply); read them first. */
    private val property: (String) -> String? = System::getProperty,
) : PlatformHost {
    /** VOICE-1: the OS text-to-speech through a short-lived helper process; no new dependency. */
    override val speaker: com.superiorsoftllc.infantry.ui.voice.Speaker = com.superiorsoftllc.infantry.ui.voice.JvmSpeaker()

    /** The incoming-comms sound: JLayer decodes the mp3, javax.sound plays it. */
    override val soundPlayer: com.superiorsoftllc.infantry.ui.sound.SoundPlayer = com.superiorsoftllc.infantry.ui.sound.JvmSoundPlayer()

    /** The owner's own sound, copied under `~/.infantry/ui` (beside the daemon's state), never read from its original path. */
    override val customSoundStore: com.superiorsoftllc.infantry.ui.sound.CustomSoundStore =
        com.superiorsoftllc.infantry.ui.sound.FileCustomSoundStore(File(home ?: ".", ".infantry/ui/incoming-comms-custom.mp3"))

    /** VOICE-1: whether one of this JVM's windows is the active (focused) one. */
    override fun windowFocused(): Boolean =
        runCatching { java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow != null }.getOrDefault(true)

    override val defaultDaemonUrl: String = "ws://127.0.0.1:${recordedPort() ?: DEFAULT_PORT}/ws"
    override fun setting(key: String): String? = env(key)

    /** BUILD-STAMP-1: `-Dinfantry.build`, set by the packaged launcher, `run` and `runRealDaemon` (infantryUi/build.gradle.kts). */
    override val appBuild: String? get() = System.getProperty("infantry.build")?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * MODAL-BLUR / TEST-SPEED-4: `-Dinfantry.ui.blur=off` makes this host report no blur (the dark scrim instead). The UI
     * tests set it: their CPU renderer draws a 12 dp blur of the whole window at ~150 ms a frame, and every modal open,
     * close and frame under a modal paid it (most of the slowest UI tests' run time). Unset, blur works as before.
     */
    override val supportsBlur: Boolean get() = System.getProperty("infantry.ui.blur")?.trim()?.lowercase() != "off"

    /** MODAL-BLUR: UI prefs live in the per-user Java preferences store (the registry on Windows), never in the daemon's state dir. */
    private val prefs get() = java.util.prefs.Preferences.userRoot().node("com/superiorsoftllc/infantry/ui").also { preferencesReads.incrementAndGet() }
    override fun uiPref(key: String): String? {
        uiPrefsOverride?.let { return it[key] }
        return runCatching { prefs.get(key, null) }.getOrNull()
    }
    override fun saveUiPref(key: String, value: String) {
        uiPrefsOverride?.let { it[key] = value; return }
        runCatching { prefs.put(key, value); prefs.flush() }
    }
    override fun readTextFile(path: String): String? = runCatching { File(path).readText() }.getOrNull()

    /** CLIPATH-1: the native file dialog, when there is a display (never on a headless JVM). */
    override val canPickFiles: Boolean get() = !java.awt.GraphicsEnvironment.isHeadless()

    /**
     * CLIPATH-1 / NATIVE-PICKER-1: the OS's own "open file" dialog (Explorer-style on Windows), modal to the app window;
     * the chosen file's absolute path, or null when cancelled. The dialog runs off the UI thread ([awaitOffUiThread]).
     */
    override fun pickFile(title: String): String? = pickPaths(title, emptyList(), multi = false).firstOrNull()

    /** NATIVE-PICKER-1: several files in one native dialog; images only narrows it to pictures (with an "All files" row). */
    override fun pickAttachments(title: String, imagesOnly: Boolean, maxBytes: Long, onPicked: (List<PickedImage>) -> Unit) {
        val paths = pickPaths(title, if (imagesOnly) IMAGE_EXTENSIONS else emptyList(), multi = true)
        onPicked(paths.mapNotNull { path ->
            val name = File(path).name
            val size = fileSize(path)
            val bytes = if (size != null && size > maxBytes) ByteArray(0) else readBytes(path)
            bytes?.let { PickedImage(name, PickedImage.mimeOf(name), it, size ?: it.size.toLong()) }
        })
    }

    private fun pickPaths(title: String, extensions: List<String>, multi: Boolean): List<String> = runCatching {
        awaitOffUiThread { DesktopPathPicker.choose(folders = false, title, "", extensions, multi) }.orEmpty()
    }.getOrDefault(emptyList())

    /** PATH-PICKER-1: the native folder/file dialog, when there is a display (never on a headless JVM). */
    override val pathPicker: com.superiorsoftllc.infantry.ui.PathPicker?
        get() = if (java.awt.GraphicsEnvironment.isHeadless()) null else DesktopPathPicker

    /** PKG-3: the desktop file manager (Explorer on Windows), when there is a display and the platform supports it. */
    override fun openFolder(path: String): Boolean = runCatching {
        val dir = File(path).apply { mkdirs() }
        if (java.awt.GraphicsEnvironment.isHeadless() || !java.awt.Desktop.isDesktopSupported()) return@runCatching false
        val desktop = java.awt.Desktop.getDesktop()
        if (!desktop.isSupported(java.awt.Desktop.Action.OPEN)) return@runCatching false
        desktop.open(dir)
        true
    }.getOrDefault(false)

    /** PAIR-1: every non-link-local address of an up interface; the pairing picker ranks them (Tailscale first). */
    override fun localAddresses(): List<String> = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress && !it.isAnyLocalAddress }
            .mapNotNull { it.hostAddress?.substringBefore('%') }
    }.getOrDefault(emptyList())

    /** PAIR-1: "Open image": ZXing decodes the QR in a picture. */
    override fun decodeQrImage(bytes: ByteArray): String? = com.superiorsoftllc.infantry.ui.pair.QrImageDecoder.decode(bytes)

    /** USER-PORTRAIT: Skia decodes PNG, JPEG and WebP alike; the square centre crop is drawn at [side] px. */
    override fun portraitPng(bytes: ByteArray, side: Int): ByteArray? = runCatching {
        val image = org.jetbrains.skia.Image.makeFromEncoded(bytes)
        val edge = minOf(image.width, image.height)
        val src = org.jetbrains.skia.Rect.makeXYWH(((image.width - edge) / 2).toFloat(), ((image.height - edge) / 2).toFloat(), edge.toFloat(), edge.toFloat())
        val surface = org.jetbrains.skia.Surface.makeRasterN32Premul(side, side)
        surface.canvas.drawImageRect(
            image, src, org.jetbrains.skia.Rect.makeWH(side.toFloat(), side.toFloat()),
            org.jetbrains.skia.SamplingMode.DEFAULT, null, true,
        )
        surface.makeImageSnapshot().encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)?.bytes
    }.getOrNull()

    override fun readBytes(path: String): ByteArray? = runCatching { File(path).takeIf { it.isFile }?.readBytes() }.getOrNull()

    override fun fileExists(path: String): Boolean? = runCatching { File(path).isFile }.getOrNull()

    /** IMG-4: a copied image file first (it keeps its own bytes), else copied pixels encoded as png. */
    override fun clipboardImage(): PickedImage? = runCatching {
        if (java.awt.GraphicsEnvironment.isHeadless()) return@runCatching null
        val contents = Toolkit.getDefaultToolkit().systemClipboard.getContents(null) ?: return@runCatching null
        imageFiles(contents).firstOrNull() ?: run {
            if (!contents.isDataFlavorSupported(DataFlavor.imageFlavor)) return@run null
            val image = contents.getTransferData(DataFlavor.imageFlavor) as? java.awt.Image ?: return@run null
            PickedImage("pasted.png", "image/png", png(image))
        }
    }.getOrNull()

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    override fun droppedImages(event: DragAndDropEvent): List<PickedImage> =
        runCatching { event.awtTransferable.let(::imageFiles) }.getOrDefault(emptyList())

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    override fun droppedFiles(event: DragAndDropEvent): List<PickedImage> =
        runCatching { event.awtTransferable.let { anyFiles(it) } }.getOrDefault(emptyList())

    override fun clipboardFile(): PickedImage? = runCatching {
        if (java.awt.GraphicsEnvironment.isHeadless()) return@runCatching null
        val contents = Toolkit.getDefaultToolkit().systemClipboard.getContents(null) ?: return@runCatching null
        anyFiles(contents).firstOrNull()
    }.getOrNull() ?: clipboardImage()

    override fun fileSize(path: String): Long? = runCatching { File(path).takeIf { it.isFile }?.length() }.getOrNull()

    private fun imageFiles(contents: Transferable): List<PickedImage> = anyFiles(contents).filter { it.isImage }

    /** ATTACH-INLINE-1: the copied / dropped files; one over the chat limit is listed by size, unread. */
    private fun anyFiles(contents: Transferable): List<PickedImage> {
        if (!contents.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return emptyList()
        val files = (contents.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>).orEmpty().filterIsInstance<File>()
        return files.filter { it.isFile }.map { f ->
            val size = f.length()
            val bytes = if (size > READ_LIMIT) ByteArray(0) else f.readBytes()
            PickedImage(f.name, PickedImage.mimeOf(f.name), bytes, size)
        }
    }

    private fun png(image: java.awt.Image): ByteArray {
        val buffered = image as? BufferedImage ?: BufferedImage(image.getWidth(null), image.getHeight(null), BufferedImage.TYPE_INT_ARGB)
            .also { b -> b.createGraphics().apply { drawImage(image, 0, 0, null); dispose() } }
        return ByteArrayOutputStream().also { ImageIO.write(buffered, "png", it) }.toByteArray()
    }

    /**
     * PLUGINS-1: the modules under the plugins root (`INFANTRY_PLUGINS_ROOT`, else `<state dir>/plugins`), loaded on first
     * use. S6: loaded again by [reloadTabs] after a drop-in install (the soft restart; jars already loaded stay loaded).
     */
    @Volatile private var loaded: com.superiorsoftllc.infantry.ui.plugin.DesktopPlugins.Loaded? = null

    /** VOICE-SEAM-1: one capture at a time on this host; the shell root draws the listening indicator from it. */
    override val captureMonitor: com.superiorsoftllc.infantry.ui.voice.CaptureMonitor = com.superiorsoftllc.infantry.ui.voice.CaptureMonitor()

    /** VOICE-SEAM-1: `speech.out` speaks with the enabled voice plugin (`<state dir>/plugins`), else the OS voice. */
    private val pluginSpeech: com.superiorsoftllc.infantry.ui.voice.Speaker by lazy {
        com.superiorsoftllc.infantry.ui.voice.plugin.PluginSpeaker.desktop(File(stateDir() ?: File(".infantry"), "plugins"), speaker)
    }

    private fun load() = synchronized(this) {
        // S7: the module set it replaces is unloaded once the new one is built (observers off, loaders closed).
        val old = loaded
        com.superiorsoftllc.infantry.ui.plugin.DesktopPlugins.load(
            stateDir() ?: File(".infantry"), env, property, speech = pluginSpeech, capture = captureMonitor,
            captureLimit = { com.superiorsoftllc.infantry.ui.voice.captureLimitSeconds(uiPref(com.superiorsoftllc.infantry.ui.voice.AUDIO_CAPTURE_MAX_SECONDS_PREF_KEY) ?: setting(com.superiorsoftllc.infantry.ui.voice.AUDIO_CAPTURE_MAX_SECONDS_ENV), it) },
        ).also { loaded = it; old?.close() }
    }

    override val tabFactory: com.superiorsoftllc.infantry.ui.plugin.TabFactory get() = (loaded ?: synchronized(this) { loaded ?: load() }).factory

    override fun reloadTabs(): com.superiorsoftllc.infantry.ui.plugin.TabFactory = load().factory

    /**
     * PLUGIN-HOTSWAP-1: the live swap of the loaded set ([com.superiorsoftllc.infantry.ui.plugin.DesktopPlugins.Loaded.swap]);
     * null (the shell soft-restarts) when nothing is loaded yet or only a full reload applies the change. The leak check
     * waits up to setting `INFANTRY_PLUGINS_UNLOAD_CHECK_MS` (default [UNLOAD_CHECK_MS]) per swapped-out class loader; with
     * `-Dinfantry.plugins.leakHeapDump=true` (dev) a leak also writes `<state dir>/logs/plugin-leak-<module>.hprof`.
     */
    override fun swapPlugins(): com.superiorsoftllc.infantry.ui.shell.PluginSwap? = synchronized(this) {
        val current = loaded ?: return null
        when (val r = current.swap()) {
            is com.superiorsoftllc.infantry.ui.plugin.DesktopPlugins.Swap.Unsupported -> {
                System.err.println("[plugins] live swap not applied: ${r.reason}; reloading the shell")
                null
            }
            is com.superiorsoftllc.infantry.ui.plugin.DesktopPlugins.Swap.Applied -> {
                loaded = r.next
                val timeout = setting(UNLOAD_CHECK_SETTING)?.toLongOrNull() ?: UNLOAD_CHECK_MS
                val dumpDir = File(stateDir() ?: File(".infantry"), "logs")
                val dumps = property("infantry.plugins.leakHeapDump") == "true"
                com.superiorsoftllc.infantry.ui.shell.PluginSwap(r.next.factory, r.message) { leakedOf(r.released, timeout, dumps, dumpDir) }
            }
        }
    }

    /** S6: drop a .ihq on the empty shell; the plugins root, demo lock and safe mode as the loader reads them. */
    override val dropIn: com.superiorsoftllc.infantry.ui.shell.PluginDropIn by lazy {
        val root = com.superiorsoftllc.infantry.plugin.host.PluginFolders.root(stateDir() ?: File(".infantry"), env, property)
        com.superiorsoftllc.infantry.ui.plugin.DesktopDropIn(
            root, com.superiorsoftllc.infantry.plugin.host.PluginFolders.lockdown(env),
            com.superiorsoftllc.infantry.plugin.host.PluginFolders.safeMode(env, property),
            picker = if (canPickFiles) ::pickFile else null,
            release = { synchronized(this) { loaded?.close() } },
        )
    }

    /**
     * The daemon's state dir, resolved as the daemon resolves it (`infantrySetting`): the `INFANTRY_STATE_DIR` system
     * property first (the installed app pins it there), then the environment variable, else `~/.infantry`.
     */
    private fun stateDir(): File? = (property("INFANTRY_STATE_DIR")?.takeIf { it.isNotBlank() } ?: env("INFANTRY_STATE_DIR")?.takeIf { it.isNotBlank() })
        ?.let(::File)
        ?: home?.let { h -> File(h, ".infantry").takeIf { it.exists() } ?: File(h, ".agenthq").takeIf { it.exists() } ?: File(h, ".infantry") } // RENAME-2: the daemon copies the old dir on its first start

    private fun recordedPort(): Int? {
        val stateDir = stateDir() ?: return null
        return runCatching { File(stateDir, PORT_FILE).readText().trim().toInt() }.getOrNull()?.takeIf { it in 1..65535 }
    }

    private companion object {
        /** PLUGIN-HOTSWAP-1: the setting (environment) that overrides [UNLOAD_CHECK_MS]. */
        const val UNLOAD_CHECK_SETTING = "INFANTRY_PLUGINS_UNLOAD_CHECK_MS"
        /** PLUGIN-HOTSWAP-1: how long the leak check waits for each swapped-out class loader to be collected. */
        const val UNLOAD_CHECK_MS = 5_000L

        /** PLUGIN-HOTSWAP-1: the swapped-out modules still in memory after [timeout] ms each, logged (and dumped in dev). */
        fun leakedOf(released: Map<String, java.lang.ref.WeakReference<ClassLoader>>, timeout: Long, dumps: Boolean, dumpDir: File): List<String> =
            released.filter { !com.superiorsoftllc.infantry.plugin.host.LeakWatch.collected(it.value, timeout) }.keys.toList().onEach { m ->
                System.err.println("[plugins] $m did not unload cleanly: its class loader is still reachable after $timeout ms")
                if (dumps) {
                    val dump = com.superiorsoftllc.infantry.plugin.host.LeakWatch.heapDump(File(dumpDir, "plugin-leak-$m.hprof"))
                    System.err.println("[plugins] $m: heap dump ${dump?.absolutePath ?: "not written"} (find the path from the GC roots to its PluginClassLoader)")
                }
            }

        /** NATIVE-PICKER-1: what an images-only attachment dialog offers. */
        val IMAGE_EXTENSIONS = listOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
        const val DEFAULT_PORT = 8787
        /** No file over the chat's largest limit (20 MB) is read into memory. */
        const val READ_LIMIT = 20L * 1024 * 1024
        /** Same name as the daemon's `DaemonPort.FILE_NAME`; this module does not depend on the daemon. */
        const val PORT_FILE = "daemon.port"
    }
}

/**
 * SHOT-PREFS (3e4eb7ed): when set, every [JvmPlatformHost] reads and writes its UI prefs in this map and never touches
 * java.util.prefs (the registry on Windows, shared with the live app). The tab-shot renders pin it so a shot never
 * depends on what the owner last toggled in the running app.
 */
@Volatile
var uiPrefsOverride: MutableMap<String, String>? = null

/** SHOT-PREFS: how many times a [JvmPlatformHost] opened the java.util.prefs store (the shot guard test reads it). */
val preferencesReads = java.util.concurrent.atomic.AtomicInteger()

actual fun defaultPlatformHost(): PlatformHost = JvmPlatformHost()
