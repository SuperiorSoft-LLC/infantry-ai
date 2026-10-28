// Desktop window shell. Shared screens and daemon client live in :agenthqUiCore.
import java.time.Duration
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.jar.JarFile

plugins {
    kotlin("jvm")
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeMultiplatform)
    // PKG-1: no Gradle `application` plugin. Compose Desktop's `application {}` (bottom of this file) owns `run`
    // and the package tasks; both plugins register `run`, so they cannot coexist. Its entry (AppMainKt) is the dev
    // window under `run`, so `run` behaves exactly as before.
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":superiorSoftLibrary:agenthqUiCore"))
    // S4 (drop-in path): the app UI is a plugin of this shell: on the run-time classpath only, found through
    // ServiceLoader, never compiled against (a compile error here is a leak; see docs/SHELL-LEAKS.md). The tests
    // compile against it: they drive the app UI's panes.
    runtimeOnly(project(":superiorSoftLibrary:agenthqAppUi"))
    testImplementation(project(":superiorSoftLibrary:agenthqAppUi"))
    // RUNTIME-3: the app loads the official Infantry_Cli jar at run time (plugins/official); the UI tests, which start
    // from an empty plugins root, carry it on their class path instead (its jar's ServiceLoader entry), so they see the
    // same CLI tab the app shows with the jar present.
    testRuntimeOnly(project(":plugins:official:Infantry_Cli"))
    // RUNTIME-4: likewise Infantry_Agents, the left panel's agents section (tests that build a factory name InfantryAgents).
    testImplementation(project(":plugins:official:Infantry_Agents"))
    // RUNTIME-5: and Infantry_Settings, the Settings host tab (the tab shots open it).
    testRuntimeOnly(project(":plugins:official:Infantry_Settings"))
    // RUNTIME-6: and Infantry_Git, the Git tab and its tree (the Git pane tests draw it).
    testImplementation(project(":plugins:official:Infantry_Git"))
    // RUNTIME-7: and Infantry_Run, the Run tab and its tree (the Run pane tests draw it).
    testImplementation(project(":plugins:official:Infantry_Run"))
    // RUNTIME-8: and Infantry_Notes and Infantry_Changes, the Notes and Changes tabs and their trees.
    testImplementation(project(":plugins:official:Infantry_Notes"))
    testImplementation(project(":plugins:official:Infantry_Changes"))
    // RUNTIME-9: and Infantry_Prompts and Infantry_Skills, the right-hand Prompts and Skills tabs.
    testImplementation(project(":plugins:official:Infantry_Prompts"))
    testImplementation(project(":plugins:official:Infantry_Skills"))
    // RUNTIME-10: and Infantry_Stats and Infantry_Usage (the Stats and Usage pane tests draw them).
    testImplementation(project(":plugins:official:Infantry_Stats"))
    testImplementation(project(":plugins:official:Infantry_Usage"))
    // RUNTIME-11: and Infantry_Epics and Infantry_Review (the Epics pane test draws it; the tab shots open both).
    testImplementation(project(":plugins:official:Infantry_Epics"))
    testImplementation(project(":plugins:official:Infantry_Review"))
    // RUNTIME-12: and Infantry_Pods and Infantry_Profiles (the profile UI test opens Profiles; the tab shots open both).
    testImplementation(project(":plugins:official:Infantry_Pods"))
    testImplementation(project(":plugins:official:Infantry_Profiles"))
    // RUNTIME-13: and Infantry_Triggers and Infantry_Attention (the question UI tests open Attention; the tab shots open both).
    testImplementation(project(":plugins:official:Infantry_Triggers"))
    testImplementation(project(":plugins:official:Infantry_Attention"))
    // RUNTIME-14: and Infantry_Roles (the role UI tests open the Roles tab and the roles window).
    testImplementation(project(":plugins:official:Infantry_Roles"))
    // RUNTIME-15: and Infantry_Board (the board tests draw its pane and open its overlay).
    testImplementation(project(":plugins:official:Infantry_Board"))
    // RUNTIME-16: and Infantry_Alerts (the sound and voice fills; the voice and sound UI tests run the whole window).
    testImplementation(project(":plugins:official:Infantry_Alerts"))
    // RUNTIME-18: and Infantry_Processes (the status bar's processes badge; its UI tests run the whole window).
    testImplementation(project(":plugins:official:Infantry_Processes"))
    // RUNTIME-20: and Infantry_Header (the title bar; every window test draws it).
    testImplementation(project(":plugins:official:Infantry_Header"))
    // RUNTIME-22: and Infantry_Chat (the centre column; its tags and sections).
    testImplementation(project(":plugins:official:Infantry_Chat"))
    // RUNTIME-23: and Infantry_Approvals (the questions pill and its tags).
    testImplementation(project(":plugins:official:Infantry_Approvals"))
    // RUNTIME-24: and Infantry_Assistant (the Assistant popup).
    testImplementation(project(":plugins:official:Infantry_Assistant"))
    implementation(compose.desktop.currentOs)
    // UPDATE-1: the update dialog (UpdatePrompt.kt); the same material3 :agenthqUiCore already ships.
    implementation(compose.material3)
    // UI-REAL: the production desktop entry (RealDaemonMain) boots the REAL daemon in-process, so the
    // daemon is a runtime dependency of the desktop, not only of the tests. ktor-server-core is needed on the
    // compile classpath for EmbeddedServer.start; the engine and websocket modules arrive via :agentHq.
    implementation(project(":superiorSoftLibrary:agentHq"))
    implementation(libs.ktor.server.core)
    // NS4: app.json (ShellApp.kt).
    implementation(libs.kotlinx.serialization.json)
    // RUN-CONSOLE (ticket 67f117b6): the run console window is JetBrains' terminal emulator, taken under its Apache-2.0
    // option (dual LGPL-3.0 / Apache-2.0), see THIRD-PARTY-NOTICES.md. 3.72 is the last release on kotlin-stdlib 2.1.
    implementation("org.jetbrains.jediterm:jediterm-core:3.72")
    implementation("org.jetbrains.jediterm:jediterm-ui:3.72")

    testImplementation(project(":superiorSoftLibrary:agentHq"))
    testImplementation(kotlin("test"))
    // A real window test: drives the composed screens (click a tab, type, press a button) against the real daemon.
    @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
    testImplementation(compose.uiTest)
    testImplementation(libs.kotlinx.coroutines.core)
    // The integration test boots the real daemon in-process; its server types are `implementation` in :agentHq.
    testImplementation(libs.ktor.server.core)
    testImplementation(libs.ktor.server.cio)
    testImplementation(libs.ktor.server.websockets)
    testImplementation(libs.ktor.server.content.negotiation)
    testImplementation(libs.ktor.serialization.kotlinx.json)
}

// Same pin as :agentHq (the catalog mixes Ktor 3.2.3 and 3.4.1; a mixed server never fires webSocket).
// Needed here because the integration test boots the real daemon on this module's test classpath.
configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "io.ktor") {
            useVersion(libs.versions.ktor.get())
        }
    }
}

// BUG-INSTALL-DUPJARS-1: these four JetBrains modules are redirects. Each desktop jar holds no classes (only
// META-INF/MANIFEST.MF + a .kotlin_module) and depends on the real androidx module of the SAME jar name at another
// version (e.g. org.jetbrains.compose.runtime:runtime-desktop-1.10.3.jar -> androidx.compose.runtime:runtime-desktop-1.10.5.jar).
// The groups differ, so Gradle never sees a conflict and both jars landed in AgentHQ.cfg. Fix: each androidx module
// also declares its stub's capability, so the pair conflicts and the androidx module is selected. Gradle rewires the
// stub's dependents to it, so the androidx jar and its version are unchanged and only the empty stub jar drops out.
// Not an exclude: for three of the four, the stub is the ONLY path to the real androidx jar.
// Asserted by verifyNoDuplicateJars (below).
val jetBrainsRedirectStubs = mapOf( // androidx module (real classes) -> JetBrains stub (empty jar)
    "androidx.compose.runtime:runtime-desktop" to "org.jetbrains.compose.runtime:runtime-desktop",
    "androidx.compose.runtime:runtime-saveable-desktop" to "org.jetbrains.compose.runtime:runtime-saveable-desktop",
    "androidx.lifecycle:lifecycle-runtime-compose-desktop" to "org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose-desktop",
    "androidx.savedstate:savedstate-compose-desktop" to "org.jetbrains.androidx.savedstate:savedstate-compose-desktop",
)
dependencies.components {
    jetBrainsRedirectStubs.forEach { (androidx, stub) ->
        val (stubGroup, stubName) = stub.split(":")
        withModule(androidx) {
            val version = id.version
            allVariants { withCapabilities { addCapability(stubGroup, stubName, version) } }
        }
    }
}
configurations.all {
    jetBrainsRedirectStubs.values.forEach { stub ->
        resolutionStrategy.capabilitiesResolution.withCapability(stub) {
            select(candidates.first { (it.id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier)?.group?.startsWith("androidx.") == true })
            because("BUG-INSTALL-DUPJARS-1: $stub is an empty redirect jar; keep the androidx module it points to")
        }
    }
}

// BUILD-STAMP-1: the build string (root gradle/build-stamp.gradle.kts), shown by the app and sent by its in-process daemon.
val agentHqBuildStamp: String = rootProject.extra["agenthqBuild"] as String

// PERF-2: the JVM heap caps shared with :agentHq's runDaemon, from one file: agentHq/jvm-caps.txt.
val agentHqJvmCaps: List<String> = project(":superiorSoftLibrary:agentHq").file("jvm-caps.txt").readLines()
    .map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

// `run` is registered by Compose Desktop after this script is evaluated, hence the lazy `matching`.
tasks.matching { it.name == "run" }.configureEach {
    group = "application"
    description = "Runs the Infantry AI desktop shell; connect to :superiorSoftLibrary:agentHq:runDaemon on ws://127.0.0.1:8787/ws."
}

tasks.register("runUi") {
    group = "application"
    description = "Alias for run."
    dependsOn("run")
}

// UI-REAL: the desktop window against the REAL daemon (provider from AGENTHQ_PROVIDER, default opencode).
// Run: ./gradlew :superiorSoftLibrary:agentHqUi:runRealDaemon   (state lives under ~/.agenthq, like the daemon's own run)
tasks.register<JavaExec>("runRealDaemon") {
    group = "application"
    description = "Starts the AgentHQ daemon in-process (AGENTHQ_PROVIDER, default opencode) and opens the desktop UI on it."
    mainClass.set("com.superiorsoftllc.agenthq.ui.RealDaemonMainKt")
    classpath = sourceSets["main"].runtimeClasspath
    // PORT-1: fixed ports per use. 8787 is the owner's daemon (runDaemon), 8788 this preview. An AGENTHQ_PORT
    // set by the caller still wins; a taken port falls back to a free one and the window follows it.
    if (System.getenv("AGENTHQ_PORT").isNullOrBlank()) environment("AGENTHQ_PORT", "8788")
    jvmArgs(agentHqJvmCaps) // PERF-2
    systemProperty("agenthq.build", agentHqBuildStamp) // BUILD-STAMP-1
    // BUG-BUILD-1: agents run the `agenthq` CLI/MCP from :agentHq's installDist (AgentHqBin), not this classpath.
    // Refresh it on every preview so agents never run an older CLI than the daemon.
    dependsOn(":superiorSoftLibrary:agentHq:installDist")
}

// Dev-only visual smoke: Compose Desktop against an in-process stub daemon with temporary state.
tasks.register<JavaExec>("runSmoke") {
    group = "application"
    mainClass.set("com.superiorsoftllc.agenthq.ui.SmokeMainKt")
    classpath = sourceSets["test"].runtimeClasspath
    jvmArgs(agentHqJvmCaps) // PERF-2
}

// GAP-006b: dev tool, PNGs of the chat pane's Start session / Apply-Cancel / Resume states (stub daemon, temp state).
tasks.register<JavaExec>("runStartSessionShots") {
    group = "application"
    mainClass.set("com.superiorsoftllc.agenthq.ui.StartSessionShotsMainKt")
    classpath = sourceSets["test"].runtimeClasspath
    jvmArgs(agentHqJvmCaps) // PERF-2
    args(project.findProperty("shotsOut")?.toString() ?: layout.buildDirectory.dir("start-session-shots").get().asFile.path)
}

// PLUGINS-1: dev tool, one PNG per tab of every tab strip (stub daemon, temp state). Args: output folder.
tasks.register<JavaExec>("runTabShots") {
    group = "application"
    mainClass.set("com.superiorsoftllc.agenthq.ui.TabShotsMainKt")
    classpath = sourceSets["test"].runtimeClasspath
    jvmArgs(agentHqJvmCaps) // PERF-2
    args(project.findProperty("tabShotsOut")?.toString() ?: layout.buildDirectory.dir("tab-shots").get().asFile.path)
    if (project.hasProperty("tabShotsDemoLock")) args("demo-lock")
}

// TAB-SHOTS-DIFF: enforces "nothing in the existing UI changes". diffTabShots renders the tab shots (the runTabShots
// renderer, same window and seed data) into a clean folder and compares each PNG pixel by pixel with the committed
// baseline (tab-shots-baseline/); it prints "name: N of M pixels differ", writes <name>.diff.png for each differing shot
// and fails if any shot exceeds the tolerance. updateTabShotBaseline refreshes the baseline: an intended UI change is
// a conscious baseline update in its own commit. See README.md, "Proving pixel-identical".
// Paths are relative to the repository root.
//   -PtabShotsBaseline=<folder>   compare against another folder (e.g. shots rendered in a worktree of the base ref)
//   -PtabShotsCurrent=<folder>    compare an existing folder instead of rendering
//   -PtabShotsTolerance=<n>       differing pixels allowed per shot (default 0)
//   -PtabShotsMask=<file>         regions to ignore (default tab-shots-baseline/mask.txt when present)
val tabShotsBaseline = layout.projectDirectory.dir("tab-shots-baseline")
val tabShotsRendered = layout.buildDirectory.dir("tab-shots-diff/current")
val renderTabShotsForDiff = tasks.register<JavaExec>("renderTabShotsForDiff") {
    group = "verification"
    description = "Renders the tab shots into a clean build/tab-shots-diff/current (input of diffTabShots and updateTabShotBaseline)."
    mainClass.set("com.superiorsoftllc.agenthq.ui.TabShotsMainKt")
    classpath = sourceSets["test"].runtimeClasspath
    jvmArgs(agentHqJvmCaps) // PERF-2
    val out = tabShotsRendered.get().asFile
    args(out.path)
    if (project.hasProperty("tabShotsDemoLock")) args("demo-lock")
    // BLOCKS-LAYOUT L10: -PtabShotsExtra=board renders only the open board, for a before/after diff (TabShotsMain).
    project.findProperty("tabShotsExtra")?.let { systemProperty("agenthq.tabshots.extra", it.toString()) }
    // No modal blur (as in the UI tests): the blur smears the clocks behind a dialog (RAM free, ages) across the
    // window, where a mask could not hold them. The blur itself is covered by ModalBlurRenderUiTest.
    systemProperty("agenthq.ui.blur", "off")
    // S6 (drop-in path): -PtabShotsExtra=dropin is the drop-in acceptance. The shell runs WITHOUT the app UI on its class
    // path (the empty shell), starts from a plugins root with no app UI in it, installs App_UI.ihq through the drop box and
    // the consent dialog, soft-restarts, and then renders the 32 default shots, which must match the baseline at 0 px. Its
    // two opt-in shots (the drop box, the consent dialog) go to dropin/ (reference: tab-shots-baseline/dropin/).
    if (project.findProperty("tabShotsExtra") == "dropin") {
        val appUi = project(":superiorSoftLibrary:agenthqAppUi")
        dependsOn(":superiorSoftLibrary:agenthqAppUi:appUiIhq")
        // RUNTIME-3..20: nor Infantry_Cli, Infantry_Agents, Infantry_Settings, Infantry_Git, Infantry_Run, Infantry_Notes, Infantry_Changes, Infantry_Prompts, Infantry_Skills, Infantry_Stats, Infantry_Usage, Infantry_Epics, Infantry_Review, Infantry_Pods, Infantry_Profiles, Infantry_Triggers, Infantry_Attention, Infantry_Roles, Infantry_Board, Infantry_Alerts, Infantry_Processes, Infantry_Header, Infantry_Chat, Infantry_Approvals, Infantry_Assistant (tests carry them compiled in).
        classpath = sourceSets["test"].runtimeClasspath.filter { p -> "agenthqAppUi" !in p.invariantSeparatorsPath && "Infantry_Board" !in p.invariantSeparatorsPath && "Infantry_Cli" !in p.invariantSeparatorsPath && "Infantry_Agents" !in p.invariantSeparatorsPath && "Infantry_Settings" !in p.invariantSeparatorsPath && "Infantry_Git" !in p.invariantSeparatorsPath && "Infantry_Run" !in p.invariantSeparatorsPath && "Infantry_Notes" !in p.invariantSeparatorsPath && "Infantry_Changes" !in p.invariantSeparatorsPath && "Infantry_Prompts" !in p.invariantSeparatorsPath && "Infantry_Skills" !in p.invariantSeparatorsPath && "Infantry_Stats" !in p.invariantSeparatorsPath && "Infantry_Usage" !in p.invariantSeparatorsPath && "Infantry_Epics" !in p.invariantSeparatorsPath && "Infantry_Review" !in p.invariantSeparatorsPath && "Infantry_Pods" !in p.invariantSeparatorsPath && "Infantry_Profiles" !in p.invariantSeparatorsPath && "Infantry_Triggers" !in p.invariantSeparatorsPath && "Infantry_Attention" !in p.invariantSeparatorsPath && "Infantry_Roles" !in p.invariantSeparatorsPath && "Infantry_Alerts" !in p.invariantSeparatorsPath && "Infantry_Processes" !in p.invariantSeparatorsPath && "Infantry_Header" !in p.invariantSeparatorsPath && "Infantry_Chat" !in p.invariantSeparatorsPath && "Infantry_Approvals" !in p.invariantSeparatorsPath && "Infantry_Assistant" !in p.invariantSeparatorsPath }
        systemProperty("agenthq.tabshots.appUiIhq", appUi.layout.buildDirectory.file("ihq/App_UI.ihq").get().asFile.path)
    }
    outputs.upToDateWhen { false }
    doFirst { out.deleteRecursively() }
}
tasks.register<JavaExec>("diffTabShots") {
    group = "verification"
    description = "Renders the tab shots and fails on any pixel difference from the baseline (tab-shots-baseline/)."
    mainClass.set("com.superiorsoftllc.agenthq.ui.TabShotDiffKt")
    classpath = sourceSets["test"].runtimeClasspath
    // Folder and file arguments are relative to the repository root.
    fun rootPath(name: String) = project.findProperty(name)?.toString()?.let { rootProject.file(it).path }
    val current = rootPath("tabShotsCurrent")
    if (current == null) dependsOn(renderTabShotsForDiff) else dependsOn("testClasses")
    val baseline = rootPath("tabShotsBaseline") ?: tabShotsBaseline.asFile.path
    val mask = rootPath("tabShotsMask") ?: tabShotsBaseline.file("mask.txt").asFile.path
    args(
        baseline, current ?: tabShotsRendered.get().asFile.path, layout.buildDirectory.dir("tab-shots-diff/diff").get().asFile.path,
        project.findProperty("tabShotsTolerance")?.toString() ?: "0", mask,
    )
    outputs.upToDateWhen { false }
}
tasks.register<Sync>("updateTabShotBaseline") {
    group = "verification"
    description = "Renders the tab shots and replaces the baseline PNGs in tab-shots-baseline/ (commit the update on its own)."
    dependsOn(renderTabShotsForDiff)
    from(tabShotsRendered) { include("*.png") }
    into(tabShotsBaseline)
    preserve { include("mask.txt", "README.md", "empty-shell/**", "dropin/**") } // S4, S6: the opt-in empty-shell and drop-in references
}

// PLUGINS-1: the dev runs read the repository's plugins/ folder (core + community, modules.json) and first build every
// community module that has sources. settings.gradle.kts finds those by scanning plugins/community; none is named here.
val repoPluginsRoot = rootProject.file("plugins")
val communityModuleJars = rootProject.subprojects.filter { it.path.startsWith(":plugins:community:") }.map { "${it.path}:jar" }
// RUNTIME-3: and every official module's jar (plugins/official/<Module>/build/libs), loaded at run time. A dev build is
// not signed with the Infantry key, so the dev runs pass devTrust (never the packaged launcher: OfficialTrust).
val officialModuleJars = rootProject.subprojects.filter { it.path.startsWith(":plugins:official:") }.map { "${it.path}:jvmJar" }
tasks.matching { it.name in setOf("run", "runRealDaemon", "runTabShots", "renderTabShotsForDiff") }.configureEach {
    (this as? JavaExec)?.systemProperty("agenthq.plugins.root", repoPluginsRoot.absolutePath)
    (this as? JavaExec)?.systemProperty("agenthq.plugins.devTrust", "true")
    dependsOn(communityModuleJars)
    dependsOn(officialModuleJars)
}
// TAB-SHOTS-DIFF: the shots never read the real plan limits (live, per account), as the UI tests do not.
tasks.matching { it.name in setOf("runTabShots", "renderTabShotsForDiff") }.configureEach {
    (this as JavaExec).environment("AGENTHQ_LIMITS_LIVE", "false")
}

// U2: the UI tests boot the real daemon in-process, and the client now asks for `limits` on connect. Keep the
// daemon's plan-limit meters off so a test never reads real credentials or calls a provider (as :agentHq does).
tasks.withType<Test>().configureEach {
    environment("AGENTHQ_LIMITS_LIVE", "false")
    // TEST-SPEED-4: no modal blur in the test windows (JvmPlatformHost.supportsBlur). The tests render on the CPU, where
    // blurring the whole window cost ~150 ms a frame under every open modal; the dark scrim draws in a few ms.
    systemProperty("agenthq.ui.blur", "off") // ModalBlurRenderUiTest turns it back on for itself and renders the blur
    // PERF-2: PackagedJvmCapsTest reads the packaged launcher's jvmArgs (the compose block below), lazily, '|'-separated (no flag contains '|').
    // BUILD-STAMP-1: the stamp's VALUE changes per commit (per minute when packaging); the test only checks it is there, so the
    // input carries a fixed placeholder and the tests stay cacheable.
    val packagedJvmArgs = provider {
        compose.desktop.application.jvmArgs.joinToString("|") { if (it.startsWith("-Dagenthq.build=")) "-Dagenthq.build=<stamp>" else it }
    }
    inputs.property("packagedJvmArgs", packagedJvmArgs)
    jvmArgumentProviders += CommandLineArgumentProvider { listOf("-Dagenthq.packagedJvmArgs=${packagedJvmArgs.get()}") }
}

// E2E-OWNER-1: the owner-path smoke. It boots RealDaemonMainKt (as runRealDaemon does) as its own JVM, headless, from a
// temp git layout (dirty main checkout + linked worktree), and drives it through the INSTALLED `agenthq` (installDist,
// as AgentHqBin resolves it) over `mcp serve` and the CLI, with a recording stub harness. Temp dirs and ports only
// (never ~/.agenthq, 8787 or 8788). Kept out of `test`; QA runs it as its own gate step, alone in a few minutes:
//   ./gradlew :superiorSoftLibrary:agentHqUi:ownerSmoke
// Each scenario's logs and stub recordings are kept under build/ownerSmoke/runs/<scenario>/.
val ownerSmokeTests = "com.superiorsoftllc.agenthq.ui.owner.*"
tasks.named<Test>("test") {
    filter { excludeTestsMatching(ownerSmokeTests) }
    // TEST-SPEED-1: parallel test-class forks (was 1 fork, fully serial). Fewer than :agentHq because each UI fork boots a
    // daemon AND a Compose window. ownerSmoke stays a single fork. Override: -Pagenthq.uiTestForks=1.
    maxParallelForks = providers.gradleProperty("agenthq.uiTestForks").map { it.toInt() }.getOrElse(2)
    maxHeapSize = "2g"
}
// TEST-SPEED-3: two tiers split by class name; `test` still runs both. `testFast` is ~30 s of plain unit tests,
// `testSlow` the ~390 s of Compose windows over a real daemon. Same fork count as `test`; ownerSmoke is in neither.
val slowUiTestPatterns = listOf("*UiTest", "*E2ETest", "*E2eTest", "*IntegrationTest")
val uiUnitTest = tasks.named<Test>("test")
fun Test.uiTier(desc: String, slow: Boolean) {
    group = "verification"
    description = desc
    testClassesDirs = uiUnitTest.get().testClassesDirs
    classpath = uiUnitTest.get().classpath
    maxParallelForks = uiUnitTest.get().maxParallelForks
    maxHeapSize = "2g"
    filter {
        excludeTestsMatching(ownerSmokeTests)
        slowUiTestPatterns.forEach { if (slow) includeTestsMatching(it) else excludeTestsMatching(it) }
    }
}
// TEST-SPEED-1: per-fork tmpdir and state dir for the parallel UI test tasks, as :agentHq does: the same TestForkAgent
// (built by :agentHq) moves each fork into <testTmp>/fork-<pid> and <test-state-dir>/fork-<pid>. ownerSmoke (one fork,
// its own temp layout) is left as it was. The tmp root sits OUTSIDE the repo, like :agentHq's (git fixtures).
val uiTestTmp = File(System.getProperty("java.io.tmpdir"), "agenthq-test-tmp/" + Integer.toHexString(projectDir.absolutePath.hashCode()))
val uiTestState = layout.buildDirectory.dir("test-state-dir").get().asFile
val forkAgentJar = project(":superiorSoftLibrary:agentHq").layout.buildDirectory.file("testForkAgent/agenthq-test-fork-agent.jar")
tasks.withType<Test>().matching { it.name in setOf("test", "testFast", "testSlow") }.configureEach {
    dependsOn(":superiorSoftLibrary:agentHq:testForkAgentJar")
    inputs.file(forkAgentJar).withPropertyName("testForkAgent").withNormalizer(ClasspathNormalizer::class.java)
    systemProperty("AGENTHQ_STATE_DIR", uiTestState.absolutePath)
    jvmArgs("-Djava.io.tmpdir=${uiTestTmp.absolutePath}")
    jvmArgumentProviders += CommandLineArgumentProvider { listOf("-javaagent:${forkAgentJar.get().asFile.absolutePath}") }
    doFirst {
        uiTestState.deleteRecursively(); uiTestState.mkdirs()
        uiTestTmp.deleteRecursively(); uiTestTmp.mkdirs()
    }
}
tasks.register<Test>("testFast") { uiTier("TEST-SPEED-3: the fast UI tier (classes NOT matched by slowUiTestPatterns).", slow = false) }
tasks.register<Test>("testSlow") { uiTier("TEST-SPEED-3: the slow UI tier (classes matched by slowUiTestPatterns).", slow = true) }
tasks.register<Test>("ownerSmoke") {
    group = "verification"
    description = "E2E-OWNER-1: the owner's path end to end (real daemon JVM, installed agenthq over MCP and CLI, stub harness)."
    val testRuntime = sourceSets["test"].runtimeClasspath
    val daemonRuntime = sourceSets["main"].runtimeClasspath
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = testRuntime
    filter { includeTestsMatching(ownerSmokeTests) }
    // BUG-BUILD-1: the agents' `agenthq` is the install; refresh it so the smoke never runs an older CLI than the daemon.
    dependsOn(":superiorSoftLibrary:agentHq:installDist")
    inputs.files(daemonRuntime).withPropertyName("daemonRuntime")
    val work = layout.buildDirectory.dir("ownerSmoke").get().asFile
    val daemonClasspathFile = File(work, "daemon-classpath.txt")
    val testClasspathFile = File(work, "test-classpath.txt")
    systemProperty("ownersmoke.install", project(":superiorSoftLibrary:agentHq").layout.buildDirectory.dir("install/agenthq").get().asFile.absolutePath)
    systemProperty("ownersmoke.daemonClasspathFile", daemonClasspathFile.absolutePath)
    systemProperty("ownersmoke.testClasspathFile", testClasspathFile.absolutePath)
    systemProperty("ownersmoke.reportDir", File(work, "runs").absolutePath)
    doFirst {
        work.mkdirs()
        // Files, not -D values: the classpaths are far longer than a Windows command line allows.
        daemonClasspathFile.writeText(daemonRuntime.files.joinToString("\n") { it.absolutePath })
        testClasspathFile.writeText(testRuntime.files.joinToString("\n") { it.absolutePath })
    }
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
    timeout.set(Duration.ofMinutes(10))
}

// PKG-1: the INSTALLABLE production build (docs/AGENTHQ-INSTALL.md). Entry AppMainKt: under `run` it is the dev window
// (MainKt, 8787); under the jpackage launcher (or -Dagenthq.installed=true) it pins its own state dir
// (%LOCALAPPDATA%\SuperiorSoft\AgentHQ, outside the MSI's install dir: INSTALL-1) and port (8797) and starts the daemon
// in-process, so the install never touches the dev daemon (8787), the dev preview (8788) or ~/.agenthq. The daemon ships on the same classpath (:agentHq is an
// `implementation` dependency above), so there is no second launcher to keep in sync.
// Build: ./gradlew :superiorSoftLibrary:agentHqUi:packageReleaseMsi   (or packageMsi / packageExe / packageReleaseExe)
val agentHqPackageVersion: String = providers.gradleProperty("agenthqVersion").orNull?.trim()?.takeIf { it.isNotEmpty() } ?: "1.0.0"
require(Regex("""(\d+)\.(\d+)\.(\d+)""").matchEntire(agentHqPackageVersion)?.groupValues?.let { (_, major, minor, build) ->
    (major.toIntOrNull() ?: 0) in 1..255 && (minor.toIntOrNull() ?: 256) <= 255 && (build.toIntOrNull() ?: 65536) <= 65535
} == true) { "INSTALL-1: -PagenthqVersion=$agentHqPackageVersion is not an MSI version (MAJOR.MINOR.BUILD, 1..255 . 0..255 . 0..65535)" }

compose.desktop {
    application {
        mainClass = "com.superiorsoftllc.agenthq.ui.AppMainKt"
        // UPDATE-1: the running version, compared with the latest GitHub release's tag (UpdateSettings.VERSION_PROPERTY).
        jvmArgs += listOf("-Dagenthq.version=$agentHqPackageVersion")
        // BUILD-STAMP-1: what Configuration > About and the footer show, and what the in-process daemon sends as `ready` build=.
        jvmArgs += listOf("-Dagenthq.build=$agentHqBuildStamp")
        // PERF-2: heap caps (GC stays G1) for the packaged launcher (jpackage java-options) and `run`; the installed app runs
        // the daemon in this same JVM, so these cover both. Asserted by PackagedJvmCapsTest.
        jvmArgs += agentHqJvmCaps
        nativeDistributions {
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Exe,
            )
            packageName = "InfantryAI"
            // INSTALL-1: the version comes from -PagenthqVersion (default 1.0.0), so an upgrade build needs no file edit:
            //   ./gradlew :superiorSoftLibrary:agentHqUi:packageMsi -PagenthqVersion=1.0.1
            // MSI needs MAJOR.MINOR.BUILD (each part numeric, MAJOR 1..255) and only upgrades over a LOWER version, so every
            // build the owner installs over an older one needs a higher number. Checked here, before jpackage spends minutes.
            packageVersion = agentHqPackageVersion
            description = "Infantry AI desktop with its own daemon"
            vendor = "SuperiorSoft LLC"
            // CLIPATH-2: the installed app's `agenthq` CLI launchers (appResources/<os>/: agenthq.cmd + a Git Bash shim on
            // Windows, an sh script on macOS/Linux) land in $APPDIR/resources; InstalledCli pins that dir as AGENTHQ_BIN
            // so every session the installed daemon spawns can run `agenthq` (CliMainKt on the app's own classpath).
            appResourcesRootDir.set(project.layout.projectDirectory.dir("appResources"))
            // jlink: bundle the whole JDK module set. The daemon pulls Ktor CIO, SQLite (JDBC + native lib),
            // java.net.http and process APIs; a hand-picked module list fails at RUNTIME on the first missing module,
            // which only an installed run would reveal. Costs roughly 40-60 MB of installer size; trim later with
            // `suggestRuntimeModules` once a real install has been exercised.
            includeAllModules = true
            windows {
                menu = true
                menuGroup = "Infantry AI"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                // APP-ICON-1: the Infantry AI emblem for the installer and exe (else Compose packages its Kotlin icon).
                iconFile.set(project.file("icons/agenthq.ico"))
                // FIXED forever: changing it makes Windows treat a new version as a different product (no upgrade).
                upgradeUuid = "6f1d3c2a-8b47-4e5a-9c1e-2a7b0d4f8e61"
            }
        }
        buildTypes.release.proguard {
            // ProGuard would strip reflective serializers and Ktor's service loaders; not worth the risk for now.
            isEnabled.set(false)
        }
    }
}

// PKG-4: keep the native `java` launcher in the bundled runtime, so the installed `agenthq` CLI (appResources/) takes
// its "bundled java" branch and runs on a machine with no JDK.
//
// Why a copy step and not a jlink flag: Compose 1.10.3 always passes `--strip-native-commands` to jlink. The switch
// exists (AbstractJLinkTask.stripNativeCommands) but is Kotlin `internal`, so it has no DSL and no stable name from a
// build script, and `freeArgs` can only ADD jlink arguments, never remove one. Instead, right after jlink writes the
// image, copy `bin/java(.exe)` from the very JDK jlink ran from (the task's public `javaHome`): same build, so it
// matches the image's libjvm. `--strip-native-commands` removes executables only; the shared library java needs
// (bin\jli.dll on Windows, lib/libjli.* elsewhere) stays in the image, and the step fails the build if it does not.
// Being a doLast of createRuntimeImage, the copy is part of that task's output: up-to-date checks and the build cache
// carry it, and every package task (createDistributable, packageMsi/Exe, release variants) gets it.
val keepJavaLauncherAction = "keepJavaLauncher"

// WEB-1: the installed app's in-process daemon serves the browser client at / from <resources>/web (WebBundle reads
// compose.application.resources.dir). Compose's prepareAppResources (a Sync) feeds every package task and `run`.
tasks.withType<Sync>().matching { it.name == "prepareAppResources" }.configureEach {
    dependsOn(":superiorSoftLibrary:agentHqWeb:assembleWeb")
    from(project(":superiorSoftLibrary:agentHqWeb").layout.buildDirectory.dir("assembleWeb")) { into("web") }
}
val javaLauncherName = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"

// PACKAGING (ticket 95c26a81, follow-up 1 of RUNTIME-3): the packaged app ships every official module, signed, as its
// bundled modules root (PluginFolders.bundledRoot): <install>/app/resources/modules (jpackage's $APPDIR/resources), laid out
//   modules/official/<Name>/module.json              (the module's own, byte for byte: OfficialTrust compares it with the signed copy)
//   modules/official/<Name>/<module.json "jar">      (e.g. build/libs/Infantry_Cli.jar, signed with the Infantry key)
//   modules/trusted-keys/infantry.cer                (the signing certificate: OfficialTrust.infantryKeys reads it)
// and the launcher sets -Dagenthq.plugins.bundled=$APPDIR/resources/modules. Signing key (until 9bc0c955 pins one):
//   -Pihq.keystore=<file> -Pihq.alias=<alias> + env IHQ_STOREPASS   the real Infantry key (keystore path relative to the repo root)
//   -Pihq.devSign=true                                              a throwaway dev key (build/official-modules/dev-key), never for a release
// With neither, stageOfficialModules (and so every package task) fails. `run` and the dev runs are untouched (devTrust).
val officialProjects = rootProject.subprojects.filter { it.path.startsWith(":plugins:official:") }
val officialStageDir = layout.buildDirectory.dir("official-modules/modules")
val stageOfficialModules = tasks.register("stageOfficialModules") {
    group = "distribution"
    description = "Builds every plugins/official jar, signs it (-Pihq.keystore/-Pihq.alias + IHQ_STOREPASS, or -Pihq.devSign=true) and lays out build/official-modules/modules."
    officialProjects.forEach { dependsOn("${it.path}:jvmJar") }
    val modules = officialProjects.map { p -> p.name to p.projectDir }
    val jars = officialProjects.associate { p -> p.name to p.tasks.named<Jar>("jvmJar").flatMap { it.archiveFile } }
    val keystoreProp = providers.gradleProperty("ihq.keystore").map { rootProject.file(it) }
    val aliasProp = providers.gradleProperty("ihq.alias")
    val storePassEnv = providers.environmentVariable("IHQ_STOREPASS")
    val devSign = providers.gradleProperty("ihq.devSign").map { it.trim().lowercase() == "true" }.orElse(false)
    inputs.files(jars.values).withPropertyName("officialJars")
    inputs.files(modules.map { File(it.second, "module.json") }).withPropertyName("moduleJsons")
    inputs.property("keystore", keystoreProp.map { it.absolutePath }.orElse(""))
    inputs.property("alias", aliasProp.orElse(""))
    inputs.property("devSign", devSign)
    val out = officialStageDir
    val devKeyDir = layout.buildDirectory.dir("official-modules/dev-key")
    outputs.dir(out)
    doLast {
        val bin = File(System.getProperty("java.home"), "bin")
        val exe = if (System.getProperty("os.name").startsWith("Windows")) ".exe" else ""
        fun tool(name: String, vararg args: String) {
            val p = ProcessBuilder(listOf(File(bin, name + exe).path) + args).redirectErrorStream(true).start()
            val text = p.inputStream.bufferedReader().readText()
            check(p.waitFor() == 0) { "PACKAGING: $name failed: $text" }
        }
        // The key: the owner's keystore, else (opt-in) a throwaway dev key, else a clear failure.
        val (keystore, alias, storePass) = when {
            keystoreProp.isPresent -> Triple(
                keystoreProp.get().also { check(it.isFile) { "PACKAGING: -Pihq.keystore=$it does not exist" } },
                aliasProp.orNull ?: error("PACKAGING: -Pihq.keystore needs -Pihq.alias=<the Infantry key's alias>"),
                storePassEnv.orNull ?: error("PACKAGING: -Pihq.keystore needs the keystore password in env IHQ_STOREPASS"),
            )
            devSign.get() -> {
                val ks = File(devKeyDir.get().asFile, "infantry-dev.p12")
                if (!ks.isFile) {
                    ks.parentFile.mkdirs()
                    tool("keytool", "-genkeypair", "-alias", "infantry-dev", "-keyalg", "RSA", "-keysize", "2048", "-validity", "3650",
                        "-dname", "CN=Infantry AI dev key (throwaway - not for release)", "-storetype", "PKCS12", "-keystore", ks.path,
                        "-storepass", "infantry-dev", "-keypass", "infantry-dev")
                }
                logger.warn("PACKAGING: official modules signed with a THROWAWAY dev key ($ks); never ship this build")
                Triple(ks, "infantry-dev", "infantry-dev")
            }
            else -> error(
                "PACKAGING: the official modules (${modules.joinToString { it.first }}) must be signed with the Infantry key. Pass " +
                    "-Pihq.keystore=<keystore> -Pihq.alias=<alias> with the password in env IHQ_STOREPASS, or -Pihq.devSign=true for a " +
                    "throwaway dev key (local test builds only).",
            )
        }
        val root = out.get().asFile
        root.deleteRecursively()
        for ((name, dir) in modules) {
            val json = File(dir, "module.json")
            val manifest = groovy.json.JsonSlurper().parse(json) as Map<*, *>
            val jarPath = (manifest["jar"] as? String)?.takeIf { it.isNotBlank() } ?: error("PACKAGING: $json names no jar")
            val moduleDir = File(root, "official/$name")
            json.copyTo(File(moduleDir, "module.json"))
            val jar = jars.getValue(name).get().asFile.copyTo(File(moduleDir, jarPath))
            tool("jarsigner", "-keystore", keystore.path, "-storepass", storePass, jar.path, alias)
        }
        val keys = File(root, "trusted-keys").apply { mkdirs() }
        tool("keytool", "-exportcert", "-rfc", "-alias", alias, "-keystore", keystore.path, "-storepass", storePass, "-file", File(keys, "infantry.cer").path)
        logger.lifecycle("PACKAGING: staged ${modules.size} official module(s) into $root")
    }
}
// The package tasks' resources: Compose's prepareAppResources (shared with `run`, left as it is) plus modules/.
val prepareDistributableResources = tasks.register<Sync>("prepareDistributableResources") {
    group = "distribution"
    description = "prepareAppResources plus the staged official modules (modules/): the resources of the package tasks."
    from(tasks.named<Sync>("prepareAppResources"))
    from(stageOfficialModules) { into("modules") }
    into(layout.buildDirectory.dir("compose/tmp/distributableResources"))
}
val bundledLauncherArg = "-Dagenthq.plugins.bundled=\$APPDIR/resources/modules"
// DEV-TRUST (ticket 98ef2f8f): a -Pihq.devSign=true build signs with the throwaway dev key, which the pinned Infantry key
// (OfficialTrust) does not match, so its launcher trusts the dev-signed modules (devTrust). A real-key build (-Pihq.keystore)
// never gets it. Same precedence as stageOfficialModules: a keystore wins over devSign.
val devTrustLauncherArg = "-Dagenthq.plugins.devTrust=true"
val usesDevKey = !hasProperty("ihq.keystore") && findProperty("ihq.devSign")?.toString()?.trim()?.lowercase() == "true"
// Compose registers its package tasks in afterEvaluate and sets appResourcesDir / launcherJvmArgs in their register
// action; a configureEach added after that registration runs after that action (one added earlier ran before it and was
// overwritten: verifyOfficialModulesPackaged catches that).
afterEvaluate {
    tasks.withType<org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask>().configureEach {
        dependsOn(prepareDistributableResources)
        appResourcesDir.set(layout.dir(prepareDistributableResources.map { it.destinationDir }))
        launcherJvmArgs.add(bundledLauncherArg)
        if (usesDevKey) launcherJvmArgs.add(devTrustLauncherArg)
    }
}
// PACKAGING acceptance (runs no jpackage): the distributable's resources carry modules/official/<Name> with a jar signed
// by the certificate in modules/trusted-keys and the on-disk module.json equal to the signed copy (what OfficialTrust
// checks), and createDistributable's launcher sets agenthq.plugins.bundled. Needs a key, so it is not in `check`:
//   ./gradlew :superiorSoftLibrary:agentHqUi:verifyOfficialModulesPackaged -Pihq.devSign=true
tasks.register("verifyOfficialModulesPackaged") {
    group = "verification"
    description = "PACKAGING: the distributable input carries every official module, signed, and the launcher sets agenthq.plugins.bundled."
    dependsOn(prepareDistributableResources)
    val names = officialProjects.map { it.name }
    val dist = tasks.named<org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask>("createDistributable")
    val launcherArgs = dist.flatMap { it.launcherJvmArgs }
    val resources = dist.flatMap { it.appResourcesDir }.map { it.asFile }
    val prepared = prepareDistributableResources.map { it.destinationDir }
    doLast {
        val res = resources.get()
        check(res == prepared.get()) { "PACKAGING: createDistributable packages $res, not the staged resources ${prepared.get()}" }
        check(bundledLauncherArg in launcherArgs.get()) { "PACKAGING: the launcher does not set $bundledLauncherArg: ${launcherArgs.get()}" }
        check((devTrustLauncherArg in launcherArgs.get()) == usesDevKey) {
            "PACKAGING: the launcher ${if (usesDevKey) "lacks" else "must not set"} $devTrustLauncherArg (devSign=$usesDevKey): ${launcherArgs.get()}"
        }
        val modules = File(res, "modules")
        val cert = CertificateFactory.getInstance("X.509")
            .generateCertificate(File(modules, "trusted-keys/infantry.cer").inputStream().use { it.readBytes() }.inputStream())
        check(names.isNotEmpty()) { "PACKAGING: no plugins/official module found" }
        for (name in names) {
            val dir = File(modules, "official/$name")
            val json = File(dir, "module.json").readText()
            val manifest = groovy.json.JsonSlurper().parseText(json) as Map<*, *>
            check(manifest["tier"] == "official") { "PACKAGING: $dir/module.json is not tier official" }
            val jar = File(dir, manifest["jar"] as String)
            JarFile(jar, true).use { j ->
                val files = j.entries().toList().filter { !it.isDirectory && !it.name.startsWith("META-INF/") }
                check(files.isNotEmpty()) { "PACKAGING: $jar is empty" }
                for (e in files) {
                    j.getInputStream(e).use { it.readBytes() } // runs the signature check
                    val signer = e.codeSigners?.firstOrNull()?.signerCertPath?.certificates?.firstOrNull()
                    check(signer == cert) { "PACKAGING: ${e.name} in $jar is not signed by trusted-keys/infantry.cer" }
                }
                val signed = j.getInputStream(j.getJarEntry("module.json") ?: error("PACKAGING: $jar carries no module.json")).use { it.readBytes().decodeToString() }
                check(signed == json) { "PACKAGING: $dir/module.json differs from the signed copy in $jar" }
            }
            logger.lifecycle("PACKAGING: modules/official/$name/${manifest["jar"]} signed by ${(cert as X509Certificate).subjectX500Principal.name}")
        }
        logger.lifecycle("PACKAGING: launcher sets $bundledLauncherArg; resources $res")
    }
}

tasks.withType<org.jetbrains.compose.desktop.application.tasks.AbstractJLinkTask>().configureEach {
    doLast(keepJavaLauncherAction) {
        val jlink = this as org.jetbrains.compose.desktop.application.tasks.AbstractJLinkTask
        val source = File(jlink.javaHome.get(), "bin/$javaLauncherName")
        check(source.isFile) { "PKG-4: no $javaLauncherName in the jlink JDK at ${source.parentFile}" }
        val image = jlink.destinationDir.get().asFile
        val target = File(image, "bin/$javaLauncherName")
        source.copyTo(target, overwrite = true)
        target.setExecutable(true, false)
        val jli = listOf(File(image, "bin/jli.dll"), File(image, "lib/libjli.so"), File(image, "lib/libjli.dylib"))
        check(jli.any { it.isFile }) { "PKG-4: the runtime image has no libjli next to $target; java cannot start" }
        logger.lifecycle("PKG-4: kept the native java launcher in the runtime image: $target")
    }
}

// PKG-4 acceptance, at configuration level (runs no jlink): every runtime-image task carries the copy step, and the
// JDK it copies from really has the launcher. Wired into `check`.
tasks.register("verifyRuntimeKeepsJava") {
    group = "verification"
    description = "PKG-4: proves the bundled runtime keeps bin/java(.exe) (createRuntimeImage copy step is wired)."
    val jlinkTasks = tasks.withType<org.jetbrains.compose.desktop.application.tasks.AbstractJLinkTask>()
    val wiring: Provider<List<Triple<String, List<String>, File>>> = provider {
        jlinkTasks.map { task ->
            val actions = task.actions.map { (it as? org.gradle.api.Describable)?.displayName ?: it.toString() }
            Triple(task.name, actions, File(task.javaHome.get(), "bin/$javaLauncherName"))
        }
    }
    doLast {
        val found = wiring.get()
        check(found.isNotEmpty()) { "PKG-4: no Compose jlink task found; is compose.desktop.application still configured?" }
        found.forEach { (name, actions, source) ->
            check(actions.any { keepJavaLauncherAction in it }) {
                "PKG-4: $name has no $keepJavaLauncherAction step; the runtime would ship without $javaLauncherName. Actions: $actions"
            }
            check(source.isFile) { "PKG-4: $name would copy from ${source.path}, which does not exist" }
            logger.lifecycle("PKG-4: $name keeps $javaLauncherName (copied from $source)")
        }
    }
}

tasks.named("check") { dependsOn("verifyRuntimeKeepsJava") }

// BUG-INSTALL-DUPJARS-1 acceptance: the packaged app's classpath (Compose packages main's runtimeClasspath) must carry
// one jar per name. Two jars that differ only in version (runtime-desktop-1.10.3.jar + runtime-desktop-1.10.5.jar)
// make which one loads depend on the launcher's classpath order. Key = the file name minus its "-<version>".
tasks.register("verifyNoDuplicateJars") {
    group = "verification"
    description = "BUG-INSTALL-DUPJARS-1: fails if main's runtimeClasspath (the packaged classpath) carries two versions of one jar name."
    val artifacts = configurations.named("runtimeClasspath").flatMap { it.incoming.artifacts.resolvedArtifacts }
    doLast {
        val byName = artifacts.get().mapNotNull { artifact ->
            val id = artifact.id.componentIdentifier as? org.gradle.api.artifacts.component.ModuleComponentIdentifier ?: return@mapNotNull null
            artifact.file.name.replace("-${id.version}", "") to "${id.group}:${id.module}:${id.version} (${artifact.file.name})"
        }.groupBy({ it.first }, { it.second }).filterValues { it.size > 1 }
        check(byName.isEmpty()) {
            "BUG-INSTALL-DUPJARS-1: the packaged classpath carries ${byName.size} jar name(s) twice:\n" +
                byName.entries.joinToString("\n") { (name, ids) -> "  $name <- ${ids.joinToString(", ")}" }
        }
        logger.lifecycle("BUG-INSTALL-DUPJARS-1: ${artifacts.get().size} runtime jars, every jar name once")
    }
}

tasks.named("check") { dependsOn("verifyNoDuplicateJars") }

// PERF-1: the repeatable memory/CPU/startup profile (docs/perf/agenthq-profile-<date>.md). It boots its OWN daemon JVM
// (agentHq's installDist, as runDaemon runs it) and UI JVM (MainKt, as `run` runs it) in a temp AGENTHQ home on a free
// port -- never ~/.agenthq, never 8787/8788 (PerfGuard asserts it) -- then drives 4 streaming stub agents and samples
// both JVMs from the outside. Opens a real window for the run's length (~14 min with the default 10 min idle).
//   ./gradlew :superiorSoftLibrary:agentHq:perfProfile [-PperfIdleMinutes=10] [-PperfNmt=true] [-PperfAgents=4] [-PperfLines=2000]
tasks.register<JavaExec>("perfProfile") {
    group = "verification"
    description = "PERF-1: profiles the AgentHQ daemon and UI JVMs (startup, RSS/heap idle/load/after-idle, idle CPU) into docs/perf/."
    val testRuntime = sourceSets["test"].runtimeClasspath
    val uiRuntime = sourceSets["main"].runtimeClasspath
    classpath = testRuntime
    mainClass.set("com.superiorsoftllc.agenthq.ui.perf.PerfProfileMainKt")
    // jcmd must come from the same JDK as the children: run on the module's toolchain JDK, which the children reuse.
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
    dependsOn(":superiorSoftLibrary:agentHq:installDist")
    val work = layout.buildDirectory.dir("perfProfile").get().asFile
    val uiClasspathFile = File(work, "ui-classpath.txt")
    val testClasspathFile = File(work, "test-classpath.txt")
    systemProperty("perf.install", project(":superiorSoftLibrary:agentHq").layout.buildDirectory.dir("install/agenthq").get().asFile.absolutePath)
    systemProperty("perf.uiClasspathFile", uiClasspathFile.absolutePath)
    systemProperty("perf.testClasspathFile", testClasspathFile.absolutePath)
    systemProperty("perf.docsDir", rootProject.layout.projectDirectory.dir("docs/perf").asFile.absolutePath)
    systemProperty("perf.workDir", work.absolutePath)
    // PERF-2: both child JVMs get the shipped caps, so the profile measures what the app runs with.
    systemProperty("perf.jvmCaps", agentHqJvmCaps.joinToString("|"))
    mapOf(
        "perfIdleMinutes" to "perf.idleMinutes", "perfSettleSeconds" to "perf.settleSeconds", "perfCpuSeconds" to "perf.cpuSeconds",
        "perfAgents" to "perf.agents", "perfStartupTrials" to "perf.startupTrials", "perfLines" to "perf.lines", "perfPaceMs" to "perf.paceMs", "perfNmt" to "perf.nmt",
    ).forEach { (gradleProp, sysProp) -> providers.gradleProperty(gradleProp).orNull?.let { systemProperty(sysProp, it) } }
    doFirst {
        work.mkdirs()
        // Files, not -D values: the classpaths are far longer than a Windows command line allows.
        uiClasspathFile.writeText(uiRuntime.files.joinToString("\n") { it.absolutePath })
        testClasspathFile.writeText(testRuntime.files.joinToString("\n") { it.absolutePath })
    }
    outputs.upToDateWhen { false }
}

// RELEASE-ASSETS-1: the assets UPDATE-1's Updater reads from a GitHub release, in one folder for the owner to upload:
//   ./gradlew :superiorSoftLibrary:agentHqUi:releaseAssets -PagenthqVersion=1.0.N
//   -> build/release/1.0.N/{InfantryAI-1.0.N.msi, SHA256SUMS, RELEASE-NOTES.md}
// The writing (copy, sha256sum-format SHA256SUMS, notes stub) is ReleaseAssets in :agentHq, unit-tested against the
// Updater's own parser (ReleaseAssetsTest). This task NEVER pushes, tags or calls GitHub; publishing is manual
// (docs/AGENTHQ-INSTALL.md, "Publishing a release").
val releaseAssets = tasks.register<JavaExec>("releaseAssets") {
    group = "distribution"
    description = "RELEASE-ASSETS-1: builds the release msi and writes build/release/<version>/ (msi, SHA256SUMS, RELEASE-NOTES.md). Needs -PagenthqVersion."
    dependsOn("packageReleaseMsi")
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.superiorsoftllc.agenthq.update.ReleaseAssetsKt")
    val msi = layout.buildDirectory.file("compose/binaries/main-release/msi/InfantryAI-$agentHqPackageVersion.msi")
    val out = layout.buildDirectory.dir("release/$agentHqPackageVersion")
    val gitSha = providers.exec {
        commandLine("git", "rev-parse", "--short", "HEAD")
        workingDir = rootProject.projectDir
        isIgnoreExitValue = true
    }.standardOutput.asText.map { it.trim().ifEmpty { "unknown" } }
    argumentProviders += CommandLineArgumentProvider {
        listOf(msi.get().asFile.absolutePath, out.get().asFile.absolutePath, agentHqPackageVersion, gitSha.get())
    }
    outputs.upToDateWhen { false }
}

// Refuse a MISSING -PagenthqVersion before packageReleaseMsi spends minutes: the INSTALL-1 check above falls back to
// 1.0.0 when the property is absent, which is fine for a local package but never for a published release.
// (An invalid value already fails at configuration, in the INSTALL-1 require.)
gradle.taskGraph.whenReady {
    if (hasTask(releaseAssets.get())) {
        check(!providers.gradleProperty("agenthqVersion").orNull.isNullOrBlank()) {
            "RELEASE-ASSETS-1: releaseAssets needs -PagenthqVersion=MAJOR.MINOR.BUILD (e.g. -PagenthqVersion=1.0.5); it never defaults"
        }
    }
}

// NS4 (docs/NORTH-STAR-DESIGN.md 2a-2c): the SHELL-ONLY target. The same entry (AppMainKt) on a classpath without the app
// UI (agenthqAppUi) and the board module (Infantry_Board), exactly as the S6 drop-in shots filter it, started with
// `--app <dir>`: <dir>/app.json names the app, <dir>/plugins is its (empty) plugins root, so the window is the empty shell
// with the drop box. The daemon stays on the classpath (connect-or-bundle; a thin no-daemon flavour is NS-L1).
//   ./gradlew :superiorSoftLibrary:agentHqUi:runShell                     (a generated app in build/shell-app)
//   ./gradlew :superiorSoftLibrary:agentHqUi:runShell -PshellApp=<dir>    (an app dir of your own, relative to the repo root)
//   ./gradlew :superiorSoftLibrary:agentHqUi:packageShellMsi [-PshellApp=<dir>]   (build/shell/msi; jpackage over the shell runtime image)
fun isShellJar(f: File) = f.invariantSeparatorsPath.let { "agenthqAppUi" !in it && "Infantry_Board" !in it }
val shellAppProp = project.findProperty("shellApp")?.toString()
val defaultShellApp = layout.buildDirectory.dir("shell-app")
val shellAppDir: Provider<File> = if (shellAppProp != null) provider { rootProject.file(shellAppProp) } else defaultShellApp.map { it.asFile }
val prepareShellApp = tasks.register("prepareShellApp") {
    group = "application"
    description = "NS4: writes the default shell app (build/shell-app: app.json + an empty plugins/) unless it exists."
    val dir = defaultShellApp
    onlyIf { shellAppProp == null }
    doLast {
        val d = dir.get().asFile
        File(d, "plugins/core").mkdirs()
        File(d, "plugins/community").mkdirs()
        val json = File(d, "app.json")
        if (!json.isFile) json.writeText(
            "{ \"appId\": \"dev.infantry.shell\", \"name\": \"Infantry Shell\", \"windowTitle\": \"Infantry Shell\",\n" +
                "  \"daemon\": { \"mode\": \"connect-or-bundle\", \"url\": null, \"seedProject\": null } }\n",
        )
    }
}
tasks.register<JavaExec>("runShell") {
    group = "application"
    description = "NS4: the empty shell (no app UI on the classpath) on an app dir: -PshellApp=<dir>, default build/shell-app."
    dependsOn(prepareShellApp)
    mainClass.set("com.superiorsoftllc.agenthq.ui.AppMainKt")
    classpath = sourceSets["main"].runtimeClasspath.filter(::isShellJar)
    jvmArgs(agentHqJvmCaps) // PERF-2
    systemProperty("agenthq.build", agentHqBuildStamp) // BUILD-STAMP-1
    argumentProviders += CommandLineArgumentProvider { listOf("--app", shellAppDir.get().absolutePath) }
}

// The shell's jars: this module's jar plus its filtered runtime classpath (the jpackage --input).
val shellJars = tasks.register<Sync>("shellJars") {
    group = "distribution"
    from(tasks.named("jar"))
    from(sourceSets["main"].runtimeClasspath.filter { isShellJar(it) && it.isFile && it.name.endsWith(".jar") })
    into(layout.buildDirectory.dir("shell/input"))
}

// Compose Desktop allows one application {} per project, so the shell installer is jpackage over the same runtime image
// (createRuntimeImage, which keeps bin/java: PKG-4) with the shell jars. Name, title and icon come from app.json; the
// upgrade code is derived from its appId only (AppManifest.upgradeUuid, same formula), so a shell app never upgrades over
// Infantry AI (6f1d3c2a-...) nor over another app. The launcher passes -Dagenthq.app.dir=$APPDIR/shell-app.
tasks.register<Exec>("packageShellMsi") {
    group = "distribution"
    description = "NS4: the shell installer (msi) for -PshellApp=<dir> (default build/shell-app) into build/shell/msi."
    dependsOn(prepareShellApp, shellJars, "createRuntimeImage")
    val input = layout.buildDirectory.dir("shell/input")
    val dest = layout.buildDirectory.dir("shell/msi")
    val jpackage = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
        .map { File(it.metadata.installationPath.asFile, "bin/" + if (System.getProperty("os.name").startsWith("Windows")) "jpackage.exe" else "jpackage") }
    val uiJar = tasks.named<Jar>("jar").flatMap { it.archiveFileName }
    val defaultIcon = project.file("icons/agenthq.ico")
    doFirst {
        val app = shellAppDir.get()
        val manifest = groovy.json.JsonSlurper().parse(File(app, "app.json")) as Map<*, *>
        val appId = manifest["appId"] as? String ?: error("NS4: ${app}/app.json has no appId")
        val name = (manifest["name"] as? String)?.takeIf { it.isNotBlank() } ?: appId
        val icon = (manifest["icon"] as? String)?.let { File(app, it) }?.takeIf { it.isFile } ?: defaultIcon
        val upgradeUuid = UUID.nameUUIDFromBytes("infantry.app:$appId".toByteArray(Charsets.UTF_8)).toString()
        // The app.json travels inside the app dir; its plugins live in the per-app state dir (ShellApp.resolve, installed).
        project.copy { from(File(app, "app.json")); into(input.get().dir("shell-app")) }
        dest.get().asFile.deleteRecursively()
        val runtime = tasks.withType<org.jetbrains.compose.desktop.application.tasks.AbstractJLinkTask>().getByName("createRuntimeImage").destinationDir.get().asFile
        val javaOptions = agentHqJvmCaps + listOf("-Dagenthq.version=$agentHqPackageVersion", "-Dagenthq.build=$agentHqBuildStamp", "-Dagenthq.app.dir=\$APPDIR/shell-app")
        commandLine(
            listOf(
                jpackage.get().path, "--type", "msi", "--name", name, "--app-version", agentHqPackageVersion, "--vendor", "SuperiorSoft LLC",
                "--input", input.get().asFile.path, "--main-jar", uiJar.get(), "--main-class", "com.superiorsoftllc.agenthq.ui.AppMainKt",
                "--runtime-image", runtime.path, "--dest", dest.get().asFile.path, "--icon", icon.path,
                "--win-upgrade-uuid", upgradeUuid, "--win-per-user-install", "--win-menu", "--win-menu-group", name, "--win-shortcut", "--win-dir-chooser",
            ) + javaOptions.flatMap { listOf("--java-options", it) },
        )
        logger.lifecycle("NS4: packaging $name (appId $appId, upgrade $upgradeUuid) from $app")
    }
}

// PACKAGING-GUARD-1: branch/sha print, dirty/branch refusal, BUILD-INFO and sha-named outputs (see the script).
apply(from = rootProject.file("gradle/packaging-guard.gradle.kts"))

// GATE-LOCK-1: one test run per build dir (see the script).
apply(from = rootProject.file("gradle/test-run-lock.gradle.kts"))
