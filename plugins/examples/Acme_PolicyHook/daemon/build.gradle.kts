// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// DAEMON-PLUGINS-1 (docs/DAEMON-PLUGINS.md): the reference daemon plugin, one tool that reads the policy action and one frameGate hook, compiled against
// the daemon API only (compileOnly: the daemon provides it at run time). `:daemon-plugin-examples-Acme_PolicyHook:jar`.
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
    archiveFileName.set("Acme_PolicyHook-daemon.jar")
    from(layout.projectDirectory.file("../module.json"))
}
