package com.superiorsoftllc.agenthq.harness.pi

import java.io.File

/**
 * A generic seam: extra pi argv decided at session START, from the working directory and the session env. Empty by default,
 * so a pi start is exactly [PiCommand]'s unless something is registered. It is NOT part of the restart fingerprint, so
 * changing what it returns never restarts a running session. The pi tool bridge experiment registers [args]; the
 * always-on daemon extras (TODO-PROGRESS-1: the `todowrite` extension) register [builtIn]. A start gets [all] of them.
 */
object PiLaunchExtras {
    @Volatile var args: (workingDir: File?, env: Map<String, String>) -> List<String> = { _, _ -> emptyList() }

    /** Always-on extras the daemon installs at startup (independent of [args], so neither overwrites the other). */
    @Volatile var builtIn: (workingDir: File?, env: Map<String, String>) -> List<String> = { _, _ -> emptyList() }

    /**
     * STALL-3: a contributor that throws (a registration whose store is gone, e.g. a stopped daemon's closed database)
     * costs only its own extras, never the pi start.
     */
    fun all(workingDir: File?, env: Map<String, String>): List<String> =
        listOf(builtIn, args).flatMap { c ->
            runCatching { c(workingDir, env) }
                .onFailure { System.err.println("[agentHq] pi launch extra skipped: ${it.message}") }
                .getOrDefault(emptyList())
        }
}
