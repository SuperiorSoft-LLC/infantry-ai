// DEMO-MERGE-1: the demo lock module (docs/DEMO-MERGE.md). A demo-only module like Demo_Banner. It is compiled into the web and Android builds only
// when -Pdemo=true (infantryAppUi:generateModuleList); the desktop and the production builds never see it. No jar, no signing.
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
        namespace = "com.superiorsoftllc.infantry.plugins.demo.lock"
        compileSdk = 36
        minSdk = 24
    }

    sourceSets {
        commonMain { kotlin.srcDir("src") }
        jvmTest { kotlin.srcDir("test") }
        jvmTest.dependencies { implementation(kotlin("test")) }
        commonMain.dependencies {
            api(project(":superiorSoftLibrary:infantryUiCore"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
        }
    }
}
