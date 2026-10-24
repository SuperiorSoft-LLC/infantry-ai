// WEB-1: the AgentHQ browser client. agenthqUiCore rendered as Kotlin/WASM, talking to a real daemon over its WebSocket
// (or, offline, to a built-in fake daemon). The daemon serves this bundle at http://host:port/.
// Single-threaded wasm, so no COOP/COEP headers are needed to host it.
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeMultiplatform)
}

kotlin {
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        outputModuleName = "agentHqWeb"
        browser()
        binaries.executable()
    }
    sourceSets {
        wasmJsMain.dependencies {
            implementation(project(":superiorSoftLibrary:agenthqUiCore"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.ktor.client.core)
        }
    }
}

configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "io.ktor") useVersion(libs.versions.ktor.get())
    }
}

// The static dist WITHOUT webpack: the raw wasm/mjs output, skiko, the compose resources, and js-joda shipped next to
// index.html behind an import map. Served by the daemon (no Python, no other server).
fun registerDist(name: String, mode: String) = tasks.register<Sync>(name) {
    group = "build"
    description = "WEB-1: the browser client's static bundle ($mode), in build/$name."
    dependsOn("wasmJs${mode}ExecutableCompileSync")
    into(layout.buildDirectory.dir(name))
    from(layout.buildDirectory.dir("compileSync/wasmJs/main/${mode.replaceFirstChar { it.lowercase() }}Executable/kotlin"))
    from(layout.buildDirectory.dir("compose/skiko-for-web-runtime"))
    from(layout.buildDirectory.dir("processedResources/wasmJs/main"))
}

// Fast, unminified: for working on the client.
registerDist("assembleDemo", "Development")
// What ships: the daemon distribution, the desktop app and the Docker image copy this into their web/ directory.
registerDist("assembleWeb", "Production")
