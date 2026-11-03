package com.superiorsoftllc.infantry.harness

import com.superiorsoftllc.infantry.runner.ProcessSpec
import java.util.concurrent.ConcurrentHashMap

/**
 * HARNESS H6c: a generic seam (it replaces the pi-only `PiLaunchExtras`): extra argv the DAEMON adds when a persistent session of
 * harness [id] starts, decided from the working directory and the session env. Empty by default, so a start is exactly the plugin's
 * unless something is registered (the pi tool bridge experiment registers one). It is NOT part of the restart fingerprint, so changing
 * what it returns never restarts a running session. A contributor that throws costs only its own extras, never the start.
 */
object HarnessLaunchExtras {
    private val contributors = ConcurrentHashMap<String, MutableList<(java.io.File?, Map<String, String>) -> List<String>>>()

    fun add(harnessId: String, contributor: (workingDir: java.io.File?, env: Map<String, String>) -> List<String>) {
        contributors.computeIfAbsent(harnessId) { java.util.concurrent.CopyOnWriteArrayList() } += contributor
    }

    /** Test seam: forget every registered contributor of [harnessId]. */
    fun clear(harnessId: String) { contributors.remove(harnessId) }

    fun extras(harnessId: String, workingDir: java.io.File?, env: Map<String, String>): List<String> =
        contributors[harnessId].orEmpty().flatMap { c ->
            runCatching { c(workingDir, env) }
                .onFailure { System.err.println("[infantry] $harnessId launch extra skipped: ${it.message}") }
                .getOrDefault(emptyList())
        }

    fun apply(harnessId: String, spec: ProcessSpec, env: Map<String, String>): ProcessSpec {
        val extra = extras(harnessId, spec.workingDir, env)
        return if (extra.isEmpty()) spec else spec.copy(command = spec.command + extra)
    }
}
