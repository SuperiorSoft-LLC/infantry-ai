// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.perf

import java.io.File

/**
 * PERF-1 acceptance 3: the profile never touches the owner's daemon or `~/.infantry`.
 *
 * Every check throws [IllegalStateException] (a `check`), so a violation aborts the run before or right after the
 * measurement instead of being reported as a number. [ownerHome] is the REAL user home (the profiler JVM's own
 * `user.home`); children run with a temp `-Duser.home`, which [requireChildHome] confirms from the child itself.
 */
class PerfGuard(private val ownerHome: File) {
    /** The owner's state as seen from outside: `~/.infantry/daemon.port` (content + mtime) and who listens on 8787/8788. */
    data class Fingerprint(val portFile: String?, val listeners: Map<Int, Long?>)

    private val ownerState: File get() = File(ownerHome, ".infantry").canonicalFile

    /** A port the rig asks for or the daemon reports it bound: never one of [OWNER_PORTS]. */
    fun requirePort(port: Int, who: String) {
        check(port !in OWNER_PORTS) { "PERF-1: the $who would use port $port, one of the owner's ports $OWNER_PORTS" }
    }

    /** [path] is under the run's temp [root] and nowhere under the owner's `~/.infantry`. */
    fun requireIsolated(path: File, root: File) {
        val p = path.canonicalFile
        check(!p.startsWith(ownerState)) { "PERF-1: $p is inside the owner's state dir $ownerState" }
        check(p.startsWith(root.canonicalFile)) { "PERF-1: $p is outside the run's temp root ${root.canonicalFile}" }
    }

    /** The `user.home` a child JVM REPORTS (from `jcmd VM.system_properties`) is the temp home it was given. */
    fun requireChildHome(who: String, reported: String?, expected: File) {
        check(reported != null) { "PERF-1: could not read the $who JVM's user.home; refusing to trust its isolation" }
        check(File(reported).canonicalFile == expected.canonicalFile) {
            "PERF-1: the $who JVM runs with user.home=$reported, not the temp home $expected"
        }
    }

    /** [listeners]: owner port -> listening pid (null = nobody), measured by the caller. */
    fun ownerFingerprint(listeners: Map<Int, Long?>): Fingerprint {
        val portFile = File(ownerState, "daemon.port")
        val stamp = if (portFile.isFile) "${portFile.readText().trim()}@${portFile.lastModified()}" else null
        return Fingerprint(stamp, listeners)
    }

    /** Every way the owner's state differs between [before] and [after]; empty = untouched. */
    fun diff(before: Fingerprint, after: Fingerprint): List<String> = buildList {
        if (before.portFile != after.portFile) add("~/.infantry/daemon.port changed: ${before.portFile} -> ${after.portFile}")
        (before.listeners.keys + after.listeners.keys).sorted().forEach { port ->
            val b = before.listeners[port]
            val a = after.listeners[port]
            if (b != a) add("the listener on owner port $port changed: pid $b -> pid $a")
        }
    }

    companion object {
        val OWNER_PORTS = setOf(8787, 8788)
    }
}
