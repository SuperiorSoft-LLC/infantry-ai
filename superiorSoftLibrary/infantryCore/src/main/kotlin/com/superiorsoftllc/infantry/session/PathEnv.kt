// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.session

import java.io.File

/**
 * ROLE-NOTE-READ-1: a session's env is several maps laid over each other (the daemon's child env, the launch-env seam, the
 * run's identity). Two of them may each set PATH (or one `Path` and one `PATH` on Windows), and the last one used to replace
 * the first whole, so the directory holding the `infantry` launcher (the daemon's `INFANTRY_BIN`) fell off the session's PATH
 * while `INFANTRY_BIN` itself stayed set. [withBinOnPath] puts that directory back, first, whatever the maps did.
 */
object PathEnv {
    /** The env name the daemon exports the CLI's launcher directory under (see InfantryBin.BIN_ENV). */
    const val BIN_ENV = "INFANTRY_BIN"

    /**
     * [env] with the directory named by its [BIN_ENV] first on PATH. The PATH entry keeps the key spelling it already has
     * (`Path` / `PATH`); duplicate spellings collapse to one, the last winning. A missing PATH starts from [inheritedPath].
     * [env] is returned as it is when it names no directory.
     */
    fun withBinOnPath(
        env: Map<String, String>,
        inheritedPath: String? = System.getenv("PATH") ?: System.getenv("Path"),
        separator: String = File.pathSeparator,
    ): Map<String, String> {
        val bin = env[BIN_ENV]?.trim()?.takeIf { it.isNotEmpty() }?.let { File(it).let { f -> if (f.isFile) f.parent else it } } ?: return env
        val pathKeys = env.keys.filter { it.equals("PATH", ignoreCase = true) }
        val key = pathKeys.lastOrNull() ?: "PATH"
        val current = pathKeys.lastOrNull()?.let { env[it] } ?: inheritedPath.orEmpty()
        val entries = current.split(separator).filter { it.isNotBlank() }
        val merged = if (entries.firstOrNull() == bin) current else (listOf(bin) + entries.filter { it != bin }).joinToString(separator)
        return env.filterKeys { !it.equals("PATH", ignoreCase = true) } + (key to merged)
    }
}
