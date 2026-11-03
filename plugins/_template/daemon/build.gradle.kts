// DAEMON-PLUGINS-1 (docs/DAEMON-PLUGINS.md): the template's daemon half. Compiled against the daemon API only (compileOnly:
// the daemon provides it at run time). Built by the repo build as `:daemon-plugin-_template:jar`, so the starting point
// always compiles; a copy under plugins/<folder>/<Vendor_Module>/ builds as `:daemon-plugin-<folder>-<Vendor_Module>:jar`.
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
    // Must equal module.json's `daemon.jar` (relative to the module folder: daemon/build/libs/<this name>).
    archiveFileName.set("Example_Starter-daemon.jar")
    from(layout.projectDirectory.file("../module.json"))
}
