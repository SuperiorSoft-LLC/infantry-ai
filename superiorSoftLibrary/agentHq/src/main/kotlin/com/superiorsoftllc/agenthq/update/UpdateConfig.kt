package com.superiorsoftllc.agenthq.update

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
    /** The running app's version (`-PagenthqVersion`, baked into the launcher as `agenthq.version`). */
    val currentVersion: SemVer,
    /** `https://api.github.com`, or a local fake Releases server in tests. No trailing slash. */
    val apiBase: String = DEFAULT_API,
    /** UPDATE-2: when set, updates come from this releases folder ([LocalDirSource]) instead of GitHub. */
    val localDir: File? = null,
    private val tokenSource: () -> String? = { null },
) {
    /** The GitHub token for a private repo, or null. Never log it. */
    val token: String? by lazy { tokenSource()?.trim()?.takeIf { it.isNotEmpty() } }

    override fun toString(): String = "UpdateConfig(repo=$repo, current=$currentVersion, api=$apiBase, token=${if (token != null) "set" else "none"})"

    companion object {
        const val DEFAULT_API = "https://api.github.com"
    }
}

/** UPDATE-1: whether this launch may update, and from where. */
sealed interface UpdateSettings {
    /** Updates are off for this launch; [reason] is shown when the owner checks by hand. */
    data class Disabled(val reason: String) : UpdateSettings

    data class Enabled(val config: UpdateConfig) : UpdateSettings

    companion object {
        /** `owner/repo`. A setting (system property or env), else `repo=` in `<stateDir>/update.properties`. */
        const val REPO = "AGENTHQ_UPDATE_REPO"

        /** The GitHub API base; only a test or a smoke run points it at a local fake server. */
        const val API = "AGENTHQ_UPDATE_API"

        /** The token for a private repo: env var ONLY (a system property would show on a command line). */
        const val TOKEN_ENV = "AGENTHQ_UPDATE_TOKEN"

        /** The running version, set on the jpackage launcher by agentHqUi/build.gradle.kts. */
        const val VERSION_PROPERTY = "agenthq.version"

        /** UPDATE-2: a local releases folder (setting/env, else `dir=` in update.properties); wins over the GitHub repo. */
        const val DIR = "AGENTHQ_UPDATE_DIR"

        /** UPDATE-4: the channels `channel=` accepts: unstable = local releases folder, production = GitHub releases. */
        val CHANNELS = setOf("unstable", "production")

        const val FILE_NAME = "update.properties"

        private val REPO_PATTERN = Regex("""^[A-Za-z0-9-]{1,39}/[A-Za-z0-9._-]{1,100}$""")

        /**
         * Resolve the settings of this launch.
         *
         * - [installed] false (a dev build) is ALWAYS disabled: a dev build never updates itself.
         * - The repo comes from [setting] (`AGENTHQ_UPDATE_REPO`), else `repo=` in [stateDir]`/update.properties` (the
         *   installed profile's own settings file, outside any tracked tree). A `token` key in that file is ignored:
         *   the token lives in the keystore, the environment or the OS credential store, never in a file.
         * - The token: `AGENTHQ_UPDATE_TOKEN` from [env] (an override), else the daemon's [keystore] (`update-token`), else [credentialStore] (the OS credential store), read lazily
         *   on the first request, and only when the API is GitHub's own ([UpdateConfig.DEFAULT_API]).
         */
        fun resolve(
            installed: Boolean,
            version: String?,
            stateDir: File?,
            setting: (String) -> String?,
            env: (String) -> String? = System::getenv,
            keystore: () -> String? = { null },
            credentialStore: () -> String? = { GitCredentialStore.githubToken() },
        ): UpdateSettings {
            if (!installed) return Disabled("this is a dev build; dev builds never update themselves")
            val current = SemVer.parse(version) ?: return Disabled("the running version is unknown (${version ?: "unset"})")
            val file = stateDir?.let { File(it, FILE_NAME) }?.takeIf { it.isFile }?.let { f ->
                runCatching { Properties().apply { f.reader().use { load(it) } } }.getOrNull()
            }
            val dir = (setting(DIR) ?: env(DIR))?.trim()?.takeIf { it.isNotEmpty() } ?: file?.getProperty("dir")?.trim()?.takeIf { it.isNotEmpty() }
            val repoSetting = setting(REPO)?.trim()?.takeIf { it.isNotEmpty() } ?: file?.getProperty("repo")?.trim()?.takeIf { it.isNotEmpty() }
            // UPDATE-4: `source=local|github` wins; else `channel=unstable` (local dir) / `channel=production` (GitHub);
            // else the older rule: a dir means local, a repo means GitHub. Nightly is not built yet.
            val explicit = file?.getProperty("source")?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            val channel = file?.getProperty("channel")?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            if (explicit != null && explicit != "local" && explicit != "github") return Disabled("update source '$explicit' is not local or github")
            if (channel != null && channel !in CHANNELS) return Disabled("update channel '$channel' is not unstable or production (nightly is not available yet)")
            val useLocal = when {
                explicit != null -> explicit == "local"
                channel != null -> channel == "unstable"
                else -> dir != null
            }
            if (useLocal) {
                dir ?: return Disabled("the local update source needs dir=<releases folder> (or $DIR)")
                return Enabled(UpdateConfig("local/releases", current, localDir = File(dir)))
            }
            val repo = repoSetting
                ?: return Disabled("no update repo configured: set $REPO=owner/repo, or repo=owner/repo in ${stateDir?.let { File(it, FILE_NAME) } ?: FILE_NAME}")
            if (!REPO_PATTERN.matches(repo)) return Disabled("the update repo '$repo' is not owner/repo")
            val api = setting(API)?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: UpdateConfig.DEFAULT_API
            // The OS credential store holds the owner's own GitHub login: only ever offer it to GitHub itself, never to an
            // API override (a fake server, a mirror). An explicit AGENTHQ_UPDATE_TOKEN goes wherever the owner points it.
            val stored: () -> String? = if (api == UpdateConfig.DEFAULT_API) credentialStore else { { null } }
            return Enabled(UpdateConfig(repo, current, api) { env(TOKEN_ENV)?.takeIf { it.isNotBlank() } ?: runCatching(keystore).getOrNull()?.takeIf { it.isNotBlank() } ?: stored() })
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
