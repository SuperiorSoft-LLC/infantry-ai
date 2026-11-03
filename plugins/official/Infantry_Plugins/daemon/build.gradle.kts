// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// PLUGIN-MCP-1: Infantry_Plugins' daemon half, the agent tools over the plugin manager. Compiled against the daemon API only
// (compileOnly: the daemon provides it at run time). `:daemon-plugin-official-Infantry_Plugins:jar`.
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

tasks.jar {
    archiveFileName.set("Infantry_Plugins-daemon.jar")
    // The daemon jar carries a copy of module.json, as every module jar does.
    from(layout.projectDirectory.file("../module.json"))
}

tasks.test { useJUnitPlatform() }
