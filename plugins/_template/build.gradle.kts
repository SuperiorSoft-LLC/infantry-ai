// PLUGINS-2 starter template. Copied to plugins/community/<Vendor_Module>/, settings.gradle.kts finds it by scanning
// and builds ONE jar named after module.json's "name" (build/libs/<Vendor_Module>.jar). The jar holds only this
// module's classes: the app provides the plugin API, Compose and the Kotlin runtime when it loads the jar.
plugins {
    kotlin("jvm")
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeMultiplatform)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    compileOnly(project(":superiorSoftLibrary:agenthqUiCore"))
    compileOnly(compose.runtime)
    compileOnly(compose.foundation)
    compileOnly(compose.material3)

    testImplementation(project(":superiorSoftLibrary:agenthqUiCore"))
    testImplementation(compose.runtime)
    testImplementation(compose.foundation)
    testImplementation(compose.material3)
    testImplementation(kotlin("test"))
}

// The jar's name follows module.json, so a copy needs no edit here.
val moduleName: String = (groovy.json.JsonSlurper().parse(file("module.json")) as Map<*, *>)["name"] as? String
    ?: error("module.json has no name")

tasks.jar {
    archiveFileName.set("$moduleName.jar")
}

tasks.test {
    useJUnitPlatform()
}
