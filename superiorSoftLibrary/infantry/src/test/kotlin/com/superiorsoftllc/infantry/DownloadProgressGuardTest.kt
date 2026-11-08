// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * CORE-PROGRESS-DOWNLOAD-1 (docs/wiki/Progress.md): one core downloader and one core progress view.
 *
 * - A file that opens its own HTTP client or URL stream (`HttpClient.newBuilder()`, `HttpClient.newHttpClient()`,
 *   `URL.openStream()`, `openConnection()`, a `BodyHandlers.ofFile` / `ofInputStream` body) must use the core downloader
 *   (`DaemonApi.downloads`, `CoreDownloads`) instead, or an API call (`http.fetch`, a WebSocket, a JSON route) must be named
 *   here with its reason.
 * - A raw Material progress indicator (`LinearProgressIndicator`, `CircularProgressIndicator`) is drawn only by the core
 *   presenter (`ui/progress/ProgressViews.kt`); every other screen draws `ProgressBar` / `ProgressView` / `ProgressIndicator`.
 *
 * The lists below are the sites that existed when the seam landed. Each is either an API call (not a download) or a
 * migration filed as a backlog ticket; remove a line when its file moves onto the seam. Never add one for new code.
 */
class DownloadProgressGuardTest {
    private val roots = listOf(File(".."), File("../../plugins"), File("../../samples"))

    private val httpRule = Regex("""HttpClient\.newBuilder\(|HttpClient\.newHttpClient\(|\.openStream\(\)|\.openConnection\(\)|BodyHandlers\.ofFile|BodyHandlers\.ofInputStream""")
    private val barRule = Regex("""\b(LinearProgressIndicator|CircularProgressIndicator)\b""")

    /** Path suffix -> why it may open its own HTTP client. */
    private val httpAllowed = mapOf(
        "infantryCore/src/main/kotlin/com/superiorsoftllc/infantry/downloads/HttpDownloadTransport.kt" to "THE core download transport",
        "infantry/src/main/kotlin/com/superiorsoftllc/infantry/cli/CliMain.kt" to "the CLI's WebSocket and JSON calls to its daemon",
        "infantry/src/main/kotlin/com/superiorsoftllc/infantry/client/ClientMain.kt" to "the client WebSocket",
        "infantry/src/main/kotlin/com/superiorsoftllc/infantry/daemon/AssistantService.kt" to "a local health probe, not a download",
        "infantry/src/main/kotlin/com/superiorsoftllc/infantry/daemon/HealthGate.kt" to "a local health probe",
        "infantry/src/main/kotlin/com/superiorsoftllc/infantry/daemon/StallProbe.kt" to "a local health probe",
        "infantry/src/main/kotlin/com/superiorsoftllc/infantry/daemon/LoopbackFrames.kt" to "loopback frames to its own daemon",
        "infantry/src/main/kotlin/com/superiorsoftllc/infantry/daemon/plugins/LoopbackFrames.kt" to "loopback frames to its own daemon",
        "infantry/src/main/kotlin/com/superiorsoftllc/infantry/daemon/plugins/manager/PluginManager.kt" to "DefaultFetcher: the fallback when no core downloader is wired (unit tests, the bare CLI)",
        "infantryCore/src/main/kotlin/com/superiorsoftllc/infantry/usage/Limits.kt" to "a JSON usage API call",
        "infantryUi/src/main/kotlin/com/superiorsoftllc/infantry/ui/WindowActivation.kt" to "a local single-instance ping",
        "infantryUiCore/src/jvmMain/kotlin/com/superiorsoftllc/infantry/ui/plugin/JvmEndpointAccess.kt" to "a plugin's declared JSON endpoint call",
        "infantryUiCore/src/jvmMain/kotlin/com/superiorsoftllc/infantry/ui/voice/plugin/VoicePluginStore.kt" to "backlog: a UI-side download not yet on the core downloader",
        "examples/Acme_NewsAgent/daemon/src/main/kotlin/com/acme/newsagent/NewsAgentDaemon.kt" to "an example plugin's feed API call",
        "official/Infantry_Claude/harness/src/main/kotlin/com/superiorsoftllc/infantry/plugins/official/claude/harness/ClaudeStatsUsage.kt" to "a JSON usage API call",
        "official/Infantry_OpenCode/harness/src/main/kotlin/com/superiorsoftllc/infantry/plugins/official/opencode/harness/OpenCodeServeSession.kt" to "the opencode server's HTTP API",
        "official/Infantry_RemoteRunner/daemon/src/main/kotlin/com/superiorsoftllc/infantry/plugins/official/remoterunner/daemon/LinkClient.kt" to "the runner link WebSocket",
        "official/Infantry_Scripting/daemon/src/main/kotlin/com/superiorsoftllc/infantry/plugins/official/scripting/daemon/FetchBridge.kt" to "http.fetch: API calls (http.download is the file download)",
        "official/Infantry_Updater/androidSrc/AndroidSelfUpdate.kt" to "backlog: the Android self-update download",
        "official/Infantry_Updater/daemon/src/main/kotlin/com/superiorsoftllc/infantry/plugins/official/updater/GitHubReleases.kt" to "release JSON API calls; its file download is the fallback when no core downloader is wired",
        "official/Infantry_Updater/daemon/src/main/kotlin/com/superiorsoftllc/infantry/plugins/official/updater/ServerUpdateCli.kt" to "backlog: the CLI update path (no daemon, so no core downloader)",
    )

    /** Path suffix -> why it may draw a raw Material indicator. */
    private val barAllowed = mapOf(
        "infantryUiCore/src/commonMain/kotlin/com/superiorsoftllc/infantry/ui/progress/ProgressViews.kt" to "THE core presenter",
        "official/Infantry_ChatFeed/src/PendingSendRow.kt" to "backlog: a sending spinner",
        "official/Infantry_Settings/src/PluginsScreen.kt" to "backlog: the pending-request spinner (its strip bar is on ProgressBar)",
        "infantryUi/src/main/kotlin/com/superiorsoftllc/infantry/ui/UpdatePrompt.kt" to "backlog: the update prompt's bars",
        "infantryUiCore/src/androidMain/kotlin/com/superiorsoftllc/infantry/ui/shell/ConnectScreen.kt" to "backlog: the connect spinner",
        "infantryUiCore/src/commonMain/kotlin/com/superiorsoftllc/infantry/ui/ServerUpdateSection.kt" to "backlog: the server update bar",
    )

    private fun sources(): Sequence<File> = roots.asSequence().filter { it.isDirectory }.flatMap { r ->
        r.walkTopDown()
            .onEnter { it.name != "build" && it.name != ".gradle" && it.name != "node_modules" && it.name != ".worktrees" && it.name != "test" && it.name != "jvmTest" && it.name != "androidUnitTest" && it.name != "headlessSmoke" }
            .filter { it.isFile && it.extension == "kt" }
    }.distinctBy { it.canonicalPath }

    private fun hits(rule: Regex, allowed: Map<String, String>): List<String> {
        val bad = mutableListOf<String>()
        for (f in sources()) {
            val path = f.canonicalFile.invariantSeparatorsPath
            if (allowed.keys.any { path.endsWith(it) }) continue
            f.readLines().forEachIndexed { i, raw ->
                val t = raw.trim()
                if (t.startsWith("*") || t.startsWith("//") || t.startsWith("/*")) return@forEachIndexed
                if (rule.containsMatchIn(raw.replace(Regex("""\s//\s.*$"""), ""))) bad += "$path:${i + 1}: ${t.take(100)}"
            }
        }
        return bad
    }

    @Test fun `no ad-hoc download code outside the core downloader`() {
        assertTrue(File("../infantryCore/src/main").isDirectory, "run from the infantry module")
        val bad = hits(httpRule, httpAllowed)
        if (bad.isNotEmpty()) fail(
            "ad-hoc HTTP client / URL stream (download a file through DaemonApi.downloads or CoreDownloads; an API call goes " +
                "through http.fetch or is named in DownloadProgressGuardTest.httpAllowed with its reason):\n" + bad.joinToString("\n"),
        )
    }

    @Test fun `no raw progress indicator outside the core presenter`() {
        val bad = hits(barRule, barAllowed)
        if (bad.isNotEmpty()) fail(
            "raw Material progress indicator (draw ProgressBar / ProgressView / ProgressIndicator from ui/progress/ProgressViews.kt):\n" +
                bad.joinToString("\n"),
        )
    }

    @Test fun `every allow-listed file still exists and still needs its entry`() {
        val all = sources().map { it.canonicalFile.invariantSeparatorsPath to it }.toList()
        val stale = mutableListOf<String>()
        for ((suffix, rule) in listOf(httpAllowed to httpRule, barAllowed to barRule).flatMap { (m, r) -> m.keys.map { it to r } }) {
            val f = all.firstOrNull { it.first.endsWith(suffix) }?.second
            if (f == null) stale += "$suffix: no such file"
            else if (f.readLines().none { l -> val t = l.trim(); !t.startsWith("*") && !t.startsWith("//") && rule.containsMatchIn(l) }) stale += "$suffix: no longer matches, remove its entry"
        }
        if (stale.isNotEmpty()) fail("stale DownloadProgressGuardTest allow-list entries:\n" + stale.joinToString("\n"))
    }
}
