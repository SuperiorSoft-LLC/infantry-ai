// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.testrun

import kotlinx.serialization.Serializable

enum class TestStatus { PASS, FAIL, SKIP }

/** One test result; identity = suite + class + name (from the parser). */
data class TestCase(val suite: String, val className: String, val name: String, val status: TestStatus, val output: String = "") {
    val identity: String get() = "$suite|$className|$name"
}

/** Pluggable result parser; junit-xml first, others (tap, ...) can be added by plugins. */
interface ResultParser {
    val format: String

    /** [files]: result files matched by tests.resultGlob; [exitCode]/[output]: the command's own result. */
    fun parse(files: List<java.io.File>, exitCode: Int, output: String): List<TestCase>
}

/**
 * Project facts as data (tests.* project settings); nothing here is a default for a particular build tool.
 * [fullCommand] and [workingBranch] have no default: unset = the runner refuses.
 */
data class TestRunConfig(
    val fullCommand: String?,
    val workingBranch: String?,
    val resultFormat: String = "junit-xml",
    val resultGlob: String? = null,
    val envOrArgs: String = "",
    val rerunCommand: String? = null,
    val memoryPressureThreshold: Int = 0,
    val timeoutMinutes: Long = 0,
) {
    companion object {
        fun from(get: (String) -> String?): TestRunConfig {
            fun s(k: String) = get(k)?.trim()?.ifEmpty { null }
            return TestRunConfig(
                fullCommand = s("tests.fullCommand"),
                workingBranch = s("tests.workingBranch"),
                resultFormat = s("tests.resultFormat") ?: "junit-xml",
                resultGlob = s("tests.resultGlob"),
                envOrArgs = s("tests.envOrArgs") ?: "",
                rerunCommand = s("tests.rerunCommand"),
                memoryPressureThreshold = s("tests.memoryPressureThreshold")?.toIntOrNull() ?: 0,
                timeoutMinutes = s("tests.timeout")?.toLongOrNull() ?: 0,
            )
        }
    }
}

@Serializable
data class FailureRecord(
    val identity: String,
    val ticketId: String? = null,
    val firstSha: String,
    val lastSha: String,
    val failCount: Int = 1,
    val flaky: Boolean = false,
)

@Serializable
data class RunResult(
    val sha: String,
    val startedAt: Long,
    val endedAt: Long,
    val passed: Int,
    val failed: Int,
    val skipped: Int,
    val failing: List<String> = emptyList(),
    val flaky: List<String> = emptyList(),
    /** ran | refused | skipped */
    val outcome: String,
    val message: String = "",
)

/** The ledger of failures already seen, persisted by the daemon (deduplicates tickets by test identity). */
@Serializable
data class Ledger(val failures: Map<String, FailureRecord> = emptyMap(), val last: RunResult? = null, val running: Boolean = false)
