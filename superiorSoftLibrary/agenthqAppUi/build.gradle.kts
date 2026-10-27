// S4 (drop-in path, docs/SHELL-EMPTY-DROPIN-FEASIBILITY.md section 4): the app UI, out of the shell. It holds the core
// modules (plugins/core, one folder per module) and Infantry_AppUi, which fills the shell's empty slots, and depends on
// :agenthqUiCore (the shell) one way only. The desktop shell has it on its run-time classpath alone (found through
// ServiceLoader); web and Android compile it in.
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
        namespace = "com.superiorsoftllc.agenthq.ui.app"
        compileSdk = 36
        minSdk = 24
    }

    sourceSets {
        // PLUGINS-1: the core modules live in <repo>/plugins/core and are compiled into every target from there.
        commonMain { kotlin.srcDir(rootProject.file("plugins/core")) }
        commonMain.dependencies {
            api(project(":superiorSoftLibrary:agenthqUiCore"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
