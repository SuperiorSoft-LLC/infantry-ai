// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// SDK-CENTRAL-1: what Maven Central needs from every published SDK artifact, shared by the SDK modules
// (gradle/sdk-publish.gradle.kts applies it) and the Gradle plugin (build-logic/build.gradle.kts applies it):
//   * a complete POM: name, description, url, licenses (SPDX id from scripts/license-paths.txt), developers, scm;
//   * a sources jar and a javadoc jar (empty: the KDoc is the API pages in docs/wiki) next to every jar;
//   * the repository `centralBundle` (build/central-bundle at the repo root): the exact file tree the Central Portal
//     upload takes (root build.gradle.kts zips and checks it: `sdkCentralBundle`, `checkSdkCentralBundle`);
//   * GPG signatures, only when -Pinfantry.central.sign=true (the publish job). Then the in-memory key is REQUIRED:
//     `signingInMemoryKey` / `signingInMemoryKeyPassword` (ORG_GRADLE_PROJECT_signingInMemoryKey... in CI). Missing ->
//     the build fails with "signing secrets missing"; nothing is ever published unsigned. The dry run signs nothing.
// Apply it AFTER the module's own plugins block and after maven-publish.
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.AbstractPublishToMaven
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.jvm.tasks.Jar
import org.gradle.plugins.signing.Sign
import org.gradle.plugins.signing.SigningExtension

apply(plugin = "maven-publish")

// The repo root is the folder holding scripts/license-paths.txt (the main build's root, or build-logic's parent).
val repoRoot: File = generateSequence(project.projectDir) { it.parentFile }
    .firstOrNull { File(it, "scripts/license-paths.txt").isFile }
    ?: error("SDK-CENTRAL-1: scripts/license-paths.txt not found above ${project.projectDir}")

// License by path prefix, first match wins; anything not listed is Apache-2.0 (the file's own rule).
val spdxLicense: String = run {
    val rel = project.projectDir.relativeTo(repoRoot).invariantSeparatorsPath + "/"
    File(repoRoot, "scripts/license-paths.txt").readLines()
        .filter { it.isNotBlank() && !it.startsWith("#") && it.contains('\t') }
        .map { it.substringBefore('\t') to it.substringAfter('\t').trim() }
        .firstOrNull { (prefix, _) -> rel.startsWith(prefix) }?.second ?: "Apache-2.0"
}
val licenseUrls = mapOf(
    "Apache-2.0" to "https://www.apache.org/licenses/LICENSE-2.0.txt",
    "MPL-2.0" to "https://www.mozilla.org/en-US/MPL/2.0/",
)
val licenseId = (findProperty("infantry.license") ?: spdxLicense).toString()
val licenseUrl = licenseUrls[licenseId]
    ?: error("SDK-CENTRAL-1: no licence URL for '$licenseId' (${project.path}); add it to licenseUrls in gradle/sdk-central.gradle.kts")

val siteUrl = (findProperty("infantry.url") ?: "https://github.com/SuperiorSoft-LLC/infantry-ai").toString()
val scmUrl = (findProperty("infantry.scm.url") ?: "https://github.com/SuperiorSoft-LLC/infantry-ai").toString()
val scmConnection = (findProperty("infantry.scm.connection") ?: "scm:git:https://github.com/SuperiorSoft-LLC/infantry-ai.git").toString()
val scmDevConnection = (findProperty("infantry.scm.developerConnection") ?: "scm:git:ssh://git@github.com/SuperiorSoft-LLC/infantry-ai.git").toString()

val centralBundleDir = File(repoRoot, "build/central-bundle")
val signForCentral = findProperty("infantry.central.sign")?.toString() == "true"

// A JVM jar component carries a sources jar (Kotlin sources included). KMP adds its own per target.
pluginManager.withPlugin("java") {
    extensions.configure<JavaPluginExtension>("java") { withSourcesJar() }
}

// POM packaging "pom" (a BOM, a Gradle plugin marker) needs no jars; everything else gets an empty javadoc jar, and a
// version catalog (packaging toml) an empty sources jar too.
val isPlatform = pluginManager.hasPlugin("java-platform")
val isCatalog = pluginManager.hasPlugin("version-catalog")
fun MavenPublication.isPomOnly() = isPlatform || name.endsWith("PluginMarkerMaven")

fun emptyJar(publication: String, classifier: String) =
    tasks.register("${publication}${classifier.replaceFirstChar { it.uppercase() }}Jar", Jar::class.java) {
        // One jar per publication, so no two Sign tasks write the same .asc.
        archiveBaseName.set("${project.name}-$publication")
        archiveClassifier.set(classifier)
        destinationDirectory.set(layout.buildDirectory.dir("central-jars"))
    }

extensions.configure<PublishingExtension>("publishing") {
    publications.withType(MavenPublication::class.java).configureEach {
        val pub = this
        if (!pub.isPomOnly()) {
            pub.artifact(emptyJar(pub.name, "javadoc"))
            if (isCatalog) pub.artifact(emptyJar(pub.name, "sources"))
        }
        pom {
            // name/description are the module's to set (sdk-publish sets them); these are the fallbacks.
            name.convention(pub.artifactId)
            description.convention("Infantry AI plugin SDK: ${pub.artifactId}.")
            url.set(siteUrl)
            licenses {
                license {
                    name.set(licenseId)
                    url.set(licenseUrl)
                    distribution.set("repo")
                }
            }
            developers {
                developer {
                    id.set("superiorsoft")
                    name.set("SuperiorSoft LLC")
                    organization.set("SuperiorSoft LLC")
                    organizationUrl.set(siteUrl)
                }
            }
            scm {
                url.set(scmUrl)
                connection.set(scmConnection)
                developerConnection.set(scmDevConnection)
            }
        }
    }
    repositories {
        maven {
            name = "centralBundle"
            url = centralBundleDir.toURI()
        }
    }
}

if (signForCentral) {
    val key = findProperty("signingInMemoryKey")?.toString()
    val password = findProperty("signingInMemoryKeyPassword")?.toString()
    if (key.isNullOrBlank() || password == null) {
        throw GradleException(
            "SDK-CENTRAL-1: signing secrets missing (signingInMemoryKey / signingInMemoryKeyPassword; in CI the secrets " +
                "MAVEN_SIGNING_KEY / MAVEN_SIGNING_PASSWORD). Refusing to publish unsigned.",
        )
    }
    apply(plugin = "signing")
    extensions.configure<SigningExtension>("signing") {
        useInMemoryPgpKeys(key, password)
        isRequired = true
        sign(extensions.getByType(PublishingExtension::class.java).publications)
    }
    // KMP publications share files (the root module, the tooling metadata): make every publish wait for every signature.
    tasks.withType(AbstractPublishToMaven::class.java).configureEach { dependsOn(tasks.withType(Sign::class.java)) }
}
