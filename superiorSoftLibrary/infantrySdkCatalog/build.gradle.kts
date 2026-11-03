// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// SDK-PUB-1: infantry-sdk-catalog, the version catalog a plugin repo imports: the Kotlin/Compose/serialization versions the host
// runs, plus the SDK artifacts (docs/PLUGIN-REPO-DESIGN.md 1.1).
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication

plugins {
    `version-catalog`
}
apply(from = rootProject.file("gradle/sdk-publish.gradle.kts"))

@Suppress("UNCHECKED_CAST")
val sdkArtifacts = extra["sdkArtifacts"] as Map<String, Pair<String, String>>
val sdkGroup = extra["sdkGroup"] as String

catalog {
    versionCatalog {
        version("kotlin", libs.versions.kotlin.get())
        version("composeMultiplatform", libs.versions.composeMultiplatform.get())
        version("kotlinx-serialization", libs.versions.serialization.get())
        version("kotlinx-coroutines", libs.versions.kotlinx.coroutines.get())
        plugin("kotlin-jvm", "org.jetbrains.kotlin.jvm").versionRef("kotlin")
        plugin("kotlin-multiplatform", "org.jetbrains.kotlin.multiplatform").versionRef("kotlin")
        plugin("kotlin-serialization", "org.jetbrains.kotlin.plugin.serialization").versionRef("kotlin")
        plugin("compose-multiplatform", "org.jetbrains.compose").versionRef("composeMultiplatform")
        plugin("compose-compiler", "org.jetbrains.kotlin.plugin.compose").versionRef("kotlin")
        library("kotlinx-serialization-json", "org.jetbrains.kotlinx", "kotlinx-serialization-json").versionRef("kotlinx-serialization")
        library("kotlinx-coroutines-core", "org.jetbrains.kotlinx", "kotlinx-coroutines-core").versionRef("kotlinx-coroutines")
        sdkArtifacts.filterKeys { it != project.name }.values.forEach { (id, v) ->
            version(id, v)
            library(id, sdkGroup, id).versionRef(id)
        }
    }
}

extensions.configure<PublishingExtension>("publishing") {
    publications.create("maven", MavenPublication::class.java) {
        artifactId = "infantry-sdk-catalog"
        from(components["versionCatalog"])
    }
}
