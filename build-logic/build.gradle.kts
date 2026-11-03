// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
plugins {
    `java-gradle-plugin`
    `maven-publish`
    kotlin("jvm") version embeddedKotlinVersion
}

group = "com.superiorsoftllc.infantry"
version = "1.0.0"

repositories { mavenCentral() }

kotlin { jvmToolchain(21) }

gradlePlugin {
    plugins {
        create("infantryPlugin") {
            id = "com.superiorsoftllc.infantry.plugin"
            implementationClass = "com.superiorsoftllc.infantry.gradle.InfantryPluginPlugin"
            displayName = "Infantry AI plugin packaging"
            description = "Validates module.json, wires the SDK and packs a plugin into a .ihq."
        }
    }
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation(gradleTestKit())
}

// Local publication only (java-gradle-plugin adds the plugin marker); nothing is published to a remote repository.
publishing { repositories { maven { name = "local"; url = layout.buildDirectory.dir("repo").get().asFile.toURI() } } }

tasks.test { useJUnitPlatform() }
