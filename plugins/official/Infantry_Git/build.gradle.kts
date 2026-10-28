// RUNTIME-6 (docs/RUNTIME-PLUGINS-AUDIT.md section 3, step 7): official module Infantry_Git, the Git tab (its tab tree ShellGit), out of the shell
// build into its own project. One source for every platform (D9):
//  - desktop: build/libs/Infantry_Git.jar (this project's jvm jar: its classes, its signed module.json and a ServiceLoader
//    entry for class paths that carry it, i.e. tests), loaded at run time from official/Infantry_Git. The jar is signed
//    when -Pihq.keystore / -Pihq.alias are given (password in env IHQ_STOREPASS), as for a .ihq; OfficialTrust then checks
//    it against the certificates in trusted-keys. Dev runs from the repo pass -Dagenthq.plugins.devTrust=true instead.
//  - web and Android: compiled in by :agenthqAppUi from the generated module list (wasmJsMain / androidMain only).
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
        namespace = "com.superiorsoftllc.agenthq.plugins.git"
        compileSdk = 36
        minSdk = 24
    }

    sourceSets {
        commonMain { kotlin.srcDir("src") }
        commonMain.dependencies {
            api(project(":superiorSoftLibrary:agenthqUiCore"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
        }
        // The ServiceLoader entry is JVM only: on Android the generated module list already names GitPlugin.
        jvmMain { resources.srcDir("jvmResources") }
        jvmTest { kotlin.srcDir("test") }
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

val officialJar = tasks.named<Jar>("jvmJar") {
    archiveFileName.set("Infantry_Git.jar")
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
    systemProperty("infantryGit.jar", officialJar.get().archiveFile.get().asFile.absolutePath)
    systemProperty("infantryGit.moduleJson", layout.projectDirectory.file("module.json").asFile.absolutePath)
}
