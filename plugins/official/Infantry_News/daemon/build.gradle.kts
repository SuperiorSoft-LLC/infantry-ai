// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// NEWS-1: Infantry_News' daemon half, the `news.headlines` capability (Google News RSS through the core Downloads seam).
// Compiled against the daemon API only (compileOnly: the daemon provides it at run time). `:daemon-plugin-official-Infantry_News:jar`.
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
    archiveFileName.set("Infantry_News-daemon.jar")
    // The daemon jar carries a copy of module.json, as every module jar does.
    from(layout.projectDirectory.file("../module.json"))
}

tasks.test { useJUnitPlatform() }
