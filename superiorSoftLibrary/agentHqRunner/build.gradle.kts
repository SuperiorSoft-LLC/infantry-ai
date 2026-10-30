// :superiorSoftLibrary:agentHqRunner -- CORE-SPLIT-2 (docs/DAEMON-CORE.md section 4): the process runner and watch mechanism
// (package `runner`: ProcessRunner, PtyProcessRunner, WatchShims, ToolProfiles, ...). A LEAF: it depends on no other agentHq
// module and no plugin module (guarded by DaemonCoreGuardTest). Package names are unchanged from when these sources lived in :agentHq.
plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    // Ticket 66153313: Windows priority classes and job objects go through Kernel32 (JNA).
    implementation("net.java.dev.jna:jna-platform:5.18.1")
    // RUN-CONSOLE (ticket 67f117b6): the pseudo-terminal runner. Pinned to 0.13.x like :agentHq (0.14 needs kotlin-stdlib 2.4).
    implementation("org.jetbrains.pty4j:pty4j:0.13.13")
}
