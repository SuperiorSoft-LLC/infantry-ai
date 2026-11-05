// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.api.updates

import com.superiorsoftllc.infantry.plugin.api.UpdateSourceRef
import java.io.File

/**
 * UPDATE-ENGINE-1 (docs/wiki/Updates.md): the daemon's ONE update engine, reached as `DaemonApi.updates`.
 *
 * It manages a list of [UpdateComponent]s: the app itself (`core`) and every installed module that declares an `update`
 * block in its module.json, 3rd-party ones included. Each component names its own source, so a vendor decides where its
 * plugin's releases live. The engine does the generic work: it picks the source type, resolves the source's credential
 * by name, asks for the newest version, downloads it into the daemon's staging folder and refuses it unless its SHA-256
 * matches. Installing a staged file is an [UpdateApplier]'s job, registered per component kind.
 *
 * Source types (how to talk to GitHub, a folder, a vendor's server) are not in core: a daemon plugin registers them with
 * [registerSource] after declaring them in `daemon.updateSources`.
 */
interface Updates {
    /** Adds a source type; its [UpdateSourceType.type] must be declared in the module's `daemon.updateSources` and not taken. */
    fun registerSource(source: UpdateSourceType): AutoCloseable

    /** Adds the installer of one component [UpdateApplier.kind] (`core`, `plugin`); one per kind. */
    fun registerApplier(applier: UpdateApplier): AutoCloseable

    /**
     * Sets (or with null, clears) where [componentId]'s new versions come from, over its module.json `update` block, for
     * example from the module's own settings. A module may set only its own id; the app's ([UpdateComponent.CORE_ID]) only
     * an official module. Throws [IllegalArgumentException] for an unknown component, [IllegalStateException] when not allowed.
     */
    fun setSource(componentId: String, source: UpdateSourceRef?)

    /** The registered source type ids. */
    fun sourceTypes(): List<String>

    /** Every component the engine manages, with its installed version and its source (never a secret). */
    fun components(): List<UpdateComponent>

    /** Asks [componentId]'s source for its newest version. Network only; nothing is downloaded. */
    fun check(componentId: String): UpdateState

    /** [check] for every component. */
    fun checkAll(): List<UpdateState> = components().map { check(it.id) }

    /** Downloads and verifies the version [check] found into the staging folder. Answers [UpdateState.Staged] or why not. */
    fun fetch(componentId: String): UpdateState

    /** Hands the staged file of [componentId] to the applier of its kind. */
    fun apply(componentId: String): UpdateState
}

/** One thing the engine can update. [kind] is `core` (the app) or `plugin` (a module); [source] null = not updatable. */
data class UpdateComponent(
    val id: String,
    val kind: String,
    val name: String,
    val currentVersion: String,
    val source: UpdateSourceRef?,
) {
    companion object {
        const val CORE_ID = "core"
        const val KIND_CORE = "core"
        const val KIND_PLUGIN = "plugin"
    }
}

/**
 * A secret a source needs (a read token for a private repo, a vendor's license key). It never prints its value: [toString]
 * says only that it is set, so it cannot leak into a log or a reply by accident. Only a source calls [reveal].
 */
class Credential(private val value: String) {
    fun reveal(): String = value
    override fun toString(): String = "Credential(set)"
    override fun equals(other: Any?): Boolean = other is Credential && other.value == value
    override fun hashCode(): Int = value.hashCode()
}

/** What the engine hands a source: the component, its declaration and the resolved credential (null = none needed or none found). */
data class SourceRequest(
    val component: UpdateComponent,
    val location: String,
    val channel: String?,
    /** The platform the component runs on (`windows`, `linux`, `macos`, `android`, `any`): the source picks the asset. */
    val platform: String,
    val credential: Credential?,
)

/**
 * A newer version a source found. [sha256] is required: the engine refuses an offer it cannot verify. [artifact] is the
 * file name to stage under; [notes] free text for the owner.
 */
data class SourceOffer(
    val version: String,
    val artifact: String,
    val sha256: String,
    val notes: String? = null,
    /** Opaque to the engine: what the source needs to download this offer later (an asset id, a URL). Never a secret. */
    val handle: String = "",
)

/** How one type of release location is read. Registered by a daemon plugin. */
interface UpdateSourceType {
    /** The id components name in `update.source.type`, for example `github`. */
    val type: String

    /** The newest version for [request], or null when there is none (or none newer than the installed one). Throw on a failure. */
    fun latest(request: SourceRequest): SourceOffer?

    /** Writes [offer]'s file into [into] (a new file there) and returns it. The engine verifies the SHA-256 afterwards. */
    fun download(request: SourceRequest, offer: SourceOffer, into: File): File
}

/** Installs a verified, staged file for every component of one [kind]. */
interface UpdateApplier {
    val kind: String
    /** Installs [staged] for [component]. Answer [UpdateState.Applied] or [UpdateState.Failed]. */
    fun apply(component: UpdateComponent, staged: UpdateState.Staged): UpdateState
}

/** Where one component stands. Every state names its component. */
sealed interface UpdateState {
    val componentId: String

    /** The component declares no source. */
    data class NoSource(override val componentId: String) : UpdateState
    data class UpToDate(override val componentId: String, val currentVersion: String) : UpdateState
    data class Available(override val componentId: String, val currentVersion: String, val offer: SourceOffer) : UpdateState
    /** Downloaded and its SHA-256 matched: [file] is ready to apply. */
    data class Staged(override val componentId: String, val offer: SourceOffer, val file: File) : UpdateState
    data class Applied(override val componentId: String, val version: String, val note: String = "") : UpdateState
    /** [reason] is for the owner; it never holds a secret. */
    data class Failed(override val componentId: String, val reason: String) : UpdateState
}

/** The engine of a daemon without one: no components, every call refused. */
object NoUpdates : Updates {
    override fun registerSource(source: UpdateSourceType): AutoCloseable = throw IllegalStateException("this daemon has no update engine")
    override fun registerApplier(applier: UpdateApplier): AutoCloseable = throw IllegalStateException("this daemon has no update engine")
    override fun setSource(componentId: String, source: UpdateSourceRef?) = throw IllegalStateException("this daemon has no update engine")
    override fun sourceTypes(): List<String> = emptyList()
    override fun components(): List<UpdateComponent> = emptyList()
    override fun check(componentId: String): UpdateState = UpdateState.Failed(componentId, "this daemon has no update engine")
    override fun fetch(componentId: String): UpdateState = check(componentId)
    override fun apply(componentId: String): UpdateState = check(componentId)
}
