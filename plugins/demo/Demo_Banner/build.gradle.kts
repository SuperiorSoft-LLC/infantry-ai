// OVERRIDE-1 (docs/PLUGIN-OVERRIDES.md section 6): a demo-only module. It is compiled into the web and Android builds only
// when -Pdemo=true (agenthqAppUi:generateModuleList); the desktop and the production builds never see it. No jar, no signing.
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeMultiplatform)
}

kotlin {
    jvmToolchain(21)
    jvm()
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs { browser() }
    androidLibrary {
        namespace = "com.superiorsoftllc.agenthq.plugins.demo.banner"
        compileSdk = 36
        minSdk = 24
    }

    sourceSets {
        commonMain { kotlin.srcDir("src") }
        commonMain.dependencies {
            api(project(":superiorSoftLibrary:agenthqUiCore"))
            // DEMO-MERGE-1: the notice comes from Demo_Lock, which wraps the frame this banner sits in.
            implementation(project(":plugins:demo:Demo_Lock"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
        }
    }
}
