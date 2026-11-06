// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.updater

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.file.Files

/**
 * UPDATE-1: what the window shows about updates. UPDATE-UX-1: nothing here blocks the window; the download runs in the
 * background ([Downloading] -> [Ready] or [DownloadFailed]) and the window shows it in a bottom-right card.
 */
sealed interface UpdateState {
    /** Nothing to show. */
    data object Idle : UpdateState
    data class Checking(val manual: Boolean) : UpdateState
    /** Only shown after a manual check: a startup check that finds nothing stays silent. */
    data class UpToDate(val current: SemVer) : UpdateState
    data class Available(val update: AvailableUpdate) : UpdateState
    /** UPDATE-UX-1: the download runs in the background; [done] of [total] bytes so far (total 0 = not known yet). */
    data class Downloading(val update: AvailableUpdate, val done: Long = 0, val total: Long = 0) : UpdateState {
        /** 0..100, or null while the size is not known. */
        val percent: Int? get() = if (total > 0) ((done.coerceIn(0, total) * 100) / total).toInt() else null
    }
    /** UPDATE-UX-1: downloaded and verified; installing waits for the user's "Restart now". */
    data class Ready(val update: AvailableUpdate, val installer: VerifiedInstaller) : UpdateState
    /** UPDATE-UX-1: the download or the installer start failed; [text] says why, Retry downloads again. */
    data class DownloadFailed(val update: AvailableUpdate, val text: String) : UpdateState
    /** The installer was verified and handed to Windows; the app is about to exit. */
    data class Restarting(val update: AvailableUpdate) : UpdateState
    /** A short message (offline, 404, rate limit, bad asset, updates off). Dismissable; never blocks the app. */
    data class Message(val text: String) : UpdateState
}

/** UPDATE-3: the confirmation before an install: with agents running it asks "N agents running, update anyway?" and defaults to Cancel. */
data class InstallPrompt(val running: Int) {
    val defaultsToCancel: Boolean get() = running > 0
    val message: String? get() = if (running > 0) "$running agent${if (running == 1) "" else "s"} running, update anyway?" else null
}

/**
 * UPDATE-3: the footer button's text for each state; [version] is the running version. Null = show nothing: UPDATE-UX-1
 * a running or failed download is shown by the bottom-right card ([indicatorLabel]), not the footer; a ready one whose
 * card was put away with "Later" keeps a footer button that brings the card back.
 */
fun UpdateState.footerLabel(version: String?, indicatorVisible: Boolean = true): String? = when (this) {
    is UpdateState.Idle -> "Up to date${version?.let { " ($it)" } ?: ""}"
    is UpdateState.Checking -> "Checking for updates…"
    is UpdateState.UpToDate -> "Up to date ($current)"
    is UpdateState.Available -> "Update available v${update.latest}"
    is UpdateState.Ready -> if (indicatorVisible) null else "Ready to install v${update.latest}"
    is UpdateState.Downloading, is UpdateState.DownloadFailed, is UpdateState.Restarting -> null
    is UpdateState.Message -> "Update error: click to retry"
}

/** UPDATE-UX-1: the bottom-right card's text for the background download states; null = no card. */
fun UpdateState.indicatorLabel(): String? = when (this) {
    is UpdateState.Downloading -> "Downloading ${update.latest}" + (percent?.let { " - $it%" } ?: "…")
    is UpdateState.Ready -> "Ready to install ${update.latest}"
    is UpdateState.DownloadFailed -> "Update ${update.latest} failed. $text"
    is UpdateState.Restarting -> "Installing ${update.latest}; Infantry AI restarts on its own."
    else -> null
}

/**
 * UPDATE-1: the update flow the window drives (HEADLESS-1: all logic here, the window only renders [state] and calls
 * [check] / [update] / [dismiss]). Work runs on [scope] (IO), never on the UI thread.
 *
 * [handoff] starts the installer for a verified download (production: [MsiHandoff.start]); [exit] then ends this
 * process so the installer can replace the files.
 */
class UpdateService(
    settings: UpdateSettings,
    private val scope: CoroutineScope,
    private val updaterFor: (UpdateConfig) -> UpdateSource = UpdateSources::forConfig,
    /** Starts the installer; throws when it cannot. */
    private val handoff: (VerifiedInstaller) -> Unit,
    private val exit: () -> Unit,
    /** UPDATE-3: how many agents are working right now (sessions that are [isBusy], background work included). */
    private val runningAgents: () -> Int = { 0 },
    private val log: (String) -> Unit = { System.err.println("[infantry-update] $it") },
    /** UPDATER-DEFAULT-1: the wall clock [checkIfDue] measures `updater.checkIntervalHours` with. */
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * UPDATER-DEFAULT-1: re-reads the settings before each check, so a fix (update.properties, the auto-check switch, the
     * daemon's mirror) applies without a restart. Null = the [settings] given stay.
     */
    private val reload: (() -> UpdateSettings)? = null,
) {
    @Volatile private var settings: UpdateSettings = settings

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private var job: Job? = null

    /** When the last check started (start-up, by hand or [checkIfDue]); null = never. */
    @Volatile private var lastCheckAt: Long? = null

    private val _toast = MutableStateFlow(false)
    private val _dialog = MutableStateFlow(false)
    private val _indicator = MutableStateFlow(true)

    /**
     * UPDATE-UX-1: the bottom-right download card is showing. "Later" on a ready update hides it (the update stays ready
     * and the footer button brings it back); every new download state shows it again.
     */
    val indicatorVisible: StateFlow<Boolean> = _indicator.asStateFlow()

    /** UPDATE-UX-1: bring the download card back (footer button, after "Later"). */
    fun showIndicator() { _indicator.value = true }

    /** UPDATE-3: the bottom-right "update found" toast is showing. Set when a check finds an update. */
    val toastVisible: StateFlow<Boolean> = _toast.asStateFlow()

    /** UPDATE-3: the install prompt (notes, Update / Later) is open. Opened from the toast or the footer button. */
    val dialogOpen: StateFlow<Boolean> = _dialog.asStateFlow()

    /** UPDATE-3: the question to ask before installing: names the running agents and defaults to Cancel when any. */
    fun installPrompt(): InstallPrompt = InstallPrompt(runCatching(runningAgents).getOrDefault(0))

    fun openDialog() { if (_state.value is UpdateState.Available) { _toast.value = false; _dialog.value = true } }
    fun closeDialog() { _dialog.value = false }
    fun dismissToast() { _toast.value = false }

    /** Whether this launch can update at all (an installed build with a repo configured). */
    val enabled: Boolean get() = settings is UpdateSettings.Enabled

    /** Ask GitHub. [manual] = the owner clicked "Check for updates": then "up to date" and "updates are off" are shown too. */
    fun check(manual: Boolean): Job? {
        if (job?.isActive == true) return job
        reload?.let { r -> runCatching(r).onSuccess { settings = it }.onFailure { log("could not re-read the update settings: ${it::class.simpleName}") } }
        val config = when (val s = settings) {
            is UpdateSettings.Disabled -> {
                if (manual) _state.value = UpdateState.Message("Updates are off: ${s.reason}.") else log("updates off: ${s.reason}")
                return null
            }
            is UpdateSettings.Enabled -> {
                s.warnings.forEach { log("settings: $it") }
                s.config
            }
        }
        // UPDATER-DEFAULT-1: `updater.autoCheck` off = only the user's own "Check for updates".
        if (!manual && !config.autoCheck) return null
        lastCheckAt = clock()
        _state.value = UpdateState.Checking(manual)
        return scope.launch(Dispatchers.IO) {
            val result = runCatching { updaterFor(config).check() }.getOrElse { UpdateCheck.Failed(UpdateFailure.BadResponse(it::class.simpleName ?: "error")) }
            _state.value = when (result) {
                is UpdateCheck.Available -> UpdateState.Available(result.update).also {
                    log("update available: ${it.update.current} -> ${it.update.latest}")
                    _toast.value = true
                }
                is UpdateCheck.UpToDate -> {
                    log("up to date: running ${result.current}, latest ${result.latest}")
                    if (manual) UpdateState.UpToDate(result.current) else UpdateState.Idle
                }
                is UpdateCheck.Failed -> {
                    log("check failed: ${result.failure}")
                    UpdateState.Message(result.failure.message)
                }
            }
        }.also { job = it }
    }

    /**
     * UPDATER-DEFAULT-1: the window gained focus. Checks again (silently, like the start-up check) when the last check is at
     * least `updater.checkIntervalHours` old; 0 hours = only at start and by hand. Never while an update is offered,
     * downloading or installing, and never more than one check at a time.
     */
    fun checkIfDue(): Job? {
        val config = (settings as? UpdateSettings.Enabled)?.config ?: return null
        val hours = config.checkIntervalHours
        if (hours <= 0 || !config.autoCheck) return null
        when (_state.value) {
            is UpdateState.Available, is UpdateState.Downloading, is UpdateState.Ready, is UpdateState.DownloadFailed, is UpdateState.Restarting -> return null
            else -> Unit
        }
        val last = lastCheckAt
        if (last != null && clock() - last < hours * HOUR_MS) return null
        return check(manual = false)
    }

    /**
     * "Update" (UPDATE-UX-1: also Retry after a failed download): close the prompt and download + verify in the
     * BACKGROUND. The window stays usable; the card shows [UpdateState.Downloading] with its percentage, then
     * [UpdateState.Ready] (Restart now / Later) or [UpdateState.DownloadFailed] (Retry). Nothing is installed here.
     */
    fun update(): Job? {
        val update = when (val s = _state.value) {
            is UpdateState.Available -> s.update
            is UpdateState.DownloadFailed -> s.update
            else -> return null
        }
        val config = (settings as? UpdateSettings.Enabled)?.config ?: return null
        if (job?.isActive == true) return job
        _dialog.value = false
        _toast.value = false
        _indicator.value = true
        val asset = update.installer
        _state.value = UpdateState.Downloading(update, 0, asset.size)
        log("download start: ${asset.name} (${asset.size} bytes) for ${update.current} -> ${update.latest}")
        return scope.launch(Dispatchers.IO) {
            val progress = object : DownloadProgress {
                private var lastPercent = -1
                override fun note(text: String) = log("download: $text")
                override fun bytes(done: Long, total: Long) {
                    val now = UpdateState.Downloading(update, done, total)
                    val pct = now.percent ?: return
                    if (pct == lastPercent) return
                    lastPercent = pct
                    _state.value = now
                    if (pct % LOG_EVERY_PERCENT == 0) log("download: $done/$total bytes ($pct%)")
                }
            }
            val downloaded = runCatching { updaterFor(config).download(update, Files.createTempDirectory("infantry-update-").toFile(), progress) }
                .getOrElse {
                    log("download error: ${it::class.simpleName}: ${it.message}")
                    Fetched.Failed(UpdateFailure.BadAsset(it::class.simpleName ?: "error"))
                }
            when (downloaded) {
                is Fetched.Failed -> {
                    log("download failed: ${downloaded.failure}")
                    _indicator.value = true
                    _state.value = UpdateState.DownloadFailed(update, downloaded.failure.message)
                }
                is Fetched.Ok -> {
                    val installer = downloaded.value
                    log("verify ok: ${installer.file} sha256=${installer.sha256} checksumAsset=${installer.checksumVerified}")
                    _indicator.value = true
                    _state.value = UpdateState.Ready(update, installer)
                }
            }
        }.also { job = it }
    }

    /** UPDATE-UX-1: "Retry" on a failed download. */
    fun retry(): Job? = update()

    /**
     * UPDATE-UX-1: "Restart now" on a ready update: hand the verified installer to Windows and exit so it can replace
     * the app. A failure to start it becomes [UpdateState.DownloadFailed] (Retry downloads again). The window asks
     * [installPrompt] first.
     */
    fun install(): Job? {
        val ready = _state.value as? UpdateState.Ready ?: return null
        if (job?.isActive == true) return job
        // Restarting at once: the card says so, and a second click finds no Ready to act on.
        _state.value = UpdateState.Restarting(ready.update)
        // Off the UI thread: the exit stops the daemon and ends the JVM.
        return scope.launch(Dispatchers.IO) {
            log("install: launching msiexec for ${ready.installer.file} (via ${MsiHandoff.SCRIPT_NAME})")
            runCatching { handoff(ready.installer) }.fold(
                onSuccess = {
                    log("installer started; exiting so it can replace the app")
                    exit()
                },
                onFailure = {
                    log("handoff failed: ${it::class.simpleName}: ${it.message}")
                    _indicator.value = true
                    _state.value = UpdateState.DownloadFailed(ready.update, UpdateFailure.InstallFailed(it.message ?: "unknown error").message)
                },
            )
        }.also { job = it }
    }

    /**
     * "Later" / dismiss. On the prompt or the toast it keeps the update offered; on a ready download it hides the card
     * (the download stays ready, the footer brings it back); on a failed download it goes back to "update available";
     * a message or "up to date" clears.
     */
    fun dismiss() {
        _dialog.value = false
        _toast.value = false
        when (val s = _state.value) {
            is UpdateState.Available, is UpdateState.Downloading, is UpdateState.Restarting -> Unit
            is UpdateState.Ready -> _indicator.value = false
            is UpdateState.DownloadFailed -> _state.value = UpdateState.Available(s.update)
            else -> _state.value = UpdateState.Idle
        }
    }
}

private const val HOUR_MS = 3_600_000L

/** UPDATE-UX-1: the log gets one progress line per this many percent (the card updates on every percent). */
private const val LOG_EVERY_PERCENT = 10
