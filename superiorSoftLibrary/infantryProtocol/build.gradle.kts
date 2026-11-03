// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
// :superiorSoftLibrary:infantryProtocol -- CORE-SPLIT-1 (docs/DAEMON-CORE.md section 4): the wire contract and DTOs (package
// `protocol`, plus the two wire enums/records it needs: `session.SessionState`, `harness.HarnessCommand`). A LEAF: it depends on
// no other infantry module and no plugin module (guarded by DaemonCoreGuardTest). Package names are unchanged from when these
// sources lived in :infantry, so no import elsewhere moved.
plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlinSerialization)
}

// SDK-PUB-1: published to Maven (gradle/sdk-publish.gradle.kts).
apply(from = rootProject.file("gradle/sdk-publish.gradle.kts"))

kotlin {
    jvmToolchain(21)
    // SDK-SURFACE-1: committed ABI dump (api/). checkLegacyAbi runs in `check`.
    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation { enabled.set(true) }
}

dependencies {
    api(libs.kotlinx.serialization.json)
}

// SDK-SURFACE-1: the ABI check gates `check`.
tasks.named("check") { dependsOn("checkLegacyAbi") }
