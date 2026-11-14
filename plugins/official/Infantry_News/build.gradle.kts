// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// NEWS-1: official module Infantry_News, the card:news chat card renderer (UI half; the daemon half is daemon/). One source for every platform (D9); see Infantry_Pace/build.gradle.kts.
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeMultiplatform)
    id("tech.superiorsoftllc.infantry.plugin")
}

kotlin {
    jvmToolchain(21)
    jvm()
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs { browser() }
    androidLibrary {
        namespace = "com.superiorsoftllc.infantry.plugins.news"
        compileSdk = 36
        minSdk = 24
    }

    sourceSets {
        commonMain { kotlin.srcDir("src") }
        commonMain.dependencies {
            api(project(":superiorSoftLibrary:infantryUiCore"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)
            implementation(compose.material3)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
        }
        // The ServiceLoader entry is JVM only: on Android the generated module list already names NewsPlugin.
        jvmMain { resources.srcDir("jvmResources") }
        jvmTest { kotlin.srcDir("test") }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            implementation(compose.uiTest)
            implementation(compose.desktop.currentOs)
        }
    }
}

val officialJar = tasks.named<Jar>("jvmJar") {
    archiveFileName.set("Infantry_News.jar")
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
    systemProperty("infantryNews.jar", officialJar.get().archiveFile.get().asFile.absolutePath)
    systemProperty("infantryNews.moduleJson", layout.projectDirectory.file("module.json").asFile.absolutePath)
}
