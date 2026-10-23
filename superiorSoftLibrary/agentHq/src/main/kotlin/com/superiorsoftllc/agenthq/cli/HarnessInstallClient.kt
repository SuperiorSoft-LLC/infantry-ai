package com.superiorsoftllc.agenthq.cli

import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.protocol.HarnessInstallReport
import com.superiorsoftllc.agenthq.protocol.HarnessInstallRequest
import com.superiorsoftllc.agenthq.protocol.HarnessInstallResult
import com.superiorsoftllc.agenthq.protocol.HarnessInstallStatusRequest
import kotlinx.serialization.json.Json

/**
 * HARNESS-INSTALL-1: `harness-status` / `harness-install` and the MCP `agenthq_harness_status` / `agenthq_harness_install`
 * read and write the same two frames and print the same text, so a remote or headless daemon is driven exactly like a local one.
 */
internal object HarnessInstallClient {
    /** An npm install of three CLIs can take minutes; the 20 s answer window of an ordinary read would cut it off. */
    const val INSTALL_TIMEOUT_MS = 15 * 60_000L

    suspend fun status(stream: FrameStream, checkLatest: Boolean): HarnessInstallReport {
        val data = json.encodeToString(HarnessInstallStatusRequest.serializer(), HarnessInstallStatusRequest(checkLatest))
        // checkLatest runs `npm view` per harness (network), so it gets the long window too.
        return stream.request(
            Envelope(Envelope.HARNESS_INSTALL_STATUS, data), answerType = Envelope.HARNESS_INSTALL_STATUS, what = "harness_install_status",
            idempotent = true, timeoutMs = if (checkLatest) INSTALL_TIMEOUT_MS else stream.answerTimeoutMs,
        ).let { json.decodeFromString(HarnessInstallReport.serializer(), it.data) }
    }

    /** Not idempotent-by-resend: a dropped connection may have already started npm, so the caller is told instead. */
    suspend fun install(stream: FrameStream, ids: List<String>, update: Boolean): HarnessInstallResult {
        val data = json.encodeToString(HarnessInstallRequest.serializer(), HarnessInstallRequest(ids, update))
        return stream.request(
            Envelope(Envelope.HARNESS_INSTALL, data), answerType = Envelope.HARNESS_INSTALL_RESULT, what = "harness_install",
            idempotent = false, timeoutMs = INSTALL_TIMEOUT_MS,
        ).let { json.decodeFromString(HarnessInstallResult.serializer(), it.data) }
    }

    fun render(report: HarnessInstallReport, out: Appendable) {
        out.appendLine("id | state | version | where | package")
        for (h in report.harnesses) {
            out.appendLine(
                buildString {
                    append(h.id).append(" | ").append(h.state).append(" | ").append(h.installedVersion ?: "-")
                    append(" | ").append(h.path?.let { if (h.managed) "$it (app-managed)" else it } ?: "-")
                    append(" | ").append(h.npmPackage)
                    h.wanted?.let { append(" | pinned: ").append(it) }
                    h.latest?.let { append(" | latest: ").append(it) }
                },
            )
        }
        out.appendLine("install dir: ${report.prefixDir}")
        out.appendLine(report.npmPath?.let { "npm: $it" } ?: "npm: NOT FOUND")
        report.guidance?.let { out.appendLine(it) }
        out.appendLine(SIGN_IN)
    }

    fun render(result: HarnessInstallResult, out: Appendable) {
        for (line in result.log) out.appendLine(line)
        for (o in result.outcomes) {
            out.appendLine("${o.id}: ${o.result}" + (o.version?.let { " $it" } ?: "") + (o.message?.let { " - $it" } ?: ""))
        }
        if (result.outcomes.isEmpty()) out.appendLine("nothing to install: every harness is already installed")
        render(result.report, out)
    }

    fun failed(result: HarnessInstallResult): Boolean = result.outcomes.any { it.result == "failed" }

    const val SIGN_IN =
        "Sign-in is separate: each CLI keeps its own login (run `claude` once, `opencode auth login`, or set the provider's " +
            "API-key environment variable). Infantry AI stores no key."

    private val json = Json { ignoreUnknownKeys = true }
}
