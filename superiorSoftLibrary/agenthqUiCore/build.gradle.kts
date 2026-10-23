// Shared AgentHQ client: Compose UI, protocol-facing model, controller and WebSocket client.
// The JVM daemon is intentionally not a dependency; the client wire DTOs live in commonMain.
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    jvmToolchain(21)
    jvm()
    androidLibrary {
        namespace = "com.superiorsoftllc.agenthq.ui.core"
        compileSdk = 36
        minSdk = 24
        // M2-1: Compose resources (the role portraits) need Android resources on the KMP library plugin.
        androidResources { enable = true }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            // M2-1: the 16 role portraits ship as composeResources/drawable PNGs (128px).
            implementation(compose.components.resources)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.websockets)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.cio)
            // PAIR-1: the QR encoder (and, on desktop, the decoder for "Open image").
            implementation(libs.zxing.core)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.cio)
            implementation(libs.androidx.activity.compose)
            // PAIR-1: QR encoder, plus the camera scan: CameraX frames to ML Kit's BUNDLED model (works without Google Play).
            implementation(libs.zxing.core)
            implementation(libs.mlkit.barcode)
            implementation(libs.camerax.camera2)
            implementation(libs.camerax.lifecycle)
            implementation(libs.camerax.view)
        }
    }
}

// M2-1: a stable, public Res class so the desktop shell's tests can name the same portraits.
compose.resources {
    publicResClass = true
    packageOfResClass = "com.superiorsoftllc.agenthq.ui.generated.resources"
}

// The version catalog's WebSockets alias still points at 3.2.3; keep every Ktor artifact aligned.
configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "io.ktor") {
            useVersion(libs.versions.ktor.get())
        }
    }
}
