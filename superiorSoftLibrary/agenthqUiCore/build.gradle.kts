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
    // WEB-1: the browser client (agentHqWeb) runs this same UI as WebAssembly.
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs { browser() }
    androidLibrary {
        namespace = "com.superiorsoftllc.agenthq.ui.core"
        compileSdk = 36
        minSdk = 24
        // M2-1: Compose resources (the role portraits) need Android resources on the KMP library plugin.
        androidResources { enable = true }
    }

    sourceSets {
        // S4: the core modules (plugins/core) are compiled into :agenthqAppUi, the app UI, not into this shell.
        commonMain.dependencies {
            // PLUGINS-1: the plugin API and its loading rules (one-way: the API knows nothing of this UI).
            api(project(":superiorSoftLibrary:agenthqPlugins"))
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
        // S4: the loader, factory and shell-tree tests run against the real core modules, which live in the app UI.
        // Test-only: the shell's main code never sees :agenthqAppUi (it depends on this module, not the reverse).
        jvmTest.dependencies {
            implementation(project(":superiorSoftLibrary:agenthqAppUi"))
            // RUNTIME-4: the official Infantry_Agents (the left panel's agents section) is no longer in the desktop list.
            implementation(project(":plugins:official:Infantry_Agents"))
            // RUNTIME-5: and the official Infantry_Settings (the Settings host tab).
            implementation(project(":plugins:official:Infantry_Settings"))
            // RUNTIME-6: and the official Infantry_Git (the Git tab and its tree).
            implementation(project(":plugins:official:Infantry_Git"))
            // RUNTIME-7: and the official Infantry_Run (the Run tab and its tree).
            implementation(project(":plugins:official:Infantry_Run"))
            // RUNTIME-8: and the official Infantry_Notes and Infantry_Changes (their tabs and trees).
            implementation(project(":plugins:official:Infantry_Notes"))
            implementation(project(":plugins:official:Infantry_Changes"))
            // RUNTIME-9: and the official Infantry_Prompts and Infantry_Skills (the right-hand Prompts and Skills tabs).
            implementation(project(":plugins:official:Infantry_Prompts"))
            implementation(project(":plugins:official:Infantry_Skills"))
            // RUNTIME-10: and the official Infantry_Stats and Infantry_Usage (their tabs).
            implementation(project(":plugins:official:Infantry_Stats"))
            implementation(project(":plugins:official:Infantry_Usage"))
            // RUNTIME-11: and the official Infantry_Epics and Infantry_Review (the Epics and Review tabs).
            implementation(project(":plugins:official:Infantry_Epics"))
            implementation(project(":plugins:official:Infantry_Review"))
            // RUNTIME-12: and the official Infantry_Pods and Infantry_Profiles (the Pods and Profiles tabs).
            implementation(project(":plugins:official:Infantry_Pods"))
            implementation(project(":plugins:official:Infantry_Profiles"))
            // RUNTIME-13: and the official Infantry_Triggers and Infantry_Attention (the Triggers and Attention tabs).
            implementation(project(":plugins:official:Infantry_Triggers"))
            implementation(project(":plugins:official:Infantry_Attention"))
            // RUNTIME-14: and the official Infantry_Roles (the Roles tab and the roles window fill).
            implementation(project(":plugins:official:Infantry_Roles"))
            // RUNTIME-15: and the official Infantry_Board (the board overlay fill and its block types).
            implementation(project(":plugins:official:Infantry_Board"))
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.cio)
            // PAIR-1: the QR encoder (and, on desktop, the decoder for "Open image").
            implementation(libs.zxing.core)
            // The mp3 decoder of the incoming-comms sound (LGPL, ~100 KB); isolated in JvmSoundPlayer.
            implementation(libs.jlayer)
        }
        wasmJsMain.dependencies {
            implementation(libs.ktor.client.js)
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

// MD-PLUGIN-1: the plugin tests load the example markdown engine from its real jar, so build it first and hand over the path.
tasks.named<Test>("jvmTest") {
    val pluginJar = project(":superiorSoftLibrary:samples:markdownEnginePlugin").tasks.named("pluginJar")
    dependsOn(pluginJar)
    systemProperty("markdown.example.jar", pluginJar.map { it.outputs.files.singleFile.absolutePath }.get())
    // HOOKS-1: DesktopPluginsJarTest loads the example community module from its real jar (never on this classpath).
    val acme = rootProject.findProject(":plugins:community:Acme_Hello")
    if (acme != null) {
        dependsOn("${acme.path}:jar")
        systemProperty("acme.hello.dir", acme.projectDir.absolutePath)
    }
}
