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

    fun all(workingDir: File?, env: Map<String, String>): List<String> = builtIn(workingDir, env) + args(workingDir, env)
}
