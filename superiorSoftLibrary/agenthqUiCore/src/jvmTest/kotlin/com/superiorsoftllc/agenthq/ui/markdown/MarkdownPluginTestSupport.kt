package com.superiorsoftllc.agenthq.ui.markdown

import java.io.File
import java.nio.file.Files

/** The example plugin jar, built by the `pluginJar` task that jvmTest depends on (path passed in by Gradle). */
fun exampleJar(): File = File(System.getProperty("markdown.example.jar") ?: error("markdown.example.jar is not set; run through Gradle jvmTest")).also {
    check(it.isFile) { "example plugin jar missing: $it" }
}

/** A fresh plugins folder holding a copy of the example jar. */
fun pluginDirWithExample(): File = Files.createTempDirectory("md-plugins").toFile().also {
    exampleJar().copyTo(File(it, "markdown-engine-example.jar"))
    it.deleteOnExit()
}
