// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// SDK-CENTRAL-1: the Maven Central Portal bundle. The SDK modules and build-logic publish into build/central-bundle
// (repository `centralBundle`, gradle/sdk-central.gradle.kts); scripts/sdk-central-bundle.sh runs those publishes, then
//   checkSdkCentralBundle  fails unless every POM has the fields Central requires, every jar-packaged component has its
//                          sources and javadoc jars, every file has .md5/.sha1, and (with -Pinfantry.central.sign=true)
//                          every file has its .asc signature;
//   sdkCentralBundle       zips the tree (without maven-metadata) into build/central-bundle.zip, the file
//                          scripts/sdk-central-upload.sh sends to the Portal API.
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

val bundleDir = layout.buildDirectory.dir("central-bundle")
val requireSignatures = findProperty("infantry.central.sign")?.toString() == "true"

val checkSdkCentralBundle = tasks.register("checkSdkCentralBundle") {
    group = "publishing"
    description = "Checks build/central-bundle has everything Maven Central requires."
    val dir = bundleDir.get().asFile
    val signed = requireSignatures
    doLast {
        if (!dir.isDirectory) throw GradleException("SDK-CENTRAL-1: $dir is missing; run scripts/sdk-central-bundle.sh")
        val problems = mutableListOf<String>()
        val poms = dir.walkTopDown().filter { it.isFile && it.name.endsWith(".pom") }.toList()
        if (poms.isEmpty()) problems += "no POM under $dir"
        fun Element.child(name: String): Element? =
            (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().firstOrNull { it.tagName == name }
        for (pom in poms) {
            val rel = pom.relativeTo(dir).invariantSeparatorsPath
            val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pom).documentElement
            fun need(path: String) {
                var e: Element? = root
                path.split('/').forEach { e = e?.child(it) }
                if (e?.textContent.isNullOrBlank()) problems += "$rel: <$path> missing"
            }
            listOf(
                "name", "description", "url", "licenses/license/name", "licenses/license/url",
                "developers/developer/name", "scm/url", "scm/connection",
            ).forEach(::need)
            val version = root.child("version")?.textContent.orEmpty()
            if (version.endsWith("-SNAPSHOT")) problems += "$rel: SNAPSHOT version $version"
            val packaging = root.child("packaging")?.textContent ?: "jar"
            if (packaging != "pom") {
                val base = pom.name.removeSuffix(".pom")
                listOf("sources", "javadoc").forEach { c ->
                    if (!File(pom.parentFile, "$base-$c.jar").isFile) problems += "$rel: $base-$c.jar missing"
                }
            }
        }
        val files = dir.walkTopDown().filter { f ->
            f.isFile && !f.name.startsWith("maven-metadata") &&
                listOf(".md5", ".sha1", ".sha256", ".sha512", ".asc").none { f.name.endsWith(it) }
        }.toList()
        for (f in files) {
            val rel = f.relativeTo(dir).invariantSeparatorsPath
            listOf(".md5", ".sha1").forEach { if (!File(f.path + it).isFile) problems += "$rel: $it missing" }
            if (signed && !File(f.path + ".asc").isFile) problems += "$rel: .asc signature missing"
        }
        if (problems.isNotEmpty()) {
            throw GradleException("SDK-CENTRAL-1: the Central bundle is incomplete:\n" + problems.joinToString("\n") { "  $it" })
        }
        logger.lifecycle(
            "checkSdkCentralBundle: ok, ${poms.size} POMs, ${files.size} files" + if (signed) ", all signed" else " (unsigned dry run)",
        )
    }
}

tasks.register("sdkCentralBundle", Zip::class.java) {
    group = "publishing"
    description = "Zips build/central-bundle into build/central-bundle.zip for the Central Portal upload."
    dependsOn(checkSdkCentralBundle)
    from(bundleDir) { exclude("**/maven-metadata*") }
    destinationDirectory.set(layout.buildDirectory)
    archiveFileName.set("central-bundle.zip")
}
