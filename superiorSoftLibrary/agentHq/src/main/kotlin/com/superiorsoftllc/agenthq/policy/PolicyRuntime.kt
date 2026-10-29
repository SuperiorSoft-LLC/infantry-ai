package com.superiorsoftllc.agenthq.policy

import com.superiorsoftllc.agenthq.daemon.DemoLock
import com.superiorsoftllc.agenthq.plugin.api.PolicyChoice
import com.superiorsoftllc.agenthq.plugin.api.PolicyPreset
import com.superiorsoftllc.agenthq.plugin.api.PolicyWire
import com.superiorsoftllc.agenthq.protocol.Envelope
import java.io.File
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/**
 * POLICY-1 (docs/POLICY.md): the ONE switch. Which preset is active, the frame gate that follows it, the seated lockdown
 * built from it ([lock]) and the `policy` frame every client is sent. Selection, first that applies: [POLICY_ENV]
 * (pinned); a preset whose `selectWhenEnv` is set truthy (pinned); the stored [selectionFile] (the owner's Settings pick);
 * production. Names no preset but production: every other id comes from a [PolicyProvider].
 */
class PolicyRuntime(
    private val providers: List<PolicyProvider>,
    private val getenv: (String) -> String?,
    /** Where the owner's pick is stored (one line: the preset id); null = not stored (a test). */
    private val selectionFile: File?,
    private val stateDir: File,
) {
    /** Told after every switch (the daemon pushes the new `policy` frame and re-seeds). */
    private val listeners = CopyOnWriteArrayList<(PolicyRuntime) -> Unit>()

    @Volatile var current: PolicyPreset = PolicyPreset.Production
        private set
    /** The env var that pins the selection, or null when the owner may switch. */
    @Volatile var pinnedBy: String? = null
        private set
    /** The seated lockdown of [current] (off when it has no `seats`). */
    @Volatile var lock: DemoLock = DemoLock(false)
        private set

    init { recompute() }

    fun presets(): List<PolicyPreset> = (listOf(BuiltInPolicies) + providers).flatMap { it.presets() }.distinctBy { it.id }

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
        lock = DemoLock.of(current, getenv, stateDir)
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
    )

    /** The extra `ready` keys of [current] (empty for production), each ` key=value`. */
    fun readyFields(): String = buildString {
        val wire = wire(false)
        for ((k, v) in wire.readyFields()) append(' ').append(k).append('=').append(v)
        val notice = lock.notice.ifBlank { current.notice.orEmpty() }
        if (notice.isNotBlank()) append(" notice=").append(Base64.getUrlEncoder().withoutPadding().encodeToString(notice.toByteArray(Charsets.UTF_8)))
    }

    /**
     * Null = [envelope] may proceed under [current]; else the refusal. Production refuses nothing. Otherwise the frame's
     * action must be allowed (an unmapped frame is refused: default deny), then the seated lockdown scopes it. The switch
     * frames are always the owner's.
     */
    fun frameRefusal(envelope: Envelope, active: String?, owner: Boolean): String? {
        val type = envelope.type
        if (type in ActionCatalog.SWITCH_FRAMES) return if (owner || type == ActionCatalog.POLICY_GET) null else "$type refused: only the owner switches the policy"
        val preset = current
        if (preset.isEmpty) return null
        if (lock.enabled) return lock.frameRefusal(envelope, active)
        return actionRefusal(preset, type)
    }

    companion object {
        const val POLICY_ENV = "AGENTHQ_POLICY"
        const val SELECTION_FILE = "policy.preset"

        fun truthy(v: String?): Boolean = v?.trim()?.lowercase()?.let { it.isNotEmpty() && it != "0" && it != "false" && it != "off" && it != "no" } ?: false

        /** The refusal of frame [type] by [preset]'s rules, or null; the text is the preset's `refusal` template. */
        fun actionRefusal(preset: PolicyPreset, type: String): String? {
            val action = ActionCatalog.forFrame(type)
            if (action != null && com.superiorsoftllc.agenthq.plugin.api.ActionPolicyMatcher.allows(preset.disable, preset.allow, action.id, action.kind)) return null
            return refusalText(preset, type, action?.id)
        }

        fun refusalText(preset: PolicyPreset, type: String, actionId: String?): String {
            val shown = type.filter { it in ' '..'~' }.take(64)
            val reason = preset.reason ?: "disabled by policy '${preset.id}'"
            return (preset.refusal ?: "policy: '{frame}' ({action}) is disabled: {reason}")
                .replace("{frame}", shown).replace("{action}", actionId ?: "unmapped").replace("{reason}", reason)
        }

        /** The real daemon's: env, the plugins roots' `policies/` folders, `<state dir>/policy.preset`. */
        fun fromEnvironment(
            stateDir: File,
            getenv: (String) -> String? = com.superiorsoftllc.agenthq.session.InfantryEnv::get,
            property: (String) -> String? = System::getProperty,
        ): PolicyRuntime {
            val root = com.superiorsoftllc.agenthq.plugin.host.PluginFolders.root(stateDir, { getenv(it) }, property)
            val bundled = com.superiorsoftllc.agenthq.plugin.host.PluginFolders.bundledRoot({ getenv(it) }, property)
            val provider = DirectoryPolicyProvider({ DirectoryPolicyProvider.defaultDirs(root, bundled, getenv) })
            return PolicyRuntime(listOf(provider), getenv, File(stateDir, SELECTION_FILE), stateDir)
        }
    }
}
