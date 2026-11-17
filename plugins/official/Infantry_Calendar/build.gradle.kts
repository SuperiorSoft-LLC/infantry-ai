// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// RUNTIME-8 (docs/RUNTIME-PLUGINS-AUDIT.md section 3, step 8): official module Infantry_Calendar, the Calendar tab (CAL-2)
// build into its own project. One source for every platform (D9):
//  - desktop: build/libs/Infantry_Calendar.jar (this project's jvm jar: its classes, its signed module.json and a ServiceLoader
//    entry for class paths that carry it, i.e. tests), loaded at run time from official/Infantry_Calendar. The jar is signed
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
        namespace = "com.superiorsoftllc.infantry.plugins.calendar"
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
            implementation(libs.kizitonwose.calendar)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.serialization.json)
        }
        // The ServiceLoader entry is JVM only: on Android the generated module list already names CalendarPlugin.
        jvmMain { resources.srcDir("jvmResources") }
        jvmTest { kotlin.srcDir("test") }
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

val officialJar = tasks.named<Jar>("jvmJar") {
    archiveFileName.set("Infantry_Calendar.jar")
    // OfficialTrust: the jar carries a signed module.json equal to the one on disk.
    from(layout.projectDirectory.file("module.json"))
    // CAL-E2E-1: the month grid library is not on the host's class path, so the jar carries it (else the tab throws
    // NoClassDefFoundError: com.kizitonwose.calendar.compose.CalendarStateKt).
    from({
        configurations.getByName("jvmRuntimeClasspath").incoming.artifactView { }.artifacts.artifacts
            .filter { it.id.componentIdentifier.displayName.startsWith("com.kizitonwose.calendar:") }
            .map { zipTree(it.file) }
    }) { exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "META-INF/MANIFEST.MF") }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
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
    systemProperty("infantryCalendar.jar", officialJar.get().archiveFile.get().asFile.absolutePath)
    systemProperty("infantryCalendar.moduleJson", layout.projectDirectory.file("module.json").asFile.absolutePath)
}
