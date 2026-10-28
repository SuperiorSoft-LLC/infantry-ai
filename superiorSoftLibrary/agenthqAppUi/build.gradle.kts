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

// S6 (drop-in path): the app UI as one droppable file, build/ihq/App_UI.ihq: module.json (Infantry_AppUi's, with its entry
// the ModuleSource that hands over every core module, so the desktop loads it as a bundle) and lib/App_UI.jar (this
// module's jvm jar). One .ihq, one signature, 18 modules (the bundle). Without -Pihq.keystore it is unsigned and the installer refuses it.
// The drop-in acceptance (agentHqUi renderTabShotsForDiff -PtabShotsExtra=dropin) signs a copy with a throwaway key.
val appUiIhqDir = layout.buildDirectory.dir("ihq")
val appUiIhqManifest = tasks.register("appUiIhqManifest") {
    val source = rootProject.file("plugins/core/Infantry_AppUi/module.json")
    inputs.file(source)
    val out = appUiIhqDir.map { it.file("stage/module.json") }
    outputs.file(out)
    doLast {
        @Suppress("UNCHECKED_CAST")
        val m = groovy.json.JsonSlurper().parse(source) as MutableMap<String, Any?>
        m["entry"] = "com.superiorsoftllc.agenthq.plugins.core.CoreModuleSource"
        m["jar"] = "lib/App_UI.jar"
        out.get().asFile.also { it.parentFile.mkdirs() }.writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(m)) + "\n")
    }
}
tasks.register<Zip>("appUiIhq") {
    group = "build"
    description = "Packages the app UI into build/ihq/App_UI.ihq (signed if -Pihq.keystore is given, else unsigned)."
    val jar = tasks.named<Jar>("jvmJar")
    dependsOn(appUiIhqManifest, jar)
    archiveFileName.set("App_UI.ihq")
    destinationDirectory.set(appUiIhqDir)
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    from(appUiIhqManifest.map { appUiIhqDir.get().file("stage/module.json") })
    from(jar.map { it.archiveFile }) { into("lib"); rename { "App_UI.jar" } }
    // S7: signed with the Infantry key when -Pihq.keystore / -Pihq.alias are given (password in env IHQ_STOREPASS), as
    // gradle/ihq.gradle.kts does for a plugin. The one signature covers the bundle's jar and its module.json, so the
    // installer reads the whole 18-module app UI as core tier from the signer. The key itself is never in the repo.
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
