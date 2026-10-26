rootProject.name = "AgentHQ"
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
    }
}

// The directory prefix is kept from the monorepo so ":superiorSoftLibrary:..." project paths stay valid.
include(":superiorSoftLibrary")
include(":superiorSoftLibrary:agentHq")
include(":superiorSoftLibrary:agenthqUiCore")
// PLUGINS-1: the plugin API and the host's loading rules (no Compose, shared by the UI and the daemon).
include(":superiorSoftLibrary:agenthqPlugins")
include(":superiorSoftLibrary:agentHqUi")
// WEB-1: browser (wasmJs) client over agenthqUiCore; the daemon serves its bundle at /.
include(":superiorSoftLibrary:agentHqWeb")
include(":superiorSoftLibrary:agentHqAndroid")
include(":superiorSoftLibrary:agentHqHeadless")
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
if (file("plugins/_template/build.gradle.kts").isFile) {
    include(":plugins:_template")
    project(":plugins:_template").projectDir = file("plugins/_template")
}
