// The template knows only Maven coordinates: the SDK comes from GitHub Packages (needs a token with read:packages), or from a
// local repository when you pass -Pinfantry.sdkRepo=<path> (the monorepo's CI uses its dry-run publish, build/m2).
val sdkVersion: String = providers.gradleProperty("infantry.sdk.version").get()
val sdkRepo: String? = providers.gradleProperty("infantry.sdkRepo").orNull

fun RepositoryHandler.infantrySdk() {
    if (sdkRepo != null) maven { name = "infantrySdkLocal"; url = file(sdkRepo).toURI() }
    maven {
        name = "GitHubPackages"
        url = uri("https://maven.pkg.github.com/SuperiorSoft-LLC/infantry-ai")
        credentials {
            username = providers.environmentVariable("GITHUB_ACTOR").orNull ?: providers.gradleProperty("gpr.user").orNull
            password = providers.environmentVariable("GITHUB_TOKEN").orNull ?: providers.gradleProperty("gpr.key").orNull
        }
    }
}

pluginManagement {
    val sdkRepoPath: String? = providers.gradleProperty("infantry.sdkRepo").orNull
    repositories {
        if (sdkRepoPath != null) maven { name = "infantrySdkLocal"; url = file(sdkRepoPath).toURI() }
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/SuperiorSoft-LLC/infantry-ai")
            credentials {
                username = providers.environmentVariable("GITHUB_ACTOR").orNull ?: providers.gradleProperty("gpr.user").orNull
                password = providers.environmentVariable("GITHUB_TOKEN").orNull ?: providers.gradleProperty("gpr.key").orNull
            }
        }
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        infantrySdk()
        mavenCentral()
        google()
    }
    versionCatalogs {
        create("infantry") { from("com.superiorsoftllc.infantry:infantry-sdk-catalog:$sdkVersion") }
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
