// DAEMON-PLUGINS-1 (docs/DAEMON-PLUGINS.md): Infantry_Roles's daemon half, the MCP tools that used to be built into the CLI.
// AR-4b-ii-b: it also owns the role catalog (RoleService, bundled roles/, avatars), the role frames and the role binding / trait
// stores, and provides the DaemonApi roles ports. Compiled against the daemon API only (compileOnly: the daemon provides it at
// run time); the wire DTOs (agentHqProtocol) are shaded in; module.json's `daemon.jar` names the jar this builds.
// `:daemon-plugin-official-Infantry_Roles:jar`.
plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    jvmToolchain(21)
}

val shaded by configurations.creating { isTransitive = false }

dependencies {
    shaded(project(":superiorSoftLibrary:agentHqProtocol"))
    compileOnly(project(":superiorSoftLibrary:agentHqProtocol"))
    compileOnly(project(":superiorSoftLibrary:agentHqDaemonApi"))
    compileOnly(libs.kotlinx.serialization.json)
    testImplementation(project(":superiorSoftLibrary:agentHqDaemonApi"))
    testImplementation(project(":superiorSoftLibrary:agentHqProtocol"))
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.serialization.json)
}

// The role catalog dump (formerly :superiorSoftLibrary:agentHq:roleCatalog): the bundled roles and the avatar resolution chain.
tasks.register<JavaExec>("roleCatalog") {
    group = "application"
    description = "Prints the Infantry AI role catalog and the avatar resolution chain (Phase 10a)."
    mainClass.set("com.superiorsoftllc.agenthq.plugins.official.roles.daemon.RoleCatalogProbeKt")
    classpath = sourceSets["main"].runtimeClasspath + configurations["compileClasspath"]
    providers.gradleProperty("roles").orNull?.takeIf { it.isNotBlank() }?.let { args(it) }
}

tasks.jar {
    archiveFileName.set("Infantry_Roles-daemon.jar")
    // OfficialTrust: like the UI jar, the daemon jar carries a signed module.json equal to the one on disk. Signed when
    // -Pihq.keystore / -Pihq.alias are given (password in env IHQ_STOREPASS); a bundled or devTrust run needs no signature.
    from(layout.projectDirectory.file("../module.json"))
    from({ shaded.map { if (it.isDirectory) it else zipTree(it) } }) { exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/MANIFEST.MF") }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    val keystore = providers.gradleProperty("ihq.keystore")
    val alias = providers.gradleProperty("ihq.alias")
    val storePass = providers.environmentVariable("IHQ_STOREPASS")
    doLast {
        if (keystore.isPresent) {
            val file = archiveFile.get().asFile
            val jarsigner = File(File(System.getProperty("java.home"), "bin"), "jarsigner" + if (org.gradle.internal.os.OperatingSystem.current().isWindows) ".exe" else "").path
            val p = ProcessBuilder(jarsigner, "-keystore", keystore.get(), "-storepass", storePass.orNull ?: error("IHQ_STOREPASS is not set"), file.path, alias.orNull ?: error("-Pihq.alias is required")).redirectErrorStream(true).start()
            val text = p.inputStream.bufferedReader().readText()
            check(p.waitFor() == 0) { "jarsigner failed: $text" }
        }
    }
}

tasks.test { useJUnitPlatform() }
