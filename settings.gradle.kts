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
include(":superiorSoftLibrary:agentHqUi")
// WEB-1: browser (wasmJs) client over agenthqUiCore; the daemon serves its bundle at /.
include(":superiorSoftLibrary:agentHqWeb")
include(":superiorSoftLibrary:agentHqAndroid")
include(":superiorSoftLibrary:agentHqHeadless")
// MD-PLUGIN-1: the example markdown engine plugin (built to a jar the app loads at runtime).
include(":superiorSoftLibrary:samples:markdownEnginePlugin")
