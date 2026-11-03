// :superiorSoftLibrary:infantryDaemonApi -- DAEMON-PLUGINS-1 (docs/DAEMON-PLUGINS.md): the ONE surface a daemon plugin compiles
// against. JVM only, small and versioned (DaemonApi.VERSION). A plugin's daemon half depends on it `compileOnly`; the daemon
// provides it at run time through the plugin's parent class loader, which exposes nothing else of the daemon.
// Do NOT add a dependency on :infantry here: a plugin must never see daemon internals.
plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // The plugin SDK's data model (PolicyPreset, SeatsSpec, ...), already the UI plugins' contract.
    api(project(":superiorSoftLibrary:infantryPlugins"))
    api(project(":superiorSoftLibrary:infantryProtocol"))
    api(libs.kotlinx.serialization.json)
}
