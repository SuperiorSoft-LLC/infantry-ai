// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.updater

import com.superiorsoftllc.infantry.protocol.EventVocabulary
import kotlinx.serialization.Serializable
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

/** SERVER-UPDATE-3: how this daemon was installed, which decides whether it may replace itself. */
enum class Distribution(val wire: String) {
    /** The `InfantryAI-server-*` archive, unpacked under a [ServerInstall] root: it can stage a new version. */
    ARCHIVE("archive"),

    /** A container image: it cannot replace its own image, so it only reports and says how to pull. */
    DOCKER("docker"),

    /** A development run (gradle, an IDE): never updates. */
    DEV("dev"),
}

/** SERVER-UPDATE-3: the newer release a check found. */
@Serializable
data class ServerUpdateOffer(val version: String, val notes: String)

/**
 * SERVER-UPDATE-3 (contract 1.110.0): `GET /update/status`, also the body of `POST /update/check`. Never carries the token.
 *
 * [canSelfUpdate] is true only for an archive install with updates configured. [instructions] is what to do by hand when
 * it is false (Docker: the pull command). [staged] is a version that was downloaded and made current but is not running
 * yet: the daemon restarts into it (the launcher's job).
 */
@Serializable
data class ServerUpdateStatus(
    val version: String,
    val contract: String = EventVocabulary.VERSION,
    val distribution: String,
    val canSelfUpdate: Boolean,
    val enabled: Boolean,
    val disabledReason: String? = null,
    val state: String,
    val available: ServerUpdateOffer? = null,
    val lastChecked: Long? = null,
    val error: String? = null,
    val instructions: String? = null,
    val runningAgents: Int = 0,
    val staged: String? = null,
)

/** SERVER-UPDATE-3: the answer to `POST /update/apply`. */
sealed interface ApplyResult {
    data class Staged(val version: String) : ApplyResult
    data object NothingToApply : ApplyResult
    data class AgentsRunning(val running: Int) : ApplyResult
    data class Unsupported(val instructions: String) : ApplyResult
    data class Failed(val message: String) : ApplyResult
}

/**
 * SERVER-UPDATE-3: the headless daemon's view of its own updates (HEADLESS-1: no UI, no HTTP in here; [updateRoutes]
 * is the HTTP face). [check] asks the configured source (GitHub or a local releases folder); [apply] downloads, verifies
 * and stages the archive through [ServerInstall], only for an [Distribution.ARCHIVE] install and, when agents are
 * working, only when the caller confirms. Nothing here restarts the process or touches the data dir.
 */
class ServerUpdateService(
    private val settings: UpdateSettings,
    val distribution: Distribution,
    val version: String,
    private val install: ServerInstall?,
    private val runningAgents: () -> Int = { 0 },
    private val dockerImage: String = DEFAULT_IMAGE,
    private val sourceFor: (UpdateConfig) -> UpdateSource = { UpdateSources.forConfig(it, kindFor(distribution)) },
    private val clock: () -> Long = System::currentTimeMillis,
    /** SERVER-UPDATE-2: runs once a version is staged. Under the launcher supervisor it restarts the daemon into it; null = the next start picks it up. */
    private val onStaged: ((String) -> Unit)? = null,
) {
    /** True when staging a version restarts this daemon by itself (it runs under `bin/infantry`'s supervisor). */
    val restartsAfterStaging: Boolean get() = onStaged != null

    private val lock = Any()
    private val working = AtomicBoolean(false)
    private var available: AvailableUpdate? = null
    private var lastChecked: Long? = null
    private var error: String? = null
    private var staged: String? = null
    private var state = "idle"

    private val config: UpdateConfig? get() = (settings as? UpdateSettings.Enabled)?.config
    private val canSelfUpdate: Boolean get() = distribution == Distribution.ARCHIVE && install != null && config != null

    fun status(): ServerUpdateStatus = synchronized(lock) {
        val offer = available
        ServerUpdateStatus(
            version = version,
            distribution = distribution.wire,
            canSelfUpdate = canSelfUpdate,
            enabled = config != null,
            disabledReason = (settings as? UpdateSettings.Disabled)?.reason,
            state = state,
            available = offer?.let { ServerUpdateOffer(it.latest.toString(), it.notes) },
            lastChecked = lastChecked,
            error = error,
            instructions = instructionsFor(offer?.latest),
            runningAgents = runCatching(runningAgents).getOrDefault(0),
            staged = staged,
        )
    }

    /** Ask the source for a newer release and remember the answer. A failure becomes [ServerUpdateStatus.error], never a throw. */
    fun check(): ServerUpdateStatus {
        val cfg = config
        if (cfg == null) {
            synchronized(lock) { error = (settings as? UpdateSettings.Disabled)?.reason; lastChecked = clock() }
            return status()
        }
        if (!working.compareAndSet(false, true)) return status()
        try {
            synchronized(lock) { state = "checking" }
            val result = runCatching { sourceFor(cfg).check() }
                .getOrElse { UpdateCheck.Failed(UpdateFailure.BadResponse(it::class.simpleName ?: "error")) }
            synchronized(lock) {
                lastChecked = clock()
                when (result) {
                    is UpdateCheck.Available -> { available = result.update; error = null; state = if (staged == result.update.latest.toString()) "staged" else "available" }
                    is UpdateCheck.UpToDate -> { available = null; error = null; state = "idle" }
                    is UpdateCheck.Failed -> { error = result.failure.message; state = "error" }
                }
            }
        } finally {
            working.set(false)
        }
        return status()
    }

    /** Download, verify and stage the offered version. [confirm] accepts that [runningAgents] agents are working. */
    fun apply(confirm: Boolean): ApplyResult {
        val cfg = config
        val inst = install
        val offer = synchronized(lock) { available }
        if (distribution == Distribution.DOCKER) return ApplyResult.Unsupported(instructionsFor(offer?.latest) ?: dockerHelp(null))
        if (inst == null || cfg == null || distribution != Distribution.ARCHIVE) {
            return ApplyResult.Unsupported(instructionsFor(null) ?: "This daemon cannot update itself.")
        }
        if (offer == null) return ApplyResult.NothingToApply
        val running = runCatching(runningAgents).getOrDefault(0)
        if (running > 0 && !confirm) return ApplyResult.AgentsRunning(running)
        if (!working.compareAndSet(false, true)) return ApplyResult.Failed("an update is already in progress")
        val temp = Files.createTempDirectory("infantry-update-").toFile()
        try {
            synchronized(lock) { state = "downloading" }
            val fetched = runCatching { sourceFor(cfg).download(offer, temp) }
                .getOrElse { Fetched.Failed(UpdateFailure.BadAsset(it::class.simpleName ?: "error")) }
            val verified = when (fetched) {
                is Fetched.Failed -> return fail(fetched.failure.message)
                is Fetched.Ok -> fetched.value
            }
            runCatching { inst.stage(verified) }.onFailure { return fail("could not install: ${it.message}") }
            synchronized(lock) { staged = offer.latest.toString(); state = "staged"; error = null }
            onStaged?.let { hook -> runCatching { hook(offer.latest.toString()) } }
            return ApplyResult.Staged(offer.latest.toString())
        } finally {
            temp.deleteRecursively()
            working.set(false)
        }
    }

    private fun fail(message: String): ApplyResult.Failed {
        synchronized(lock) { error = message; state = "error" }
        return ApplyResult.Failed(message)
    }

    private fun instructionsFor(latest: SemVer?): String? = when (distribution) {
        Distribution.DOCKER -> dockerHelp(latest)
        Distribution.DEV -> "This is a development run; it never updates itself."
        Distribution.ARCHIVE -> if (canSelfUpdate) null else "Updates are not configured: ${(settings as? UpdateSettings.Disabled)?.reason ?: "no install folder"}."
    }

    private fun dockerHelp(latest: SemVer?): String {
        val tag = latest?.toString() ?: "latest"
        return "A container cannot replace its own image. Pull the new image and recreate the container " +
            "(keep the /data volume): `docker pull $dockerImage:$tag`, then stop and remove the container and run it again " +
            "with the same options; with Compose: `docker compose pull && docker compose up -d`. " +
            "Watchtower can do this automatically."
    }

    companion object {
        /** The exit code `bin/infantry`'s supervisor treats as "start again" (BSD EX_TEMPFAIL). */
        const val RESTART_EXIT_CODE = 75

        private fun exitForRestart(@Suppress("UNUSED_PARAMETER") version: String) {
            Thread {
                Thread.sleep(1_500)
                kotlin.system.exitProcess(RESTART_EXIT_CODE)
            }.apply { isDaemon = true; name = "update-restart" }.start()
        }

        const val DEFAULT_IMAGE = "infantryai/infantry-ai"

        /** Docker only reports; its image is linux, so a check looks for the linux x64 archive of the same release. */
        fun kindFor(distribution: Distribution): AssetKind =
            if (distribution == Distribution.DOCKER) AssetKind.ServerArchiveFor(ServerArchive.Platform("linux", "x64"))
            else AssetKind.ServerArchiveFor()

        /**
         * Read this process's install from the environment.
         *
         * - Distribution: `INFANTRY_DISTRIBUTION=docker|archive`, else `/.dockerenv` means Docker, else an
         *   `INFANTRY_APP_HOME` (set by the headless launcher) means archive, else a development run.
         * - Version: `INFANTRY_VERSION`, else the `infantry.version` property, else the `VERSION` file in the app home.
         * - Install root: `INFANTRY_INSTALL_ROOT`, else the folder holding `versions/` when the app home is
         *   `<root>/versions/<v>`, else the app home's parent.
         * - Settings: [UpdateSettings.resolve] (`INFANTRY_UPDATE_REPO`/`_DIR`, `update.properties`, `INFANTRY_UPDATE_TOKEN`
         *   or its `INFANTRY_UPDATE_TOKEN` alias; the token is never written anywhere).
         */
        fun fromEnvironment(
            runningAgents: () -> Int,
            setting: (String) -> String? = { UpdaterEnv.setting(it) },
            dockerEnvExists: () -> Boolean = { File("/.dockerenv").exists() },
            stateDir: File? = runCatching { UpdaterEnv.stateDir(setting) }.getOrNull(),
            /** What a staged version triggers under the supervisor: exit 75 shortly after the reply left (the supervisor relaunches). */
            restart: (String) -> Unit = ::exitForRestart,
            /** The environment (the token, `INFANTRY_UPDATE_TOKEN`); the daemon half passes its declared [com.superiorsoftllc.infantry.daemon.api.DaemonApi.env]. */
            env: (String) -> String? = UpdaterEnv::env,
            /** UPDATER-1: the keystore's `update-token` (the daemon half's `DaemonApi.secret`); none outside the daemon. */
            keystore: () -> String? = { null },
            /** UPDATER-1: the module's `updater.*` config values (the daemon half's `DaemonApi.config`); none outside the daemon. */
            config: (String) -> String? = { null },
        ): ServerUpdateService {
            val appHome = setting("INFANTRY_APP_HOME")?.let(::File)?.takeIf { it.isDirectory }
            val distribution = when (setting("INFANTRY_DISTRIBUTION")?.trim()?.lowercase()) {
                "docker" -> Distribution.DOCKER
                "archive" -> Distribution.ARCHIVE
                else -> when {
                    dockerEnvExists() -> Distribution.DOCKER
                    appHome != null -> Distribution.ARCHIVE
                    else -> Distribution.DEV
                }
            }
            val version = (setting("INFANTRY_VERSION") ?: System.getProperty("infantry.version"))?.trim()?.takeIf { it.isNotEmpty() }
                ?: appHome?.let { runCatching { File(it, "VERSION").readText().trim() }.getOrNull() }?.takeIf { it.isNotEmpty() }
            val settings = UpdateSettings.resolve(
                installed = distribution != Distribution.DEV,
                version = version,
                stateDir = stateDir,
                setting = setting,
                env = env,
                keystore = keystore,
                config = config,
            )
            val root = if (distribution != Distribution.ARCHIVE) null else
                setting("INFANTRY_INSTALL_ROOT")?.let(::File)
                    ?: appHome?.let { home -> if (home.parentFile?.name == "versions") home.parentFile.parentFile else home.parentFile }
            return ServerUpdateService(
                settings = settings,
                distribution = distribution,
                version = version ?: "dev",
                install = root?.let { ServerInstall(it) },
                runningAgents = runningAgents,
                dockerImage = setting("INFANTRY_DOCKER_IMAGE")?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_IMAGE,
                onStaged = if (distribution == Distribution.ARCHIVE && setting("INFANTRY_SUPERVISED") == "1") restart else null,
            )
        }
    }
}
