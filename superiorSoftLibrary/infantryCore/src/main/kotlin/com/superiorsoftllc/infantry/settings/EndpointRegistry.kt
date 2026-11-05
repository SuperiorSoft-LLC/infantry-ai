// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.settings

/**
 * DOMAIN-ENV-1: the one place that names every domain, URL and repository the product reaches (site, docs, downloads,
 * the releases repository and API, the Docker image). Moving the site or the release files is a configuration change:
 *
 * **environment variable > saved setting > built-in default**
 *
 * The environment wins so a deployment (`docker run -e`, compose) can repoint an image without touching its data
 * volume. A blank value at any layer counts as unset. The mechanism is generic: a def is a key, an env name, a kind
 * (what a value must look like) and a default; nothing here knows what a reader does with the value.
 */
data class EndpointDef(
    /** The settings key, e.g. `urls.site`. */
    val key: String,
    /** The environment variable that overrides it, e.g. `INFANTRY_SITE_URL` (the legacy `AGENTHQ_*` name is read too). */
    val env: String,
    val kind: EndpointKind,
    /** What works today; "" = none (the reader treats it as not configured). */
    val default: String,
    /** One line for the settings catalog and the wiki: what reads this value. */
    val description: String,
)

/** What a value of an [EndpointDef] must look like. */
enum class EndpointKind(val hint: String) {
    /** An absolute `http://` or `https://` URL. */
    URL("an http:// or https:// URL"),

    /** A GitHub-style `owner/repo`. */
    REPO("owner/repo"),

    /** A container image reference without a tag, e.g. `registry.example.com/team/app`. */
    IMAGE("an image name such as owner/name or registry/owner/name");

    fun accepts(value: String): Boolean = when (this) {
        URL -> URL_PATTERN.matches(value)
        REPO -> REPO_PATTERN.matches(value)
        IMAGE -> IMAGE_PATTERN.matches(value)
    }

    private companion object {
        val URL_PATTERN = Regex("""^https?://[^\s/?#]+[^\s]*$""")
        val REPO_PATTERN = Regex("""^[A-Za-z0-9-]{1,39}/[A-Za-z0-9._-]{1,100}$""")
        val IMAGE_PATTERN = Regex("""^[a-z0-9]([a-z0-9._/:-]*[a-z0-9])?$""")
    }
}

object EndpointRegistry {
    /** The product's home page (About, footer links). */
    val SITE = EndpointDef(
        "urls.site", "INFANTRY_SITE_URL", EndpointKind.URL,
        "https://github.com/SuperiorSoft-LLC/infantry-ai",
        "The product home page clients link to (About, help menus).",
    )

    /** The documentation root. */
    val DOCS = EndpointDef(
        "urls.docs", "INFANTRY_DOCS_URL", EndpointKind.URL,
        "https://github.com/SuperiorSoft-LLC/infantry-ai/tree/main/docs/wiki",
        "The documentation root clients link to (Help, wiki links).",
    )

    /** Where a person downloads an installer by hand. */
    val DOWNLOADS = EndpointDef(
        "urls.downloads", "INFANTRY_DOWNLOAD_URL", EndpointKind.URL,
        "https://github.com/SuperiorSoft-LLC/infantry-ai/releases",
        "The page a person downloads installers from by hand (update notices, the site).",
    )

    /** The repository whose releases carry the update files. Empty today: updates stay off until one is set. */
    val RELEASES_REPO = EndpointDef(
        "urls.releasesRepo", "INFANTRY_UPDATE_REPO", EndpointKind.REPO,
        "",
        "owner/repo whose releases hold the update files (the updater). Empty = updates not configured.",
    )

    /** The releases API base (GitHub's, or a mirror / fake server). */
    val RELEASES_API = EndpointDef(
        "urls.releasesApi", "INFANTRY_UPDATE_API", EndpointKind.URL,
        "https://api.github.com",
        "The releases API base the updater asks for new versions; no trailing slash.",
    )

    /** The Docker image a Docker install tells the owner to pull. */
    val DOCKER_IMAGE = EndpointDef(
        "urls.dockerImage", "INFANTRY_DOCKER_IMAGE", EndpointKind.IMAGE,
        "infantryai/infantry-ai",
        "The image a Docker install reports for `docker pull` when an update exists.",
    )

    /** CONTRIB-1: the source repository's web page (About > Contribute, Report a problem, View on GitHub). "" hides those links. */
    val SOURCE_REPO = EndpointDef(
        "urls.sourceRepo", "INFANTRY_SOURCE_URL", EndpointKind.URL,
        "https://github.com/SuperiorSoft-LLC/infantry-ai",
        "The source repository's web page; About links to its CONTRIBUTING.md, new issue form and home. Empty = the links hide.",
    )

    val ALL: List<EndpointDef> = listOf(SITE, DOCS, DOWNLOADS, SOURCE_REPO, RELEASES_REPO, RELEASES_API, DOCKER_IMAGE)

    private val byKey = ALL.associateBy { it.key }

    fun get(key: String): EndpointDef? = byKey[key]

    /**
     * [def]'s effective value: [env] (`def.env`), else [stored] (the saved setting), else the default. A value that does
     * not fit [EndpointDef.kind] is skipped (logged by the caller through [problems]), so a typo never breaks a reader.
     * A URL loses its trailing slash.
     */
    fun resolve(def: EndpointDef, env: (String) -> String?, stored: (String) -> String?): String =
        listOf(env(def.env), stored(def.key)).firstNotNullOfOrNull { clean(def, it) } ?: def.default

    /** Which layer [resolve] took the value from: `env`, `setting` or `default`. */
    fun source(def: EndpointDef, env: (String) -> String?, stored: (String) -> String?): String = when {
        clean(def, env(def.env)) != null -> "env"
        clean(def, stored(def.key)) != null -> "setting"
        else -> "default"
    }

    /** One line per environment value that is set but does not fit its kind (it is ignored). */
    fun problems(env: (String) -> String?): List<String> = ALL.mapNotNull { def ->
        val raw = env(def.env)?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        if (clean(def, raw) == null) "${def.env}='$raw' is not ${def.kind.hint}; ${def.key} uses its setting or default" else null
    }

    /** [value] as it is stored for [def]: trimmed, a URL without a trailing slash; "" resets to the default. Throws on a bad value. */
    fun validate(def: EndpointDef, value: String): String {
        val v = value.trim()
        if (v.isEmpty()) return ""
        return clean(def, v) ?: throw IllegalArgumentException("setting '${def.key}' must be ${def.kind.hint} (\"\" = the default)")
    }

    private fun clean(def: EndpointDef, raw: String?): String? {
        val v = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val n = if (def.kind == EndpointKind.URL) v.trimEnd('/') else v
        return n.takeIf { def.kind.accepts(it) }
    }
}
