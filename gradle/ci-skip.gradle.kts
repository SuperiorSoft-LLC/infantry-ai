// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// CI-TESTS-2: the ONE mechanism that skips machine-only tests in CI. With INFANTRY_CI=1 every Test task of the applying
// project excludes the classes listed in gradle/ci-machine-only.txt. Locally (no INFANTRY_CI) nothing is excluded.
// Apply with: apply(from = rootProject.file("gradle/ci-skip.gradle.kts"))
val ciMachineOnly: List<String> = rootProject.file("gradle/ci-machine-only.txt").readLines()
    .map { it.substringBefore(" # ").trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

if (System.getenv("INFANTRY_CI") == "1") {
    tasks.withType<Test>().configureEach {
        filter { ciMachineOnly.forEach { excludeTestsMatching(it) } }
    }
}
