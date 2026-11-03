package com.superiorsoftllc.infantry.ui.voice.plugin

import java.io.File
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.util.zip.ZipInputStream

/** Fetches one asset to [target]; replaceable so tests never touch the network. Throws on any failure. */
fun interface AssetDownloader {
    fun download(url: String, target: File, maxBytes: Long, onProgress: (bytes: Long) -> Unit)
}

/** JDK HttpClient (no dependency), redirects followed, refuses a body larger than [maxBytes]. */
object HttpAssetDownloader : AssetDownloader {
    private val client by lazy {
        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(20)).build()
    }

    override fun download(url: String, target: File, maxBytes: Long, onProgress: (Long) -> Unit) {
        val request = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofMinutes(15)).GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        response.body().use { body ->
            if (response.statusCode() != 200) throw IOException("HTTP ${response.statusCode()} from $url")
            target.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var total = 0L
                var lastReport = 0L
                while (true) {
                    val n = body.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) throw IOException("$url is larger than the expected $maxBytes bytes")
                    out.write(buf, 0, n)
                    if (total - lastReport >= 4_000_000) { lastReport = total; onProgress(total) }
                }
                onProgress(total)
            }
        }
    }
}

/** An installed plugin that passed every check: its folder and its manifest. */
class InstalledVoicePlugin(val dir: File, val manifest: VoicePluginManifest) {
    /** The entry argv with `{dir}` replaced by this plugin's folder. */
    fun argv(): List<String> = manifest.entry.map { it.replace("{dir}", dir.absolutePath) }
}

/**
 * VOICE-PLUGIN-1: the voice plugins on this machine, one folder each under [dir] (`<dir>/<id>/plugin.json` + assets).
 * The [catalog] (a JSON file shipped with the app, a few KB) lists what can be installed; nothing is downloaded except
 * by [install], which the UI calls only on the user's Install click. An install downloads into `<id>.partial`,
 * verifies every asset's size and sha256, unpacks, then renames the folder into place: a failure leaves nothing behind.
 * Which plugin speaks is kept in `<dir>/enabled` (one id, or no file).
 */
class VoicePluginStore(
    val dir: File,
    private val catalog: List<VoicePluginManifest> = VoicePluginCatalog.bundled(),
    private val platform: String = VoicePlatform.current(),
    private val downloader: AssetDownloader = HttpAssetDownloader,
) : VoicePlugins {
    private val lock = Any()
    /** Assets verified in this JVM, keyed by path, so a model is hashed once per launch, not per alert. */
    private val verified = HashMap<String, Pair<Long, Long>>()

    private val enabledFile get() = File(dir, "enabled")

    fun enabledId(): String? = runCatching { enabledFile.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() } }.getOrNull()

    override fun setEnabled(id: String, enabled: Boolean) = synchronized(lock) {
        runCatching {
            if (enabled) { dir.mkdirs(); enabledFile.writeText(id) }
            else if (enabledId() == id) enabledFile.delete()
        }
        Unit
    }

    /** The manifest installed at `<dir>/<id>/plugin.json`, or the reason it cannot be used. */
    fun installedManifest(id: String): Result<VoicePluginManifest> {
        val file = File(File(dir, id), MANIFEST)
        if (!file.isFile) return Result.failure(IllegalStateException("not installed"))
        val m = VoicePluginManifests.parse(runCatching { file.readText() }.getOrElse { return Result.failure(it) }).getOrElse { return Result.failure(it) }
        if (m.id != id) return Result.failure(IllegalStateException("plugin.json names id '${m.id}', folder is '$id'"))
        val problems = VoicePluginManifests.problems(m, platform)
        return if (problems.isEmpty()) Result.success(m) else Result.failure(IllegalStateException(problems.joinToString("; ")))
    }

    /**
     * The plugin [id] if it can speak right now: a valid manifest for this platform, the entry program present, and
     * every kept asset with its recorded size and sha256 (hashed once per launch). Null + [why] otherwise.
     */
    fun ready(id: String, why: (String) -> Unit = {}): InstalledVoicePlugin? {
        val m = installedManifest(id).getOrElse { why(it.message ?: "unusable"); return null }
        val pluginDir = File(dir, id)
        for (a in m.assets.filter { !it.unpack }) {
            val f = File(pluginDir, a.path)
            if (!f.isFile || f.length() != a.size) { why("${a.path} is missing or has the wrong size"); return null }
            val stamp = f.length() to f.lastModified()
            val known = synchronized(verified) { verified[f.path] }
            if (known != stamp) {
                if (sha256(f) != a.sha256) { why("${a.path} failed its checksum"); return null }
                synchronized(verified) { verified[f.path] = stamp }
            }
        }
        val plugin = InstalledVoicePlugin(pluginDir, m)
        val exe = plugin.argv().first()
        if (exe.startsWith(pluginDir.absolutePath) && !File(exe).isFile) { why("$exe is missing"); return null }
        return plugin
    }

    override fun rows(): List<VoicePluginRow> {
        val enabled = enabledId()
        val offered = catalog.filter { platform in it.platforms }
        val ids = (offered.map { it.id } + installedIds()).distinct()
        return ids.map { id ->
            val c = offered.firstOrNull { it.id == id }
            val installed = File(File(dir, id), MANIFEST).isFile
            val m = installedManifest(id)
            val base = VoicePluginRow(
                id = id,
                name = c?.name ?: m.getOrNull()?.name ?: id,
                description = c?.description ?: m.getOrNull()?.description.orEmpty(),
                license = c?.license ?: m.getOrNull()?.license.orEmpty(),
                downloadBytes = c?.downloadBytes ?: 0,
                state = "missing",
            )
            when {
                !installed -> base
                m.isSuccess -> base.copy(state = "installed", installedVersion = m.getOrNull()?.version, enabled = enabled == id)
                else -> base.copy(state = "broken", enabled = enabled == id, note = m.exceptionOrNull()?.message)
            }
        }
    }

    private fun installedIds(): List<String> =
        dir.listFiles()?.filter { it.isDirectory && File(it, MANIFEST).isFile }?.map { it.name }?.sorted().orEmpty()

    override fun install(id: String, onProgress: (String) -> Unit): VoicePluginOutcome = synchronized(lock) {
        val m = catalog.firstOrNull { it.id == id } ?: return VoicePluginOutcome("failed", "no plugin '$id' in the catalog")
        val problems = VoicePluginManifests.problems(m, platform)
        if (problems.isNotEmpty()) return VoicePluginOutcome("failed", problems.joinToString("; "))
        val staging = File(dir, "$id$PARTIAL")
        try {
            staging.deleteRecursively()
            if (!staging.mkdirs()) throw IOException("cannot create ${staging.path}")
            for (a in m.assets) {
                val target = File(staging, a.path).also { it.parentFile.mkdirs() }
                onProgress("Downloading ${a.path} (${PluginsSetting.sizeText(a.size)})")
                downloader.download(a.url, target, a.size) { n -> onProgress("Downloading ${a.path}: ${PluginsSetting.sizeText(n)} of ${PluginsSetting.sizeText(a.size)}") }
                if (target.length() != a.size) throw IOException("${a.path}: got ${target.length()} bytes, expected ${a.size}")
                if (sha256(target) != a.sha256) throw IOException("${a.path} failed its checksum (sha256 mismatch)")
                if (a.unpack) { unzip(target, staging); target.delete() }
            }
            File(staging, MANIFEST).writeText(VoicePluginManifests.encode(m))
            val final = File(dir, id)
            final.deleteRecursively()
            if (!staging.renameTo(final)) throw IOException("cannot move the plugin into ${final.path}")
            synchronized(verified) { verified.clear() }
            onProgress("Installed")
            VoicePluginOutcome("installed")
        } catch (e: Exception) {
            staging.deleteRecursively()
            VoicePluginOutcome("failed", e.message ?: e::class.simpleName)
        }
    }

    override fun remove(id: String): VoicePluginOutcome = synchronized(lock) {
        if (!VoicePluginManifests.safeRelative(id) || '/' in id) return VoicePluginOutcome("failed", "bad id '$id'")
        val target = File(dir, id)
        target.deleteRecursively()
        File(dir, "$id$PARTIAL").deleteRecursively()
        if (enabledId() == id) enabledFile.delete()
        synchronized(verified) { verified.clear() }
        if (target.exists()) VoicePluginOutcome("failed", "could not delete every file in ${target.path} (is it speaking?)") else VoicePluginOutcome("removed")
    }

    companion object {
        const val MANIFEST = "plugin.json"
        private const val PARTIAL = ".partial"

        fun sha256(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { input ->
                val buf = ByteArray(1 shl 16)
                while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        /** Extracts [zip] into [into]; an entry that would land outside [into] (zip slip) fails the install. */
        fun unzip(zip: File, into: File) {
            val root = into.canonicalFile
            ZipInputStream(zip.inputStream().buffered()).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    val out = File(root, e.name).canonicalFile
                    if (!out.path.startsWith(root.path + File.separator)) throw IOException("zip entry '${e.name}' escapes the plugin folder")
                    if (e.isDirectory) out.mkdirs() else { out.parentFile.mkdirs(); out.outputStream().use { z.copyTo(it) } }
                }
            }
        }
    }
}

object VoicePluginCatalog {
    private const val RESOURCE = "/com/superiorsoftllc/infantry/ui/voice/plugin/catalog.json"

    /** The catalog shipped in the app; an unreadable catalog offers nothing (the OS voice stays). */
    fun bundled(): List<VoicePluginManifest> =
        VoicePluginCatalog::class.java.getResourceAsStream(RESOURCE)?.use { it.readBytes().decodeToString() }
            ?.let { VoicePluginManifests.parseCatalog(it).getOrNull() }.orEmpty()
}

object VoicePlatform {
    /** `windows-x64`, `windows-arm64`, `macos-arm64`, `linux-x64`, ... */
    fun current(os: String = System.getProperty("os.name").orEmpty(), arch: String = System.getProperty("os.arch").orEmpty()): String {
        val o = when {
            os.startsWith("Windows", ignoreCase = true) -> "windows"
            os.startsWith("Mac", ignoreCase = true) -> "macos"
            else -> "linux"
        }
        val a = when (arch.lowercase()) {
            "amd64", "x86_64", "x64" -> "x64"
            "aarch64", "arm64" -> "arm64"
            else -> arch.lowercase()
        }
        return "$o-$a"
    }
}
