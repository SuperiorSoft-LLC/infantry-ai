// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// LICENSE-NOTICES-2: SBOM of the shipped artifacts. DRAFT until the public launch.
// Run: ./gradlew generateSbom --console=plain   -> build/sbom/<artifact>.cdx.json (CycloneDX 1.5 JSON, components only)
// Each artifact's runtime classpath is resolved inside its own project and every external module is listed.
// Artifacts: daemon (infantry), desktop (infantryUi), android (infantryAndroid, release). A missing configuration is
// skipped with a warning, never silently. THIRD-PARTY-NOTICES.md is written by hand from this list.
val sbomTargets = mapOf(
    "daemon" to (":superiorSoftLibrary:infantry" to listOf("jvmRuntimeClasspath", "runtimeClasspath")),
    "desktop" to (":superiorSoftLibrary:infantryUi" to listOf("jvmRuntimeClasspath", "runtimeClasspath")),
    "android" to (":superiorSoftLibrary:infantryAndroid" to listOf("releaseRuntimeClasspath")),
)
val sbomRoot = rootProject
gradle.projectsEvaluated {
    val perArtifact = sbomTargets.mapNotNull { (name, spec) ->
        val p = sbomRoot.findProject(spec.first) ?: return@mapNotNull null
        p.tasks.register("generateSbom") {
            val outDir = sbomRoot.layout.buildDirectory.dir("sbom")
            doLast {
                val cfg = spec.second.firstNotNullOfOrNull { p.configurations.findByName(it) }
                if (cfg == null || !cfg.isCanBeResolved) {
                    p.logger.warn("generateSbom: no resolvable classpath for $name"); return@doLast
                }
                val dir = outDir.get().asFile.apply { mkdirs() }
                val mods = cfg.incoming.resolutionResult.allComponents
                    .mapNotNull { it.moduleVersion }
                    .filter { it.group.isNotBlank() && it.version != "unspecified" }
                    .map { Triple(it.group, it.name, it.version) }.distinct().sortedBy { "${it.first}:${it.second}" }
                val comps = mods.joinToString(",\n") { (g, n, v) ->
                    """    {"type":"library","group":"$g","name":"$n","version":"$v","purl":"pkg:maven/$g/$n@$v"}"""
                }
                File(dir, "$name.cdx.json").writeText(
                    "{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.5\",\"version\":1,\"metadata\":{\"component\":" +
                        "{\"type\":\"application\",\"name\":\"infantry-$name\"}},\"components\":[\n$comps\n]}\n")
                p.logger.lifecycle("generateSbom: $name -> ${mods.size} components")
            }
        }
    }
    sbomRoot.tasks.register("generateSbom") {
        group = "verification"
        description = "Writes a CycloneDX JSON component list per shipped artifact to build/sbom."
        dependsOn(perArtifact)
    }
}
