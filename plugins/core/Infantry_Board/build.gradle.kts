// NS2 (docs/NORTH-STAR-DESIGN.md 3b): core module Infantry_Board, the scrum board, split out of the app UI into its own
// Gradle project and jar. It registers the board's 14 block types (board.pane and everything under it), so the board can
// fill any slot (the main app's board overlay, a fresh shell's center). :agenthqAppUi depends on it and still draws the
// overlay from these types; appUiIhq bundles it, boardIhq publishes it alone as build/ihq/Infantry_Board.ihq.
// The sources stay flat in src/ (and test/), like the other plugins/core modules.
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
        namespace = "com.superiorsoftllc.agenthq.plugins.board"
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
        jvmTest { kotlin.srcDir("test") }
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// NS2: the board alone as one droppable file, build/ihq/Infantry_Board.ihq: module.json (with its entry, the ModuleSource
// that hands over Infantry_Board) and lib/Infantry_Board.jar (this project's jvm jar). Signed like App_UI.ihq when
// -Pihq.keystore / -Pihq.alias are given (password in env IHQ_STOREPASS), so the installer reads it as core tier.
val boardIhqDir = layout.buildDirectory.dir("ihq")
val boardIhqManifest = tasks.register("boardIhqManifest") {
    val source = file("module.json")
    inputs.file(source)
    val out = boardIhqDir.map { it.file("stage/module.json") }
    outputs.file(out)
    doLast {
        @Suppress("UNCHECKED_CAST")
        val m = groovy.json.JsonSlurper().parse(source) as MutableMap<String, Any?>
        m["entry"] = "com.superiorsoftllc.agenthq.plugins.core.BoardModuleSource"
        m["jar"] = "lib/Infantry_Board.jar"
        out.get().asFile.also { it.parentFile.mkdirs() }.writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(m)) + "\n")
    }
}
tasks.register<Zip>("boardIhq") {
    group = "build"
    description = "Packages the board into build/ihq/Infantry_Board.ihq (signed if -Pihq.keystore is given, else unsigned)."
    val jar = tasks.named<Jar>("jvmJar")
    dependsOn(boardIhqManifest, jar)
    archiveFileName.set("Infantry_Board.ihq")
    destinationDirectory.set(boardIhqDir)
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    from(boardIhqManifest.map { boardIhqDir.get().file("stage/module.json") })
    from(jar.map { it.archiveFile }) { into("lib"); rename { "Infantry_Board.jar" } }
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
