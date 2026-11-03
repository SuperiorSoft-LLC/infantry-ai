// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// PLUGINS-1 example community module. Found by settings.gradle.kts scanning plugins/community; nothing outside this
// folder names it. Builds ONE jar (build/libs/Acme_Hello.jar, as module.json says) holding only this module's
// classes: the app provides the plugin API, Compose and the Kotlin runtime at load time.
plugins {
    kotlin("jvm")
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeMultiplatform)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    compileOnly(project(":superiorSoftLibrary:infantryUiCore"))
    compileOnly(compose.runtime)
    compileOnly(compose.foundation)
    compileOnly(compose.material3)
    compileOnly(libs.kotlinx.coroutines.core)

    // EXAMPLE-1: the guard that module.json's config equals AcmeHelloConfig (and that the factory binds the block).
    testImplementation(project(":superiorSoftLibrary:infantryUiCore"))
    testImplementation(compose.runtime)
    testImplementation(compose.foundation)
    testImplementation(compose.material3)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    archiveFileName.set("Acme_Hello.jar")
}
