package com.superiorsoftllc.infantry.plugins.demo.lock.daemon

import com.superiorsoftllc.infantry.daemon.api.ActionInfo
import com.superiorsoftllc.infantry.daemon.api.Frame
import com.superiorsoftllc.infantry.daemon.api.Policy
import com.superiorsoftllc.infantry.plugin.api.PolicyPreset
import com.superiorsoftllc.infantry.policy.ActionCatalog
import com.superiorsoftllc.infantry.policy.PolicyRuntime
import com.superiorsoftllc.infantry.protocol.Envelope
import kotlinx.serialization.json.Json
import java.io.File

/** The demo preset as this module ships it (`policies/demo.json`), and the lock the daemon would build from it. */
object DemoPreset {
    val file: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }
            .flatMap { sequenceOf(File(it, "policies/demo.json"), File(it, "plugins/demo/Demo_Lock/policies/demo.json")) }
            .firstOrNull { it.isFile } ?: error("Demo_Lock/policies/demo.json not found above ${File("").absolutePath}")
    }
    val preset: PolicyPreset by lazy { Json { ignoreUnknownKeys = true }.decodeFromString(PolicyPreset.serializer(), file.readText()) }

    /** The lock of the demo preset, nothing from the env. */
    fun lock(notice: String = "") = DemoLock(
        enabled = true, notice = notice, projectDir = preset.seats!!.projectDir, projectName = preset.seats!!.projectName,
        seats = DemoLock.parseSeats(preset.seats!!.seats),
        mailRefusalText = preset.seats!!.mailRefusal, httpRefusalText = preset.seats!!.httpRefusal,
    )
}

/**
 * The daemon's [Policy] for one fixed [preset], over core's real action catalog and refusal texts (what
 * `CoreDaemonServices.policy()` answers while [preset] is active).
 */
class PresetPolicy(private val preset: PolicyPreset) : Policy {
    override fun active(): PolicyPreset = preset
    override fun onChange(listener: (PolicyPreset) -> Unit): AutoCloseable = AutoCloseable {}
    override fun actionOf(frame: Frame): ActionInfo? = ActionCatalog.forEnvelope(frame.envelope())?.let { ActionInfo(it.id, it.kind, it.scope.name) }
    override fun refusal(frame: Frame): String? = PolicyRuntime.actionRefusal(preset, frame.envelope())
    override fun refusalText(type: String): String = PolicyRuntime.refusalText(preset, type, ActionCatalog.forFrame(type)?.id)
    override fun reportSeating(error: String?) {}
    override fun claimSeats() {}

    /** Core's order: the preset's own rules first, then the lock's scoping (the plugin's `frameGate`). */
    fun gate(lock: DemoLock, frame: Frame, active: String? = null): String? = refusal(frame)?.let { refusalText(frame.type) } ?: lock.frameRefusal(frame, active, this)

    private fun Frame.envelope() = Envelope(type, data, sessionId = sessionId, requestId = requestId)
}
