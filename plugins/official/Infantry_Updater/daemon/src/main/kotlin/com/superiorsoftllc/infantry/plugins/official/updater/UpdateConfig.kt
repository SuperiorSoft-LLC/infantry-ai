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
    private val tokenSource: () -> String? = { null },
) {
    /** The GitHub token for a private repo, or null. Never log it. */
    val token: String? by lazy { if (visibility == UpdateVisibility.PUBLIC) null else tokenSource()?.trim()?.takeIf { it.isNotEmpty() } }

    override fun toString(): String = "UpdateConfig(repo=$repo, current=$currentVersion, api=$apiBase, source=${visibility.wire}, token=${if (token != null) "set" else "none"})"

    /** The same source asked on behalf of another install running [version] (UPDATER-1: a phone asking its daemon). */
    fun forVersion(version: SemVer): UpdateConfig = UpdateConfig(repo, version, apiBase, localDir, visibility, checkIntervalHours, tokenSource)

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

    data class Enabled(val config: UpdateConfig) : UpdateSettings

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
        val SETTING_KEYS = listOf(KEY_SOURCE, KEY_REPO, KEY_CHANNEL, KEY_DIR, KEY_INTERVAL)

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
         * - Each value, first match wins: [setting] / [env] (the `INFANTRY_UPDATE_*` overrides), then `<stateDir>/update.properties`
         *   (hand-written: the old keys `channel`, `source`, `dir`, `repo`, and the new `updater.*` keys), then the plugin's
         *   settings ([config], else the mirror file the daemon half writes), then the defaults. A `token` key in any file is
         *   ignored: the token lives in the keystore, the environment or the OS credential store, never in a file.
         * - `updater.source` = public (the default) never sends a token, whatever is set. private: `INFANTRY_UPDATE_TOKEN`
         *   from [env], else the daemon's [keystore] (`update-token`), else [credentialStore] (the OS credential store, only
         *   for GitHub's own API), read lazily on the first request.
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
        ): UpdateSettings {
            if (!installed) return Disabled("this is a dev build; dev builds never update themselves")
            val current = SemVer.parse(version) ?: return Disabled("the running version is unknown (${version ?: "unset"})")
            val file = load(stateDir?.let { File(it, FILE_NAME) })
            val mirror = load(stateDir?.let(::settingsMirror))
            fun clean(v: String?) = v?.trim()?.takeIf { it.isNotEmpty() }
            fun plugin(key: String) = clean(file?.getProperty(key)) ?: clean(config(key)) ?: clean(mirror?.getProperty(key))
            val dir = clean(setting(DIR)) ?: clean(env(DIR)) ?: clean(file?.getProperty("dir")) ?: plugin(KEY_DIR)
            val repoSetting = clean(setting(REPO)) ?: clean(file?.getProperty("repo")) ?: plugin(KEY_REPO)
            val sourceText = clean(env(SOURCE_ENV)) ?: plugin(KEY_SOURCE)
            val visibility = if (sourceText == null) UpdateVisibility.PUBLIC
            else UpdateVisibility.parse(sourceText) ?: return Disabled("$KEY_SOURCE '$sourceText' is not public or private")
            val intervalText = plugin(KEY_INTERVAL)
            val interval = if (intervalText == null) DEFAULT_INTERVAL_HOURS
            else intervalText.toIntOrNull()?.takeIf { it >= 0 } ?: return Disabled("$KEY_INTERVAL '$intervalText' is not a whole number of hours")
            // UPDATE-4: `source=local|github` wins; else `channel=unstable` (local dir) / `channel=production` (GitHub);
            // else the older rule: a dir means local, a repo means GitHub. Nightly is not built yet.
            val explicit = clean(file?.getProperty("source"))?.lowercase()
            val channel = (clean(file?.getProperty("channel")) ?: plugin(KEY_CHANNEL))?.lowercase()
            if (explicit != null && explicit != "local" && explicit != "github") return Disabled("update source '$explicit' is not local or github")
            if (channel != null && channel !in CHANNELS) return Disabled("update channel '$channel' is not unstable or production (nightly is not available yet)")
            val useLocal = when {
                explicit != null -> explicit == "local"
                channel != null -> channel == "unstable"
                else -> dir != null
            }
            if (useLocal) {
                dir ?: return Disabled("the local update source needs dir=<releases folder> (or $DIR)")
                return Enabled(UpdateConfig("local/releases", current, localDir = File(dir), visibility = visibility, checkIntervalHours = interval))
            }
            val repo = repoSetting
                ?: return Disabled("no update repo configured: set $KEY_REPO=owner/repo in the Updater settings, $REPO=owner/repo, or repo=owner/repo in ${stateDir?.let { File(it, FILE_NAME) } ?: FILE_NAME}")
            if (!REPO_PATTERN.matches(repo)) return Disabled("the update repo '$repo' is not owner/repo")
            val api = setting(API)?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: UpdateConfig.DEFAULT_API
            // Public: no token ever leaves this machine, even when one is set somewhere (launch day: the switch is enough).
            if (visibility == UpdateVisibility.PUBLIC) {
                return Enabled(UpdateConfig(repo, current, api, visibility = visibility, checkIntervalHours = interval))
            }
            // The OS credential store holds the owner's own GitHub login: only ever offer it to GitHub itself, never to an
            // API override (a fake server, a mirror). An explicit INFANTRY_UPDATE_TOKEN goes wherever the owner points it.
            val stored: () -> String? = if (api == UpdateConfig.DEFAULT_API) credentialStore else { { null } }
            return Enabled(UpdateConfig(repo, current, api, visibility = visibility, checkIntervalHours = interval) {
                env(TOKEN_ENV)?.takeIf { it.isNotBlank() } ?: runCatching(keystore).getOrNull()?.takeIf { it.isNotBlank() } ?: stored()
            })
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
