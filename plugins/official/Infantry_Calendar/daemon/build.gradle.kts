// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// CAL-1 (Calendar epic): Infantry_Calendar daemon half (tables, store, agent tools, frames). The UI half comes with CAL-2.
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
    testImplementation(libs.sqlite.jdbc)
}

tasks.jar {
    archiveFileName.set("Infantry_Calendar-daemon.jar")
    // The daemon jar carries a copy of module.json (and the knowledge files it names), as every module jar does.
    from(layout.projectDirectory.file("../module.json"))
    from(layout.projectDirectory.dir("../knowledge")) { into("knowledge") }
}

tasks.test { useJUnitPlatform() }
