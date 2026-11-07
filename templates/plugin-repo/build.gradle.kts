// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The UI half. The Infantry Gradle plugin reads module.json, adds the SDK, and registers `ihq` and `catalogEntry`.
// Delete src/ and the Compose lines for a daemon-only plugin.
plugins {
    alias(infantry.plugins.kotlin.jvm)
    alias(infantry.plugins.compose.compiler)
    alias(infantry.plugins.compose.multiplatform)
    id("tech.superiorsoftllc.infantry.plugin") version "1.0.0"
}

kotlin { jvmToolchain(21) }

dependencies {
    compileOnly(compose.runtime)
    compileOnly(compose.foundation)
    compileOnly(compose.material3)
    testImplementation(compose.runtime)
    testImplementation(compose.foundation)
    testImplementation(compose.material3)
    testImplementation(kotlin("test"))
}

// The jar's name follows module.json, so a rename needs no edit here.
val moduleName: String = (groovy.json.JsonSlurper().parse(file("module.json")) as Map<*, *>)["name"] as? String
    ?: error("module.json has no name")

tasks.jar { archiveFileName.set("$moduleName.jar") }
tasks.test { useJUnitPlatform() }
