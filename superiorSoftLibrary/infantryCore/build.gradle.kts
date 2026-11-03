// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
// :superiorSoftLibrary:infantryCore -- CORE-SPLIT-4b (docs/DAEMON-CORE.md section 4): the packages that stay in core
// (session ledger, mailbox routing, policy, settings, db/keystore, harness API + providers, mvp registry, persistence,
// worktree/projectgit, usage/stats, prompts, agents, executables, daemon.AccessPolicy). Package names are unchanged from
// when these sources lived in :infantry, which is now the app/CLI shell depending on this module (one-way: infantry -> here;
// guarded by DaemonCoreGuardTest). It depends on no feature module and no plugin module.
plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.sqldelight)
}

sqldelight {
    databases {
        create("InfantryDatabase") {
            packageName.set("com.superiorsoftllc.infantry.db")
            srcDirs.setFrom("src/main/sqldelight")
        }
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(project(":superiorSoftLibrary:infantryPlugins"))
    api(project(":superiorSoftLibrary:infantryDaemonApi"))
    api(project(":superiorSoftLibrary:infantryHarnessApi"))
    api(project(":superiorSoftLibrary:infantryProtocol"))
    api(project(":superiorSoftLibrary:infantryRunner"))
    api(project(":superiorSoftLibrary:infantryBoard"))
    api(libs.kotlinx.serialization.json)
    api(libs.ktor.server.core)
    api(libs.sqlite.driver)
    api(libs.sqlite.jdbc)
    api(libs.sqldelight.runtime)
    api(libs.sqldelight.jdbc.driver)
    api(libs.primitive.adapters)
    implementation("net.java.dev.jna:jna-platform:5.18.1")
    testImplementation(kotlin("test"))
}

// Same pin as :infantry (the catalog mixes Ktor versions).
configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "io.ktor") useVersion(libs.versions.ktor.get())
    }
}

// Disabled repo-wide by design, as in :infantry.
tasks.withType<app.cash.sqldelight.gradle.VerifyMigrationTask>().configureEach { enabled = false }
