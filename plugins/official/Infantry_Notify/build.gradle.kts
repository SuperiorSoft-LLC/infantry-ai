// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// NOTIFY-1: official module Infantry_Notify (UI half): the system notification of each platform (Windows tray toast,
// Android channel, browser Notification), the sound list card. jvmSrc / androidSrc / wasmSrc hold each platform's Notifier.
//  - desktop: build/libs/Infantry_Notify.jar, loaded at run time from official/Infantry_Notify (signed as the other official jars).
//  - web and Android: compiled in by :infantryAppUi from the generated module list.
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
        namespace = "com.superiorsoftllc.infantry.plugins.notify"
        compileSdk = 36
        minSdk = 24
    }

    sourceSets {
        commonMain { kotlin.srcDir("src") }
        commonMain.dependencies {
            api(project(":superiorSoftLibrary:infantryUiCore"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
        }
        // The ServiceLoader entry is JVM only: on Android the generated module list already names NotifyPlugin.
        jvmMain { kotlin.srcDir("jvmSrc"); resources.srcDir("jvmResources") }
        androidMain { kotlin.srcDir("androidSrc") }
        androidMain.dependencies { implementation(libs.androidx.activity.compose) }
        wasmJsMain { kotlin.srcDir("wasmSrc") }
        jvmTest { kotlin.srcDir("test") }
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

val officialJar = tasks.named<Jar>("jvmJar") {
    archiveFileName.set("Infantry_Notify.jar")
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

// The normal-path test loads the built jar exactly as the desktop does.
tasks.named<Test>("jvmTest") {
    dependsOn(officialJar)
    systemProperty("infantryNotify.jar", officialJar.get().archiveFile.get().asFile.absolutePath)
    systemProperty("infantryNotify.moduleJson", layout.projectDirectory.file("module.json").asFile.absolutePath)
}
