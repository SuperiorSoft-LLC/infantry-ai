// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The daemon half, compiled against the daemon API only (the Infantry Gradle plugin adds it as compileOnly).
plugins {
    alias(infantry.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(21) }

dependencies {
    testImplementation(kotlin("test"))
}

tasks.jar {
    val name = (groovy.json.JsonSlurper().parse(file("../module.json")) as Map<*, *>)["name"] as String
    archiveFileName.set("$name-daemon.jar")
}
tasks.test { useJUnitPlatform() }
