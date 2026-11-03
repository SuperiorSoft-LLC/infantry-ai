// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// GATE-LOCK-1: one test run per build dir. Two Gradle builds running this project's Test tasks in the same build dir
// make the second one wipe build/test-results/<task>/binary under the first, which then fails with
// java.nio.file.NoSuchFileException: ...\in-progress-results-generic*.bin (ticket e7b6b957).
// When the task graph holds any Test task of this project, the build takes an exclusive OS file lock on
// build/test-run.lock before any task runs and keeps it until the build ends (the lock dies with the JVM, so a killed
// build never leaves a stale lock). A second build in the same dir is refused at once with the holder's pid.
// One lock per build dir: parallel test forks inside one build share it.
// Applied from :infantry and :infantryUi (apply(from = rootProject.file("gradle/test-run-lock.gradle.kts"))).

import java.lang.management.ManagementFactory
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.StandardOpenOption

abstract class TestRunLock : BuildService<TestRunLock.Params>, AutoCloseable {
    interface Params : BuildServiceParameters {
        val buildDir: DirectoryProperty
    }

    private var channel: FileChannel? = null
    private var lock: FileLock? = null

    @Synchronized
    fun acquire(projectPath: String) {
        if (lock != null) return
        val dir = parameters.buildDir.get().asFile.apply { mkdirs() }
        val owner = File(dir, "test-run.owner")
        val ch = FileChannel.open(File(dir, "test-run.lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        val l = try { ch.tryLock() } catch (e: java.io.IOException) { null }
        if (l == null) {
            ch.close()
            val holder = owner.takeIf { it.isFile }?.readText()?.trim().orEmpty().ifEmpty { "unknown" }
            throw GradleException(
                "TEST RUN REFUSED: another Gradle build is running $projectPath tests in $dir ($holder). " +
                    "One test run per build dir: wait for it to finish, or run the suite in its own worktree " +
                    "(scripts/infantry-gate.sh does). See docs/GATE.md."
            )
        }
        channel = ch; lock = l
        owner.writeText("pid ${ManagementFactory.getRuntimeMXBean().name.substringBefore('@')}, since ${java.time.LocalDateTime.now().withNano(0)}\n")
    }

    @Synchronized
    override fun close() {
        try { lock?.release() } catch (_: Exception) {}
        try { channel?.close() } catch (_: Exception) {}
        lock = null; channel = null
    }
}

val testRunLock = gradle.sharedServices.registerIfAbsent("testRunLock${project.path.replace(':', '_')}", TestRunLock::class) {
    parameters.buildDir.set(layout.buildDirectory)
}
tasks.withType<Test>().configureEach { usesService(testRunLock) }

// Before any task runs (compiles included), so a refused build touches nothing in the other build's dir.
val lockedProject = project
gradle.taskGraph.whenReady {
    if (allTasks.any { it.project == lockedProject && it is Test }) testRunLock.get().acquire(lockedProject.path)
}
