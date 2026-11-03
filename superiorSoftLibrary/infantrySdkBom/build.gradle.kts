// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// SDK-PUB-1: infantry-sdk-bom, one compatible set of the published SDK artifacts (docs/PLUGIN-REPO-DESIGN.md 1.1).
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication

plugins {
    `java-platform`
}
apply(from = rootProject.file("gradle/sdk-publish.gradle.kts"))

@Suppress("UNCHECKED_CAST")
val sdkArtifacts = extra["sdkArtifacts"] as Map<String, Pair<String, String>>
val sdkGroup = extra["sdkGroup"] as String

dependencies {
    constraints {
        sdkArtifacts.filterKeys { it != project.name && it != "infantrySdkCatalog" }.values.forEach { (id, v) ->
            api("$sdkGroup:$id:$v")
        }
    }
}

extensions.configure<PublishingExtension>("publishing") {
    publications.create("maven", MavenPublication::class.java) {
        artifactId = "infantry-sdk-bom"
        from(components["javaPlatform"])
    }
}
