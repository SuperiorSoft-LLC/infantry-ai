package com.superiorsoftllc.infantry.update

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** UPDATE-1: what the window shows about updates. Every state but [Available] and [Restarting] is non-blocking. */
sealed interface UpdateState {
    /** Nothing to show. */
    data object Idle : UpdateState
    data class Checking(val manual: Boolean) : UpdateState
    /** Only shown after a manual check: a startup check that finds nothing stays silent. */
    data class UpToDate(val current: SemVer) : UpdateState
    data class Available(val update: AvailableUpdate) : UpdateState
    data class Downloading(val update: AvailableUpdate) : UpdateState
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

/** UPDATE-3: the footer button's text for each state; [version] is the running version. Null = show nothing. */
fun UpdateState.footerLabel(version: String?): String = when (this) {
    is UpdateState.Idle -> "Up to date${version?.let { " ($it)" } ?: ""}"
    is UpdateState.Checking -> "Checking for updates…"
    is UpdateState.UpToDate -> "Up to date ($current)"
    is UpdateState.Available -> "Update available v${update.latest}"
    is UpdateState.Downloading -> "Downloading v${update.latest}…"
    is UpdateState.Restarting -> "Ready to install v${update.latest}"
    is UpdateState.Message -> "Update error: click to retry"
}

/**
 * UPDATE-1: the update flow the window drives (HEADLESS-1: all logic here, the window only renders [state] and calls
 * [check] / [update] / [dismiss]). Work runs on [scope] (IO), never on the UI thread.
 *
 * [handoff] starts the installer for a verified download (production: [MsiHandoff.start]); [exit] then ends this
 * process so the installer can replace the files.
 */
class UpdateService(
    private val settings: UpdateSettings,
    private val scope: CoroutineScope,
    private val updaterFor: (UpdateConfig) -> UpdateSource = UpdateSources::forConfig,
    /** Starts the installer; throws when it cannot. */
    private val handoff: (VerifiedInstaller) -> Unit,
    private val exit: () -> Unit,
    /** UPDATE-3: how many agents are working right now (sessions that are [isBusy], background work included). */
    private val runningAgents: () -> Int = { 0 },
    private val log: (String) -> Unit = { System.err.println("[infantry-update] $it") },
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private var job: Job? = null

    private val _toast = MutableStateFlow(false)
    private val _dialog = MutableStateFlow(false)

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
        val config = when (settings) {
            is UpdateSettings.Disabled -> {
                if (manual) _state.value = UpdateState.Message("Updates are off: ${settings.reason}.") else log("updates off: ${settings.reason}")
                return null
            }
            is UpdateSettings.Enabled -> settings.config
        }
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

    /** "Update": download, verify, hand to the installer, exit. Any failure becomes a [UpdateState.Message]. */
    fun update(): Job? {
        val available = _state.value as? UpdateState.Available ?: return null
        val config = (settings as? UpdateSettings.Enabled)?.config ?: return null
        if (job?.isActive == true) return job
        _state.value = UpdateState.Downloading(available.update)
        return scope.launch(Dispatchers.IO) {
            val downloaded = runCatching { updaterFor(config).download(available.update) }
                .getOrElse { Fetched.Failed(UpdateFailure.BadAsset(it::class.simpleName ?: "error")) }
            when (downloaded) {
                is Fetched.Failed -> {
                    log("download refused: ${downloaded.failure}")
                    _state.value = UpdateState.Message(downloaded.failure.message)
                }
                is Fetched.Ok -> {
                    val installer = downloaded.value
                    log("verified ${installer.file} sha256=${installer.sha256} checksumAsset=${installer.checksumVerified}")
                    runCatching { handoff(installer) }.fold(
                        onSuccess = {
                            _state.value = UpdateState.Restarting(available.update)
                            log("installer started; exiting so it can replace the app")
                            exit()
                        },
                        onFailure = {
                            log("handoff failed: ${it.message}")
                            _state.value = UpdateState.Message(UpdateFailure.InstallFailed(it.message ?: "unknown error").message)
                        },
                    )
                }
            }
        }.also { job = it }
    }

    /** "Later" / dismiss a message. */
    fun dismiss() {
        _dialog.value = false
        _toast.value = false
        // "Later" on an available update keeps the footer button; only a message or "up to date" clears.
        if (_state.value is UpdateState.Available) return
        if (_state.value !is UpdateState.Downloading && _state.value !is UpdateState.Restarting) _state.value = UpdateState.Idle
    }
}
