// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.policy

import com.superiorsoftllc.infantry.plugin.api.PolicyChoice
import com.superiorsoftllc.infantry.plugin.api.PolicyPreset
import com.superiorsoftllc.infantry.plugin.api.PolicyWire
import com.superiorsoftllc.infantry.protocol.Envelope
import java.io.File
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/**
 * POLICY-1 (docs/POLICY.md): the ONE switch. Which preset is active, the frame gate that follows it and the `policy` frame
 * every client is sent. Selection, first that applies: [POLICY_ENV] (pinned); a preset whose `selectWhenEnv` is set truthy
 * (pinned); the stored [selectionFile] (the owner's Settings pick); production. Names no preset but production: every
 * other id comes from a [PolicyProvider].
 *
 * DAEMON-PLUGINS-1 (docs/DAEMON-PLUGINS.md): daemon plugins extend it through [Extensions] (their presets, the seats a
 * preset declares, the pinned notice, their own frames' actions). A preset with `seats` that no loaded plugin implements
 * refuses every frame but the switch (fail closed): core has no seated lockdown of its own.
 */
class PolicyRuntime(
    private val providers: List<PolicyProvider>,
    private val getenv: (String) -> String?,
    /** Where the owner's pick is stored (one line: the preset id); null = not stored (a test). */
    private val selectionFile: File?,
    @Suppress("unused") private val stateDir: File,
) {
    /** DAEMON-PLUGINS-1: what the daemon plugins add to the policy; every default is "nothing". */
    interface Extensions {
        fun presets(): List<PolicyPreset> = emptyList()
        /** True when a loaded, healthy plugin implements a preset's `seats` (its frame gate scopes them). */
        fun seatsClaimed(): Boolean = false
        /** A plugin's pinned notice (it may come from the deployment's env), else null. */
        fun notice(): String? = null
        /** The action of a frame core's catalog does not know (a plugin's frame or tool call); null = unmapped. */
        fun action(envelope: Envelope): ActionCatalog.Action? = null
        /** The plugins' frames, for the `policy` frame's catalog. */
        fun frameActions(): Map<String, ActionCatalog.Action> = emptyMap()

        companion object { val NONE = object : Extensions {} }
    }

    /** Told after every switch (the daemon pushes the new `policy` frame and re-seeds). */
    private val listeners = CopyOnWriteArrayList<(PolicyRuntime) -> Unit>()

    @Volatile private var extensions: Extensions = Extensions.NONE

    @Volatile var current: PolicyPreset = PolicyPreset.Production
        private set
    /** The env var that pins the selection, or null when the owner may switch. */
    @Volatile var pinnedBy: String? = null
        private set
    /** GAP-AUDIT-1 (f): why seating [current] failed after the last switch (sent in the `policy` frame), or null. */
    @Volatile var seatingError: String? = null
        private set

    /** GAP-AUDIT-1 (f): the plugin seating [current] reports its outcome; null = seated. */
    fun seatingFailed(error: String?) { seatingError = error }

    init { recompute() }

    /**
     * DAEMON-PLUGINS-1: attach the daemon plugins (once, after they loaded) and select again, since a plugin may provide
     * the pinned preset. Listeners are not told: nothing was served before.
     */
    @Synchronized
    fun attach(extensions: Extensions) {
        this.extensions = extensions
        recompute()
    }

    fun presets(): List<PolicyPreset> = (listOf(BuiltInPolicies) + providers + PolicyProvider { extensions.presets() })
        .flatMap { it.presets() }.distinctBy { it.id }

    private fun find(id: String): PolicyPreset? {
        val all = presets().associateBy { it.id }
        return PolicyPreset.resolve(id, all::get)
    }

    private fun recompute() {
        val pinnedId = getenv(POLICY_ENV)?.trim()?.takeIf { it.isNotEmpty() }
        val (id, pin) = when {
            pinnedId != null -> pinnedId to POLICY_ENV
            else -> presets().firstOrNull { p -> p.selectWhenEnv?.let { truthy(getenv(it)) } == true }?.let { it.id to it.selectWhenEnv }
                ?: ((stored() ?: PolicyPreset.PRODUCTION) to null)
        }
        // A pinned id nobody provides is fail-closed; a stored one that vanished falls back to production.
        current = find(id) ?: if (pin != null) PolicyPreset.missing(id) else PolicyPreset.Production
        pinnedBy = pin
        seatingError = null
    }

    private fun stored(): String? = selectionFile?.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }

    /** The owner's switch. Null = done; else why not (pinned, unknown id). */
    @Synchronized
    fun select(id: String): String? {
        val wanted = id.trim()
        pinnedBy?.let { return "policy is pinned by $it" }
        if (wanted != PolicyPreset.PRODUCTION && presets().none { it.id == wanted }) return "unknown policy '$wanted'"
        selectionFile?.let { f ->
            f.parentFile?.mkdirs()
            if (wanted == PolicyPreset.PRODUCTION) f.delete() else f.writeText(wanted + "\n")
        }
        recompute()
        listeners.forEach { runCatching { it(this) }.onFailure { e -> System.err.println("[policy] listener failed: ${e.message}") } }
        return null
    }

    fun onChange(listener: (PolicyRuntime) -> Unit): AutoCloseable { listeners += listener; return AutoCloseable { listeners -= listener } }

    /** The `policy` frame for a connection; [owner] decides [PolicyWire.canSwitch]. */
    fun wire(owner: Boolean): PolicyWire = PolicyWire.of(
        current, pinnedBy, canSwitch = owner && pinnedBy == null,
        presets = presets().map { PolicyChoice(it.id, it.label.ifBlank { it.id }, it.description) },
    ).copy(
        notice = notice(), seatingError = seatingError,
        frames = if (current.isEmpty) emptyMap()
        else ActionCatalog.wireFrames() + extensions.frameActions().mapValues { (_, a) -> com.superiorsoftllc.infantry.plugin.api.FrameAction(a.id, a.kind) },
    )

    /** The pinned notice: a plugin's, else the preset's; null = none. Only a non-production preset pins one. */
    private fun notice(): String? = (extensions.notice()?.takeIf { it.isNotBlank() && !current.isEmpty } ?: current.notice.orEmpty()).takeIf { it.isNotBlank() }

    /** The extra `ready` keys of [current] (empty for production), each ` key=value`. */
    fun readyFields(): String = buildString {
        for ((k, v) in wire(false).readyFields()) append(' ').append(k).append('=').append(v)
        notice()?.let { append(" notice=").append(Base64.getUrlEncoder().withoutPadding().encodeToString(it.toByteArray(Charsets.UTF_8))) }
    }

    /** The action [envelope] performs: core's catalog, else a plugin's frame or tool ([Extensions.action]); null = unmapped. */
    fun actionOf(envelope: Envelope): ActionCatalog.Action? = ActionCatalog.forEnvelope(envelope) ?: extensions.action(envelope)

    /**
     * Null = [envelope] may proceed under [current]; else the refusal. Production refuses nothing. Otherwise the frame's
     * action must be allowed (an unmapped frame is refused: default deny); a preset with `seats` needs a plugin that
     * implements them (its frame gate scopes what passes here), else every frame is refused. The switch frames are always
     * the owner's.
     */
    fun frameRefusal(envelope: Envelope, @Suppress("UNUSED_PARAMETER") active: String?, owner: Boolean): String? {
        val type = envelope.type
        if (type in ActionCatalog.SWITCH_FRAMES) return if (owner || type == ActionCatalog.POLICY_GET) null else "$type refused: only the owner switches the policy"
        val preset = current
        if (preset.isEmpty) return null
        if (preset.seats != null && !extensions.seatsClaimed()) {
            return "$type refused: policy '${preset.id}' declares seats and no loaded daemon plugin implements them (fail closed)"
        }
        return actionRefusal(preset, envelope, extensions.action(envelope))
    }

    companion object {
        const val POLICY_ENV = "INFANTRY_POLICY"
        const val SELECTION_FILE = "policy.preset"

        fun truthy(v: String?): Boolean = v?.trim()?.lowercase()?.let { it.isNotEmpty() && it != "0" && it != "false" && it != "off" && it != "no" } ?: false

        /** The refusal of frame [type] by [preset]'s rules, or null; the text is the preset's `refusal` template. */
        fun actionRefusal(preset: PolicyPreset, type: String): String? = actionRefusal(preset, Envelope(type, ""))

        /**
         * As above for a whole frame: its action is read from the payload when the frame alone does not say
         * ([ActionCatalog.forEnvelope]); [extra] is a plugin frame's action when core's catalog has none.
         */
        fun actionRefusal(preset: PolicyPreset, envelope: Envelope, extra: ActionCatalog.Action? = null): String? {
            val type = envelope.type
            val action = ActionCatalog.forEnvelope(envelope) ?: extra
            if (action != null && com.superiorsoftllc.infantry.plugin.api.ActionPolicyMatcher.allows(preset.disable, preset.allow, action.id, action.kind)) return null
            return refusalText(preset, type, action?.id)
        }

        fun refusalText(preset: PolicyPreset, type: String, actionId: String?): String =
            com.superiorsoftllc.infantry.plugin.api.PolicyRefusal.text(preset.refusal, preset.reason, preset.id, type, actionId)

        /** The real daemon's: env, the plugins roots' `policies/` folders, `<state dir>/policy.preset`. */
        fun fromEnvironment(
            stateDir: File,
            getenv: (String) -> String? = com.superiorsoftllc.infantry.session.InfantryEnv::get,
            property: (String) -> String? = System::getProperty,
        ): PolicyRuntime {
            val root = com.superiorsoftllc.infantry.plugin.host.PluginFolders.root(stateDir, { getenv(it) }, property)
            val bundled = com.superiorsoftllc.infantry.plugin.host.PluginFolders.bundledRoot({ getenv(it) }, property)
            val provider = DirectoryPolicyProvider({ DirectoryPolicyProvider.defaultDirs(root, bundled, getenv) })
            return PolicyRuntime(listOf(provider), getenv, File(stateDir, SELECTION_FILE), stateDir)
        }
    }
}
