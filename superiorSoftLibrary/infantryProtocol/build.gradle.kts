// :superiorSoftLibrary:infantryProtocol -- CORE-SPLIT-1 (docs/DAEMON-CORE.md section 4): the wire contract and DTOs (package
// `protocol`, plus the two wire enums/records it needs: `session.SessionState`, `harness.HarnessCommand`). A LEAF: it depends on
// no other infantry module and no plugin module (guarded by DaemonCoreGuardTest). Package names are unchanged from when these
// sources lived in :infantry, so no import elsewhere moved.
plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(libs.kotlinx.serialization.json)
}
