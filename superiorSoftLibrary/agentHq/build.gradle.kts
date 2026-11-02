// :superiorSoftLibrary:agentHq -- AgentHQ daemon + CLI. JVM ONLY.
//
// WHY JVM-ONLY AND NOT KMP: this module drives OS subprocesses, and the parked Pty4J path is
// JVM+native. A KMP module here would drag JS/WASM/Android targets into the build for a server
// that can only ever run on a JVM. Do NOT convert this to kotlin("multiplatform").
//
// Phase 0 verdict (agenthq-phase0-spike/FINDINGS.md): PIPES. PipedProcessRunner is the default;
// PtyProcessRunner ships behind the same seam but is inert because Pty4J was deliberately NOT
// added as a dependency.

plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.sqldelight)
    application
}

// SQLDelight 2.2.1, matching superiorSoftLibrary. The driver is the catalog's
// `sqlite-driver` -- NOT `sqldelight-jdbc-driver`. jdbc-driver needs a SQLite
// JDBC driver supplied separately (org.xerial:sqlite-jdbc) and that coordinate
// is NOT in libs.versions.toml, so it would break the "no new dependency
// beyond the catalog" constraint. sqlite-driver is a multiplatform artifact
// whose JVM variant is what resolves here; consuming it from a kotlin("jvm")
// module adds NO compilation targets to this module.
sqldelight {
    databases {
        create("AgentHQDatabase") {
            packageName.set("com.superiorsoftllc.agenthq.db")
            srcDirs.setFrom("src/main/sqldelight")
        }
    }
}

kotlin {
    jvmToolchain(21)
}

application {
    // The CLI is the installDist main entry point so `installDist` produces a real `agenthq` binary.
    mainClass.set("com.superiorsoftllc.agenthq.cli.CliMainKt")
    // BUG-G2: the launchers are `bin/agenthq` (POSIX / Git Bash) and `bin/agenthq.bat`, so the name a
    // prompt uses (`agenthq knowledge`) resolves on case-sensitive filesystems too. The daemon puts this
    // bin dir on every spawned session's PATH (AgentHqBin).
    applicationName = "agenthq"
    // BUILD-STAMP-1: the build string (root gradle/build-stamp.gradle.kts): `agenthq version` prints it, a daemon sends it in `ready`.
    applicationDefaultJvmArgs = listOf("-Dagenthq.build=${rootProject.extra["agenthqBuild"]}")
}

// Infantry AI: `infantry` is the product-name launcher; `agenthq` stays (agents, scripts and prompts still call it).
// Same script and classpath, copied next to the generated ones.
tasks.named<CreateStartScripts>("startScripts") {
    doLast {
        listOf("agenthq", "agenthq.bat").forEach { n ->
            val src = File(outputDir, n)
            if (src.isFile) src.copyTo(File(outputDir, n.replace("agenthq", "infantry")), overwrite = true)
        }
    }
}

dependencies {
    // Everything here is already in the version catalog via :superiorSoftService -- no new
    // coordinates are introduced.
    implementation(libs.kotlinx.coroutines.core)
    // PLUGINS-1: the module manifest and loading rules, shared with the desktop UI.
    implementation(project(":superiorSoftLibrary:agenthqPlugins"))
    // DAEMON-PLUGINS-1: the API daemon plugins compile against (docs/DAEMON-PLUGINS.md).
    implementation(project(":superiorSoftLibrary:agentHqDaemonApi"))
    // DP-5e: epics, brief, ledger, context, ticketfiles live in :agentHqBoard (still one daemon jar).
    api(project(":superiorSoftLibrary:agentHqBoard"))
    // HARNESS-PLUGIN-3 H3: the API harness plugins compile against (docs/HARNESS-PLUGINS.md section 3.1); a leaf.
    implementation(project(":superiorSoftLibrary:agentHqHarnessApi"))
    // CORE-SPLIT-1/2: the wire contract and the process runner are leaf modules; `api` keeps their types visible to :agentHqUi as before.
    api(project(":superiorSoftLibrary:agentHqProtocol"))
    api(project(":superiorSoftLibrary:agentHqRunner"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    // Persistence story 1/3: the `runs` table. The catalog's `sqlite-driver`
    // (app.cash.sqldelight:sqlite-driver, 2.2.1) -- NOT `sqldelight-jdbc-driver`, which would need
    // org.xerial:sqlite-jdbc supplied separately (the catalog now carries it as `sqlite-jdbc`, pinned below).
    // sqlite-driver resolves the JVM variant here and adds no compilation targets.
    implementation(libs.sqlite.driver)
    // Ticket 55d60a5e: the sqlite-jdbc sqlite-driver brings (3.51.0.0) deadlocks: a connection closing in WAL mode holds
    // the inode lock and waits for the unix-VFS mutex (3.51.0's unixIsSharingShmNode) while a connection opening holds
    // that mutex and waits for the inode lock (findReusableFd). Every later open queues on the mutex, so the daemon's
    // per-query connections all hang in NativeDB._open_utf8 (measured with gdb in the Docker image). SQLite 3.51.2 fixed
    // it; this pins the newer driver, which wins the version conflict over sqlite-driver's transitive 3.51.0.0.
    implementation(libs.sqlite.jdbc)
    // Phase 4.5: the rest of the SQLDelight JVM stack, all from the catalog. `runtime` and
    // `jdbc-driver` already arrive transitively through sqlite-driver; they are declared so the
    // module states what it compiles against instead of leaning on a transitive edge.
    // primitive-adapters supplies IntColumnAdapter (pods.max_concurrency). It is a KMP artifact,
    // but a kotlin("jvm") consumer resolves only its -jvm variant: no target is added here.
    implementation(libs.sqldelight.runtime)
    implementation(libs.sqldelight.jdbc.driver)
    implementation(libs.primitive.adapters)

    // BUG-INSTALL-SLF4J-1: Ktor and sqlite log through SLF4J, and with no provider SLF4J binds its NOP logger and
    // drops every line. slf4j-simple writes to the System.err of each call (src/main/resources/simplelogger.properties),
    // which the installed app routes to agenthq.log (agentHqUi InstallLogs.install), so the lines land in the one log
    // file without a second sink. runtimeOnly flows to :agentHqUi's runtime through its implementation(project) edge,
    // so the desktop (which runs this daemon in-process) gets the same one provider. Version = the slf4j-api Ktor
    // resolves (2.0.17); a provider ahead of or behind the API is reported as a mismatch by SLF4J.
    runtimeOnly("org.slf4j:slf4j-simple:2.0.17")

    // Ticket 66153313: worker sessions run at a lower CPU priority. Windows has no JVM API for a priority class or a Job
    // object, so the daemon calls Kernel32 through JNA (SetPriorityClass + a per-session Job with a priority-class limit).
    implementation("net.java.dev.jna:jna-platform:5.18.1")
    // RUN-CONSOLE (ticket 67f117b6): saved-command runs get a real pseudo-terminal (ConPTY / winpty on Windows, the
    // POSIX pty elsewhere), so tools print their colours, spinners and  progress. EPL-1.0, see THIRD-PARTY-NOTICES.md.
    // Pinned to 0.13.x: 0.14 needs kotlin-stdlib 2.4, this build is on 2.3.
    implementation("org.jetbrains.pty4j:pty4j:0.13.13")

    // AR-4b-ii-b: the role catalog classes (Role, RoleService, RoleCatalog) live in the Infantry_Roles daemon half; tests read them.
    testImplementation(project(":daemon-plugin-official-Infantry_Roles"))
    testImplementation(kotlin("test"))
    // BUILD-VB-1: VerifyBaseTaskTest runs preflight/verify-base.gradle.kts in a throwaway build.
    testImplementation(gradleTestKit())
}

// The catalog is inconsistent for Ktor: `ktor-server-cio` and `ktor-server-websockets` point at
// `ktorClientCio` (3.2.3) while `ktor-server-core` / content-negotiation / websockets-core resolve
// to `ktor` (3.4.1). Left mixed, `webSocket("/ws")` NEVER fires -- the 3.2.3 WebSockets plugin does
// not invoke the 3.4.1 route, so the handshake succeeds and the session dies with 1006. Pin every
// io.ktor module to the catalog's `ktor` version.
configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "io.ktor") {
            useVersion(libs.versions.ktor.get())
        }
    }
}

// The CLI client is a second entry point in the same module; the `application` plugin can only
// point at one main, so the client gets its own JavaExec task. It uses the JDK's own WebSocket
// client, so it needs no extra dependency.
fun agentHqTestTmp(project: Project): File {
    val hash = Integer.toHexString(project.projectDir.absolutePath.hashCode())
    return File(System.getProperty("java.io.tmpdir"), "agenthq-test-tmp/$hash")
}

tasks.register<JavaExec>("runClient") {
    group = "application"
    description = "Runs the AgentHQ CLI client against a running daemon."
    mainClass.set("com.superiorsoftllc.agenthq.client.ClientMainKt")
    classpath = sourceSets["main"].runtimeClasspath
}

// PERF-2: the JVM caps every AgentHQ daemon/UI launch shares (see jvm-caps.txt); agentHqUi reads the same file.
val agentHqJvmCaps: List<String> = file("jvm-caps.txt").readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

// Daemon entry point for development: `./gradlew :superiorSoftLibrary:agentHq:runDaemon`
tasks.register<JavaExec>("runDaemon") {
    group = "application"
    description = "Runs the AgentHQ daemon."
    mainClass.set("com.superiorsoftllc.agenthq.daemon.DaemonMainKt")
    classpath = sourceSets["main"].runtimeClasspath
    // BUG-G2: sessions run `agenthq ...`, so the CLI is installed first and its bin dir handed to the
    // daemon, which prepends it to every spawned session's PATH.
    dependsOn("installDist")
    environment("AGENTHQ_BIN", layout.buildDirectory.dir("install/agenthq/bin").get().asFile.absolutePath)
    // PERF-2: the same heap caps and GC as the installed app (jvm-caps.txt).
    jvmArgs(agentHqJvmCaps)
    systemProperty("agenthq.build", rootProject.extra["agenthqBuild"] as String) // BUILD-STAMP-1
}


// VerifyMigrationTask proves a hand-written .sqm migration reproduces the .sq schema. It is
// disabled repo-wide BY DESIGN (same line as superiorSoftLibrary/build.gradle.kts:97): the
// `runs` table is brand new, there is no shipped v0 to migrate, and a false mismatch here would
// fail a green test run for no product reason. Do NOT re-enable.
tasks.withType<app.cash.sqldelight.gradle.VerifyMigrationTask>().configureEach {
    enabled = false
}

// ---- Base-commit preflight ------------------------------------------------------------------
// The verifyBase task is defined in preflight/verify-base.gradle.kts (BUILD-VB-1), shared with its test.
apply(from = "preflight/verify-base.gradle.kts")

// ---- Merge preflight (stale-branch guard) ---------------------------------------------------
// verifyBase can only check the direction visible from inside a branch: that THIS checkout contains
// its declared base. It cannot see the hazard that has actually bitten us three times — a branch
// that is BEHIND the current feature-agenthq-mvp tip. Such a branch still merges (git combines both
// sides), but its GREEN was measured on a tree that never contained mvp's later work, and it can
// conflict on exactly the files it and mvp both touch (AgentProfiles.kt, for UX-7). AGENT-UX-12,
// AGENT-UX-7 and AGENT-UX-13 were all cut from a base that had already moved.
//
// Run this BEFORE a merge, from the mvp checkout:
//   ./gradlew :superiorSoftLibrary:agentHq:verifyMergeable -PmergeBranch=<branch>
// It fails when <branch> does not contain the current feature-agenthq-mvp tip. Rebase the branch
// onto the tip, re-gate, then merge. Override the tip ref with -PmvpRef=<ref>.
val verifyMergeable by tasks.registering {
    group = "verification"
    description =
        "Fails when the branch named by -PmergeBranch does not contain the current feature-agenthq-mvp tip."
    val checkoutDir = projectDir
    val mvpRef = (findProperty("mvpRef") as? String)?.trim()?.takeUnless { it.isEmpty() }
        ?: "feature-agenthq-mvp"
    val mergeBranch = (findProperty("mergeBranch") as? String)?.trim()
    doLast {
        fun git(vararg args: String): Pair<Int, String> {
            val process = ProcessBuilder(listOf("git") + args.toList())
                .directory(checkoutDir)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            return process.waitFor() to output
        }

        val branch = mergeBranch
        if (branch.isNullOrEmpty()) {
            throw GradleException(
                "verifyMergeable: pass the branch to merge, " +
                    "e.g. -PmergeBranch=feature-agenthq-ux13-agent-to-agent"
            )
        }

        val (tipExit, tipSha) = git("rev-parse", "--verify", "--quiet", "$mvpRef^{commit}")
        if (tipExit != 0) {
            throw GradleException("verifyMergeable: tip ref '$mvpRef' does not exist in this repository.")
        }
        val (branchExit, branchSha) = git("rev-parse", "--verify", "--quiet", "$branch^{commit}")
        if (branchExit != 0) {
            throw GradleException("verifyMergeable: branch '$branch' does not exist in this repository.")
        }

        when (git("merge-base", "--is-ancestor", mvpRef, branch).first) {
            0 -> println(
                "verifyMergeable: OK. '$branch' ($branchSha) contains the current $mvpRef tip $tipSha."
            )
            1 -> throw GradleException(
                "STALE BRANCH: '$branch' ($branchSha) does NOT contain the current $mvpRef tip $tipSha. " +
                    "Rebase '$branch' onto $mvpRef, re-gate, then merge — merging now would trust a " +
                    "GREEN measured on a tree without $mvpRef."
            )
            else -> throw GradleException("verifyMergeable: git merge-base failed for '$branch'.")
        }
    }
}

// Every compile path goes through the preflight, so a wrong base fails before any Kotlin is compiled.
tasks.matching { it.name == "compileKotlin" || it.name == "compileTestKotlin" }.configureEach {
    dependsOn("verifyBase")
}

// ---- SOAK-1: forward the `-Dagenthq.soak.*` knobs from the Gradle daemon into the test JVM ----
//
// The Gradle daemon's `-D` flags set the daemon's own JVM properties, not the test JVM's: the test
// worker is a separate process and only sees what we forward here. Without this forwarding, the
// SOAK-1 knobs in `SoakRunArchive.forCurrentRun()` and `CliAgentsLiveSoakTest.Companion.REPEAT`
// silently keep their defaults and a CLI run like
//   .\gradlew.bat :superiorSoftLibrary:agentHq:test -Dagenthq.soak.repeat=5
// reports "5 iterations" only inside the daemon's logs, not in the test.
//
// Forwarding order: a `-D` (JVM system property on the daemon) wins over the same name in
// `gradle.properties`, so a one-off run on the command line overrides any repo-wide default.
val soakKnobs = listOf(
    "agenthq.soak.pairs",
    "agenthq.soak.run",
    "agenthq.soak.repeat",
    "agenthq.soak.archive",
    "agenthq.soak.results",
)
// TEST-SPEED-1: a -javaagent that moves each parallel test fork into its own tmpdir and state dir (see TestForkAgent).
val testForkAgent: SourceSet = sourceSets.create("testForkAgent")
val testForkAgentJar = tasks.register<Jar>("testForkAgentJar") {
    archiveFileName.set("agenthq-test-fork-agent.jar")
    destinationDirectory.set(layout.buildDirectory.dir("testForkAgent"))
    from(testForkAgent.output)
    manifest { attributes("Premain-Class" to "com.superiorsoftllc.agenthq.testfork.TestForkAgent") }
}
tasks.named<JavaCompile>(testForkAgent.compileJavaTaskName) { options.release.set(21) }

// ContractFrameDocTest reads CONTRACT.md at run time, which Gradle cannot see as a test input. Declared
// here so a doc-only change (say, a removed frame row) re-runs the test instead of being served
// FROM-CACHE as the previous green result.
tasks.register("cleanTestTmp") {
    group = "build"
    doLast { agentHqTestTmp(project).deleteRecursively() }
}
tasks.named("clean") { dependsOn("cleanTestTmp") }

tasks.withType<Test>().configureEach {
    inputs.file("CONTRACT.md")
    // BUG-PERM-4: ToolApprovalMatrixTest loads the shipped sample descriptors, so editing one must re-run the tests.
    inputs.dir("docs/harnesses")
    // MAIL-FOOTER-1: AgentQuickRefCommandsTest parses every command in the quick reference; a doc edit must re-run it.
    inputs.file("docs/AGENT-QUICKREF.md")
    // U1: a daemon a test builds with the default limit meters must never read real credentials or call a provider.
    environment("AGENTHQ_LIMITS_LIVE", "false")
    // BUILD-VB-1: VerifyBaseTaskTest applies this exact script plugin to a throwaway TestKit build.
    val verifyBaseScript = file("preflight/verify-base.gradle.kts")
    inputs.file(verifyBaseScript)
    systemProperty("agenthq.verifyBase.script", verifyBaseScript.absolutePath)
    // A test JVM never sees the machine's real state dir (~/.agenthq): `agenthq remote on` there (network.properties,
    // remote.token) would otherwise leak remote=true and a stored token into every fixture daemon and CLI.
    val isolatedState = layout.buildDirectory.dir("test-state-dir").get().asFile
    systemProperty("AGENTHQ_STATE_DIR", isolatedState.absolutePath)
    // Test temp files (createTempFile SQLite DBs, zstd-jni's extracted libzstd-jni-*.dll) land in one per-project
    // folder, not loose in the real TEMP where they piled up ~1 GB/day; wiped per run and by `cleanTestTmp`.
    // It must stay OUTSIDE the repo: inside, git discovers the enclosing checkout for "not a repo" fixtures.
    val testTmp = agentHqTestTmp(project)
    jvmArgs("-Djava.io.tmpdir=${testTmp.absolutePath}")
    doFirst {
        isolatedState.deleteRecursively(); isolatedState.mkdirs()
        testTmp.deleteRecursively(); testTmp.mkdirs()
    }
    // TEST-SPEED-1: the suite was fully serial (1 fork, ~13 min for ~780 s of tests on a 16-core host). Forks run test
    // CLASSES in parallel; a class still runs its own tests in order. Each fork gets its own <testTmp>/fork-<pid> and
    // <test-state-dir>/fork-<pid> from TestForkAgent (all forks share one command line, so the agent picks the folder
    // inside the fork; JDK createTempFile still uses the shared parent, unique names). Fixture daemons bind ephemeral ports.
    // The heap cap keeps 4 forks inside the host RAM budget (one fork peaks ~800 MB). Override: -Pagenthq.testForks=1.
    val agentJar = testForkAgentJar.flatMap { it.archiveFile }
    inputs.file(agentJar).withPropertyName("testForkAgent").withNormalizer(ClasspathNormalizer::class.java)
    jvmArgumentProviders += CommandLineArgumentProvider { listOf("-javaagent:${agentJar.get().asFile.absolutePath}") }
    maxParallelForks = providers.gradleProperty("agenthq.testForks").map { it.toInt() }.getOrElse(4)
    maxHeapSize = "2g"
    // DAEMON-PLUGINS-1: the daemon tests load the real daemon halves (TestDaemonPlugins), so their jars are built first.
    dependsOn(":daemon-plugin-demo-Demo_Lock:jar", ":daemon-plugin-official-Infantry_Board:jar", ":daemon-plugin-official-Infantry_Usage:jar", ":daemon-plugin-official-Infantry_Processes:jar", ":daemon-plugin-official-Infantry_Agents:jar", ":daemon-plugin-official-Infantry_Roles:jar", ":daemon-plugin-official-Infantry_Chat:jar", ":daemon-plugin-official-Infantry_Git:jar", ":daemon-plugin-official-Infantry_Attention:jar", ":daemon-plugin-official-Infantry_Settings:jar", ":daemon-plugin-official-Infantry_Approvals:jar", ":daemon-plugin-official-Infantry_Run:jar", ":daemon-plugin-examples-Acme_DaemonHello:jar", ":daemon-plugin-examples-Acme_PolicyHook:jar", ":daemon-plugin-examples-Acme_NewsAgent:jar")
    // HARNESS-PLUGIN-3: the harness E2E loads the example harness module's built jar.
    dependsOn(":harness-plugin-examples-Acme_EchoHarness:jar")
}

tasks.withType<Test>().configureEach {
    soakKnobs.forEach { knob ->
        val fromCmd = System.getProperty(knob)?.takeUnless { it.isBlank() }
        val fromGradleProps = providers.gradleProperty(knob).orNull?.takeUnless { it.isBlank() }
        val value = fromCmd ?: fromGradleProps
        if (value != null) systemProperty(knob, value)
    }
}

// TEST-SPEED-3: two tiers split by class name. `test` still runs BOTH (the gate is unchanged); `testFast` is the quick
// inner loop (~240 s of tests, unit-level), `testSlow` is the rest (~550 s: real git, CLI processes, fixture daemons,
// E2E/soak/live). Every class is in exactly one tier: the slow patterns are an include list for one and an exclude list
// for the other. A new slow class joins `testSlow` by its name (suffix or package) or by adding a pattern here.
val slowTestPatterns = listOf(
    "*E2eTest", "*E2ETest", "*SoakTest", "*LiveTest", "*DaemonTest", "*AcceptanceTest", "*IntegrationTest",
    "com.superiorsoftllc.agenthq.cli.*",
    "com.superiorsoftllc.agenthq.projectgit.*",
    "com.superiorsoftllc.agenthq.worktree.*",
    "*.VerifyBaseTaskTest",
    "*.ToolApprovalMatrixTest",
)
val unitTest = tasks.named<Test>("test")
tasks.register<Test>("testFast") {
    group = "verification"
    description = "TEST-SPEED-3: the fast tier (every test class NOT matched by slowTestPatterns)."
    testClassesDirs = unitTest.get().testClassesDirs
    classpath = unitTest.get().classpath
    filter { slowTestPatterns.forEach { excludeTestsMatching(it) } }
}
tasks.register<Test>("testSlow") {
    group = "verification"
    description = "TEST-SPEED-3: the slow tier (test classes matched by slowTestPatterns)."
    testClassesDirs = unitTest.get().testClassesDirs
    classpath = unitTest.get().classpath
    filter { slowTestPatterns.forEach { includeTestsMatching(it) } }
}

// PERF-1: `./gradlew :superiorSoftLibrary:agentHq:perfProfile` -- the profile lives in agentHqUi (it needs the UI's
// classpath and the window's Controller), so this is an alias. See agentHqUi/build.gradle.kts `perfProfile`.
tasks.register("perfProfile") {
    group = "verification"
    description = "PERF-1: alias of :superiorSoftLibrary:agentHqUi:perfProfile (daemon + UI memory/CPU/startup profile)."
    dependsOn(":superiorSoftLibrary:agentHqUi:perfProfile")
}

// DAEMON-PLUGINS-1 (docs/DAEMON-PLUGINS.md): an install ships its daemon plugins in `<install>/modules/official/<Module>/`
// (module.json, `policies/`, the built daemon jar): the bundled root the daemon defaults to (bundled = trusted). Every
// official module with a daemon half is in installDist; the docker image adds the demo lock.
// PACKAGE-1: a module has a daemon half when its module.json has a `daemon` block (gradle/daemon-halves.gradle.kts); its
// module.json `daemon.jar` is what ships, and installDist / dockerContext fail when a declared jar is not staged.
@Suppress("UNCHECKED_CAST")
val ihqDaemonJar = rootProject.extra["ihqDaemonJar"] as (File) -> String?
@Suppress("UNCHECKED_CAST")
val ihqHarnessJar = rootProject.extra["ihqHarnessJar"] as (File) -> String?
@Suppress("UNCHECKED_CAST")
val ihqVerifyDaemonHalves = rootProject.extra["ihqVerifyDaemonHalves"] as (File, String, List<String>) -> Unit
// HARNESS-PLUGIN-3: a module with a harness half (module.json `harness.jar`) ships the same way, its jar from `:harness-plugin-*`.
fun daemonModuleDirs(folder: String): List<File> = rootProject.file("plugins/$folder")
    .listFiles { f -> f.isDirectory && !f.name.startsWith("_") && (ihqDaemonJar(f) != null || ihqHarnessJar(f) != null) }.orEmpty().sortedBy { it.name }
fun Task.dependsOnDaemonJars(folder: String) = daemonModuleDirs(folder).forEach { dir ->
    val producers = listOfNotNull(
        ihqDaemonJar(dir)?.let { ":daemon-plugin-$folder-${dir.name}" },
        ihqHarnessJar(dir)?.let { ":harness-plugin-$folder-${dir.name}" },
    )
    for (producer in producers) {
        checkNotNull(rootProject.findProject(producer)) { "PACKAGE-1: plugins/$folder/${dir.name} declares a daemon or harness half but $producer does not exist" }
        dependsOn("$producer:jar")
    }
}
fun CopySpec.daemonModules(folder: String, prefix: String) {
    for (dir in daemonModuleDirs(folder)) from(dir) {
        include(listOfNotNull("module.json", "policies/**", ihqDaemonJar(dir), ihqHarnessJar(dir)))
        into("$prefix/modules/official/${dir.name}")
    }
}
tasks.named<Sync>("installDist") {
    dependsOnDaemonJars("official")
    daemonModules("official", ".")
    val modules = destinationDir.resolve("modules")
    val expected = daemonModuleDirs("official").map { it.name }
    doLast { ihqVerifyDaemonHalves(modules, "installDist", expected) }
}
tasks.matching { it.name == "check" }.configureEach { dependsOn(":verifyDaemonHalvesGuard") }

// DOCKER-1: the build context for the headless daemon image: `agenthq/` (installDist) + the Dockerfile. Nothing is pushed.
//   ./gradlew :superiorSoftLibrary:agentHq:dockerContext
//   docker build -t infantryai/infantry-ai:dev superiorSoftLibrary/agentHq/build/docker
tasks.register<Sync>("dockerContext") {
    group = "distribution"
    description = "DOCKER-1: assembles the Docker build context (installDist + docker/Dockerfile) in build/docker."
    dependsOn("installDist")
    from(layout.buildDirectory.dir("install/agenthq")) { into("agenthq") }
    // WEB-1: the browser client; the Dockerfile points AGENTHQ_WEB_DIR at /opt/agenthq/web.
    dependsOn(webBundleTask)
    from(webBundleDir) { into("agenthq/web") }
    from(rootProject.layout.projectDirectory.file("docker/Dockerfile"))
    // DOCKER-2: the entrypoint (first-start token in the state volume, JAVA_OPTS).
    from(rootProject.layout.projectDirectory.file("docker/entrypoint.sh"))
    // SANDBOX-1: the Landlock launcher the Dockerfile compiles (limits.sandbox.mode).
    from(rootProject.layout.projectDirectory.file("docker/landlock-exec.c"))
    // POLICY-1: the policy presets the plugins ship as data (the demo preset: plugins/demo/Demo_Lock/policies); the Dockerfile
    // points AGENTHQ_POLICY_DIRS at /opt/agenthq/policies. A preset is only active when selected (AGENTHQ_DEMO_LOCK=1 selects demo).
    from(rootProject.layout.projectDirectory.dir("plugins/demo/Demo_Lock/policies")) { into("agenthq/policies") }
    // DAEMON-PLUGINS-1: the demo lock's daemon half (bundled, so trusted); inert unless a seated preset is active.
    dependsOnDaemonJars("demo")
    daemonModules("demo", "agenthq")
    into(layout.buildDirectory.dir("docker"))
    val modules = layout.buildDirectory.dir("docker/agenthq/modules")
    val expected = (daemonModuleDirs("official") + daemonModuleDirs("demo")).map { it.name }
    doLast { ihqVerifyDaemonHalves(modules.get().asFile, "dockerContext", expected) }
}

// HEADLESS-DIST-1: the downloadable server, no Docker and no Java install needed. The archive is installDist (lib/ and the
// `agenthq` CLI) + bin/agenthq-server.{sh,bat} (the daemon) + runtime/ (a jlink JRE for THIS build machine's OS/arch) +
// README.txt. Release CI builds it once per OS (release.yml `headless`).
//   ./gradlew :superiorSoftLibrary:agentHq:headlessDist   -> build/distributions/InfantryAI-server-<version>-<os>-<arch>.{zip|tar.gz}
val headlessVersion: String = providers.gradleProperty("agenthqVersion").getOrElse("dev")
val headlessOs: String = System.getProperty("os.name").lowercase().let {
    when {
        it.startsWith("windows") -> "windows"
        it.startsWith("mac") -> "macos"
        else -> "linux"
    }
}
// Spelled as ServerArchive.hostPlatform() spells it, so the updater finds the archive for its own CPU (x64, arm64).
val headlessArch: String = System.getProperty("os.arch").let { if (it == "amd64" || it == "x86_64") "x64" else if (it == "aarch64") "arm64" else it }
val headlessBaseName = "InfantryAI-server-$headlessVersion-$headlessOs-$headlessArch"
val headlessJdk = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }

val headlessRuntime = tasks.register<Exec>("headlessRuntime") {
    group = "distribution"
    description = "HEADLESS-DIST-1: a trimmed Java 21 runtime (jlink) for the headless server archive."
    val out = layout.buildDirectory.dir("headless/runtime")
    outputs.dir(out)
    doFirst {
        delete(out)
        val jlink = headlessJdk.get().metadata.installationPath.file(if (headlessOs == "windows") "bin/jlink.exe" else "bin/jlink")
        // java.se covers everything the daemon and its libraries use; jdk.unsupported (Ktor/coroutines), jdk.crypto.ec (TLS),
        // jdk.zipfs and jdk.charsets are outside it.
        commandLine(
            jlink.asFile.absolutePath,
            "--add-modules", "java.se,jdk.unsupported,jdk.crypto.ec,jdk.zipfs,jdk.charsets,jdk.localedata",
            "--strip-debug", "--no-header-files", "--no-man-pages", "--compress=zip-6",
            "--output", out.get().asFile.absolutePath,
        )
    }
}

// WEB-1: the browser client's production bundle (:agentHqWeb:assembleWeb). Only the distributions (headless archive, Docker
// context) build it: installDist, test and check never compile wasm.
val webBundleTask = ":superiorSoftLibrary:agentHqWeb:assembleWeb"
val webBundleDir = project(":superiorSoftLibrary:agentHqWeb").layout.buildDirectory.dir("assembleWeb")

val headlessContents: CopySpec = copySpec {
    into(headlessBaseName)
    from(tasks.named("installDist")) {
        // bin/infantry here is the plain startScripts copy; the supervisor launcher from headless/ (below) is the one to ship.
        exclude("bin/infantry")
        // The CLI launchers honour JAVA_HOME only: point an unset JAVA_HOME at the bundled runtime.
        filesMatching("bin/agenthq") {
            filter { line ->
                if (line.startsWith("if [ -n \"\$JAVA_HOME\" ]")) {
                    "if [ -z \"\$JAVA_HOME\" ] && [ -x \"\$APP_HOME/runtime/bin/java\" ] ; then JAVA_HOME=\$APP_HOME/runtime ; fi\n$line"
                } else line
            }
        }
        filesMatching("bin/agenthq.bat") {
            filter { line ->
                if (line.startsWith("if defined JAVA_HOME goto")) {
                    """if not defined JAVA_HOME if exist "%APP_HOME%\runtime\bin\java.exe" set "JAVA_HOME=%APP_HOME%\runtime"""" + "\r\n" + line
                } else line
            }
        }
    }
    from(headlessRuntime) { into("runtime") }
    from("headless") {
        include("agenthq-server.sh", "agenthq-server.bat", "infantry", "infantry.cmd")
        into("bin")
    }
    from("headless/README.txt")
    // WEB-1: the browser client, served at / (WebBundle finds <AGENTHQ_APP_HOME>/web; the launcher sets the app home).
    from(webBundleDir) { into("web") }
    // The running version, read by the daemon's update routes (ServerUpdateService.fromEnvironment).
    from(resources.text.fromString(headlessVersion)) { rename { "VERSION" } }
    // BUILD-STAMP-1: the server's build string, read by BuildInfo when no -Dagenthq.build reached the JVM.
    from(resources.text.fromString(rootProject.extra["agenthqBuild"] as String)) { rename { "BUILD" } }
    eachFile {
        if (path.endsWith(".sh") || path.endsWith("/bin/agenthq") || path.endsWith("/bin/infantry") || path.contains("/runtime/bin/") || path.contains("/runtime/lib/jspawnhelper")) {
            permissions { unix("rwxr-xr-x") }
        }
    }
}

tasks.register<Zip>("headlessDistZip") {
    group = "distribution"
    description = "HEADLESS-DIST-1: the headless server as a zip (Windows)."
    archiveFileName.set("$headlessBaseName.zip")
    dependsOn(webBundleTask)
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    with(headlessContents)
}

tasks.register<Tar>("headlessDistTar") {
    group = "distribution"
    description = "HEADLESS-DIST-1: the headless server as a tar.gz (Linux/macOS, keeps the executable bits)."
    archiveFileName.set("$headlessBaseName.tar.gz")
    compression = Compression.GZIP
    dependsOn(webBundleTask)
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    with(headlessContents)
}

tasks.register("headlessDist") {
    group = "distribution"
    description = "HEADLESS-DIST-1: the headless server archive for this OS (zip on Windows, tar.gz elsewhere)."
    dependsOn(if (headlessOs == "windows") "headlessDistZip" else "headlessDistTar")
}

// GATE-LOCK-1: one test run per build dir (see the script).
apply(from = rootProject.file("gradle/test-run-lock.gradle.kts"))
