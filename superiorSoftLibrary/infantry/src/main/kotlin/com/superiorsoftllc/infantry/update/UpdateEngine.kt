// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.update

import com.superiorsoftllc.infantry.daemon.api.updates.Credential
import com.superiorsoftllc.infantry.daemon.api.updates.SourceOffer
import com.superiorsoftllc.infantry.daemon.api.updates.SourceRequest
import com.superiorsoftllc.infantry.daemon.api.updates.UpdateApplier
import com.superiorsoftllc.infantry.daemon.api.updates.UpdateComponent
import com.superiorsoftllc.infantry.daemon.api.updates.UpdateSourceType
import com.superiorsoftllc.infantry.daemon.api.updates.UpdateState
import com.superiorsoftllc.infantry.plugin.api.UpdateSourceRef
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * UPDATE-ENGINE-1 (docs/wiki/Updates.md): the generic update engine behind `DaemonApi.updates`. It knows no release host,
 * no repo and no file format. It walks the components, hands each one's declaration to the source type it names, resolves
 * the credential by name, stages the download and refuses it unless the SHA-256 the source announced matches. Appliers
 * (one per component kind) install a staged file. Every answer is an [UpdateState]; nothing here throws at a caller.
 *
 * [declared] is read at each call, so a module loaded later is managed too. [credential] resolves a component's
 * credential reference (the host applies the "only your own declared names" rule); the value is never logged.
 */
class UpdateEngine(
    private val declared: () -> List<UpdateComponent>,
    private val credential: (UpdateComponent, UpdateSourceRef) -> Credential?,
    private val stagingRoot: File,
    private val platform: String = currentPlatform(),
    private val log: (String) -> Unit = {},
) {
    private val sources = ConcurrentHashMap<String, UpdateSourceType>()
    private val appliers = ConcurrentHashMap<String, UpdateApplier>()
    private val overrides = ConcurrentHashMap<String, Override>()
    private val last = ConcurrentHashMap<String, UpdateState>()

    /** A [setSource] value; [source] null clears the module.json one too. */
    private data class Override(val source: UpdateSourceRef?)

    fun registerSource(source: UpdateSourceType): AutoCloseable {
        require(TYPE.matches(source.type)) { "update source type '${source.type}' must match ${TYPE.pattern}" }
        check(sources.putIfAbsent(source.type, source) == null) { "update source type '${source.type}' is already registered" }
        return AutoCloseable { sources.remove(source.type, source) }
    }

    fun registerApplier(applier: UpdateApplier): AutoCloseable {
        check(appliers.putIfAbsent(applier.kind, applier) == null) { "an update applier for '${applier.kind}' is already registered" }
        return AutoCloseable { appliers.remove(applier.kind, applier) }
    }

    fun sourceTypes(): List<String> = sources.keys.sorted()

    fun setSource(componentId: String, source: UpdateSourceRef?) {
        require(declared().any { it.id == componentId }) { "unknown update component '$componentId'" }
        overrides[componentId] = Override(source)
        last.remove(componentId)
    }

    fun components(): List<UpdateComponent> = declared().map { c -> overrides[c.id]?.let { c.copy(source = it.source) } ?: c }

    fun component(id: String): UpdateComponent? = components().firstOrNull { it.id == id }

    fun check(componentId: String): UpdateState {
        val c = component(componentId) ?: return UpdateState.Failed(componentId, "unknown update component")
        val state = checkOf(c)
        last[componentId] = state
        return state
    }

    fun checkAll(): List<UpdateState> = components().map { check(it.id) }

    private fun checkOf(c: UpdateComponent): UpdateState {
        val (source, request) = resolve(c) ?: return UpdateState.NoSource(c.id)
        if (source == null) return UpdateState.Failed(c.id, "no update source of type '${c.source?.type}' is installed")
        val offer = try {
            source.latest(request)
        } catch (e: Exception) {
            return UpdateState.Failed(c.id, "the ${c.source?.type} source failed: ${safe(e)}")
        } ?: return UpdateState.UpToDate(c.id, c.currentVersion)
        problem(offer)?.let { return UpdateState.Failed(c.id, it) }
        if (!newer(offer.version, c.currentVersion)) return UpdateState.UpToDate(c.id, c.currentVersion)
        return UpdateState.Available(c.id, c.currentVersion, offer)
    }

    /** Downloads the offer [check] found (checking first when there is none) and verifies it. */
    fun fetch(componentId: String): UpdateState {
        val c = component(componentId) ?: return UpdateState.Failed(componentId, "unknown update component")
        val found = (last[componentId] as? UpdateState.Available) ?: check(componentId)
        val available = found as? UpdateState.Available ?: return found
        val (source, request) = resolve(c) ?: return UpdateState.NoSource(c.id)
        if (source == null) return UpdateState.Failed(c.id, "no update source of type '${c.source?.type}' is installed")
        val offer = available.offer
        val dir = File(stagingRoot, "${fileSafe(c.id)}/${fileSafe(offer.version)}").apply { deleteRecursively(); mkdirs() }
        fun failed(reason: String): UpdateState {
            dir.deleteRecursively()
            return UpdateState.Failed(c.id, reason).also { last[componentId] = it }
        }
        val file = try {
            source.download(request, offer, dir)
        } catch (e: Exception) {
            return failed("download failed: ${safe(e)}")
        }
        if (!file.isFile || !file.canonicalPath.startsWith(dir.canonicalPath + File.separator)) {
            return failed("the source wrote no file in the staging folder")
        }
        val actual = sha256(file)
        if (!actual.equals(offer.sha256, ignoreCase = true)) {
            return failed("SHA-256 mismatch for ${offer.artifact}: got $actual, the source announced ${offer.sha256}")
        }
        log("[updates] ${c.id} ${offer.version} staged and verified (${file.name})")
        return UpdateState.Staged(c.id, offer, file).also { last[componentId] = it }
    }

    /** Installs the staged file through the applier of the component's kind. */
    fun apply(componentId: String): UpdateState {
        val c = component(componentId) ?: return UpdateState.Failed(componentId, "unknown update component")
        val staged = last[componentId] as? UpdateState.Staged
            ?: return UpdateState.Failed(c.id, "nothing is staged: fetch first")
        val applier = appliers[c.kind] ?: return UpdateState.Failed(c.id, "no installer for '${c.kind}' components is installed")
        val result = try {
            applier.apply(c, staged)
        } catch (e: Exception) {
            UpdateState.Failed(c.id, "install failed: ${safe(e)}")
        }
        last[componentId] = result
        return result
    }

    /** The last state the engine saw for [componentId], or null before any check. */
    fun lastState(componentId: String): UpdateState? = last[componentId]

    /** Null = the component declares no source; first = null when its type is not registered. */
    private fun resolve(c: UpdateComponent): Pair<UpdateSourceType?, SourceRequest>? {
        val ref = c.source ?: return null
        val cred = ref.credential?.takeUnless { it.isEmpty }?.let { credential(c, ref) }
        return sources[ref.type] to SourceRequest(c, ref.location, ref.channel, platform, cred)
    }

    private fun problem(offer: SourceOffer): String? = when {
        SemVer.parse(offer.version) == null -> "the source offered version '${offer.version}', which is not MAJOR.MINOR.PATCH"
        !SHA256.matches(offer.sha256) -> "the source offered ${offer.artifact} without a SHA-256: refused (every update is verified)"
        offer.artifact.isBlank() || fileSafe(offer.artifact) != offer.artifact -> "the source offered an unsafe file name '${offer.artifact}'"
        else -> null
    }

    companion object {
        private val TYPE = Regex("^[a-z][a-z0-9_.-]*$")
        private val SHA256 = Regex("^[0-9a-fA-F]{64}$")

        fun newer(offered: String, current: String): Boolean {
            val o = SemVer.parse(offered) ?: return false
            val c = SemVer.parse(current) ?: return true
            return o > c
        }

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        /** Only letters, digits and `._-`: a component id or version can never climb out of the staging folder. */
        fun fileSafe(name: String): String = name.replace(Regex("[^A-Za-z0-9._-]"), "_").let { if (it.trim('.').isEmpty()) "_" else it }

        /** The exception's message (or its type), never its cause chain: a source may wrap a URL that carried a secret. */
        private fun safe(e: Exception): String = e.message?.takeIf { it.isNotBlank() } ?: e::class.simpleName.orEmpty()

        fun currentPlatform(): String {
            val os = System.getProperty("os.name").orEmpty().lowercase()
            return when {
                "android" in System.getProperty("java.vendor").orEmpty().lowercase() -> "android"
                os.startsWith("windows") -> "windows"
                os.startsWith("mac") -> "macos"
                os.startsWith("linux") -> "linux"
                else -> "any"
            }
        }
    }
}
