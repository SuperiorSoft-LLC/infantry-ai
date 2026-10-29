package com.superiorsoftllc.agenthq.daemon

import com.superiorsoftllc.agenthq.plugin.api.PolicyPreset
import com.superiorsoftllc.agenthq.protocol.Envelope
import kotlinx.serialization.json.Json
import java.io.File

/**
 * POLICY-1: the demo preset as the demo plugin ships it (plugins/demo/Demo_Lock/policies/demo.json), read from the repo so
 * the daemon's demo tests run against the plugin's data, not against values in core. Plus the FROZEN allow-lists of the
 * pre-POLICY-1 DemoLock (DemoLock.READ_FRAMES / PROJECT_READ_FRAMES), the parity baseline.
 */
object DemoPresetFixture {
    val file: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "plugins/demo/Demo_Lock/policies/demo.json") }
            .firstOrNull { it.isFile } ?: error("plugins/demo/Demo_Lock/policies/demo.json not found above ${File("").absolutePath}")
    }
    val preset: PolicyPreset by lazy { Json { ignoreUnknownKeys = true }.decodeFromString(PolicyPreset.serializer(), file.readText()) }

    /** The old `DemoLock.READ_FRAMES` (DEMO-MERGE-1, 8b7fd5a5), frozen. */
    val READ_FRAMES: Set<String> = setOf(
        Envelope.SESSIONS, Envelope.PROJECT_LIST, Envelope.PROJECT_GET, Envelope.WORKSPACE_LIST,
        Envelope.SETTINGS_GET, Envelope.LIMIT_CATALOG, Envelope.LIMITS, Envelope.USAGE_GET,
        Envelope.TICKET_LIST, Envelope.TRIGGER_LIST, Envelope.COMMAND_LIST, Envelope.ROLE_LIST,
        Envelope.PROVIDERS, Envelope.MODELS, Envelope.SESSION_STATS_GET, Envelope.QUESTION_LIST,
        Envelope.MAIL_INBOX, Envelope.MAIL_OUTBOX, Envelope.MAIL_THREAD, Envelope.MAIL_THREADS,
        Envelope.MAIL_WAITING, Envelope.MAIL_READ,
        Envelope.AGENT_LIST, Envelope.ROLE_BINDING_LIST, Envelope.TRAIT_LIST, Envelope.ROLE_TRAITS_LIST,
        Envelope.ATTENTION_LIST, Envelope.PROVIDER_STATES, Envelope.PROMPT_LIST, Envelope.SKILL_LIST,
        Envelope.USAGE_METER_GET, Envelope.CLI_LOCATIONS, Envelope.TOOL_APPROVAL, Envelope.APPROVAL_RULES, Envelope.ROLE_BINDING_GET,
        Envelope.PROMPT_GET, Envelope.SKILL_GET, Envelope.TRAIT_GET, Envelope.ROLE_TRAITS_GET, Envelope.AGENT_ROSTER, Envelope.TICKET_GET,
        Envelope.TRIGGER_EVENTS, Envelope.POD_QUERY,
    )

    /** The old `DemoLock.PROJECT_READ_FRAMES`, frozen. */
    val PROJECT_READ_FRAMES: Set<String> = setOf(
        Envelope.PROJECT_GIT_STATUS, Envelope.PROJECT_GIT_LOG, Envelope.PROJECT_GIT_DIFF,
        Envelope.PROJECT_GIT_BRANCHES, Envelope.PROJECT_GIT_BRANCH_CHANGES, Envelope.PROJECT_GIT_BRANCH_DIFF,
        Envelope.PROJECT_GIT_WORKTREES, Envelope.PROJECT_GIT_WORKTREE_STATUS,
        Envelope.EPIC_LIST, Envelope.MEMORY_LIST, Envelope.MEMORY_SEARCH, Envelope.MEMORY_GET, Envelope.PIPELINE_SHOW,
    )

    /** The old `DemoLock.ALLOWED_TYPES`: every frame the lock let through in some form. */
    val ALLOWED_TYPES: Set<String> = READ_FRAMES + PROJECT_READ_FRAMES + setOf("replay_request", Envelope.PROMPT, Envelope.RESUME, Envelope.MAIL_SEND)

    /** The demo lock the old `DemoLock(enabled = true, notice = ...)` was: the demo preset's values. */
    fun lock(notice: String = "") = DemoLock(
        enabled = true, notice = notice, projectDir = preset.seats!!.projectDir, projectName = preset.seats!!.projectName,
        seats = DemoLock.parseSeats(preset.seats!!.seats), preset = preset,
    )
}
