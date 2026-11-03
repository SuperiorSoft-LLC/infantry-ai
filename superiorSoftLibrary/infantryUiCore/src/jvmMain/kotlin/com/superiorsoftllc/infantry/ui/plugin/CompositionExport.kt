// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.plugin

import com.superiorsoftllc.infantry.plugin.api.DaemonCapabilities
import com.superiorsoftllc.infantry.plugin.api.DependencyDeclaration
import com.superiorsoftllc.infantry.plugin.api.FillDeclaration
import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.plugin.host.PluginJson
import com.superiorsoftllc.infantry.ui.block.layout.Block
import com.superiorsoftllc.infantry.ui.block.layout.LayoutExport
import com.superiorsoftllc.infantry.ui.shell.blocks.CoreShell
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * NS3 (docs/NORTH-STAR-DESIGN.md 1b): writes an exported section as one droppable `.ihq` composition:
 * `module.json` (no entry/jar; `fills[{node, layout}]`, `dependencies`, `daemon`), `layout/<name>.json` and each
 * dependency's published package byte-identical under `deps/`. Signed as a whole with the installation's [ExportKey]
 * when one is given (community tier on any shell: it is never the Infantry key).
 */
object CompositionExport {
    sealed interface Outcome {
        data class Written(val file: File, val result: LayoutExport.Result) : Outcome
        data class Refused(val refusals: List<String>) : Outcome
    }

    /** Where published module packages (`<Name>.ihq`) are looked up: `<plugins root>/packages`, then `-Dinfantry.packages`. */
    fun packageDirs(pluginsRoot: File, property: (String) -> String? = System::getProperty): List<File> =
        listOfNotNull(File(pluginsRoot, "packages"), property("infantry.packages")?.let(::File))

    fun findPackage(dirs: List<File>, module: String): File? = dirs.map { File(it, "$module.ihq") }.firstOrNull { it.isFile }

    /** Exports [node] of [factory]'s drawn tree as composition [name] that fills [fill] of a fresh shell. */
    fun export(factory: TabFactory, node: String, name: String, out: File, packageDirs: List<File>, key: ExportKey?, fill: String = "infantry.shell.center"): Outcome {
        val root = factory.shellRoot.find(node) ?: return Outcome.Refused(listOf("no node $node in the drawn tree"))
        val page = factory.pageJson(root)
        @Suppress("UNCHECKED_CAST")
        val configOf: (Block) -> Map<String, kotlinx.serialization.json.JsonElement> = { b -> findConfig(page["root"] as JsonObject, b.id) }
        val result = LayoutExport.export(
            root, factory.blockTypes, factory.modules.associate { it.manifest.name to it.manifest }, configOf,
            packaged = { findPackage(packageDirs, it) != null }, locked = CoreShell.locked,
        )
        if (!result.ok) return Outcome.Refused(result.refusals)
        write(out, name, "1.0.0", fill, result.page!!, result.dependencies.map { (m, v) -> Triple(m, v, findPackage(packageDirs, m)!!) }, result.daemon, key)
        return Outcome.Written(out, result)
    }

    /** Writes the composition package; [deps] are (module, version, its published .ihq). */
    fun write(out: File, name: String, version: String, fill: String, page: JsonObject, deps: List<Triple<String, String, File>>, daemon: DaemonCapabilities, key: ExportKey?) {
        val layout = "layout/${name.lowercase()}.json"
        val manifest = ModuleManifest(
            name = name, version = version, author = key?.publisher ?: "export", license = "NOASSERTION",
            description = "Exported section ($fill)",
            fills = listOf(FillDeclaration(fill, layout)),
            dependencies = deps.map { (m, v, _) -> DependencyDeclaration(m, v) },
            daemon = daemon,
        )
        out.parentFile?.mkdirs()
        val unsigned = if (key == null) out else File(out.path + ".unsigned")
        ZipOutputStream(unsigned.outputStream()).use { z ->
            fun put(n: String, bytes: ByteArray) { z.putNextEntry(ZipEntry(n)); z.write(bytes); z.closeEntry() }
            put("module.json", PluginJson.encodeToString(ModuleManifest.serializer(), manifest).toByteArray())
            put(layout, Json.encodeToString(JsonObject.serializer(), page).toByteArray())
            deps.forEach { (m, _, f) -> put("deps/$m.ihq", f.readBytes()) }
        }
        if (key != null) try { key.sign(unsigned, out) } finally { unsigned.delete() }
    }

    private fun Block.find(id: String): Block? = if (this.id == id) this else children.firstNotNullOfOrNull { it.find(id) }

    private fun findConfig(n: JsonObject, id: String): Map<String, kotlinx.serialization.json.JsonElement> {
        if ((n["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content == id) return (n["config"] as? JsonObject).orEmpty()
        return (n["children"] as? kotlinx.serialization.json.JsonArray).orEmpty().firstNotNullOfOrNull { c -> findConfig(c as JsonObject, id).takeIf { it.isNotEmpty() } }.orEmpty()
    }
}

/**
 * NS3 (1b): the per-installation export key: an RSA key and a self-signed certificate, made on the first export into
 * `<dir>/export.p12` (PKCS12, password in `<dir>/export.pass`, both owner-only where the file system allows). The
 * certificate is built here (DER) because an installed jlink runtime has no keytool. Trusted as a community publisher
 * only; it never goes into trusted-keys.
 */
class ExportKey(val key: PrivateKey, val cert: X509Certificate) {
    val publisher: String get() = cert.subjectX500Principal.name

    fun sign(unsigned: File, out: File) {
        val path = CertificateFactory.getInstance("X.509").generateCertPath(listOf(cert))
        val signer = jdk.security.jarsigner.JarSigner.Builder(key, path).digestAlgorithm("SHA-256").signatureAlgorithm("SHA256withRSA").signerName("EXPORT").build()
        ZipFile(unsigned).use { zf -> out.outputStream().use { signer.sign(zf, it) } }
    }

    companion object {
        private const val ALIAS = "export"

        fun loadOrCreate(dir: File, owner: String = System.getProperty("user.name") ?: "owner"): ExportKey {
            val store = File(dir, "export.p12")
            val passFile = File(dir, "export.pass")
            if (store.isFile && passFile.isFile) {
                val pass = passFile.readText().trim().toCharArray()
                val ks = KeyStore.getInstance("PKCS12").apply { store.inputStream().use { load(it, pass) } }
                return ExportKey(ks.getKey(ALIAS, pass) as PrivateKey, ks.getCertificate(ALIAS) as X509Certificate)
            }
            dir.mkdirs()
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val cert = selfSigned(pair, "CN=Infantry export ($owner)", years = 10)
            val pass = BigInteger(130, SecureRandom()).toString(32)
            val ks = KeyStore.getInstance("PKCS12").apply { load(null, null); setKeyEntry(ALIAS, pair.private, pass.toCharArray(), arrayOf(cert)) }
            passFile.writeText(pass)
            store.outputStream().use { ks.store(it, pass.toCharArray()) }
            for (f in listOf(passFile, store)) { f.setReadable(false, false); f.setReadable(true, true); f.setWritable(false, false); f.setWritable(true, true) }
            return ExportKey(pair.private, cert)
        }

        /** A minimal X.509 v3 self-signed certificate (SHA256withRSA), DER-encoded by hand. */
        fun selfSigned(pair: java.security.KeyPair, dn: String, years: Int): X509Certificate {
            val name = x500(dn)
            val alg = seq(oid(byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B)), byteArrayOf(0x05, 0x00))
            val now = System.currentTimeMillis()
            val validity = seq(time(Date(now - 60_000)), time(Date(now + years * 365L * 24 * 3600 * 1000)))
            val tbs = seq(
                tlv(0xA0, tlv(0x02, byteArrayOf(2))),
                tlv(0x02, BigInteger(63, SecureRandom()).add(BigInteger.ONE).toByteArray()),
                alg, name, validity, name, pair.public.encoded,
            )
            val sig = Signature.getInstance("SHA256withRSA").apply { initSign(pair.private); update(tbs) }.sign()
            val der = seq(tbs, alg, tlv(0x03, byteArrayOf(0) + sig))
            return CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate
        }

        private fun x500(dn: String): ByteArray = javax.security.auth.x500.X500Principal(dn).encoded
        private fun oid(body: ByteArray) = tlv(0x06, body)
        private fun time(d: Date): ByteArray {
            val f = SimpleDateFormat("yyyyMMddHHmmss'Z'").apply { timeZone = TimeZone.getTimeZone("UTC") }
            return tlv(0x18, f.format(d).toByteArray())
        }
        private fun seq(vararg parts: ByteArray) = tlv(0x30, parts.fold(ByteArray(0)) { a, b -> a + b })
        private fun tlv(tag: Int, body: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(tag)
            val n = body.size
            when {
                n < 0x80 -> out.write(n)
                n < 0x100 -> { out.write(0x81); out.write(n) }
                n < 0x10000 -> { out.write(0x82); out.write(n shr 8); out.write(n and 0xFF) }
                else -> { out.write(0x83); out.write(n shr 16); out.write((n shr 8) and 0xFF); out.write(n and 0xFF) }
            }
            out.write(body)
            return out.toByteArray()
        }
    }
}
