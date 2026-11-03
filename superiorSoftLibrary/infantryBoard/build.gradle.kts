// :superiorSoftLibrary:infantryBoard -- DAEMON-PLUGIN DP-5e (docs/DAEMON-CORE.md): the board's lower-level packages (epics, brief, ledger,
// context, ticketfiles). Depends on the daemon API and the protocol, NEVER on :infantry (the dependency points one way: infantry -> here;
// guarded by BoardImportGuardTest). What these packages need of the host comes in through small ports declared here; :infantry adapts them.
// Package names are unchanged from when these sources lived in :infantry, so no import elsewhere moved.
plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":superiorSoftLibrary:infantryDaemonApi"))
    api(project(":superiorSoftLibrary:infantryProtocol"))
    api(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
}
