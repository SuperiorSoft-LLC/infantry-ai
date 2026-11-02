// :superiorSoftLibrary:agentHqCore -- CORE-SPLIT-4b (docs/DAEMON-CORE.md section 4): the packages that stay in core
// (session ledger, mailbox routing, policy, settings, db/keystore, harness API + providers, mvp registry, persistence,
// worktree/projectgit, usage/stats, prompts, agents, executables, daemon.AccessPolicy). Package names are unchanged from
// when these sources lived in :agentHq, which is now the app/CLI shell depending on this module (one-way: agentHq -> here;
// guarded by DaemonCoreGuardTest). It depends on no feature module and no plugin module.
plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.sqldelight)
}

sqldelight {
    databases {
        create("AgentHQDatabase") {
            packageName.set("com.superiorsoftllc.agenthq.db")
            srcDirs.setFrom("src/main/sqldelight")
        }
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(project(":superiorSoftLibrary:agenthqPlugins"))
    api(project(":superiorSoftLibrary:agentHqDaemonApi"))
    api(project(":superiorSoftLibrary:agentHqHarnessApi"))
    api(project(":superiorSoftLibrary:agentHqProtocol"))
    api(project(":superiorSoftLibrary:agentHqRunner"))
    api(project(":superiorSoftLibrary:agentHqBoard"))
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

// Same pin as :agentHq (the catalog mixes Ktor versions).
configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "io.ktor") useVersion(libs.versions.ktor.get())
    }
}

// Disabled repo-wide by design, as in :agentHq.
tasks.withType<app.cash.sqldelight.gradle.VerifyMigrationTask>().configureEach { enabled = false }
