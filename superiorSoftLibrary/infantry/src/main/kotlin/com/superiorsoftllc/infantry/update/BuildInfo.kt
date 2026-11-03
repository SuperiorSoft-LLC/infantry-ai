// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.update

import java.io.File

/**
 * BUILD-STAMP-1: this process's build string, e.g. `1.0.1+20261023.1432.e85b223` (`.dirty` appended for a build from a
 * changed working tree). Gradle writes it (root `gradle/build-stamp.gradle.kts`) and hands it over as `-Dinfantry.build`:
 * the packaged desktop launcher, `installDist`'s `infantry` scripts, `runDaemon` and `runRealDaemon`. The headless server
 * archive also carries it as a `BUILD` file next to `VERSION`.
 *
 * The daemon sends it in `ready` (`build=<string>`), `infantry version` prints it, the app shows it.
 */
object BuildInfo {
    const val PROPERTY = "infantry.build"
    const val FILE = "BUILD"

    /** `<version>+<UTC yyyyMMdd>.<UTC HHmm>.<short sha>[.dirty]`. */
    val PATTERN = Regex("""^(\d+\.\d+\.\d+)\+(\d{8})\.(\d{4})\.([0-9a-f]{4,40}|unknown)(\.dirty)?$""")

    /**
     * The first of: [property] (`-Dinfantry.build`), the `BUILD` file in [appHome], [version] (`-Dinfantry.version`), "dev".
     * Whitespace is never part of it (it travels as one `key=value` token of `ready`).
     */
    fun resolve(property: String?, appHome: File?, version: String?): String =
        listOf(
            { property },
            { appHome?.let { runCatching { File(it, FILE).readText() }.getOrNull() } },
            { version },
        ).firstNotNullOfOrNull { it()?.trim()?.replace(Regex("""\s+"""), "")?.takeIf { s -> s.isNotEmpty() } } ?: "dev"

    /** This JVM's build string. */
    val current: String by lazy {
        resolve(
            System.getProperty(PROPERTY),
            com.superiorsoftllc.infantry.session.InfantryEnv.get("INFANTRY_APP_HOME")?.let(::File)?.takeIf { it.isDirectory },
            System.getProperty(UpdateSettings.VERSION_PROPERTY),
        )
    }
}
