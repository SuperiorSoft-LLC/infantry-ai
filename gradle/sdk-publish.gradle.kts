// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// SDK-PUB-1 (docs/PLUGIN-REPO-DESIGN.md section 1): shared publishing setup for the plugin SDK modules. Applied with
// `apply(from = rootProject.file("gradle/sdk-publish.gradle.kts"))` AFTER the module's own plugins block.
// Versions are READ from the code constants (never typed here). SDK-CENTRAL-1: the rest of the POM (url, licence from
// scripts/license-paths.txt, developers, scm), the sources/javadoc jars, signing and the `centralBundle` repository come from
// gradle/sdk-central.gradle.kts, applied below.
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository

apply(plugin = "maven-publish")
apply(from = rootProject.file("gradle/sdk-central.gradle.kts"))

val sdkGroup = "com.superiorsoftllc.infantry"

fun constant(path: String, regex: String): String {
    val text = rootProject.file(path).readText()
    return Regex(regex).find(text)?.groupValues?.get(1) ?: error("SDK-PUB-1: no match for $regex in $path")
}

val appVersion = rootProject.property("infantryVersion").toString()
val pluginApiMajor = constant(
    "superiorSoftLibrary/infantryPlugins/src/commonMain/kotlin/com/superiorsoftllc/infantry/plugin/api/ModuleManifest.kt",
    "const val PLUGIN_API_VERSION\\s*=\\s*(\\d+)",
)
val pluginApiMinorPatch = (rootProject.findProperty("infantryPluginApiMinorPatch") ?: "0.0").toString()
val daemonApiVersion = constant(
    "superiorSoftLibrary/infantryDaemonApi/src/main/kotlin/com/superiorsoftllc/infantry/daemon/api/DaemonApi.kt",
    "const val VERSION\\s*=\\s*\"([^\"]+)\"",
)
val harnessApiVersion = constant(
    "superiorSoftLibrary/infantryHarnessApi/src/main/kotlin/com/superiorsoftllc/infantry/harness/api/HarnessApi.kt",
    "const val HARNESS_API_VERSION\\s*(?::\\s*String)?\\s*=\\s*\"([^\"]+)\"",
)

// project name -> (artifactId, version). Read by the BOM and the catalog through `extra["sdkArtifacts"]`.
val sdkArtifacts: Map<String, Pair<String, String>> = mapOf(
    "infantryPlugins" to ("infantry-plugin-api" to "$pluginApiMajor.$pluginApiMinorPatch"),
    "infantryProtocol" to ("infantry-protocol" to appVersion),
    "infantryDaemonApi" to ("infantry-daemon-api" to daemonApiVersion),
    "infantryHarnessApi" to ("infantry-harness-api" to harnessApiVersion),
    "infantryUiCore" to ("infantry-ui-core" to appVersion),
    "infantrySdkBom" to ("infantry-sdk-bom" to appVersion),
    "infantrySdkCatalog" to ("infantry-sdk-catalog" to appVersion),
)
extra["sdkGroup"] = sdkGroup
extra["sdkArtifacts"] = sdkArtifacts

val (artifactBase, artifactVersion) = sdkArtifacts[project.name] ?: error("SDK-PUB-1: ${project.name} is not an SDK module")
group = sdkGroup
version = artifactVersion

val isKmp = pluginManager.hasPlugin("org.jetbrains.kotlin.multiplatform")
val isJvmOnly = pluginManager.hasPlugin("org.jetbrains.kotlin.jvm")
val uiCore = project.name == "infantryUiCore"

extensions.configure<PublishingExtension>("publishing") {
    if (isJvmOnly) {
        publications.create("maven", MavenPublication::class.java) {
            artifactId = artifactBase
            from(components["java"])
        }
    }
    publications.withType(MavenPublication::class.java).configureEach {
        pom {
            name.set(artifactBase)
            description.set(
                if (uiCore) "Infantry AI desktop UI core: unstable surface, no compatibility promise."
                else "Infantry AI plugin SDK: $artifactBase.",
            )
        }
    }
    repositories {
        maven {
            name = "dryRun"
            url = uri(rootProject.layout.buildDirectory.dir("m2"))
        }
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/SuperiorSoft-LLC/infantry-ai")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}

// The Kotlin plugin names KMP publications and their artifactIds late, so rename and prune after evaluation.
afterEvaluate {
    extensions.configure<PublishingExtension>("publishing") {
        if (isKmp) {
            publications.withType(MavenPublication::class.java).configureEach {
                // KMP names its publications <project>, <project>-jvm, <project>-android, <project>-wasm-js.
                artifactId = if (uiCore) artifactBase else artifactId.replaceFirst(project.name, artifactBase, ignoreCase = true)
            }
        }
    }
}

if (uiCore) {
    // Desktop plugins only: no root KMP module, no android/wasm publications. Dropping the dependencies keeps their
    // (metadata, android, wasm) compile tasks out of the publish run.
    gradle.projectsEvaluated {
        tasks.matching { it.name.startsWith("publishAllPublicationsTo") }.forEach { agg ->
            val repo = agg.name.removePrefix("publishAllPublicationsTo")
            agg.setDependsOn(listOf("publishJvmPublicationTo$repo"))
        }
    }
}
