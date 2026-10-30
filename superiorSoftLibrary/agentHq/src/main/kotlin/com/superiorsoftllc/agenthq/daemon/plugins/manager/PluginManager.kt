package com.superiorsoftllc.agenthq.daemon.plugins.manager

import com.superiorsoftllc.agenthq.daemon.api.DaemonApi
import com.superiorsoftllc.agenthq.plugin.api.ModuleManifest
import com.superiorsoftllc.agenthq.plugin.api.PLUGIN_API_VERSION
import com.superiorsoftllc.agenthq.plugin.api.Tier
import com.superiorsoftllc.agenthq.plugin.host.IhqInstaller
import com.superiorsoftllc.agenthq.plugin.host.IhqRefusal
import com.superiorsoftllc.agenthq.plugin.host.IhqResult
import com.superiorsoftllc.agenthq.plugin.host.InstallSettings
import com.superiorsoftllc.agenthq.plugin.host.ModulesFile
import com.superiorsoftllc.agenthq.plugin.host.PluginFolders
import com.superiorsoftllc.agenthq.plugin.host.SemVer
import com.superiorsoftllc.agenthq.plugin.host.VersionRange
import com.superiorsoftllc.agenthq.update.ReleaseAssets
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.jar.JarFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** What a manager call did. [code] is the refusal code (null = done). [requested] = the grants the caller must consent to, on CONSENT_REQUIRED / GRANTS_RAISED. */
data class ManagerResult(
    val ok: Boolean,
    val message: String,
    val code: String? = null,
    /** Daemon and harness halves load at daemon start: a change to one takes effect at the next start. */
    val restartNeeded: Boolean = false,
    val requested: List<String> = emptyList(),
) {
    companion object {
        fun done(message: String, restartNeeded: Boolean = false) = ManagerResult(true, message, restartNeeded = restartNeeded)
        fun refused(code: String, message: String, requested: List<String> = emptyList()) = ManagerResult(false, message, code, requested = requested)
    }
}

/** What `inspect` found; nothing is installed. [token] binds a consent to these exact bytes and grants. */
data class Inspection(
    val id: String,
    val version: String,
    val cls: PluginClass,
    val tier: Tier,
    val license: String?,
    val signer: String?,
    /** Signed by a key the user chose to trust under `trusted-keys/custom/` (proves the publisher, grants nothing). */
    val signedByUserKey: Boolean,
    val sha256: String,
    val grants: List<String>,
    val warnings: List<String>,
    val source: String,
    val token: String,
    internal val file: File,
    internal val installed: Boolean,
)

data class PluginRow(
    val id: String,
    val version: String,
    val cls: PluginClass,
    val tier: Tier,
    val bundled: Boolean,
    val enabled: Boolean,
    val source: String?,
    val license: String?,
    val requested: List<String>,
    val granted: List<String>,
    val history: List<String>,
)

data class CatalogHit(val source: String, val cls: PluginClass, val entry: CatalogEntry, val latest: CatalogVersion?)

data class UpdateOffer(val id: String, val from: String, val to: String, val source: String, val newGrants: List<String>) {
    val grantsRaised: Boolean get() = newGrants.isNotEmpty()
}

data class SourceRefresh(val source: String, val ok: Boolean, val stale: Boolean, val plugins: Int, val message: String)

/** Gets a catalog or a package: an http(s) or file URL, or a plain path, into [dest]; throws [IOException] (offline is a clean failure). */
fun interface Fetcher {
    fun fetch(url: String, dest: File, maxBytes: Long)
}

object DefaultFetcher : Fetcher {
    override fun fetch(url: String, dest: File, maxBytes: Long) {
        dest.parentFile?.mkdirs()
        val scheme = runCatching { URI(url).scheme }.getOrNull()?.lowercase()
        val stream = when {
            scheme == "http" || scheme == "https" -> {
                val client = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(15))
                    .followRedirects(java.net.http.HttpClient.Redirect.NORMAL).build()
                val resp = client.send(
                    java.net.http.HttpRequest.newBuilder(URI(url)).timeout(java.time.Duration.ofMinutes(5)).GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.ofInputStream(),
                )
                if (resp.statusCode() != 200) { resp.body().close(); throw IOException("HTTP ${resp.statusCode()} for $url") }
                resp.body()
            }
            scheme == "file" -> File(URI(url)).inputStream()
            else -> File(url).inputStream()
        }
        stream.use { ins ->
            dest.outputStream().use { out ->
                val buf = ByteArray(16 * 1024)
                var n = 0L
                while (true) {
                    val r = ins.read(buf)
                    if (r < 0) break
                    n += r
                    if (n > maxBytes) throw IOException("$url is over the $maxBytes byte limit")
                    out.write(buf, 0, r)
                }
            }
        }
    }
}

/**
 * PLUGIN-MANAGER-1 (docs/PLUGIN-MANAGER.md): one manager for the plugins root of this host. It owns no enable list and no
 * trust of its own: enable/grants live in `modules.json` ([ModulesFile]), signatures and limits in [IhqInstaller], loading in
 * the hosts. It adds catalogs and sources (data), the class label, hash pins, grant consent, and update/rollback/remove with a
 * version store. Default deny and fail closed: anything that does not check out is a refusal and nothing is changed.
 *
 * Daemon and harness halves load at daemon start, so a change is [ManagerResult.restartNeeded]; a module whose files are
 * locked (Windows keeps a loaded jar open) is refused as IN_USE. The staged apply at next start is still a gap (G7).
 */
class PluginManager(
    private val root: File,
    private val stateDir: File,
    private val bundledRoot: File? = null,
    private val fetcher: Fetcher = DefaultFetcher,
    private val hostPluginApi: Int = PLUGIN_API_VERSION,
    private val hostDaemonApi: String = DaemonApi.VERSION,
    private val keepVersions: Int = 2,
) {
    private val catalogCache get() = File(stateDir, "plugin-catalogs")
    private val downloads get() = File(stateDir, "plugin-downloads")
    private val versionsDir get() = File(root, "_versions")

    // ---------------------------------------------------------------- sources and catalogs

    fun sources(): List<CatalogSource> =
        File(root, "sources.json").takeIf { it.isFile }
            ?.let { runCatching { ManagerJson.decodeFromString(SourcesFile.serializer(), it.readText()).sources }.getOrNull() }.orEmpty()

    fun setSources(list: List<CatalogSource>) {
        root.mkdirs()
        File(root, "sources.json").writeText(ManagerJson.encodeToString(SourcesFile.serializer(), SourcesFile(list)) + "\n")
    }

    fun addSource(s: CatalogSource): ManagerResult {
        if (!ID.matches(s.id)) return ManagerResult.refused("BAD_SOURCE", "source id '${s.id.take(40)}' is not a plain name")
        if ((s.url == null) == (s.dir == null)) return ManagerResult.refused("BAD_SOURCE", "a source has exactly one of url or dir")
        setSources(sources().filter { it.id != s.id } + s)
        return ManagerResult.done("source ${s.id} (${s.pluginClass.label}) saved")
    }

    fun removeSource(id: String): ManagerResult {
        if (sources().none { it.id == id }) return ManagerResult.refused("NO_SUCH_SOURCE", "no source '$id'")
        setSources(sources().filter { it.id != id })
        File(catalogCache, "$id.json").delete()
        return ManagerResult.done("source $id removed")
    }

    private data class Loaded(val source: CatalogSource, val catalog: Catalog, val base: String, val stale: Boolean)

    /** Fetches url sources into the cache (an offline source keeps its cache, marked stale); dir sources need no network. Never throws. */
    fun refresh(sourceId: String? = null): List<SourceRefresh> =
        sources().filter { it.enabled && (sourceId == null || it.id == sourceId) }.map { s ->
            val r = load(s, refresh = true)
            if (r == null) SourceRefresh(s.id, false, false, 0, "no catalog (offline and not cached, or unreadable)")
            else SourceRefresh(s.id, !r.stale, r.stale, r.catalog.plugins.size, if (r.stale) "offline: using the cached catalog" else "ok")
        }

    private fun load(s: CatalogSource, refresh: Boolean): Loaded? {
        if (s.dir != null) {
            val dir = File(s.dir)
            val cat = File(dir, "catalog.json")
            val catalog = if (cat.isFile) runCatching { ManagerJson.decodeFromString(Catalog.serializer(), cat.readText()) }.getOrNull() else indexDir(dir, s)
            return catalog?.let { Loaded(s, it, dir.absolutePath, false) }
        }
        val url = s.url ?: return null
        val cache = File(catalogCache, "${s.id}.json")
        var stale = false
        if (refresh || !cache.isFile) {
            val tmp = File(catalogCache, "${s.id}.json.part")
            try {
                fetchAny(url, tmp, MAX_CATALOG)
                runCatching { ManagerJson.decodeFromString(Catalog.serializer(), tmp.readText()) }.getOrNull() ?: throw IOException("not a catalog")
                Files.move(tmp.toPath(), cache.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } catch (e: Exception) {
                tmp.delete()
                stale = true
            }
        }
        val catalog = cache.takeIf { it.isFile }?.let { runCatching { ManagerJson.decodeFromString(Catalog.serializer(), it.readText()) }.getOrNull() } ?: return null
        return Loaded(s, catalog, url.substringBeforeLast('/', ""), stale)
    }

    /** A bare folder of `.ihq` files is indexed on the fly: the manifest is read, nothing is trusted yet. */
    private fun indexDir(dir: File, s: CatalogSource): Catalog? {
        if (!dir.isDirectory) return null
        val entries = dir.listFiles { f -> f.isFile && f.extension == "ihq" }.orEmpty().sortedBy { it.name }.mapNotNull { f ->
            val m = readManifest(f) ?: return@mapNotNull null
            CatalogEntry(
                m.name, m.description, emptyList(), m.license, null,
                listOf(CatalogVersion(m.version, ApiRanges(), RequestedGrants.of(m), f.absolutePath, f.length(), ReleaseAssets.sha256(f))),
            )
        }
        return Catalog(id = s.id, cls = s.cls, plugins = entries)
    }

    private fun readManifest(ihq: File): ModuleManifest? = runCatching {
        if (ihq.isDirectory) ManagerJson.decodeFromString(ModuleManifest.serializer(), File(ihq, "module.json").readText())
        else JarFile(ihq, false).use { j ->
            j.getJarEntry("module.json")?.let { e -> j.getInputStream(e).use { ManagerJson.decodeFromString(ModuleManifest.serializer(), it.readBytes().decodeToString()) } }
        }
    }.getOrNull()

    private fun loaded(): List<Loaded> = sources().filter { it.enabled }.mapNotNull { load(it, refresh = false) }

    fun search(query: String = "", tags: Set<String> = emptySet()): List<CatalogHit> {
        val q = query.trim().lowercase()
        return loaded().flatMap { l ->
            l.catalog.plugins.filter { e ->
                (q.isEmpty() || e.id.lowercase().contains(q) || e.description.lowercase().contains(q) || e.tags.any { it.lowercase() == q }) &&
                    tags.all { t -> e.tags.any { it.equals(t, true) } }
            }.map { CatalogHit(l.source.id, l.source.pluginClass, it, compatible(it).maxByOrNull { v -> semver(v.version)!! }) }
        }
    }

    private fun compatible(e: CatalogEntry): List<CatalogVersion> = e.versions.filter { semver(it.version) != null && apiProblem(it.api) == null }

    private fun semver(v: String) = SemVer.parseOrNull(v)

    private fun apiProblem(r: ApiRanges): String? {
        fun bad(range: String?, actual: String, what: String): String? {
            if (range.isNullOrBlank()) return null
            val vr = VersionRange.parseOrNull(range) ?: return "$what range '$range' is not valid"
            val v = SemVer.parseOrNull(actual) ?: return null
            return if (vr.contains(v)) null else "needs $what $range, this host is $actual"
        }
        return bad(r.plugin, "$hostPluginApi.0.0", "plugin API") ?: bad(r.daemon, hostDaemonApi, "daemon API")
    }

    // ---------------------------------------------------------------- installed

    private fun config() = PluginFolders.config(root)
    private fun ledger(): Ledger =
        File(root, "installed.json").takeIf { it.isFile }?.let { runCatching { ManagerJson.decodeFromString(Ledger.serializer(), it.readText()) }.getOrNull() } ?: Ledger()

    private fun saveLedger(l: Ledger) {
        root.mkdirs()
        File(root, "installed.json").writeText(ManagerJson.encodeToString(Ledger.serializer(), l) + "\n")
    }

    private fun userFolder(id: String): Pair<File, Tier>? =
        config().folders.map { File(File(root, it.path), id) to it.tier }.firstOrNull { it.first.isDirectory }

    fun list(): List<PluginRow> {
        val cfg = config()
        val led = ledger().installed
        return PluginFolders.scan(root, cfg, bundledRoot).mapNotNull { c ->
            val m = c.manifest ?: return@mapNotNull null
            val bundled = bundledRoot != null && c.dir != null && File(c.dir!!).canonicalPath.startsWith(bundledRoot.canonicalPath + File.separator)
            val row = led[c.folderName]
            val setting = cfg.modules[c.folderName]
            PluginRow(
                c.folderName, m.version,
                row?.let { PluginClass.of(it.cls) } ?: if (c.tier == Tier.COMMUNITY) PluginClass.CUSTOM else PluginClass.OFFICIAL,
                c.tier, bundled,
                setting?.enabled ?: (c.tier != Tier.COMMUNITY),
                row?.source ?: if (bundled) "bundled" else null, m.license, RequestedGrants.of(m), row?.granted.orEmpty(), row?.history.orEmpty(),
            )
        }.sortedBy { it.id }
    }

    fun status(id: String): PluginRow? = list().firstOrNull { it.id == id }

    fun setEnabled(id: String, enabled: Boolean): ManagerResult {
        val row = status(id) ?: return ManagerResult.refused("NOT_INSTALLED", "$id is not installed")
        ModulesFile.update(root, id, enabled = enabled)
        val missing = if (enabled && row.tier == Tier.COMMUNITY) row.requested - row.granted.toSet() else emptyList()
        return ManagerResult.done(
            "$id ${if (enabled) "enabled" else "disabled"}" + if (missing.isNotEmpty()) " (not granted: ${missing.joinToString()}; those parts will not load)" else "",
            restartNeeded = true,
        )
    }

    fun setGrants(id: String, granted: Set<String>): ManagerResult {
        val (dir, tier) = userFolder(id) ?: return ManagerResult.refused("NOT_INSTALLED", "$id is not installed (bundled plugins have no grants to change)")
        val m = readManifest(dir) ?: return ManagerResult.refused("BAD_MANIFEST", "$id has no readable module.json")
        val requested = RequestedGrants.of(m)
        (granted - requested.toSet()).firstOrNull()?.let { return ManagerResult.refused("UNKNOWN_GRANT", "$id does not ask for '$it'", requested) }
        writeGrants(id, granted)
        val l = ledger()
        l.installed[id]?.let { saveLedger(Ledger(l.installed + (id to it.copy(granted = granted.sorted())))) }
        return ManagerResult.done("$id: granted ${granted.size} of ${requested.size} requested (${tier.name.lowercase()} tier)", restartNeeded = true)
    }

    private fun writeGrants(id: String, granted: Collection<String>) {
        val (allow, over, inj) = RequestedGrants.split(granted)
        ModulesFile.update(root, id, allow = allow, allowOverride = over, allowInject = inj)
    }

    // ---------------------------------------------------------------- inspect

    /** http(s) goes through the [fetcher]; a plain path or `file:` URL is read locally, so custom and folder sources never need a network. */
    private fun fetchAny(url: String, dest: File, max: Long) {
        val web = url.startsWith("http://", true) || url.startsWith("https://", true)
        (if (web) fetcher else DefaultFetcher).fetch(url, dest, max)
    }

    private val urlLike = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")

    /**
     * Resolves [ref] to a package, fetches it, and runs every check short of installing: size and sha256 pin (catalog refs),
     * signature and tier (from the bytes, never the class), class rules, manifest equals the catalog entry, api ranges.
     * [ref] is `[source:]id[@version]` (catalog), a `.ihq` path or URL, or a plugin directory. Returns the inspection or the refusal.
     */
    fun inspect(ref: String, forUpdate: Boolean = false): Pair<Inspection?, ManagerResult?> {
        val warnings = mutableListOf<String>()
        var cls = PluginClass.CUSTOM
        var pin: CatalogVersion? = null
        var entry: CatalogEntry? = null
        val sourceName: String
        val file: File
        val asPath = File(ref)
        downloads.mkdirs()
        when {
            asPath.isDirectory -> {
                if (File(asPath, "catalog.json").isFile && !File(asPath, "module.json").isFile) return null to ManagerResult.refused("IS_A_CATALOG", "$ref is a catalog folder; add it with: plugin sources add --dir")
                if (!File(asPath, "module.json").isFile) return null to ManagerResult.refused("BAD_MANIFEST", "$ref has no module.json")
                file = zipDir(asPath, File(downloads, "dir-${System.nanoTime()}.ihq"))
                sourceName = "dir:${asPath.absolutePath}"
                warnings += CUSTOM_WARNING
            }
            asPath.isFile -> {
                file = File(downloads, "file-${System.nanoTime()}.ihq")
                asPath.copyTo(file, overwrite = true)
                sourceName = "file:${asPath.absolutePath}"
                warnings += CUSTOM_WARNING
            }
            urlLike.containsMatchIn(ref) -> {
                file = File(downloads, "url-${System.nanoTime()}.ihq")
                try { fetchAny(ref, file, InstallSettings.load(root).maxPackageBytes) } catch (e: Exception) { return null to ManagerResult.refused("FETCH_FAILED", "could not fetch $ref: ${e.message}") }
                sourceName = "url:$ref"
                warnings += CUSTOM_WARNING
            }
            else -> {
                val hint = ref.substringBefore(':', "").takeIf { ':' in ref && it.isNotEmpty() }
                val rest = if (hint != null) ref.substringAfter(':') else ref
                val id = rest.substringBefore('@')
                val want = rest.substringAfter('@', "").takeIf { it.isNotEmpty() }
                val found = loaded().filter { hint == null || it.source.id == hint }.flatMap { l -> l.catalog.plugins.filter { it.id == id }.map { l to it } }
                if (found.isEmpty()) {
                    val names = sources().filter { it.enabled }.joinToString { it.id }.ifEmpty { "none" }
                    return null to ManagerResult.refused("NOT_IN_CATALOG", "'$id' is not in any enabled catalog (sources: $names); a file, folder or URL can be installed as a custom plugin")
                }
                val (l, e) = found.first()
                entry = e
                cls = l.source.pluginClass
                val all = e.versions.filter { semver(it.version) != null }
                val chosen = if (want != null) all.firstOrNull { it.version == want } else compatible(e).maxByOrNull { semver(it.version)!! }
                if (chosen == null) {
                    return if (want != null) null to ManagerResult.refused("NOT_IN_CATALOG", "$id has no version $want")
                    else null to ManagerResult.refused("API_INCOMPATIBLE", "no version of $id works with this host (${all.mapNotNull { apiProblem(it.api) }.firstOrNull() ?: "no versions"})")
                }
                apiProblem(chosen.api)?.let { return null to ManagerResult.refused("API_INCOMPATIBLE", "$id ${chosen.version} $it") }
                pin = chosen
                sourceName = l.source.id
                file = File(downloads, "${l.source.id}-$id-${chosen.version}-${System.nanoTime()}.ihq")
                val url = if (urlLike.containsMatchIn(chosen.url) || File(chosen.url).isAbsolute) chosen.url else l.base.trimEnd('/', '\\') + "/" + chosen.url
                try { fetchAny(url, file, InstallSettings.load(root).maxPackageBytes) } catch (e2: Exception) { file.delete(); return null to ManagerResult.refused("FETCH_FAILED", "could not fetch $url: ${e2.message}") }
                if (chosen.size != null && file.length() != chosen.size) {
                    val got = file.length(); file.delete()
                    return null to ManagerResult.refused("HASH_MISMATCH", "$id ${chosen.version}: size $got is not the catalog's ${chosen.size}")
                }
            }
        }
        fun refuse(code: String, message: String): Pair<Inspection?, ManagerResult?> { file.delete(); return null to ManagerResult.refused(code, message) }

        val sha = ReleaseAssets.sha256(file)
        if (pin != null && !sha.equals(pin.sha256, ignoreCase = true)) return refuse("HASH_MISMATCH", "${entry!!.id} ${pin.version}: sha256 $sha is not the catalog's ${pin.sha256}")

        val installedNow = readManifest(file)?.name?.let { userFolder(it) != null } == true
        // A broken signature is always refused. Unsigned is accepted for community/custom (the pin, or the user's own say, is the proof), never for official.
        val settings = InstallSettings.load(root).copy(allowUnsigned = cls != PluginClass.OFFICIAL)
        val checked = when (val r = IhqInstaller.inspect(file, root, config(), settings, trustedTier = Tier.OFFICIAL, replace = forUpdate || installedNow)) {
            is IhqResult.Checked -> r
            is IhqResult.Refused -> return refuse(if (cls == PluginClass.OFFICIAL && r.reason == IhqRefusal.UNSIGNED) "NOT_SIGNED_OFFICIAL" else r.reason.name, r.message)
            else -> return refuse("IO", "unexpected installer answer")
        }
        val m = checked.manifest
        if (cls == PluginClass.OFFICIAL && checked.tier != Tier.OFFICIAL) return refuse("NOT_SIGNED_OFFICIAL", "${m.name} comes from an official source but is not signed by a trusted Infantry key")
        if (m.apiVersion != hostPluginApi) return refuse("API_INCOMPATIBLE", "${m.name} ${m.version} is built for plugin API ${m.apiVersion}, this host speaks $hostPluginApi")
        m.daemon?.apiAtLeast?.let { need ->
            val n = SemVer.parseOrNull(need)
            val h = SemVer.parseOrNull(hostDaemonApi)
            if (n != null && h != null && h < n) return refuse("API_INCOMPATIBLE", "${m.name} needs daemon API $need, this host is $hostDaemonApi")
        }
        val grants = RequestedGrants.of(m)
        if (pin != null) {
            if (m.name != entry!!.id || m.version != pin.version) return refuse("GRANTS_MISMATCH", "package is ${m.name} ${m.version}, the catalog entry says ${entry.id} ${pin.version}")
            if (grants.toSet() != pin.grants.toSet()) return refuse("GRANTS_MISMATCH", "package asks for ${grants.joinToString()} but the catalog lists ${pin.grants.joinToString()}")
        }
        val signedByUser = checked.signer != null && userKeys().any { it.subjectX500Principal.name == checked.signer }
        if (checked.signer == null) warnings += "unsigned; the sha256 is the only proof of the bytes"
        if (checked.tier == Tier.OFFICIAL && cls != PluginClass.OFFICIAL) warnings += "signed by a trusted Infantry key (official tier) although the source is ${cls.label}"
        if (m.license.isNullOrBlank() && entry?.license.isNullOrBlank()) warnings += "licence not stated"
        val token = sha256Text(sha + grants.sorted().joinToString(","))
        return Inspection(m.name, m.version, cls, checked.tier, m.license ?: entry?.license, checked.signer, signedByUser, sha, grants, warnings, sourceName, token, file, installedNow) to null
    }

    // ---------------------------------------------------------------- install / update / rollback / remove

    /**
     * Installs [ref]. [consent] = the grants the owner accepts (null = none given: the answer lists what is requested and
     * nothing is installed). [token] (from an earlier inspect) must still match the fresh bytes. Lands enabled with exactly the consented grants.
     */
    fun install(ref: String, consent: Set<String>?, token: String? = null, enable: Boolean = true, grantAll: Boolean = false): ManagerResult {
        val (insp, refused) = inspect(ref)
        if (insp == null) return refused!!
        val consent = if (grantAll) insp.grants.toSet() else consent
        try {
            if (insp.installed) return ManagerResult.refused("ALREADY_INSTALLED", "${insp.id} is installed; use update")
            if (token != null && token != insp.token) return ManagerResult.refused("CHANGED", "${insp.id} changed since it was checked; inspect it again")
            val needsConsent = insp.tier == Tier.COMMUNITY
            if (needsConsent) consentProblem(insp.grants, consent)?.let { return it }
            val settings = InstallSettings.load(root).copy(allowUnsigned = insp.cls != PluginClass.OFFICIAL)
            return when (val r = IhqInstaller.install(insp.file, root, config(), settings, trustedTier = Tier.OFFICIAL)) {
                is IhqResult.Refused -> ManagerResult.refused(r.reason.name, r.message)
                is IhqResult.Installed -> {
                    val granted = if (needsConsent) consent.orEmpty() else insp.grants.toSet()
                    if (needsConsent) writeGrants(r.name, granted)
                    ModulesFile.update(root, r.name, enabled = enable)
                    val l = ledger()
                    val row = LedgerRow(r.version, insp.cls.label, r.tier.name.lowercase(), insp.source, insp.sha256, insp.signer, granted.sorted(), emptyList(), insp.license)
                    saveLedger(Ledger(l.installed + (r.name to row)))
                    ManagerResult.done("${r.name} ${r.version} installed as ${insp.cls.label} (${r.tier.name.lowercase()} tier), ${if (enable) "enabled" else "disabled"}", restartNeeded = true)
                }
                else -> ManagerResult.refused("IO", "unexpected installer answer")
            }
        } finally {
            insp.file.delete()
        }
    }

    private fun consentProblem(requested: List<String>, consent: Set<String>?): ManagerResult? {
        if (consent == null && requested.isNotEmpty()) return ManagerResult.refused("CONSENT_REQUIRED", "asks for ${requested.size} grant(s): ${requested.joinToString()}", requested)
        (consent.orEmpty() - requested.toSet()).firstOrNull()?.let { return ManagerResult.refused("UNKNOWN_GRANT", "the plugin does not ask for '$it'", requested) }
        return null
    }

    /** The newer compatible version of every plugin installed from a catalog; [UpdateOffer.grantsRaised] ones never auto-apply. */
    fun checkUpdates(): List<UpdateOffer> {
        val cats = loaded()
        return ledger().installed.mapNotNull { (id, row) ->
            val cur = semver(row.version) ?: return@mapNotNull null
            val l = cats.firstOrNull { it.source.id == row.source } ?: return@mapNotNull null
            val e = l.catalog.plugins.firstOrNull { it.id == id } ?: return@mapNotNull null
            val best = compatible(e).filter { semver(it.version)!! > cur }.maxByOrNull { semver(it.version)!! } ?: return@mapNotNull null
            UpdateOffer(id, row.version, best.version, row.source, best.grants - row.granted.toSet())
        }.sortedBy { it.id }
    }

    /**
     * Updates [id] from its catalog source, or from [ref] (a newer file/dir/URL). Grants the new version asks for beyond
     * what was consented are never applied silently: [consent] must cover them or the answer is GRANTS_RAISED.
     */
    fun update(id: String, consent: Set<String>? = null, ref: String? = null, grantAll: Boolean = false): ManagerResult {
        val row = ledger().installed[id]
        val (dir, tier) = userFolder(id)
            ?: return ManagerResult.refused("NOT_INSTALLED", "$id is not installed" + if (status(id)?.bundled == true) " by the manager (bundled plugins update with the app)" else "")
        if (row == null && ref == null) return ManagerResult.refused("NO_SOURCE", "$id was not installed through the manager; give a package to update from")
        val target = ref ?: "${row!!.source}:$id"
        val (insp, refused) = inspect(target, forUpdate = true)
        if (insp == null) return refused!!
        val consent = if (grantAll) insp.grants.toSet() else consent
        try {
            if (insp.id != id) return ManagerResult.refused("BAD_MANIFEST", "that package is ${insp.id}, not $id")
            val cur = semver(readManifest(dir)?.version ?: "") ?: return ManagerResult.refused("BAD_MANIFEST", "$id has no readable version")
            val next = semver(insp.version) ?: return ManagerResult.refused("BAD_MANIFEST", "bad version ${insp.version}")
            if (next <= cur) return ManagerResult.refused("NOT_NEWER", "$id ${insp.version} is not newer than $cur")
            val previouslyGranted = row?.granted.orEmpty().toSet()
            val newAsks = if (tier == Tier.COMMUNITY) insp.grants.filter { it !in previouslyGranted } else emptyList()
            if (newAsks.isNotEmpty() && !(consent != null && consent.containsAll(newAsks))) {
                return ManagerResult.refused("GRANTS_RAISED", "$id ${insp.version} asks for new grant(s): ${newAsks.joinToString()}; consent is needed, it is never applied automatically", newAsks)
            }
            (consent.orEmpty() - insp.grants.toSet()).firstOrNull()?.let { return ManagerResult.refused("UNKNOWN_GRANT", "the plugin does not ask for '$it'", insp.grants) }
            val keep = File(versionsDir, "$id/$cur")
            swapOut(dir, keep)?.let { return it }
            val settings = InstallSettings.load(root).copy(allowUnsigned = insp.cls != PluginClass.OFFICIAL)
            val r = IhqInstaller.install(insp.file, root, config(), settings, trustedTier = Tier.OFFICIAL, replace = true)
            if (r !is IhqResult.Installed) {
                runCatching { File(dir.path).deleteRecursively(); Files.move(keep.toPath(), dir.toPath()) }
                val refusal = r as? IhqResult.Refused
                return ManagerResult.refused(refusal?.reason?.name ?: "IO", refusal?.message ?: "install failed; the old version was restored")
            }
            val granted = (previouslyGranted.filter { it in insp.grants } + consent.orEmpty()).toSet()
            if (r.tier == Tier.COMMUNITY) writeGrants(id, granted)
            val history = pruneHistory(id, (row?.history.orEmpty() + cur.toString()).distinct())
            val updated = LedgerRow(r.version, row?.cls ?: insp.cls.label, r.tier.name.lowercase(), if (ref != null) insp.source else row!!.source, insp.sha256, insp.signer, granted.sorted(), history, insp.license)
            saveLedger(Ledger(ledger().installed + (id to updated)))
            return ManagerResult.done("$id updated $cur -> ${r.version}; $cur kept for rollback", restartNeeded = true)
        } finally {
            insp.file.delete()
        }
    }

    /** Swaps the active version for the newest kept one; the active one is kept in turn, so a rollback can be undone with an update. */
    fun rollback(id: String): ManagerResult {
        val row = ledger().installed[id] ?: return ManagerResult.refused("NOT_INSTALLED", "$id was not installed through the manager")
        val (dir, tier) = userFolder(id) ?: return ManagerResult.refused("NOT_INSTALLED", "$id is not installed")
        val prev = row.history.filter { File(versionsDir, "$id/$it").isDirectory }.maxByOrNull { semver(it) ?: SemVer(0, 0, 0) }
            ?: return ManagerResult.refused("NO_ROLLBACK", "$id has no previous version kept")
        val cur = readManifest(dir)?.version ?: row.version
        val keepCur = File(versionsDir, "$id/$cur")
        swapOut(dir, keepCur)?.let { return it }
        try {
            Files.move(File(versionsDir, "$id/$prev").toPath(), dir.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            runCatching { dir.deleteRecursively(); Files.move(keepCur.toPath(), dir.toPath()) }
            return ManagerResult.refused("IN_USE", "could not restore $prev: ${e.message}")
        }
        val requested = readManifest(dir)?.let { RequestedGrants.of(it) }.orEmpty().toSet()
        // The older version may ask for less: extra grants are dropped, never kept.
        val granted = row.granted.filter { it in requested }
        if (tier == Tier.COMMUNITY) writeGrants(id, granted)
        saveLedger(Ledger(ledger().installed + (id to row.copy(version = prev, granted = granted, history = (row.history - prev + cur).distinct()))))
        return ManagerResult.done("$id rolled back $cur -> $prev", restartNeeded = true)
    }

    /** Removes an installed plugin (its files go to the version store unless [purge]); bundled plugins are only disabled. */
    fun remove(id: String, purge: Boolean = false): ManagerResult {
        val (dir, _) = userFolder(id) ?: return if (status(id)?.bundled == true) ManagerResult.refused("BUNDLED", "$id is bundled with the app; disable it instead")
        else ManagerResult.refused("NOT_INSTALLED", "$id is not installed")
        val ver = readManifest(dir)?.version ?: "unknown"
        if (purge) {
            if (!dir.deleteRecursively()) return ManagerResult.refused("IN_USE", "could not delete ${dir.path} (a file in it is in use)")
            File(versionsDir, id).deleteRecursively()
            File(stateDir, "plugin-data/$id").deleteRecursively()
        } else swapOut(dir, File(versionsDir, "$id/$ver"))?.let { return it }
        ModulesFile.forget(root, id)
        val l = ledger()
        if (id in l.installed) saveLedger(Ledger(l.installed - id))
        return ManagerResult.done("$id removed" + if (purge) " (and its data)" else "", restartNeeded = true)
    }

    private fun swapOut(dir: File, keep: File): ManagerResult? {
        keep.parentFile.mkdirs()
        if (keep.exists()) keep.deleteRecursively()
        return try {
            Files.move(dir.toPath(), keep.toPath(), StandardCopyOption.ATOMIC_MOVE)
            null
        } catch (e: IOException) {
            ManagerResult.refused("IN_USE", "${dir.name} is in use (${e.message?.lineSequence()?.first()}); stop the daemon or the app and try again")
        }
    }

    private fun pruneHistory(id: String, history: List<String>): List<String> {
        val sorted = history.sortedBy { semver(it) ?: SemVer(0, 0, 0) }
        sorted.dropLast(keepVersions).forEach { File(versionsDir, "$id/$it").deleteRecursively() }
        return sorted.takeLast(keepVersions)
    }

    // ---------------------------------------------------------------- validate (community index CI)

    /** The checks a community index runs on a pull request: sha256, size, manifest, grants and api of every listed version. [base] resolves relative urls. */
    fun validate(catalog: Catalog, base: File): List<String> {
        val problems = mutableListOf<String>()
        if (catalog.schema != 1) problems += "schema ${catalog.schema} is not 1"
        for (e in catalog.plugins) for (v in e.versions) {
            val tag = "${e.id} ${v.version}"
            if (!ID.matches(e.id)) problems += "$tag: bad id"
            if (semver(v.version) == null) problems += "$tag: version is not semver"
            listOfNotNull(v.api.plugin, v.api.daemon, v.api.harness).filter { VersionRange.parseOrNull(it) == null }.forEach { problems += "$tag: bad api range '$it'" }
            val f = if (File(v.url).isAbsolute) File(v.url) else File(base, v.url)
            if (!f.isFile) { problems += "$tag: asset ${v.url} not found"; continue }
            if (!ReleaseAssets.sha256(f).equals(v.sha256, true)) problems += "$tag: sha256 does not match the asset"
            if (v.size != null && v.size != f.length()) problems += "$tag: size does not match the asset"
            val m = readManifest(f)
            when {
                m == null -> problems += "$tag: asset has no readable module.json"
                m.name != e.id || m.version != v.version -> problems += "$tag: asset is ${m.name} ${m.version}"
                RequestedGrants.of(m).toSet() != v.grants.toSet() -> problems += "$tag: grants should be ${RequestedGrants.of(m).joinToString()}"
            }
        }
        return problems
    }

    // ---------------------------------------------------------------- helpers

    private fun userKeys(): List<X509Certificate> {
        val dir = File(File(root, InstallSettings.load(root).trustedKeys), "custom")
        val cf = CertificateFactory.getInstance("X.509")
        return dir.listFiles { f -> f.isFile && f.extension.lowercase() in setOf("cer", "crt", "pem") }.orEmpty()
            .mapNotNull { f -> runCatching { f.inputStream().use { cf.generateCertificate(it) as X509Certificate } }.getOrNull() }
    }

    private fun zipDir(dir: File, out: File): File {
        ZipOutputStream(out.outputStream()).use { z ->
            dir.walkTopDown().filter { it.isFile }.forEach { f ->
                val rel = f.relativeTo(dir).invariantSeparatorsPath
                if (rel == "module.json" || rel.startsWith("lib/") || rel.startsWith("layout/")) {
                    z.putNextEntry(ZipEntry(rel))
                    f.inputStream().use { it.copyTo(z) }
                    z.closeEntry()
                }
            }
        }
        return out
    }

    private fun sha256Text(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        val ID = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")
        const val MAX_CATALOG = 8L shl 20
        const val CUSTOM_WARNING = "not listed in any catalog: this is a CUSTOM plugin from a source you pointed at; only install code you trust"
    }
}
