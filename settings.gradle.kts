// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
rootProject.name = "InfantryAI"
pluginManagement {
    repositories {
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        google()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
        // RUN-CONSOLE (ticket 67f117b6): JediTerm is published only on JetBrains' IntelliJ dependencies repository,
        // not on Maven Central. Content-filtered so nothing else can ever resolve from it.
        maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies") {
            content { includeGroup("org.jetbrains.jediterm") }
        }
    }
}

// The directory prefix is kept from the monorepo so ":superiorSoftLibrary:..." project paths stay valid.
include(":superiorSoftLibrary")
include(":superiorSoftLibrary:infantry")
// CORE-SPLIT-1/2 (docs/DAEMON-CORE.md): leaf modules carved out of infantry; package names unchanged.
include(":superiorSoftLibrary:infantryProtocol")
include(":superiorSoftLibrary:infantryRunner")
include(":superiorSoftLibrary:infantryCore")
// DP-5e: the board's lower-level packages (epics, brief, ledger, context, ticketfiles); depends on the daemon API, never on infantry.
include(":superiorSoftLibrary:infantryBoard")
include(":superiorSoftLibrary:infantryUiCore")
include(":superiorSoftLibrary:infantryAppUi")
// RUNTIME-3: an official module with sources (plugins/official/<Module>/build.gradle.kts) builds as its own project and
// jar, found by scanning like community: Infantry_Cli first. Desktop loads the jar at run time; web/Android compile it in.
file("plugins/official").listFiles { f -> f.isDirectory && !f.name.startsWith("_") && java.io.File(f, "build.gradle.kts").isFile }.orEmpty().sortedBy { it.name }.forEach { dir ->
    include(":plugins:official:${dir.name}")
    project(":plugins:official:${dir.name}").projectDir = dir
}
// OVERRIDE-1: a demo-only module (plugins/demo/<Module>/build.gradle.kts) builds as its own project; the web and Android
// builds compile it in only with -Pdemo=true (infantryAppUi:generateModuleList), after the live modules, so it overrides them.
file("plugins/demo").listFiles { f -> f.isDirectory && !f.name.startsWith("_") && java.io.File(f, "build.gradle.kts").isFile }.orEmpty().sortedBy { it.name }.forEach { dir ->
    include(":plugins:demo:${dir.name}")
    project(":plugins:demo:${dir.name}").projectDir = dir
}
// PLUGINS-1: the plugin API and the host's loading rules (no Compose, shared by the UI and the daemon).
include(":superiorSoftLibrary:infantryPlugins")
include(":superiorSoftLibrary:infantryUi")
// WEB-1: browser (wasmJs) client over infantryUiCore; the daemon serves its bundle at /.
include(":superiorSoftLibrary:infantryWeb")
include(":superiorSoftLibrary:infantryAndroid")
include(":superiorSoftLibrary:infantryHeadless")
// MD-PLUGIN-1: the example markdown engine plugin (built to a jar the app loads at runtime).
include(":superiorSoftLibrary:samples:markdownEnginePlugin")

// PLUGINS-1: a community module with sources builds as its own project, found by scanning: every plugins/community/<Module>/
// that has a build.gradle.kts. Nothing here names a module, so dropping a module's folder in is the whole install.
file("plugins/community").listFiles { f -> f.isDirectory && !f.name.startsWith("_") && java.io.File(f, "build.gradle.kts").isFile }.orEmpty().sortedBy { it.name }.forEach { dir ->
    include(":plugins:community:${dir.name}")
    project(":plugins:community:${dir.name}").projectDir = dir
}
// PLUGINS-2: the starter template builds and tests like any community module, so a copy of it is known to build. The
// loader never sees it: it is outside core/ and community/, and a folder starting with "_" is not a module.
// DAEMON-PLUGINS-1 (docs/DAEMON-PLUGINS.md): the daemon API every daemon half compiles against, and each module's daemon half
// (`plugins/<folder>/<Module>/daemon`) as `:daemon-plugin-<folder>-<Module>` (flat: Gradle 9 will not configure directory-less parents), apart from the `:plugins:*` UI projects.
include(":superiorSoftLibrary:infantryDaemonApi")
include(":superiorSoftLibrary:infantryHarnessApi")
listOf("core", "official", "demo", "community", "examples").forEach { folder ->
    file("plugins/$folder").listFiles { f -> f.isDirectory && !f.name.startsWith("_") && java.io.File(f, "daemon/build.gradle.kts").isFile }.orEmpty().sortedBy { it.name }.forEach { dir ->
        include(":daemon-plugin-$folder-${dir.name}")
        project(":daemon-plugin-$folder-${dir.name}").projectDir = java.io.File(dir, "daemon")
    }
}
// HARNESS-PLUGIN-3: a module's harness half (`<Module>/harness/build.gradle.kts`) is `:harness-plugin-<folder>-<Module>`.
listOf("core", "official", "demo", "community", "examples").forEach { folder ->
    file("plugins/$folder").listFiles { f -> f.isDirectory && !f.name.startsWith("_") && java.io.File(f, "harness/build.gradle.kts").isFile }.orEmpty().sortedBy { it.name }.forEach { dir ->
        include(":harness-plugin-$folder-${dir.name}")
        project(":harness-plugin-$folder-${dir.name}").projectDir = java.io.File(dir, "harness")
    }
}
if (file("plugins/_template/daemon/build.gradle.kts").isFile) {
    include(":daemon-plugin-_template")
    project(":daemon-plugin-_template").projectDir = file("plugins/_template/daemon")
}
if (file("plugins/_template/build.gradle.kts").isFile) {
    include(":plugins:_template")
    project(":plugins:_template").projectDir = file("plugins/_template")
}
