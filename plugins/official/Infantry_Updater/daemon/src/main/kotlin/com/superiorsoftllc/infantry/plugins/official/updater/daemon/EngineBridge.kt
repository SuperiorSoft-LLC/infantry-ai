// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.updater.daemon

import com.superiorsoftllc.infantry.daemon.api.updates.SourceOffer
import com.superiorsoftllc.infantry.daemon.api.updates.SourceRequest
import com.superiorsoftllc.infantry.daemon.api.updates.UpdateApplier
import com.superiorsoftllc.infantry.daemon.api.updates.UpdateComponent
import com.superiorsoftllc.infantry.daemon.api.updates.UpdateSourceType
import com.superiorsoftllc.infantry.daemon.api.updates.UpdateState
import com.superiorsoftllc.infantry.daemon.api.updates.Updates
import com.superiorsoftllc.infantry.plugin.api.UpdateCredentialRef
import com.superiorsoftllc.infantry.plugin.api.UpdateSourceRef
import com.superiorsoftllc.infantry.plugins.official.updater.AssetKind
import com.superiorsoftllc.infantry.plugins.official.updater.AvailableUpdate
import com.superiorsoftllc.infantry.plugins.official.updater.EngineAnswer
import com.superiorsoftllc.infantry.plugins.official.updater.EnginePath
import com.superiorsoftllc.infantry.plugins.official.updater.Fetched
import com.superiorsoftllc.infantry.plugins.official.updater.SemVer
import com.superiorsoftllc.infantry.plugins.official.updater.ServerInstall
import com.superiorsoftllc.infantry.plugins.official.updater.UpdateCheck
import com.superiorsoftllc.infantry.plugins.official.updater.UpdateConfig
import com.superiorsoftllc.infantry.plugins.official.updater.UpdateSettings
import com.superiorsoftllc.infantry.plugins.official.updater.UpdateSource
import com.superiorsoftllc.infantry.plugins.official.updater.UpdateSources
import com.superiorsoftllc.infantry.plugins.official.updater.UpdateVisibility
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * UPDATER-1 (docs/wiki/Updates.md): Infantry_Updater on core's ONE update engine. The plugin brings what core must not
 * know: how an Infantry release is laid out (GitHub Releases or a local releases folder, which asset is the server
 * archive, where its SHA-256 is), and how the app installs one. Core does the generic part (component list, credential
 * lookup by name, staging, SHA-256 check).
 *
 *  - [ReleaseSourceType] is the source type `infantry-release`.
 *  - [CoreArchiveApplier] installs a staged server archive for the `core` component (an archive install only).
 *  - [CoreEnginePath] is how the plugin's `/update/...` routes reach the engine.
 *  - [coreSource] is the app's source, set from the plugin's own `updater.*` settings.
 *
 * The desktop window's MSI handoff and the phone's APK offer (`/update/client`) still use the plugin's sources directly:
 * the window has no engine of its own, and the phone's file is installed by the phone, not by this daemon.
 */
class ReleaseSourceType(
    /** The plugin's resolved settings when updates are on (repo or folder, api base, visibility, its own token lookup). */
    private val base: () -> UpdateConfig?,
    /** Which asset this daemon installs (the server archive of its OS/CPU; Docker: the linux one, report only). */
    private val kind: () -> AssetKind,
    private val sources: (UpdateConfig, AssetKind) -> UpdateSource = UpdateSources::forConfig,
) : UpdateSourceType {
    override val type: String = TYPE

    /** The last offer per component, so [download] does not ask the release list again. */
    private val found = ConcurrentHashMap<String, Pair<UpdateSource, AvailableUpdate>>()

    override fun latest(request: SourceRequest): SourceOffer? {
        val source = sources(configFor(request), kind())
        val update = when (val result = source.check()) {
            is UpdateCheck.UpToDate -> return null.also { found.remove(request.component.id) }
            is UpdateCheck.Failed -> throw IOException(result.failure.message)
            is UpdateCheck.Available -> result.update
        }
        val sha = when (val got = source.expectedSha256(update)) {
            is Fetched.Failed -> throw IOException(got.failure.message)
            is Fetched.Ok -> got.value
        }
        found[request.component.id] = source to update
        return SourceOffer(version = update.latest.toString(), artifact = File(update.installer.name).name, sha256 = sha, notes = update.notes, handle = update.installer.name)
    }

    override fun download(request: SourceRequest, offer: SourceOffer, into: File): File {
        val (source, update) = found[request.component.id]?.takeIf { it.second.latest.toString() == offer.version }
            ?: run { latest(request); found[request.component.id] }
            ?: throw IOException("version ${offer.version} is no longer offered")
        return when (val got = source.download(update, into)) {
            is Fetched.Failed -> throw IOException(got.failure.message)
            is Fetched.Ok -> got.value.file
        }
    }

    /**
     * The plugin's settings, asked on behalf of the engine's component: its installed version, its location (`owner/repo`
     * or a releases folder) and the credential the engine resolved by name, falling back to the plugin's own lookup (the
     * OS credential store). A public source never sends a token ([UpdateConfig.token]).
     */
    private fun configFor(request: SourceRequest): UpdateConfig {
        val b = base() ?: throw IOException("updates are not configured")
        val current = SemVer.parse(request.component.currentVersion) ?: b.currentVersion
        val location = request.location.trim()
        val dir = if (location.isNotEmpty() && File(location).isAbsolute) File(location) else b.localDir
        val repo = if (dir == null && location.isNotEmpty()) location else b.repo
        val engineToken = request.credential
        return UpdateConfig(repo, current, b.apiBase, dir, b.visibility, b.checkIntervalHours, channel = b.channel, tokenSource = { engineToken?.reveal() ?: b.token })
    }

    companion object {
        /** Declared in module.json `daemon.updateSources`. */
        const val TYPE = "infantry-release"

        /** The app's source for [config]: GitHub `owner/repo` or the releases folder; a private one names the token's env / keystore entry. */
        fun coreSource(config: UpdateConfig?): UpdateSourceRef? = config?.let {
            UpdateSourceRef(
                type = TYPE,
                location = it.localDir?.absolutePath ?: it.repo,
                credential = if (it.visibility == UpdateVisibility.PRIVATE) UpdateCredentialRef(env = UpdateSettings.TOKEN_ENV, secret = UpdateSettings.TOKEN_SECRET) else null,
            )
        }
    }
}

/** UPDATER-1: installs a staged, verified server archive for the `core` component: unpacked under the install root and made `current`. */
class CoreArchiveApplier(private val install: () -> ServerInstall?) : UpdateApplier {
    override val kind: String = UpdateComponent.KIND_CORE

    override fun apply(component: UpdateComponent, staged: UpdateState.Staged): UpdateState {
        val inst = install() ?: return UpdateState.Failed(component.id, "this daemon is not an archive install; it cannot replace itself")
        inst.stage(staged.offer.version, staged.file)
        return UpdateState.Applied(component.id, staged.offer.version, "made current; the next start runs it")
    }
}

/** UPDATER-1: the plugin's `/update/...` service reaching core's engine for the `core` component. */
class CoreEnginePath(private val updates: Updates) : EnginePath {
    override fun check(): EngineAnswer = when (val s = updates.check(UpdateComponent.CORE_ID)) {
        is UpdateState.Available -> EngineAnswer.Available(s.offer.version, s.offer.notes?.trim()?.takeIf { it.isNotEmpty() } ?: "(no release notes)")
        is UpdateState.UpToDate -> EngineAnswer.UpToDate
        is UpdateState.NoSource -> EngineAnswer.Failed("updates are not configured")
        is UpdateState.Failed -> EngineAnswer.Failed(s.reason)
        else -> EngineAnswer.UpToDate
    }

    override fun install(): EngineAnswer {
        when (val fetched = updates.fetch(UpdateComponent.CORE_ID)) {
            is UpdateState.Staged -> Unit
            is UpdateState.Failed -> return EngineAnswer.Failed(fetched.reason)
            is UpdateState.UpToDate -> return EngineAnswer.Failed("already up to date")
            else -> return EngineAnswer.Failed("nothing to install")
        }
        return when (val applied = updates.apply(UpdateComponent.CORE_ID)) {
            is UpdateState.Applied -> EngineAnswer.Installed(applied.version)
            is UpdateState.Failed -> EngineAnswer.Failed(applied.reason)
            else -> EngineAnswer.Failed("the install did not finish")
        }
    }
}
