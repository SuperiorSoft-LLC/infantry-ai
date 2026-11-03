// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// MD-PLUGIN-1: the example markdown engine plugin. A plain Kotlin/JVM module that is NOT part of the app: it is built
// to ONE jar the app loads from <state dir>/markdown-engines/. Copy this module to write your own engine (README.md).
plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // The engine interface and ChatBlock only. The host provides them (and the Kotlin runtime) at load time, so the
    // jar must NOT bundle them.
    compileOnly(project(":superiorSoftLibrary:infantryUiCore"))
}

// ./gradlew :superiorSoftLibrary:samples:markdownEnginePlugin:pluginJar  ->  build/libs/markdown-engine-example.jar
tasks.register<Jar>("pluginJar") {
    group = "build"
    description = "Builds the example markdown engine plugin jar (classes + META-INF/services only)."
    archiveFileName.set("markdown-engine-example.jar")
    destinationDirectory.set(layout.buildDirectory.dir("libs"))
    from(sourceSets.main.get().output)
}
