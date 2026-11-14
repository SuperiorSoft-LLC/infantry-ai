// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// DOCS-LOOKUP-1: Infantry_Docs' daemon half, the in-app docs lookup (capabilities docs.list / docs.show). The user-facing wiki
// pages are copied into the jar from docs/wiki. `:daemon-plugin-official-Infantry_Docs:jar`.
plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    compileOnly(project(":superiorSoftLibrary:infantryDaemonApi"))
    testImplementation(project(":superiorSoftLibrary:infantryDaemonApi"))
    testImplementation(kotlin("test"))
}

/** The wiki pages an agent may read in a user's install: user-facing, not design records or plugin-author pages. */
val shippedPages = listOf(
    "Usage", "Home", "Settings", "Harnesses", "Updates", "Health", "Waiting", "Progress", "Memory", "Login", "Board", "Secrets",
    "Notifications", "CommandLine", "runner-quickstart", "run-a-remote-environment", "Scripting",
)

tasks.jar {
    archiveFileName.set("Infantry_Docs-daemon.jar")
    from(layout.projectDirectory.file("../module.json"))
    from(rootProject.layout.projectDirectory.dir("docs/wiki")) {
        include(shippedPages.map { "$it.md" })
        into("docs")
    }
    // The page list the plugin serves: one name per line, generated from shippedPages.
    val index = layout.buildDirectory.file("docs-index/docs/index.txt")
    doFirst { index.get().asFile.also { it.parentFile.mkdirs() }.writeText(shippedPages.joinToString("\n") + "\n") }
    from(layout.buildDirectory.dir("docs-index"))
}

tasks.test { useJUnitPlatform() }
