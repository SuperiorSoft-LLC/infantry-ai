package com.superiorsoftllc.agenthq.plugin.host

import com.superiorsoftllc.agenthq.plugin.api.ModuleManifest
import com.superiorsoftllc.agenthq.plugin.api.ModulesConfig
import com.superiorsoftllc.agenthq.plugin.api.Tier
import kotlinx.serialization.Serializable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.jar.JarEntry
import java.util.jar.JarFile

/**
 * S5 (desktop JVM only): the settings of an install, `<plugins root>/install.json`. Every limit and the trusted-key
 * location are settings; the values here are only the defaults of a missing file.
 */
@Serializable
data class InstallSettings(
    /** The .ihq file itself. */
    val maxPackageBytes: Long = 64L shl 20,
    /** One entry, counted as it is really unpacked (the size a zip header claims is not believed). */
    val maxEntryBytes: Long = 32L shl 20,
    /** All entries together, unpacked. */
    val maxTotalBytes: Long = 128L shl 20,
    val maxEntries: Int = 512,
    /** Folder of trusted X.509 certificates (.cer/.crt/.pem), relative to the plugins root unless absolute. The Infantry key goes here. */
    val trustedKeys: String = "trusted-keys",
    /** Accept a package with no signature at all, into the community tier. A broken signature is refused whatever this says. */
    val allowUnsigned: Boolean = false,
) {
    companion object {
        fun load(root: File): InstallSettings {
            val f = File(root, "install.json")
            if (!f.isFile) return InstallSettings()
            return runCatching { PluginJson.decodeFromString(serializer(), f.readText()) }.getOrDefault(InstallSettings())
        }
    }
}

enum class IhqRefusal { NOT_A_ZIP, TOO_BIG, TOO_MANY_ENTRIES, UNSAFE_ENTRY, UNEXPECTED_ENTRY, BAD_MANIFEST, BAD_SIGNATURE, UNSIGNED, CERT_NOT_VALID, ALREADY_INSTALLED, NO_TIER_FOLDER, IO }

sealed interface IhqResult {
    data class Installed(val name: String, val version: String, val tier: Tier, val dir: File, val signer: String?) : IhqResult
    data class Refused(val reason: IhqRefusal, val message: String) : IhqResult
    /** S6: [IhqInstaller.inspect]'s answer: every check passed, nothing was installed. What the consent dialog shows. */
    data class Checked(val manifest: ModuleManifest, val tier: Tier, val signer: String?) : IhqResult
}

private class Refusal(val reason: IhqRefusal, message: String) : Exception(message)

/**
 * S5: installs a `.ihq` (a zip with `module.json`, the jars under `lib/` and a jarsigner signature over the whole zip) into the
 * tier folder that [PluginFolders.scan] already reads. Everything is checked before anything is visible: the package is
 * unpacked into a `_install-` staging folder under the root (the scan skips `_` folders) and renamed into place, so a failure
 * at any point leaves no half-installed module. The tier comes from the signer, never from the package's own module.json.
 */
object IhqInstaller {
    fun install(
        ihq: File,
        root: File,
        config: ModulesConfig = PluginFolders.config(root),
        settings: InstallSettings = InstallSettings.load(root),
        /** Test hook: runs after the package is unpacked and checked, just before the rename. */
        beforeCommit: () -> Unit = {},
    ): IhqResult = unpack(ihq, root, config, settings, beforeCommit, commit = true)

    /**
     * S6: every check [install] makes (signature, limits, manifest, tier, not installed yet), then the staging folder is
     * removed: [IhqResult.Checked] with the manifest and the tier it would get, or the refusal [install] would give. The
     * consent dialog shows this before anything is installed.
     */
    fun inspect(
        ihq: File,
        root: File,
        config: ModulesConfig = PluginFolders.config(root),
        settings: InstallSettings = InstallSettings.load(root),
    ): IhqResult = unpack(ihq, root, config, settings, {}, commit = false)

    /**
     * S6: removes installed community module [name]: its folder and its modules.json entry. Run it with the module not
     * loaded (safe mode), or Windows keeps its jar open. A core module is never removed here.
     */
    fun uninstall(root: File, name: String, config: ModulesConfig = PluginFolders.config(root)): String? {
        if (!Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}").matches(name)) return "'${name.take(80)}' is not a module name"
        val dir = config.folders.filter { it.tier == Tier.COMMUNITY }.map { File(File(root, it.path), name) }.firstOrNull { it.isDirectory }
            ?: return "$name is not an installed community module"
        if (!dir.deleteRecursively()) return "could not delete ${dir.path} (a file in it is in use)"
        ModulesFile.forget(root, name)
        return null
    }

    private fun unpack(ihq: File, root: File, config: ModulesConfig, settings: InstallSettings, beforeCommit: () -> Unit, commit: Boolean): IhqResult {
        var staging: File? = null
        return try {
            if (!ihq.isFile) throw Refusal(IhqRefusal.NOT_A_ZIP, "${ihq.name} is not a file")
            if (ihq.length() > settings.maxPackageBytes) throw Refusal(IhqRefusal.TOO_BIG, "package is ${ihq.length()} bytes, the limit is ${settings.maxPackageBytes}")
            val trusted = trustedCerts(root, settings)
            JarFile(ihq, true).use { jar ->
                val entries = jar.entries().toList()
                if (entries.size > settings.maxEntries) throw Refusal(IhqRefusal.TOO_MANY_ENTRIES, "${entries.size} entries, the limit is ${settings.maxEntries}")
                entries.forEach { checkName(it.name) }
                val payload = entries.filter { !it.name.startsWith("META-INF/") }
                for (e in payload) {
                    val n = e.name
                    val ok = n == "module.json" || n == "lib/" || (n.startsWith("lib/") && (e.isDirectory || n.endsWith(".jar")))
                    if (!ok) throw Refusal(IhqRefusal.UNEXPECTED_ENTRY, "unexpected entry '$n' (only module.json and lib/*.jar are allowed)")
                }
                if (payload.none { it.name == "module.json" }) throw Refusal(IhqRefusal.BAD_MANIFEST, "no module.json")

                root.mkdirs()
                val stage = File(root, "_install-${UUID.randomUUID()}").also { it.mkdirs() }
                staging = stage
                var total = 0L
                try {
                    // Reading every byte is what runs the signature check.
                    for (e in payload) {
                        val out = File(stage, e.name)
                        if (!out.canonicalPath.startsWith(stage.canonicalPath + File.separator)) throw Refusal(IhqRefusal.UNSAFE_ENTRY, "entry '${e.name}' leaves the package folder")
                        if (e.isDirectory) { out.mkdirs(); continue }
                        out.parentFile.mkdirs()
                        jar.getInputStream(e).use { ins -> out.outputStream().use { total += copyLimited(ins, it, settings.maxEntryBytes, settings.maxTotalBytes - total, e.name) } }
                    }
                } catch (se: SecurityException) {
                    throw Refusal(IhqRefusal.BAD_SIGNATURE, "signature check failed: ${se.message}")
                }
                // Entries the signature lists but the zip no longer holds were removed after signing.
                val names = entries.map { it.name }.toSet()
                val missing = jar.manifest?.entries?.keys.orEmpty().filter { it !in names && "$it/" !in names }
                if (missing.isNotEmpty()) throw Refusal(IhqRefusal.BAD_SIGNATURE, "signed entries missing: ${missing.joinToString()}")

                val manifest = runCatching { PluginJson.decodeFromString(ModuleManifest.serializer(), File(stage, "module.json").readText()) }
                    .getOrElse { throw Refusal(IhqRefusal.BAD_MANIFEST, "module.json does not parse: ${it.message?.lineSequence()?.first()}") }
                checkManifest(manifest, stage)

                val signer = signerOf(payload, settings)
                val tier = if (signer != null && trusted.any { it == signer }) Tier.CORE else Tier.COMMUNITY
                val folder = config.folders.firstOrNull { it.tier == tier } ?: throw Refusal(IhqRefusal.NO_TIER_FOLDER, "modules.json has no ${tier.name.lowercase()} folder")
                if (config.folders.any { f -> File(File(root, f.path), manifest.name).exists() }) throw Refusal(IhqRefusal.ALREADY_INSTALLED, "${manifest.name} is already installed")
                val tierDir = File(root, folder.path).also { it.mkdirs() }
                val target = File(tierDir, manifest.name)
                if (!commit) return IhqResult.Checked(manifest, tier, signer?.subjectX500Principal?.name)

                beforeCommit()
                try {
                    Files.move(stage.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                } catch (e: IOException) {
                    throw Refusal(IhqRefusal.IO, "could not move into place: ${e.message}")
                }
                staging = null
                IhqResult.Installed(manifest.name, manifest.version, tier, target, signer?.subjectX500Principal?.name)
            }
        } catch (r: Refusal) {
            IhqResult.Refused(r.reason, r.message.orEmpty())
        } catch (e: java.util.zip.ZipException) {
            IhqResult.Refused(IhqRefusal.NOT_A_ZIP, e.message.orEmpty())
        } catch (e: IOException) {
            IhqResult.Refused(IhqRefusal.IO, e.message.orEmpty())
        } finally {
            staging?.deleteRecursively()
        }
    }

    /** Removes `_install-` staging folders an interrupted install left behind (a killed process cannot run [install]'s cleanup). */
    fun sweep(root: File) {
        root.listFiles { f -> f.isDirectory && f.name.startsWith("_install-") }.orEmpty().forEach { it.deleteRecursively() }
    }

    private val RESERVED_NAMES = setOf("CON", "PRN", "AUX", "NUL") + (1..9).flatMap { listOf("COM$it", "LPT$it") }

    private fun checkName(name: String) {
        val bad = name.isEmpty() || name.startsWith("/") || name.contains('\\') || name.contains('\u0000') ||
            Regex("^[A-Za-z]:").containsMatchIn(name) || name.split('/').any { it == ".." || it == "." }
        if (bad) throw Refusal(IhqRefusal.UNSAFE_ENTRY, "unsafe entry name '${name.take(80)}'")
    }

    private fun checkManifest(m: ModuleManifest, stage: File) {
        if (!Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}").matches(m.name)) throw Refusal(IhqRefusal.BAD_MANIFEST, "name '${m.name.take(80)}' is not a valid folder name")
        if (m.name.endsWith(".")) throw Refusal(IhqRefusal.BAD_MANIFEST, "name '${m.name}' must not end with a dot")
        if (m.name.substringBefore('.').uppercase() in RESERVED_NAMES) throw Refusal(IhqRefusal.BAD_MANIFEST, "name '${m.name}' is a reserved device name")
        if (m.entry.isNullOrBlank()) throw Refusal(IhqRefusal.BAD_MANIFEST, "module.json has no entry class")
        val jar = m.jar?.takeIf { it.isNotBlank() } ?: throw Refusal(IhqRefusal.BAD_MANIFEST, "module.json has no jar")
        val f = File(stage, jar)
        if (!jar.startsWith("lib/") || !f.isFile || !f.canonicalPath.startsWith(File(stage, "lib").canonicalPath + File.separator))
            throw Refusal(IhqRefusal.BAD_MANIFEST, "jar '$jar' is not a lib/ jar in the package")
    }

    /** The one signer of every file entry; null = unsigned (only when settings allow it). */
    private fun signerOf(payload: List<JarEntry>, settings: InstallSettings): X509Certificate? {
        val signed = payload.filter { !it.isDirectory }.map { e -> e.codeSigners?.firstOrNull()?.signerCertPath?.certificates?.firstOrNull() as? X509Certificate }
        if (signed.all { it == null }) {
            if (!settings.allowUnsigned) throw Refusal(IhqRefusal.UNSIGNED, "the package is not signed")
            return null
        }
        if (signed.any { it == null }) throw Refusal(IhqRefusal.BAD_SIGNATURE, "some entries are not covered by the signature")
        val first = signed.first()!!
        if (signed.any { it != first }) throw Refusal(IhqRefusal.BAD_SIGNATURE, "entries are signed by different keys")
        try { first.checkValidity() } catch (e: CertificateException) { throw Refusal(IhqRefusal.CERT_NOT_VALID, "signing certificate is not valid now: ${e.message}") }
        return first
    }

    private fun trustedCerts(root: File, settings: InstallSettings): List<X509Certificate> {
        val dir = File(settings.trustedKeys).let { if (it.isAbsolute) it else File(root, settings.trustedKeys) }
        val cf = CertificateFactory.getInstance("X.509")
        return dir.listFiles { f -> f.isFile && f.extension.lowercase() in setOf("cer", "crt", "pem") }.orEmpty().sortedBy { it.name }
            .mapNotNull { f -> runCatching { f.inputStream().use { cf.generateCertificate(it) as X509Certificate } }.getOrNull() }
    }

    private fun copyLimited(ins: InputStream, out: OutputStream, entryLimit: Long, totalLeft: Long, name: String): Long {
        val buf = ByteArray(16 * 1024)
        var n = 0L
        while (true) {
            val r = ins.read(buf)
            if (r < 0) break
            n += r
            if (n > entryLimit) throw Refusal(IhqRefusal.TOO_BIG, "entry '$name' is over the $entryLimit byte limit")
            if (n > totalLeft) throw Refusal(IhqRefusal.TOO_BIG, "package unpacks to more than the total limit")
            out.write(buf, 0, r)
        }
        return n
    }
}
