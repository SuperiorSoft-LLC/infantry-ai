// MOBILE-ANDROID-1: thin Android application shell around agenthqUiCore's AgentHqActivity.
// No Kotlin sources here: the activity, host and UI all live in agenthqUiCore (androidMain + commonMain).
import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    // MOBILE-CONNECT-1: Kotlin for the JVM unit tests under src/test (android.builtInKotlin=false in this build).
    id("org.jetbrains.kotlin.android")
}

// RELEASE-ANDROID-1: release signing secrets are NOT tracked (same resolver as SEC-SECRETS-1 in
// webTvClient/build.gradle.kts). Resolution order per key:
//   1. <repo root>/secrets.local.properties (gitignored)
//   2. environment variable of the same name
//   3. Gradle properties (-P, ~/.gradle/gradle.properties)
// When any key is missing or the store file does not exist, no release signingConfig is created and
// assembleRelease/bundleRelease still succeed, unsigned. Only key NAMES are ever logged.
val localSecrets = Properties().apply {
    val f = rootDir.resolve("secrets.local.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}
fun resolveSecret(name: String): String? =
    localSecrets.getProperty(name)?.takeIf { it.isNotBlank() }
        ?: System.getenv(name.replace('.', '_').uppercase())?.takeIf { it.isNotBlank() }
        ?: providers.gradleProperty(name).orNull?.takeIf { it.isNotBlank() }

val releaseSigningKeys = listOf(
    "AGENTHQ_ANDROID_RELEASE_STORE_FILE",
    "AGENTHQ_ANDROID_RELEASE_STORE_PASSWORD",
    "AGENTHQ_ANDROID_RELEASE_KEY_ALIAS",
    "AGENTHQ_ANDROID_RELEASE_KEY_PASSWORD",
)
val releaseSigningValues: Map<String, String?> = releaseSigningKeys.associateWith { resolveSecret(it) }
val releaseSigningMissing: List<String> = releaseSigningKeys.filter { releaseSigningValues[it] == null }
val releaseStoreFile: File? = releaseSigningValues[releaseSigningKeys[0]]?.let { rootProject.file(it) }
val releaseSigningResolved: Boolean = releaseSigningMissing.isEmpty() && releaseStoreFile?.isFile == true
if (!releaseSigningResolved) {
    logger.lifecycle(
        "[agentHqAndroid] Release signing NOT configured — " +
            (if (releaseSigningMissing.isNotEmpty()) "missing key(s): ${releaseSigningMissing.joinToString()}"
            else "keystore file not found") +
            ". assembleRelease/bundleRelease will produce UNSIGNED output."
    )
}

// RELEASE-ANDROID-1: launch version. -PagenthqVersion is the same knob agentHqUi uses for the desktop package.
val agentHqVersionName: String =
    providers.gradleProperty("agenthqVersion").orNull?.trim()?.takeIf { it.isNotEmpty() } ?: "1.0.0"
val agentHqVersionCode: Int =
    providers.gradleProperty("agenthqVersionCode").orNull?.trim()?.toIntOrNull()
        // RELEASE-AUTO-1: monotonic code derived from MAJOR.MINOR.BUILD, so a bumped version always upgrades.
        ?: agentHqVersionName.split(".").map { it.toIntOrNull() ?: 0 }.let { p -> p.getOrElse(0) { 0 } * 1_000_000 + p.getOrElse(1) { 0 } * 10_000 + p.getOrElse(2) { 0 } }

configure<com.android.build.api.dsl.ApplicationExtension> {
    namespace = "com.superiorsoftllc.agenthq.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.superiorsoftllc.agenthq.android"
        minSdk = 24
        targetSdk = 36
        versionCode = agentHqVersionCode
        // BUILD-STAMP-1: the whole build string (1.0.1+20261023.1432.e85b223), so Android's app info and the app's own
        // footer and Configuration > About name the exact build. versionCode above stays derived from the plain version.
        versionName = rootProject.extra["agenthqBuild"] as String
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // ANDROID-MINIFY-1: instrumented tests run against minifiedSmoke, i.e. the release R8 configuration.
    testBuildType = "minifiedSmoke"

    signingConfigs {
        // Created only when every key resolved and the store file exists; otherwise release stays unsigned.
        if (releaseSigningResolved) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = releaseSigningValues.getValue("AGENTHQ_ANDROID_RELEASE_STORE_PASSWORD")
                keyAlias = releaseSigningValues.getValue("AGENTHQ_ANDROID_RELEASE_KEY_ALIAS")
                keyPassword = releaseSigningValues.getValue("AGENTHQ_ANDROID_RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            // ANDROID-MINIFY-1: R8 + resource shrinking (unsigned APK 10,612,919 -> ~2.77 MB). Serialization keep
            // rules come from kotlinx-serialization's own consumer rules; see proguard-rules.pro. Guarded at runtime
            // by src/androidTest MinifiedSmokeTest on the minifiedSmoke build type below.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
        // ANDROID-MINIFY-1: the release build (same minify/shrink/keep rules) but debug-signed, so the device smoke
        // (src/androidTest MinifiedSmokeTest) can install it next to its test APK. Never shipped.
        create("minifiedSmoke") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
            // Smoke-only keeps for what the androidx.test runner borrows from the app (see the file); never release.
            proguardFile("proguard-smoke-rules.pro")
            testProguardFiles("proguard-test-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

// ANDROID-MINIFY-1: AGP 9 creates host (unit) tests only for testBuildType, which is now minifiedSmoke for the device
// smoke. Keep the JVM unit tests on debug (testDebugUnitTest), not on the minified smoke variant.
extensions.configure<com.android.build.api.variant.ApplicationAndroidComponentsExtension> {
    beforeVariants { variant ->
        variant.hostTests[com.android.build.api.variant.HostTestBuilder.UNIT_TEST_TYPE]?.enable =
            variant.buildType == "debug"
    }
}

// RELEASE-ANDROID-1: reports whether release signing resolved and asserts the report matches the real
// wiring. Prints key NAMES only, never a value.
tasks.register("printReleaseSigningResolved") {
    group = "verification"
    description = "Prints whether the release signing config resolved (true/false) and missing key names. Never prints values."
    val resolved = releaseSigningResolved
    val missing = releaseSigningMissing
    val androidExt = project.extensions.getByType<com.android.build.api.dsl.ApplicationExtension>()
    val configPresent = androidExt.signingConfigs.findByName("release") != null
    val releaseTypeSigned = androidExt.buildTypes.getByName("release").signingConfig != null
    doLast {
        println("releaseSigningResolved=$resolved")
        println("releaseSigningMissingKeys=${missing.joinToString(",").ifEmpty { "none" }}")
        check(resolved == configPresent) { "release signingConfig present=$configPresent but resolved=$resolved" }
        check(resolved == releaseTypeSigned) { "release buildType signed=$releaseTypeSigned but resolved=$resolved" }
        check(!resolved || missing.isEmpty()) { "resolved=$resolved but missing=$missing" }
    }
}

dependencies {
    implementation(project(":superiorSoftLibrary:agenthqUiCore"))
    // S4: the app UI, compiled in; the shell finds its modules through ServiceLoader (META-INF/services in its jar).
    implementation(project(":superiorSoftLibrary:agenthqAppUi"))
    testImplementation(libs.kotlin.testJunit)
    testImplementation(libs.junit)
    // MOBILE-PROBE-TEST-1: a real in-test daemon for probeDaemon (MockEngine has no WebSocket capability).
    testImplementation("io.ktor:ktor-server-cio:${libs.versions.ktor.get()}")
    testImplementation("io.ktor:ktor-server-websockets:${libs.versions.ktor.get()}")
    // ANDROID-MINIFY-1: device smoke on the minified build.
    androidTestImplementation(libs.androidx.runner)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.kotlinx.serialization.json)
}

kotlin { jvmToolchain(21) }

// PACKAGING-GUARD-1: branch/sha print, dirty/branch refusal, BUILD-INFO and sha-named outputs (see the script).
apply(from = rootProject.file("gradle/packaging-guard.gradle.kts"))
