// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.plugins.manager

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import java.io.IOException
import java.net.URI

/**
 * PLUGIN-REPO-1 (docs/PLUGIN-MANAGER.md "Plugin lists"): a hand-written plugin list is a schema-1 [Catalog] whose entries may
 * carry only `id`, `name`, `description`, `repo` and `tags`; versions, sha256 and grants are optional extras. There is no
 * second format: an imported list is saved as a [CatalogSource] in `sources.json` and read by the same manager.
 */
object PluginLists {
    private val ID = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")

    /** Reads [text] as a plugin list; returns the catalog, or every problem found (nothing is half-accepted). */
    fun parse(text: String): Pair<Catalog?, List<String>> {
        val c = runCatching { ManagerJson.decodeFromString(Catalog.serializer(), text) }
            .getOrElse { return null to listOf("not a plugin list: ${it.message?.lineSequence()?.first()}") }
        val problems = mutableListOf<String>()
        if (c.schema != 1) problems += "schema ${c.schema} is not 1"
        for (e in c.plugins) {
            if (!ID.matches(e.id)) problems += "'${e.id.take(40)}': id is not a plain name"
            if (e.versions.isEmpty() && e.repo.isNullOrBlank()) problems += "${e.id}: needs a repo (or versions)"
            e.repo?.takeIf { it.isNotBlank() }?.let { if (RepoRef.parse(it) == null) problems += "${e.id}: repo '${it.take(80)}' is not owner/name or an https/file URL" }
        }
        c.plugins.groupBy { it.id }.filter { it.value.size > 1 }.keys.forEach { problems += "$it: id is not unique" }
        return if (problems.isEmpty()) c to emptyList() else null to problems
    }

    /** The source id an imported list is saved under: its `id`, else a slug of its `name`, else of the file name. */
    fun sourceId(c: Catalog, location: String): String {
        // A slug longer than the id pattern allows fails it and the next candidate is tried.
        fun slug(s: String) = s.lowercase().replace(Regex("[^a-z0-9_.-]+"), "-").trim('-', '.')
        return listOf(c.id, c.name.orEmpty(), location.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.'))
            .map { slug(it) }.firstOrNull { ID.matches(it) } ?: "plugin-list"
    }

    /** A plain path becomes a `file:` URL, so the source re-reads the file on refresh; a URL stays as given. */
    fun locationUrl(location: String): String =
        if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(location)) location else File(location).absoluteFile.toURI().toString()
}

/** PLUGIN-REPO-1: where a list entry's `repo` points. */
sealed interface RepoRef {
    /** A GitHub-style `owner/name`: the package is an asset of its latest release. */
    data class Releases(val ownerRepo: String) : RepoRef
    /** An https / http / file URL to the package itself. */
    data class Direct(val url: String) : RepoRef

    companion object {
        private val OWNER_REPO = Regex("[A-Za-z0-9][A-Za-z0-9_.-]*/[A-Za-z0-9_.-]+")
        private val GITHUB_PAGE = Regex("^https?://(www\\.)?github\\.com/([A-Za-z0-9][A-Za-z0-9_.-]*/[A-Za-z0-9_.-]+?)(\\.git)?/?$", RegexOption.IGNORE_CASE)

        fun parse(repo: String): RepoRef? {
            val r = repo.trim()
            GITHUB_PAGE.matchEntire(r)?.let { return Releases(it.groupValues[2]) }
            if (OWNER_REPO.matches(r)) return Releases(r)
            val scheme = runCatching { URI(r).scheme?.lowercase() }.getOrNull()
            return if (scheme == "https" || scheme == "http" || scheme == "file") Direct(r) else null
        }
    }
}

/** The fields of `GET /repos/{owner}/{repo}/releases/latest` the manager reads (the Updater reads the same shape). */
@Serializable
data class RepoRelease(
    @SerialName("tag_name") val tagName: String,
    @SerialName("html_url") val htmlUrl: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<RepoAsset> = emptyList(),
)

@Serializable
data class RepoAsset(val name: String, val size: Long = 0, @SerialName("browser_download_url") val browserDownloadUrl: String? = null)

/** A repo entry resolved to one package: its [version] (the release tag), the asset [url], and the SHA256SUMS line for it (null = the release has none). */
data class ResolvedPackage(val version: String, val url: String, val assetName: String, val sha256: String?, val release: String?)

/**
 * PLUGIN-REPO-1: resolves a list entry's `repo` to a package through the public releases API ([apiBase], the
 * `urls.pluginReleasesApi` setting; no token, public repos only), over the manager's [Fetcher] so tests and the check run
 * against a local server. The asset is the `.ihq` / `.zip` / `.jar` whose name starts with the plugin id, else the only one.
 * [maxBytes] caps the release JSON and the SHA256SUMS file (the manager passes its catalog fetch cap).
 */
class RepoReleases(private val fetcher: Fetcher, private val apiBase: () -> String, private val scratch: File, private val maxBytes: Long) {
    fun latest(ownerRepo: String): RepoRelease {
        val f = File(scratch, "release-${System.nanoTime()}.json")
        try {
            fetcher.fetch("${apiBase().trimEnd('/')}/repos/$ownerRepo/releases/latest", f, maxBytes)
            return runCatching { ManagerJson.decodeFromString(RepoRelease.serializer(), f.readText()) }
                .getOrElse { throw IOException("the release JSON of $ownerRepo could not be read") }
        } finally {
            f.delete()
        }
    }

    /** The newest version [ref] offers, without downloading the package (null for a [RepoRef.Direct], which has no version). */
    fun latestVersion(ref: RepoRef): String? = (ref as? RepoRef.Releases)?.let { versionOf(latest(it.ownerRepo).tagName) }

    fun resolve(id: String, ref: RepoRef): ResolvedPackage = when (ref) {
        is RepoRef.Direct -> ResolvedPackage("", ref.url, ref.url.substringAfterLast('/'), null, null)
        is RepoRef.Releases -> {
            val rel = latest(ref.ownerRepo)
            val asset = pickAsset(id, rel.assets) ?: throw IOException(
                "release ${rel.tagName} of ${ref.ownerRepo} has no plugin package (an .ihq, .zip or .jar named $id..., or a single asset)",
            )
            val url = asset.browserDownloadUrl ?: throw IOException("${asset.name} has no download url")
            val sums = rel.assets.firstOrNull { it.name.equals(SUMS, ignoreCase = true) }?.browserDownloadUrl
            val sha = sums?.let { u ->
                val f = File(scratch, "sums-${System.nanoTime()}.txt")
                try {
                    fetcher.fetch(u, f, maxBytes)
                    sumFor(f.readText(), asset.name) ?: throw IOException("$SUMS of ${rel.tagName} has no line for ${asset.name}")
                } finally {
                    f.delete()
                }
            }
            ResolvedPackage(versionOf(rel.tagName), url, asset.name, sha, rel.htmlUrl)
        }
    }

    companion object {
        const val SUMS = "SHA256SUMS"
        private val PACKAGE_EXT = setOf("ihq", "zip", "jar")

        fun versionOf(tag: String) = tag.trim().removePrefix("v").removePrefix("V")

        fun pickAsset(id: String, assets: List<RepoAsset>): RepoAsset? {
            val packages = assets.filter { it.name.substringAfterLast('.', "").lowercase() in PACKAGE_EXT }
            return packages.firstOrNull { it.name.startsWith(id, ignoreCase = true) }
                ?: packages.singleOrNull()
                ?: assets.filter { !it.name.equals(SUMS, ignoreCase = true) }.singleOrNull()
        }

        /** The hash `sha256sum` wrote for [name] (`<hex>  <name>`, or `<hex> *<name>`), lowercase; null = no line. */
        fun sumFor(text: String, name: String): String? = text.lineSequence().map { it.trim() }.mapNotNull { line ->
            val m = Regex("^([0-9a-fA-F]{64})\\s+\\*?(.+)$").matchEntire(line) ?: return@mapNotNull null
            m.groupValues[1].lowercase().takeIf { m.groupValues[2].trim() == name }
        }.firstOrNull()
    }
}
