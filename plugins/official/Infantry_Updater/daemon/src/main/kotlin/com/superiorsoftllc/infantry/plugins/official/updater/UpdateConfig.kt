// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.updater


import java.io.File
import java.util.Properties
import java.util.concurrent.TimeUnit

/**
 * UPDATE-1: what the updater needs to ask GitHub Releases for a newer build of the INSTALLED app.
 *
 * [token] is read on demand and never stored in a field that prints: [toString] says only whether one is set.
 */
class UpdateConfig(
    /** `owner/repo` on GitHub. */
    val repo: String,
    /** The running app's version (`-PinfantryVersion`, baked into the launcher as `infantry.version`). */
    val currentVersion: SemVer,
    /** `https://api.github.com`, or a local fake Releases server in tests. No trailing slash. */
    val apiBase: String = DEFAULT_API,
    /** UPDATE-2: when set, updates come from this releases folder ([LocalDirSource]) instead of GitHub. */
    val localDir: File? = null,
    /** UPDATER-1: public never carries a token ([token] is always null); private reads one through [tokenSource]. */
    val visibility: UpdateVisibility = UpdateVisibility.PUBLIC,
    /** UPDATER-1: hours between background re-checks; 0 = only at start and by hand. */
    val checkIntervalHours: Int = 0,
    /** UPDATER-DEFAULT-1 (`updater.autoCheck`, the one user setting): false = check only when the user asks. */
    val autoCheck: Boolean = true,
    /** NIGHTLY-1 (`updater.releaseChannel`, a user setting): which releases count ([ReleaseChannel]); Stable by default. */
    val channel: ReleaseChannel = ReleaseChannel.STABLE,
    private val tokenSource: () -> String? = { null },
) {
    /** The GitHub token for a private repo, or null. Never log it. */
    val token: String? by lazy { if (visibility == UpdateVisibility.PUBLIC) null else tokenSource()?.trim()?.takeIf { it.isNotEmpty() } }

    override fun toString(): String = "UpdateConfig(repo=$repo, current=$currentVersion, api=$apiBase, source=${visibility.wire}, channel=${channel.id}, token=${if (token != null) "set" else "none"})"

    /** The same source asked on behalf of another install running [version] (UPDATER-1: a phone asking its daemon). */
    fun forVersion(version: SemVer): UpdateConfig = UpdateConfig(repo, version, apiBase, localDir, visibility, checkIntervalHours, autoCheck, channel, tokenSource)

    companion object {
        const val DEFAULT_API = "https://api.github.com"
    }
}

/** UPDATER-1 (`updater.source`): public = no token is ever sent; private = a read token from env, keystore or the OS store. */
enum class UpdateVisibility(val wire: String) {
    PUBLIC("public"),
    PRIVATE("private");

    companion object {
        fun parse(text: String?): UpdateVisibility? = entries.firstOrNull { it.wire == text?.trim()?.lowercase() }
    }
}

/** UPDATE-1: whether this launch may update, and from where. */
sealed interface UpdateSettings {
    /** Updates are off for this launch; [reason] is shown when the owner checks by hand. */
    data class Disabled(val reason: String) : UpdateSettings

    /** [warnings]: values that were ignored (a bad override never turns updates off); log them, never block on them. */
    data class Enabled(val config: UpdateConfig, val warnings: List<String> = emptyList()) : UpdateSettings

    companion object {
        /** `owner/repo`. A setting (system property or env), else `repo=` in `<stateDir>/update.properties`. */
        const val REPO = "INFANTRY_UPDATE_REPO"

        /** The GitHub API base; only a test or a smoke run points it at a local fake server. */
        const val API = "INFANTRY_UPDATE_API"

        /** The token for a private repo: env var ONLY (a system property would show on a command line). */
        const val TOKEN_ENV = "INFANTRY_UPDATE_TOKEN"

        /** The keystore entry that holds the private-repo token (module.json `daemon.secrets`). */
        const val TOKEN_SECRET = "update-token"

        /** UPDATER-1: `public` | `private`, an environment override of the `updater.source` setting. */
        const val SOURCE_ENV = "INFANTRY_UPDATE_SOURCE"

        /** The running version, set on the jpackage launcher by infantryUi/build.gradle.kts. */
        const val VERSION_PROPERTY = "infantry.version"

        /** UPDATE-2: a local releases folder (setting/env, else `dir=` in update.properties); wins over the GitHub repo. */
        const val DIR = "INFANTRY_UPDATE_DIR"

        /** UPDATE-4: the channels `channel=` accepts: unstable = local releases folder, production = GitHub releases. */
        val CHANNELS = setOf("unstable", "production")

        const val FILE_NAME = "update.properties"

        /**
         * UPDATER-1: the plugin's settings (module.json `config`). The daemon half reads them with `DaemonApi.config` and
         * mirrors them to [SETTINGS_FILE] in its plugin-data folder, so the window's own updater (which starts before the
         * daemon) reads the same values. The mirror never holds a token.
         */
        const val KEY_SOURCE = "updater.source"
        const val KEY_REPO = "updater.repo"
        const val KEY_CHANNEL = "updater.channel"
        const val KEY_DIR = "updater.dir"
        const val KEY_INTERVAL = "updater.checkIntervalHours"

        /** UPDATER-DEFAULT-1: the ONE user setting (Configuration > About > Updater); the keys above are developer overrides. */
        const val KEY_AUTO = "updater.autoCheck"

        /**
         * NIGHTLY-1: the second user setting, "Update channel" (Stable / Nightly). Its values are the ids of the manifest's
         * `channels`; not to be confused with [KEY_CHANNEL], the developer switch between a local folder and GitHub.
         */
        const val KEY_RELEASE_CHANNEL = "updater.releaseChannel"
        val SETTING_KEYS = listOf(KEY_SOURCE, KEY_REPO, KEY_CHANNEL, KEY_DIR, KEY_INTERVAL, KEY_AUTO, KEY_RELEASE_CHANNEL)

        const val MODULE_ID = "Infantry_Updater"
        const val SETTINGS_FILE = "settings.properties"

        /** `<stateDir>/plugin-data/Infantry_Updater/settings.properties`: the mirror of the plugin's settings. */
        fun settingsMirror(stateDir: File): File = File(File(File(stateDir, "plugin-data"), MODULE_ID), SETTINGS_FILE)

        /** Default re-check period: once a day (one small API call); 0 = only at start and by hand. */
        const val DEFAULT_INTERVAL_HOURS = 24

        private val REPO_PATTERN = Regex("""^[A-Za-z0-9-]{1,39}/[A-Za-z0-9._-]{1,100}$""")

        private fun load(f: File?): Properties? = f?.takeIf { it.isFile }?.let { file ->
            runCatching { Properties().apply { file.reader().use { load(it) } } }.getOrNull()
        }

        /**
         * Resolve the settings of this launch.
         *
         * - [installed] false (a dev build) is ALWAYS disabled: a dev build never updates itself.
         * - Where releases come from is MANIFEST data ([manifest], UPDATER-DEFAULT-1: the module.json `appUpdate` block the build
         *   baked in), so an official install needs no setup. Developer overrides, first match wins: [setting] / [env] (the
         *   `INFANTRY_UPDATE_*` variables), then `<stateDir>/update.properties` (hand-written: the old keys `channel`, `source`,
         *   `dir`, `repo`, and the `updater.*` keys), then the module config ([config], else the mirror file the daemon half
         *   writes; the UI shows only `updater.autoCheck`), then the manifest, then the defaults.
         * - A bad override (a visibility that is not public/private, an unknown channel, a repo that is not owner/repo, an
         *   interval that is not a whole number) is IGNORED with a warning ([Enabled.warnings]); it never turns updates off.
         *   `source=public|private` in update.properties is read as the visibility (UPDATER-1's key), `source=local|github`
         *   as the old source switch.
         * - A `token` key in any file is ignored: the token lives in the keystore, the environment or the OS credential store.
         * - Visibility public (the default) never sends a token, whatever is set. private: `INFANTRY_UPDATE_TOKEN` from [env],
         *   else the daemon's [keystore] (`update-token`), else [credentialStore] (the OS credential store, only for GitHub's
         *   own API), read lazily on the first request.
         */
        fun resolve(
            installed: Boolean,
            version: String?,
            stateDir: File?,
            setting: (String) -> String?,
            env: (String) -> String? = UpdaterEnv::env,
            keystore: () -> String? = { null },
            credentialStore: () -> String? = { GitCredentialStore.githubToken() },
            config: (String) -> String? = { null },
            manifest: (String) -> String? = UpdateManifest::get,
        ): UpdateSettings {
            if (!installed) return Disabled("this is a dev build; dev builds never update themselves")
            val current = SemVer.parse(version) ?: return Disabled("the running version is unknown (${version ?: "unset"})")
            val warnings = mutableListOf<String>()
            val file = load(stateDir?.let { File(it, FILE_NAME) })
            val mirror = load(stateDir?.let(::settingsMirror))
            fun clean(v: String?) = v?.trim()?.takeIf { it.isNotEmpty() }
            fun plugin(key: String) = clean(file?.getProperty(key)) ?: clean(config(key)) ?: clean(mirror?.getProperty(key))
            // The first candidate [ok] accepts; each rejected one is a warning, never a reason to stop.
            fun pick(what: String, candidates: List<String?>, ok: (String) -> Boolean): String? {
                for (c in candidates) {
                    val v = clean(c) ?: continue
                    if (ok(v)) return v
                    warnings += "ignored $what '$v'"
                }
                return null
            }
            // update.properties `source=`: the old local|github switch, or (UPDATER-1's key, a clash) public|private.
            val fileSource = clean(file?.getProperty("source"))?.lowercase()
            val explicit = fileSource?.takeIf { it == "local" || it == "github" }
            val fileVisibility = fileSource?.takeIf { UpdateVisibility.parse(it) != null }
            if (fileSource != null && explicit == null && fileVisibility == null) warnings += "ignored update source '$fileSource' (not local, github, public or private)"
            val dir = clean(setting(DIR)) ?: clean(env(DIR)) ?: clean(file?.getProperty("dir")) ?: plugin(KEY_DIR)
            val repo = pick("update repo (not owner/repo)", listOf(setting(REPO), file?.getProperty("repo"), plugin(KEY_REPO), manifest(UpdateManifest.KEY_REPO))) { REPO_PATTERN.matches(it) }
            val visibility = pick("$KEY_SOURCE (not public or private)", listOf(env(SOURCE_ENV), plugin(KEY_SOURCE), fileVisibility, manifest(UpdateManifest.KEY_VISIBILITY))) { UpdateVisibility.parse(it) != null }
                ?.let(UpdateVisibility::parse) ?: UpdateVisibility.PUBLIC
            val interval = pick("$KEY_INTERVAL (not a whole number of hours)", listOf(plugin(KEY_INTERVAL), manifest(UpdateManifest.KEY_INTERVAL))) { (it.toIntOrNull() ?: -1) >= 0 }
                ?.toInt() ?: DEFAULT_INTERVAL_HOURS
            val autoCheck = plugin(KEY_AUTO)?.lowercase() != "false"
            // NIGHTLY-1: the channels are manifest data; the user's pick wins, then the manifest's default, then Stable.
            val channels = ReleaseChannel.fromManifest(manifest, warnings)
            val channel = pick("$KEY_RELEASE_CHANNEL (not one of ${channels.joinToString("/") { it.id }})", listOf(plugin(KEY_RELEASE_CHANNEL), manifest(UpdateManifest.KEY_DEFAULT_CHANNEL))) { v ->
                channels.any { it.id == v.lowercase() }
            }?.let { v -> channels.first { it.id == v.lowercase() } } ?: channels.first { it.id == ReleaseChannel.STABLE_ID }
            // UPDATE-4: `source=local|github` wins; else `channel=unstable` (local dir) / `channel=production` (GitHub);
            // else the older rule: a dir means local, a repo means GitHub. (The release CHANNEL, Stable or Nightly, is a separate setting.) A channel that only the
            // manifest names is the build's default, so a developer's dir= still switches to the local folder.
            val overrideChannel = pick("update channel (not unstable or production)", listOf(file?.getProperty("channel"), plugin(KEY_CHANNEL))) { it.lowercase() in CHANNELS }?.lowercase()
            val useLocal = when {
                explicit != null -> explicit == "local"
                overrideChannel != null -> overrideChannel == "unstable"
                dir != null -> true
                else -> manifest(UpdateManifest.KEY_CHANNEL)?.lowercase() == "unstable"
            }
            if (useLocal) {
                dir ?: return Disabled("the local update source needs dir=<releases folder> (or $DIR)")
                return Enabled(UpdateConfig("local/releases", current, localDir = File(dir), visibility = visibility, checkIntervalHours = interval, autoCheck = autoCheck, channel = channel), warnings)
            }
            repo ?: return Disabled("this build names no update repo (its manifest has no update.repo); a developer can set $REPO=owner/repo")
            val api = setting(API)?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: UpdateConfig.DEFAULT_API
            // Public: no token ever leaves this machine, even when one is set somewhere (launch day: the switch is enough).
            if (visibility == UpdateVisibility.PUBLIC) {
                return Enabled(UpdateConfig(repo, current, api, visibility = visibility, checkIntervalHours = interval, autoCheck = autoCheck, channel = channel), warnings)
            }
            // The OS credential store holds the owner's own GitHub login: only ever offer it to GitHub itself, never to an
            // API override (a fake server, a mirror). An explicit INFANTRY_UPDATE_TOKEN goes wherever the owner points it.
            val stored: () -> String? = if (api == UpdateConfig.DEFAULT_API) credentialStore else { { null } }
            return Enabled(UpdateConfig(repo, current, api, visibility = visibility, checkIntervalHours = interval, autoCheck = autoCheck, channel = channel) {
                env(TOKEN_ENV)?.takeIf { it.isNotBlank() } ?: runCatching(keystore).getOrNull()?.takeIf { it.isNotBlank() } ?: stored()
            }, warnings)
        }
    }
}

/**
 * UPDATE-1: the OS credential store, through Git's credential helper (Git Credential Manager keeps the GitHub token in
 * the Windows Credential Manager). Non-interactive: never prompts, gives up after a few seconds, and returns null on
 * any failure (no git, no stored credential).
 */
object GitCredentialStore {
    fun githubToken(timeoutSeconds: Long = 5): String? = runCatching {
        val process = ProcessBuilder("git", "credential", "fill").apply {
            environment()["GIT_TERMINAL_PROMPT"] = "0"
            environment()["GCM_INTERACTIVE"] = "never"
            redirectError(ProcessBuilder.Redirect.DISCARD)
        }.start()
        process.outputStream.bufferedWriter().use { it.write("protocol=https\nhost=github.com\n\n") }
        // The answer is a few short lines, far below a pipe buffer, so waiting before reading cannot deadlock.
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return@runCatching null
        }
        if (process.exitValue() != 0) return@runCatching null
        val out = process.inputStream.bufferedReader().readText()
        out.lineSequence().firstOrNull { it.startsWith("password=") }?.removePrefix("password=")?.takeIf { it.isNotBlank() }
    }.getOrNull()
}
