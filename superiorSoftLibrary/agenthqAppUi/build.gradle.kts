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
// module's jvm jar). Unsigned: the installer refuses it until it is signed (jarsigner, the Infantry key; never in the repo).
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
    description = "Packages the app UI into build/ihq/App_UI.ihq (unsigned)."
    val jar = tasks.named<Jar>("jvmJar")
    dependsOn(appUiIhqManifest, jar)
    archiveFileName.set("App_UI.ihq")
    destinationDirectory.set(appUiIhqDir)
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    from(appUiIhqManifest.map { appUiIhqDir.get().file("stage/module.json") })
    from(jar.map { it.archiveFile }) { into("lib"); rename { "App_UI.jar" } }
}
