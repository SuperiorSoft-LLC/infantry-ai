// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// CARD-2 example community module: one chat card renderer (`card:hello`) in the `chat.cards` registry. Found by
// settings.gradle.kts scanning plugins/community; off until plugins/modules.json enables it. Builds ONE jar
// (build/libs/Acme_HelloCard.jar): the app provides the plugin API, Compose and the Kotlin runtime at load time.
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
    compileOnly(libs.kotlinx.serialization.json)

    testImplementation(project(":superiorSoftLibrary:infantryUiCore"))
    testImplementation(compose.runtime)
    testImplementation(compose.foundation)
    testImplementation(compose.material3)
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    archiveFileName.set("Acme_HelloCard.jar")
}
