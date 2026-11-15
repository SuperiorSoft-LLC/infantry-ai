// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.update

import java.io.File

/**
 * ENDPOINT-ENV-1: the build environment this process runs in, a plain name (`dev`, `staging`, `prod`, or any other). Core knows
 * it only as a string, never what it points at: plugins map it to addresses in their module.json (`daemon.endpoints`).
 *
 * Gradle bakes it in (root `gradle/build-stamp.gradle.kts`, the `infantryEnv` property or `INFANTRY_ENV` at build time) and
 * hands it over as `-Dinfantry.env` (desktop launcher, `installDist`'s scripts, `runDaemon`); the headless archive also carries
 * it as an `ENVIRONMENT` file next to `BUILD`. A developer may override it at run time with the `INFANTRY_ENV` variable.
 */
object BuildEnvironment {
    const val VARIABLE = "INFANTRY_ENV"
    const val PROPERTY = "infantry.env"
    const val FILE = "ENVIRONMENT"
    /** What a process with no environment at all runs as: the real service, so a build that lost its stamp still works. */
    const val FALLBACK = "prod"

    private val NAME = Regex("^[a-z0-9][a-z0-9._-]{0,31}$")

    /** The first valid name of: [variable] (run time), [property] (baked), the `ENVIRONMENT` file in [appHome], else [FALLBACK]. */
    fun resolve(variable: String?, property: String?, appHome: File?): String =
        listOf(
            { variable },
            { property },
            { appHome?.let { runCatching { File(it, FILE).readText() }.getOrNull() } },
        ).firstNotNullOfOrNull { it()?.trim()?.lowercase()?.takeIf { s -> NAME.matches(s) } } ?: FALLBACK

    /** This JVM's environment. */
    val current: String by lazy {
        resolve(
            com.superiorsoftllc.infantry.session.InfantryEnv.get(VARIABLE),
            System.getProperty(PROPERTY),
            com.superiorsoftllc.infantry.session.InfantryEnv.get("INFANTRY_APP_HOME")?.let(::File)?.takeIf { it.isDirectory },
        )
    }
}
