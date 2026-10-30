package com.superiorsoftllc.agenthq.cli

import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.protocol.HarnessInstallReport
import com.superiorsoftllc.agenthq.protocol.HarnessInstallRequest
import com.superiorsoftllc.agenthq.protocol.HarnessInstallResult
import com.superiorsoftllc.agenthq.protocol.HarnessInstallStatusRequest
import com.superiorsoftllc.agenthq.daemon.api.harness.HarnessInstallText
import kotlinx.serialization.json.Json

/**
 * HARNESS-INSTALL-1: `harness-status` / `harness-install` and the MCP `agenthq_harness_status` / `agenthq_harness_install`
 * read and write the same two frames and print the same text, so a remote or headless daemon is driven exactly like a local one.
 */
private typealias ApiReport = com.superiorsoftllc.agenthq.daemon.api.harness.HarnessInstallReport
private typealias ApiResult = com.superiorsoftllc.agenthq.daemon.api.harness.HarnessInstallResult

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

    /** The printed tables are the API's [HarnessInstallText] (the Infantry_Agents tools print the same); the wire types are re-read as its models. */
    fun render(report: HarnessInstallReport, out: Appendable) =
        HarnessInstallText.render(json.decodeFromString(ApiReport.serializer(), json.encodeToString(HarnessInstallReport.serializer(), report)), out)

    fun render(result: HarnessInstallResult, out: Appendable) =
        HarnessInstallText.render(json.decodeFromString(ApiResult.serializer(), json.encodeToString(HarnessInstallResult.serializer(), result)), out)

    fun failed(result: HarnessInstallResult): Boolean = result.outcomes.any { it.result == "failed" }

    const val SIGN_IN = HarnessInstallText.SIGN_IN

    private val json = Json { ignoreUnknownKeys = true }
}
