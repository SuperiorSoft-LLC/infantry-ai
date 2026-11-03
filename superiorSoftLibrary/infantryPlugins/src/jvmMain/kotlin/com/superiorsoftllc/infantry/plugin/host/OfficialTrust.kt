package com.superiorsoftllc.infantry.plugin.host

import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.plugin.api.Tier
import java.io.File
import java.io.IOException
import java.security.cert.X509Certificate
import java.util.jar.JarFile

/**
 * RUNTIME-2 (EMPTY-SHELL-DESIGN 3b): an official module loads only when its jar proves it was made by Infantry.
 *  1. every file entry of its jar verifies (`JarFile(verify = true)`) against an Infantry key, and no signed entry is missing;
 *  2. the jar carries a signed `module.json` that claims tier `official` and equals the module.json on disk (so the
 *     declarations the host trusts, services included, are the signed ones).
 * A module that fails is refused, never demoted. `infantry.plugins.devTrust=true` ([PluginFolders.devTrust]) skips both
 * checks for dev runs from the repository. Nothing here loads a class.
 */
object OfficialTrust {
    /**
     * The Infantry signing key pinned in the binary: SHA-256 fingerprints (uppercase hex, no separators) of the Infantry
     * signing certificate(s). A cert in a trusted-keys folder is user-writable and so is NOT enough on its own: an official
     * jar must be signed by a cert whose fingerprint is listed here. Only public fingerprints live here, never a private key.
     *
     * PLACEHOLDER: the real Infantry certificate is not available yet. Replace this value with the SHA-256 fingerprint of
     * the real Infantry signing cert (`keytool -printcert -file infantry.cer`, SHA256 line, colons removed). Until then
     * no jar verifies as official (devTrust still works for dev runs).
     */
    const val INFANTRY_KEY_FINGERPRINT_PLACEHOLDER = "REPLACE_WITH_REAL_INFANTRY_SIGNING_CERT_SHA256_FINGERPRINT"
    val PINNED_FINGERPRINTS: Set<String> = setOf(INFANTRY_KEY_FINGERPRINT_PLACEHOLDER)

    /** The SHA-256 fingerprint of [cert], uppercase hex without separators (the form of [PINNED_FINGERPRINTS]). */
    fun fingerprint(cert: X509Certificate): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02X".format(it) }

    private fun normalize(fp: String) = fp.replace(":", "").replace(" ", "").uppercase()

    /**
     * The Infantry keys: the X.509 certificates of the plugins root's trusted-keys folder (`install.json` `trustedKeys`)
     * and of the bundled root's `trusted-keys/` (the ones the install ships).
     */
    fun infantryKeys(root: File, bundled: File?): List<X509Certificate> =
        IhqInstaller.trustedCerts(root, InstallSettings.load(root)) +
            (bundled?.let { IhqInstaller.trustedCerts(it, InstallSettings()) } ?: emptyList())

    /** [candidates] with each official module that fails the checks marked refused (its [ModuleCandidate.refusal]). */
    fun check(candidates: List<ModuleCandidate>, keys: List<X509Certificate>, devTrust: Boolean, log: (String) -> Unit = {},
              pinned: Set<String> = PINNED_FINGERPRINTS): List<ModuleCandidate> {
        val official = candidates.filter { it.tier == Tier.OFFICIAL && it.manifest != null && it.dir != null }
        if (official.isEmpty()) return candidates
        if (devTrust) {
            log("[plugins] devTrust on: ${official.size} official module(s) load without the Infantry signature check")
            return candidates
        }
        return candidates.map { c -> if (c in official) refusal(c, keys, pinned)?.let { c.copy(refusal = "official module refused: $it") } ?: c else c }
    }

    /**
     * Why official module [c] may not load; null = signed by an Infantry key (a cert of [keys] whose fingerprint is
     * [pinned] in the binary) and its module.json is the signed one. [pinned] is overridable for tests only.
     */
    fun refusal(c: ModuleCandidate, keys: List<X509Certificate>, pinned: Set<String> = PINNED_FINGERPRINTS): String? =
        refusal(c, c.manifest?.jar, keys, pinned)

    /**
     * DAEMON-PLUGINS-1: the same checks for the jar [jarPath] of [c] (the daemon half's `daemon.jar`, or the UI [ModuleManifest.jar]).
     */
    fun refusal(c: ModuleCandidate, jarPath: String?, keys: List<X509Certificate>, pinned: Set<String> = PINNED_FINGERPRINTS): String? {
        val pins = pinned.map(::normalize).toSet()
        val m = c.manifest ?: return "no module.json"
        val dir = File(c.dir ?: return "no folder")
        val jarName = jarPath?.takeIf { it.isNotBlank() } ?: return "module.json names no jar to verify"
        val jar = File(dir, jarName)
        if (!jar.isFile) return "jar $jarName not found"
        if (!jar.canonicalPath.startsWith(dir.canonicalPath + File.separator)) return "jar $jarName is outside the module folder"
        if (keys.isEmpty()) return "no Infantry key is installed (trusted-keys)"
        return try {
            JarFile(jar, true).use { j ->
                val entries = j.entries().toList()
                val files = entries.filter { !it.isDirectory && !it.name.startsWith("META-INF/") }
                if (files.isEmpty()) return "jar $jarName is empty"
                // Reading every byte is what runs the signature check; a tampered entry throws SecurityException.
                for (e in files) j.getInputStream(e).use { it.readBytes() }
                for (e in files) {
                    val signer = e.codeSigners?.firstOrNull()?.signerCertPath?.certificates?.firstOrNull() as? X509Certificate
                        ?: return "jar $jarName is not signed (${e.name})"
                    if (keys.none { it == signer }) return "jar $jarName is signed by ${signer.subjectX500Principal.name}, not by the Infantry key"
                    if (fingerprint(signer) !in pins) return "jar $jarName is signed by ${signer.subjectX500Principal.name}, a trusted-keys cert that is not the pinned Infantry key"
                }
                val names = entries.map { it.name }.toSet()
                val missing = j.manifest?.entries?.keys.orEmpty().filter { it !in names && "$it/" !in names }
                if (missing.isNotEmpty()) return "signed entries missing from $jarName: ${missing.joinToString()}"
                val signedJson = j.getJarEntry("module.json") ?: return "jar $jarName carries no signed module.json"
                val signed = runCatching { PluginJson.decodeFromString(ModuleManifest.serializer(), j.getInputStream(signedJson).use { it.readBytes().decodeToString() }) }
                    .getOrElse { return "the signed module.json does not parse" }
                when {
                    !"official".equals(signed.tier, ignoreCase = true) -> "the signed module.json claims tier '${signed.tier}', not official"
                    signed != m -> "module.json differs from the signed copy in $jarName"
                    else -> null
                }
            }
        } catch (e: SecurityException) {
            "signature check of $jarName failed: ${e.message}"
        } catch (e: IOException) {
            "jar $jarName could not be read: ${e.message}"
        }
    }
}
