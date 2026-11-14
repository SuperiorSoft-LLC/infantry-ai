// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// BROWSER-1: Infantry_Browser's daemon half. It only declares the browser MCP server in module.json (daemon.mcpServers);
// the entry class has nothing to start. `:daemon-plugin-official-Infantry_Browser:jar`.
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
    archiveFileName.set("Infantry_Browser-daemon.jar")
    from(layout.projectDirectory.file("../module.json"))
}

dependencies { testImplementation(kotlin("test")) }
tasks.test { useJUnitPlatform() }
