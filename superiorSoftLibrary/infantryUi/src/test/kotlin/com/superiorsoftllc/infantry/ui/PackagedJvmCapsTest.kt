package com.superiorsoftllc.infantry.ui

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * PERF-2: the installed app (jpackage launcher, which also runs the daemon in-process) must not start on the JVM's
 * server-sized heap defaults (MaxHeap 1/4 of RAM, InitialHeap 1/64; PERF-1 measured 8 GB / 504 MB for a 10-16 MB live
 * heap). infantryUi/build.gradle.kts hands the test JVM the exact `compose.desktop.application.jvmArgs` list that
 * jpackage writes into the launcher's `java-options`, as `infantry.packagedJvmArgs` ('|'-separated).
 */
class PackagedJvmCapsTest {
    private val packaged: List<String> =
        System.getProperty("infantry.packagedJvmArgs")?.split('|')?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: fail("infantry.packagedJvmArgs is not set: run through Gradle (:superiorSoftLibrary:infantryUi:test)")

    @Test
    fun packagedLauncherCapsTheHeap() {
        assertTrue("-Xmx512m" in packaged, "packaged jvmArgs have no -Xmx512m cap: $packaged")
        assertTrue("-Xms32m" in packaged, "packaged jvmArgs have no -Xms32m initial heap: $packaged")
    }

    @Test
    fun packagedLauncherKeepsTheDefaultG1() {
        // Serial GC read +12-17% UI startup in PERF-2's profile runs (limit 10%): caps only, G1 stays (infantry/jvm-caps.txt).
        assertTrue(packaged.none { it.startsWith("-XX:+Use") && it.endsWith("GC") }, "packaged jvmArgs pick a non-default GC: $packaged")
    }

    @Test
    fun packagedLauncherKeepsTheVersionProperty() {
        assertTrue(packaged.any { it.startsWith("-Dinfantry.version=") }, "UPDATE-1's -Dinfantry.version is gone: $packaged")
    }

    @Test
    fun packagedLauncherCarriesTheBuildStamp() {
        // BUILD-STAMP-1: Configuration > About, the footer and the in-process daemon's `ready` build= all read it.
        assertTrue(packaged.any { it.startsWith("-Dinfantry.build=") }, "BUILD-STAMP-1's -Dinfantry.build is missing: $packaged")
    }
}
