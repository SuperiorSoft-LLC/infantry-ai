// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// AGENT-REGISTRY-1 (docs/AGENT-REGISTRY.md section 7): the reference agent plugin, an agent with no code orchestration, compiled
// against the daemon API only (compileOnly: the daemon provides it at run time). `:daemon-plugin-examples-Acme_NewsAgent:jar`.
plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    compileOnly(project(":superiorSoftLibrary:infantryDaemonApi"))
}

tasks.jar {
    // module.json's `daemon.jar` names this file; the jar carries a copy of module.json, as every module jar does.
    archiveFileName.set("Acme_NewsAgent-daemon.jar")
    from(layout.projectDirectory.file("../module.json"))
}
