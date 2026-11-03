// PLUGIN-ENDPOINTS-2 example community module: a plugin with a backend of its own (plugins/GUIDE.md, "Your own backend in an
// hour"). Found by settings.gradle.kts scanning plugins/community; builds ONE jar, build/libs/Acme_Echo.jar.
plugins {
    kotlin("jvm")
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeMultiplatform)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    compileOnly(project(":superiorSoftLibrary:infantryUiCore"))
    compileOnly(compose.runtime)
    compileOnly(compose.foundation)
    compileOnly(compose.material3)
    compileOnly(libs.kotlinx.coroutines.core)
}

tasks.jar {
    archiveFileName.set("Acme_Echo.jar")
}
