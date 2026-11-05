// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// UPDATER-1: Infantry_Updater's UI half. One source for every platform (D9), like Infantry_Usage:
//  - desktop: build/libs/Infantry_Updater.jar, loaded at run time from official/Infantry_Updater (the desktop window keeps its
//    own MSI updater from the daemon half's engine, so on desktop this half registers a provider that offers nothing).
//  - Android: compiled in by :infantryAppUi from the generated module list; androidMain asks the daemon's `GET /update/client`,
//    downloads the APK, checks its size and SHA-256, and hands it to the system installer (PackageInstaller).
//  - web: compiled in; a browser updates by reloading, so it offers nothing.
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
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs { browser() }
    androidLibrary {
        namespace = "com.superiorsoftllc.infantry.plugins.updater"
        compileSdk = 36
        minSdk = 24
    }

    sourceSets {
        commonMain { kotlin.srcDir("src") }
        commonMain.dependencies {
            api(project(":superiorSoftLibrary:infantryUiCore"))
            implementation(compose.runtime)
            implementation(libs.kotlinx.serialization.json)
        }
        androidMain { kotlin.srcDir("androidSrc") }
        // The ServiceLoader entry is JVM only: on Android the generated module list already names UpdaterPlugin.
        jvmMain { kotlin.srcDir("jvmSrc"); resources.srcDir("jvmResources") }
        wasmJsMain { kotlin.srcDir("wasmSrc") }
        jvmTest { kotlin.srcDir("test") }
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

val officialJar = tasks.named<Jar>("jvmJar") {
    archiveFileName.set("Infantry_Updater.jar")
    // OfficialTrust: the jar carries a signed module.json equal to the one on disk.
    from(layout.projectDirectory.file("module.json"))
    val keystore = providers.gradleProperty("ihq.keystore")
    val alias = providers.gradleProperty("ihq.alias")
    val storePass = providers.environmentVariable("IHQ_STOREPASS")
    doLast {
        if (keystore.isPresent) {
            val file = archiveFile.get().asFile
            val jarsigner = File(File(System.getProperty("java.home"), "bin"), "jarsigner" + if (org.gradle.internal.os.OperatingSystem.current().isWindows) ".exe" else "").path
            val p = ProcessBuilder(jarsigner, "-keystore", keystore.get(), "-storepass", storePass.orNull ?: error("IHQ_STOREPASS is not set"), file.path, alias.orNull ?: error("-Pihq.alias is required")).redirectErrorStream(true).start()
            val text = p.inputStream.bufferedReader().readText()
            check(p.waitFor() == 0) { "jarsigner failed: $text" }
        }
    }
}
