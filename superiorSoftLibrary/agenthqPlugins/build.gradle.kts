// PLUGINS-1: the plugin contract (package plugin.api) and the host's pure loading rules (package plugin.host).
// No Compose and no app code, so the desktop UI, the web/Android clients and the headless daemon all share it, and the
// API can carry its own license later. One-way: the app and the modules depend on this; this depends on neither.
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    jvmToolchain(21)
    jvm()
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs { browser() }
    androidLibrary {
        namespace = "com.superiorsoftllc.agenthq.plugin"
        compileSdk = 36
        minSdk = 24
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
