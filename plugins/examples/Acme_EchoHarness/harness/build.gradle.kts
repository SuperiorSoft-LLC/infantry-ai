// HARNESS-PLUGIN-3 (docs/HARNESS-PLUGINS.md): the reference harness plugin, compiled against the harness API only (compileOnly:
// the daemon provides it at run time through HarnessApiClassLoader). `:harness-plugin-examples-Acme_EchoHarness:jar`.
plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    compileOnly(project(":superiorSoftLibrary:infantryHarnessApi"))
}

tasks.jar {
    // module.json's `harness.jar` names this file; the jar carries a copy of module.json, as every module jar does.
    archiveFileName.set("Acme_EchoHarness-harness.jar")
    from(layout.projectDirectory.file("../module.json"))
}
