// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// :superiorSoftLibrary:infantryHeadless -- HEADLESS-1, the headless guard. TEST-ONLY, JVM.
//
// The owner's requirement (2026-10-20): the Infantry daemon is a server-side app that runs with no UI, so a phone app,
// a local PC or a droplet can all drive the same daemon. `headlessSmoke` proves it end to end, every run:
//  - the daemon runs as its OWN JVM from :infantry's installDist (the artifact a droplet runs), bound to the wildcard
//    address with a bearer token, on temp state and an ephemeral port (never ~/.infantry, never 8787/8788), with a
//    scrubbed environment;
//  - a client in THIS JVM drives it with the WebSocket protocol and the token ALONE: connect, list projects, create a
//    ticket, start it on the stub harness, read the session output (live, then replayed by a second client);
//  - it fails when the daemon loads a UI/Compose class, when a non-loopback bind starts without a token, and when a
//    request without the token is accepted.
//
// WHY A SEPARATE MODULE WITH NO DEPENDENCY ON :infantry: the client compiles against the JDK and
// kotlinx-serialization-json only. If a step needed a daemon class to succeed, a non-JVM client (a phone) could not
// do it either, and this module would not compile. Do NOT add project(":superiorSoftLibrary:infantry"), infantryUiCore
// or infantryUi to these dependencies: the daemon is reached as a launched process, never as a library.
//
// The smoke lives in its own `headlessSmoke` source set, so `test` and `check` never run it. QA runs it as a gate step:
//   ./gradlew :superiorSoftLibrary:infantryHeadless:headlessSmoke
// Evidence per scenario lands in build/headlessSmoke/runs/<scenario>/: daemon.out, daemon.err, classload.log,
// frames-*.jsonl and summary.txt.
import java.time.Duration
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
}

val headlessSmokeSources: SourceSet = sourceSets.create("headlessSmoke")

dependencies {
    "headlessSmokeImplementation"(libs.kotlinx.serialization.json)
    "headlessSmokeImplementation"(libs.kotlin.testJunit)
    "headlessSmokeImplementation"(libs.junit)
}

tasks.register<Test>("headlessSmoke") {
    group = "verification"
    description = "HEADLESS-1: the daemon runs as its own JVM with no UI, and a remote client drives it with the protocol and the token alone."
    testClassesDirs = headlessSmokeSources.output.classesDirs
    classpath = headlessSmokeSources.runtimeClasspath
    useJUnit()
    // The daemon under test is the installed artifact, rebuilt from the current sources on every run.
    dependsOn(":superiorSoftLibrary:infantry:installDist")
    systemProperty(
        "headlessSmoke.install",
        project(":superiorSoftLibrary:infantry").layout.buildDirectory.dir("install/infantry").get().asFile.absolutePath,
    )
    systemProperty("headlessSmoke.reportDir", layout.buildDirectory.dir("headlessSmoke/runs").get().asFile.absolutePath)
    // A smoke is evidence about the tree as it is NOW: never up to date, never served from the build cache.
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
    timeout.set(Duration.ofMinutes(5))
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = TestExceptionFormat.FULL
        showStandardStreams = true
    }
}
