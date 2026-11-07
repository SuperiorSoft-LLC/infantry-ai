// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The template knows only Maven coordinates: the SDK and the Infantry Gradle plugin come from Maven Central (no token).
// -Pinfantry.sdkRepo=<path> puts a local repository first (the monorepo's CI uses its dry-run publish, build/m2).
// Optional fallback: -Pinfantry.sdk.githubPackages=true also reads GitHub Packages, which needs a token with read:packages
// (GITHUB_ACTOR / GITHUB_TOKEN, or gpr.user / gpr.key); use it only for an SDK release that is not on Central yet.
val sdkVersion: String = providers.gradleProperty("infantry.sdk.version").get()
val sdkRepo: String? = providers.gradleProperty("infantry.sdkRepo").orNull
val sdkGitHubPackages: Boolean = providers.gradleProperty("infantry.sdk.githubPackages").orNull == "true"

fun RepositoryHandler.infantrySdk() {
    if (sdkRepo != null) maven { name = "infantrySdkLocal"; url = file(sdkRepo).toURI() }
    mavenCentral()
    if (sdkGitHubPackages) {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/SuperiorSoft-LLC/infantry-ai")
            credentials {
                username = providers.environmentVariable("GITHUB_ACTOR").orNull ?: providers.gradleProperty("gpr.user").orNull
                password = providers.environmentVariable("GITHUB_TOKEN").orNull ?: providers.gradleProperty("gpr.key").orNull
            }
        }
    }
}

pluginManagement {
    val sdkRepoPath: String? = providers.gradleProperty("infantry.sdkRepo").orNull
    val useGitHubPackages = providers.gradleProperty("infantry.sdk.githubPackages").orNull == "true"
    repositories {
        if (sdkRepoPath != null) maven { name = "infantrySdkLocal"; url = file(sdkRepoPath).toURI() }
        mavenCentral()
        gradlePluginPortal()
        google()
        // Optional fallback, see the top of this file.
        if (useGitHubPackages) {
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/SuperiorSoft-LLC/infantry-ai")
                credentials {
                    username = providers.environmentVariable("GITHUB_ACTOR").orNull ?: providers.gradleProperty("gpr.user").orNull
                    password = providers.environmentVariable("GITHUB_TOKEN").orNull ?: providers.gradleProperty("gpr.key").orNull
                }
            }
        }
    }
}

dependencyResolutionManagement {
    repositories {
        infantrySdk()
        google()
    }
    versionCatalogs {
        create("infantry") { from("tech.superiorsoftllc.infantry:infantry-sdk-catalog:$sdkVersion") }
    }
}

// The folder name is the plugin's Gradle identity; the Infantry Gradle plugin looks for ":daemon-plugin-<folder>" and
// ":harness-plugin-<folder>", so a rename of the folder needs no edit here.
rootProject.name = rootDir.name
if (file("daemon/build.gradle.kts").isFile) {
    include(":daemon-plugin-${rootDir.name}")
    project(":daemon-plugin-${rootDir.name}").projectDir = file("daemon")
}
// harness/ is omitted from this template; see README.md ("Add a harness half").
if (file("harness/build.gradle.kts").isFile) {
    include(":harness-plugin-${rootDir.name}")
    project(":harness-plugin-${rootDir.name}").projectDir = file("harness")
}
