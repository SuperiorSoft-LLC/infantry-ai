// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.session

import java.io.File

/**
 * PROGRESS-GRADLE-1: the Gradle "progress adapter". An init script that, only when INFANTRY_PROGRESS=1, prints
 * `##infantry plan <total>` once the task graph is known and `##infantry count <done> <total>` + `##infantry status <task>`
 * after every task (up-to-date and skipped tasks count too). Without the env var it prints nothing, so the owner's own runs are unchanged.
 */
object GradleProgressInit {
    const val ENV = "INFANTRY_PROGRESS"
    const val FILE_NAME = "infantry-progress.init.gradle"

    val script: String = """
        // Installed by Infantry AI (PROGRESS-GRADLE-1). Silent unless INFANTRY_PROGRESS=1.
        if (System.getenv('INFANTRY_PROGRESS') == '1' && gradle.parent == null) {
            def infTotal = 0
            def infDone = 0
            gradle.taskGraph.whenReady { g ->
                infTotal = g.allTasks.size()
                println "##infantry plan " + infTotal
            }
            gradle.taskGraph.afterTask { t ->
                infDone++
                println "##infantry count " + infDone + " " + infTotal
                println "##infantry status " + infDone + "/" + infTotal + " tasks - " + t.path
            }
        }
    """.trimIndent() + "\n"

    /** Writes the script into `<gradleUserHome>/init.d` when missing or different. Never throws. */
    fun install(gradleUserHome: File = defaultHome()): Boolean = runCatching {
        val f = File(gradleUserHome, "init.d/$FILE_NAME")
        if (f.isFile && f.readText() == script) return@runCatching false
        f.parentFile.mkdirs(); f.writeText(script); true
    }.getOrDefault(false)

    private fun defaultHome(): File =
        System.getenv("GRADLE_USER_HOME")?.takeIf { it.isNotBlank() }?.let(::File) ?: File(System.getProperty("user.home"), ".gradle")
}
