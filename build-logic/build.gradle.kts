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

// Local publications (java-gradle-plugin adds the plugin marker), plus SDK-CENTRAL-1: Maven Central through the same
// bundle as the SDK (gradle/sdk-central.gradle.kts adds the POM fields, sources/javadoc jars, signing and the
// `centralBundle` repository), so an out-of-tree plugin resolves `com.superiorsoftllc.infantry.plugin` with no token.
apply(from = rootDir.resolve("../gradle/sdk-central.gradle.kts"))

publishing {
    publications.withType<MavenPublication>().configureEach {
        if (name == "pluginMaven") artifactId = "infantry-gradle-plugin"
        pom {
            name.set(if (this@configureEach.name == "pluginMaven") "infantry-gradle-plugin" else "com.superiorsoftllc.infantry.plugin")
            description.set("Infantry AI Gradle plugin: validates module.json, wires the SDK and packs a plugin into a .ihq.")
        }
    }
    repositories {
        maven { name = "local"; url = layout.buildDirectory.dir("repo").get().asFile.toURI() }
        // TEMPLATE-REPO-1: the same dry-run repo the SDK modules publish to, so templates/plugin-repo resolves the plugin offline.
        maven { name = "dryRun"; url = rootDir.resolve("../build/m2").toURI() }
    }
}

tasks.test { useJUnitPlatform() }
