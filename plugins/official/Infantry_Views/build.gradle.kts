// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// DV-3 (docs/DECL-UI-DESIGN.md): official module Infantry_Views, out of the shell
// build into its own project. One source for every platform (D9):
//  - desktop: build/libs/Infantry_Views.jar (this project's jvm jar: its classes, its signed module.json and a ServiceLoader
//    entry for class paths that carry it, i.e. tests), loaded at run time from official/Infantry_Views. The jar is signed
//    when -Pihq.keystore / -Pihq.alias are given (password in env IHQ_STOREPASS), as for a .ihq; OfficialTrust then checks
//    it against the certificates in trusted-keys. Dev runs from the repo pass -Dinfantry.plugins.devTrust=true instead.
//  - web and Android: compiled in by :infantryAppUi from the generated module list (wasmJsMain / androidMain only).
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
        namespace = "com.superiorsoftllc.infantry.plugins.views"
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
        }
        // The ServiceLoader entry is JVM only: on Android the generated module list already names NotesPlugin.
        jvmTest { kotlin.srcDir("test") }
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

val officialJar = tasks.named<Jar>("jvmJar") {
    archiveFileName.set("Infantry_Views.jar")
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
    systemProperty("infantryViews.jar", officialJar.get().archiveFile.get().asFile.absolutePath)
    systemProperty("infantryViews.moduleJson", layout.projectDirectory.file("module.json").asFile.absolutePath)
}
